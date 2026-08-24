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

public sealed class HelperSessionStore(IOptions<HelperOptions> options, TimeProvider timeProvider)
{
    private sealed record Session(AuthIdentity Identity, DateTimeOffset ExpiresAt);
    private readonly ConcurrentDictionary<string, Session> _sessions = new();

    public (string Token, DateTimeOffset ExpiresAt) Create(AuthIdentity identity)
    {
        var token = Convert.ToBase64String(RandomNumberGenerator.GetBytes(32)).TrimEnd('=').Replace('+', '-').Replace('/', '_');
        var expires = timeProvider.GetUtcNow().AddMinutes(options.Value.SessionMinutes);
        _sessions[Hash(token)] = new(identity, expires);
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
    private sealed record Pending(string Verifier, DateTimeOffset ExpiresAt);
    private readonly ConcurrentDictionary<string, Pending> _pending = new();
    public string Mode => "google_oauth";

    public Task<string> BeginLoginAsync(CancellationToken cancellationToken)
    {
        var cfg = options.Value.GoogleOAuth;
        if (string.IsNullOrWhiteSpace(cfg.ClientId)) throw new InvalidOperationException("Google OAuth is not configured.");
        var state = Token(24); var verifier = Token(48);
        _pending[state] = new(verifier, DateTimeOffset.UtcNow.AddMinutes(5));
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
        await secrets.SaveAsync("google-token", JsonSerializer.Serialize(token), cancellationToken);
        await secrets.SaveAsync("google-id-token", token.IdToken, cancellationToken);
        return new(true, new AuthIdentity(validation.Subject, validation.Email ?? "", validation.Name ?? validation.Email ?? "Google User"));
    }

    private static string Token(int bytes) => Base64Url(RandomNumberGenerator.GetBytes(bytes));
    private static string Base64Url(byte[] value) => Convert.ToBase64String(value).TrimEnd('=').Replace('+', '-').Replace('/', '_');
    private sealed record GoogleToken([property: JsonPropertyName("access_token")] string? AccessToken, [property: JsonPropertyName("refresh_token")] string? RefreshToken, [property: JsonPropertyName("id_token")] string? IdToken, [property: JsonPropertyName("expires_in")] int ExpiresIn);
    private sealed record GoogleIdentity([property: JsonPropertyName("sub")] string Subject, [property: JsonPropertyName("aud")] string Audience, [property: JsonPropertyName("email")] string? Email, [property: JsonPropertyName("email_verified")] string? EmailVerified, [property: JsonPropertyName("name")] string? Name);
}

