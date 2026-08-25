using System.Collections.Concurrent;
using System.Diagnostics;
using System.Net.Http.Json;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.Json.Serialization;
using Microsoft.Extensions.Options;

namespace Gpos.Helper;

public interface IAuthProvider
{
    string Mode { get; }
    Task<string> BeginLoginAsync(CancellationToken cancellationToken);
    Task<AuthResult> CompleteLoginAsync(string code, string state, CancellationToken cancellationToken);
}

public sealed record StoredGoogleToken(string? AccessToken, string? RefreshToken, string IdToken, DateTimeOffset ExpiresAt);

public interface IGoogleCredentialProvider
{
    Task<string?> GetIdTokenAsync(CancellationToken cancellationToken);
    Task<string> GetStateAsync(CancellationToken cancellationToken);
    Task ClearAsync(CancellationToken cancellationToken);
    Task<string> RevokeAsync(CancellationToken cancellationToken);
}

public sealed class GoogleCredentialProvider(
    ISecretStore secrets,
    IHttpClientFactory clients,
    IOptions<HelperOptions> options,
    TimeProvider timeProvider,
    ILogger<GoogleCredentialProvider> logger) : IGoogleCredentialProvider
{
    private readonly SemaphoreSlim _refreshLock = new(1, 1);

    public async Task<string?> GetIdTokenAsync(CancellationToken cancellationToken)
    {
        var stored = await ReadAsync(cancellationToken);
        if (IsUsable(stored)) return stored!.IdToken;
        if (string.IsNullOrWhiteSpace(stored?.RefreshToken)) return null;

        await _refreshLock.WaitAsync(cancellationToken);
        try
        {
            stored = await ReadAsync(cancellationToken);
            if (IsUsable(stored)) return stored!.IdToken;
            if (string.IsNullOrWhiteSpace(stored?.RefreshToken)) return null;

            var oauth = options.Value.GoogleOAuth;
            if (string.IsNullOrWhiteSpace(oauth.ClientId)) return null;
            using var timeout = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
            timeout.CancelAfter(TimeSpan.FromSeconds(20));
            using var request = new HttpRequestMessage(HttpMethod.Post, "https://oauth2.googleapis.com/token")
            {
                Content = new FormUrlEncodedContent(new Dictionary<string, string>
                {
                    ["client_id"] = oauth.ClientId,
                    ["client_secret"] = oauth.ClientSecret,
                    ["refresh_token"] = stored.RefreshToken,
                    ["grant_type"] = "refresh_token"
                })
            };
            using var response = await clients.CreateClient("oauth").SendAsync(request, timeout.Token);
            if (!response.IsSuccessStatusCode)
            {
                logger.LogWarning("Google credential refresh failed category={Category} status={Status}", "oauth_refresh_failed", (int)response.StatusCode);
                return null;
            }
            var refreshed = await response.Content.ReadFromJsonAsync<OAuthTokenResponse>(cancellationToken: timeout.Token);
            if (string.IsNullOrWhiteSpace(refreshed?.IdToken))
            {
                logger.LogWarning("Google credential refresh failed category={Category}", "oauth_refresh_id_token_missing");
                return null;
            }
            var next = new StoredGoogleToken(refreshed.AccessToken, refreshed.RefreshToken ?? stored.RefreshToken, refreshed.IdToken, timeProvider.GetUtcNow().AddSeconds(Math.Max(60, refreshed.ExpiresIn)));
            await secrets.SaveAsync("google-token", JsonSerializer.Serialize(next), cancellationToken);
            logger.LogInformation("Google credential refreshed result={Result} expires_at={ExpiresAt}", "success", next.ExpiresAt);
            return next.IdToken;
        }
        catch (OperationCanceledException) when (!cancellationToken.IsCancellationRequested)
        {
            logger.LogWarning("Google credential refresh failed category={Category}", "oauth_refresh_timeout");
            return null;
        }
        catch (HttpRequestException ex)
        {
            logger.LogWarning("Google credential refresh failed category={Category} detail={Detail}", "oauth_refresh_unavailable", SecretRedactor.Redact(ex.Message));
            return null;
        }
        finally { _refreshLock.Release(); }
    }

    public async Task<string> GetStateAsync(CancellationToken cancellationToken)
    {
        var stored = await ReadAsync(cancellationToken);
        if (stored is null) return "ABSENT";
        if (IsUsable(stored)) return "VALID";
        return string.IsNullOrWhiteSpace(stored.RefreshToken) ? "EXPIRED" : "REFRESHABLE";
    }

    public Task ClearAsync(CancellationToken cancellationToken) => secrets.DeleteAsync("google-token", cancellationToken);

    public async Task<string> RevokeAsync(CancellationToken cancellationToken)
    {
        var stored = await ReadAsync(cancellationToken);
        var token = stored?.RefreshToken ?? stored?.AccessToken;
        if (string.IsNullOrWhiteSpace(token)) { await ClearAsync(cancellationToken); return "NOT_PRESENT"; }
        var result = "FAILED";
        try
        {
            using var timeout = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
            timeout.CancelAfter(TimeSpan.FromSeconds(10));
            using var request = new HttpRequestMessage(HttpMethod.Post, "https://oauth2.googleapis.com/revoke") { Content = new FormUrlEncodedContent(new Dictionary<string, string> { ["token"] = token }) };
            using var response = await clients.CreateClient("oauth").SendAsync(request, timeout.Token);
            result = response.IsSuccessStatusCode ? "REVOKED" : "FAILED";
            if (!response.IsSuccessStatusCode) logger.LogWarning("Google credential revocation failed category={Category} status={Status}", "oauth_revoke_failed", (int)response.StatusCode);
        }
        catch (OperationCanceledException) when (!cancellationToken.IsCancellationRequested) { logger.LogWarning("Google credential revocation failed category={Category}", "oauth_revoke_timeout"); }
        catch (HttpRequestException ex) { logger.LogWarning("Google credential revocation failed category={Category} detail={Detail}", "oauth_revoke_unavailable", SecretRedactor.Redact(ex.Message)); }
        finally { await ClearAsync(cancellationToken); }
        return result;
    }

    private async Task<StoredGoogleToken?> ReadAsync(CancellationToken cancellationToken)
    {
        var json = await secrets.GetAsync("google-token", cancellationToken);
        if (string.IsNullOrWhiteSpace(json)) return null;
        try { return JsonSerializer.Deserialize<StoredGoogleToken>(json); }
        catch (JsonException)
        {
            logger.LogWarning("Stored Google credential is unreadable category={Category}", "credential_malformed");
            return null;
        }
    }

    private bool IsUsable(StoredGoogleToken? token) => token is not null && !string.IsNullOrWhiteSpace(token.IdToken) && token.ExpiresAt > timeProvider.GetUtcNow().AddMinutes(2);
}

public sealed class HelperSessionStore(IOptions<HelperOptions> options, TimeProvider timeProvider)
{
    private const int Capacity = 100;
    private sealed record Session(AuthIdentity Identity, DateTimeOffset CreatedAt, DateTimeOffset ExpiresAt);
    private readonly ConcurrentDictionary<string, Session> _sessions = new();
    private readonly object _gate = new();

    public (string Token, DateTimeOffset ExpiresAt) Create(AuthIdentity identity)
    {
        var token = Convert.ToBase64String(RandomNumberGenerator.GetBytes(32)).TrimEnd('=').Replace('+', '-').Replace('/', '_');
        var now = timeProvider.GetUtcNow(); var expires = now.AddMinutes(options.Value.SessionMinutes);
        lock (_gate)
        {
            foreach (var entry in _sessions.Where(x => x.Value.ExpiresAt <= now)) _sessions.TryRemove(entry.Key, out _);
            while (_sessions.Count >= Capacity)
            {
                var oldest = _sessions.MinBy(x => x.Value.CreatedAt);
                if (!_sessions.TryRemove(oldest.Key, out _)) break;
            }
            _sessions[Hash(token)] = new(identity, now, expires);
        }
        return (token, expires);
    }

    public AuthIdentity? Validate(string? token)
    {
        if (string.IsNullOrWhiteSpace(token) || !_sessions.TryGetValue(Hash(token), out var session)) return null;
        if (session.ExpiresAt <= timeProvider.GetUtcNow()) { _sessions.TryRemove(Hash(token), out _); return null; }
        return session.Identity;
    }

    public void Revoke(string? token) { if (!string.IsNullOrWhiteSpace(token)) _sessions.TryRemove(Hash(token), out _); }
    private static string Hash(string token) => Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(token)));
}

public sealed class DevelopmentAuthProvider : IAuthProvider
{
    public string Mode => "development_mock";
    public Task<string> BeginLoginAsync(CancellationToken cancellationToken) => Task.FromResult("/api/v1/auth/callback?code=development&state=development");
    public Task<AuthResult> CompleteLoginAsync(string code, string state, CancellationToken cancellationToken) =>
        Task.FromResult(code == "development" && state == "development"
            ? new AuthResult(true, new AuthIdentity("development-user", "developer@localhost", "Development User"))
            : new AuthResult(false, Error: "invalid_development_callback"));
}

public sealed class GoogleOAuthProvider(IOptions<HelperOptions> options, IHttpClientFactory clients, ISecretStore secrets) : IAuthProvider
{
    private const int PendingCapacity = 100;
    private sealed record Pending(string Verifier, DateTimeOffset ExpiresAt);
    private readonly ConcurrentDictionary<string, Pending> _pending = new();
    public string Mode => "google_oauth";

    public Task<string> BeginLoginAsync(CancellationToken cancellationToken)
    {
        var cfg = options.Value.GoogleOAuth;
        if (string.IsNullOrWhiteSpace(cfg.ClientId)) throw new InvalidOperationException("Google OAuth is not configured.");
        var now = DateTimeOffset.UtcNow;
        foreach (var entry in _pending.Where(x => x.Value.ExpiresAt <= now)) _pending.TryRemove(entry.Key, out _);
        while (_pending.Count >= PendingCapacity)
        {
            var oldest = _pending.MinBy(x => x.Value.ExpiresAt);
            if (!_pending.TryRemove(oldest.Key, out _)) break;
        }
        var state = Token(24); var verifier = Token(48);
        _pending[state] = new(verifier, now.AddMinutes(5));
        var challenge = Base64Url(SHA256.HashData(Encoding.ASCII.GetBytes(verifier)));
        var query = new Dictionary<string, string?>
        {
            ["client_id"] = cfg.ClientId, ["redirect_uri"] = cfg.RedirectUri, ["response_type"] = "code",
            ["scope"] = string.Join(' ', cfg.Scopes), ["access_type"] = "offline", ["prompt"] = "consent",
            ["state"] = state, ["code_challenge"] = challenge, ["code_challenge_method"] = "S256"
        };
        return Task.FromResult(QueryString.Create(query).ToUriComponent().Insert(0, "https://accounts.google.com/o/oauth2/v2/auth"));
    }

    public async Task<AuthResult> CompleteLoginAsync(string code, string state, CancellationToken cancellationToken)
    {
        if (!_pending.TryRemove(state, out var pending) || pending.ExpiresAt < DateTimeOffset.UtcNow) return new(false, Error: "invalid_or_expired_state");
        var cfg = options.Value.GoogleOAuth;
        using var request = new HttpRequestMessage(HttpMethod.Post, "https://oauth2.googleapis.com/token")
        {
            Content = new FormUrlEncodedContent(new Dictionary<string, string>
            {
                ["client_id"] = cfg.ClientId, ["client_secret"] = cfg.ClientSecret, ["code"] = code,
                ["code_verifier"] = pending.Verifier, ["grant_type"] = "authorization_code", ["redirect_uri"] = cfg.RedirectUri
            })
        };
        using var response = await clients.CreateClient("oauth").SendAsync(request, cancellationToken);
        if (!response.IsSuccessStatusCode) return new(false, Error: "oauth_token_exchange_failed");
        var token = await response.Content.ReadFromJsonAsync<GoogleToken>(cancellationToken: cancellationToken);
        if (string.IsNullOrWhiteSpace(token?.IdToken)) return new(false, Error: "oauth_id_token_missing");
        var validation = await clients.CreateClient("oauth").GetFromJsonAsync<GoogleIdentity>("https://oauth2.googleapis.com/tokeninfo?id_token=" + Uri.EscapeDataString(token.IdToken), cancellationToken);
        if (validation is null || validation.Audience != cfg.ClientId || validation.EmailVerified != "true" || string.IsNullOrWhiteSpace(validation.Subject)) return new(false, Error: "oauth_identity_invalid");
        if (string.IsNullOrWhiteSpace(validation.Email) || !options.Value.AllowedEmails.Contains(validation.Email, StringComparer.OrdinalIgnoreCase)) return new(false, Error: "identity_not_allowed");
        var stored = new StoredGoogleToken(token.AccessToken, token.RefreshToken, token.IdToken, DateTimeOffset.UtcNow.AddSeconds(Math.Max(60, token.ExpiresIn)));
        await secrets.SaveAsync("google-token", JsonSerializer.Serialize(stored), cancellationToken);
        return new(true, new AuthIdentity(validation.Subject, validation.Email ?? "", validation.Name ?? validation.Email ?? "Google User"));
    }

    private static string Token(int bytes) => Base64Url(RandomNumberGenerator.GetBytes(bytes));
    private static string Base64Url(byte[] value) => Convert.ToBase64String(value).TrimEnd('=').Replace('+', '-').Replace('/', '_');
    private sealed record GoogleToken([property: JsonPropertyName("access_token")] string? AccessToken, [property: JsonPropertyName("refresh_token")] string? RefreshToken, [property: JsonPropertyName("id_token")] string? IdToken, [property: JsonPropertyName("expires_in")] int ExpiresIn);
    private sealed record GoogleIdentity([property: JsonPropertyName("sub")] string Subject, [property: JsonPropertyName("aud")] string Audience, [property: JsonPropertyName("email")] string? Email, [property: JsonPropertyName("email_verified")] string? EmailVerified, [property: JsonPropertyName("name")] string? Name);
}

public sealed record OAuthTokenResponse(
    [property: JsonPropertyName("access_token")] string? AccessToken,
    [property: JsonPropertyName("refresh_token")] string? RefreshToken,
    [property: JsonPropertyName("id_token")] string? IdToken,
    [property: JsonPropertyName("expires_in")] int ExpiresIn);

