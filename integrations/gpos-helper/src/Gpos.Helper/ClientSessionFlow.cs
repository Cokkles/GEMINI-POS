using System.Collections.Concurrent;
using System.Security.Cryptography;
using System.Text;

namespace Gpos.Helper;

public sealed class ClientSessionFlowStore(TimeProvider timeProvider)
{
    private const int Capacity = 100;
    private sealed record Pending(string ReturnUrl, string Challenge, DateTimeOffset ExpiresAt);
    private sealed record Grant(AuthIdentity Identity, string Challenge, DateTimeOffset ExpiresAt);
    private readonly ConcurrentDictionary<string, Pending> pending = new();
    private readonly ConcurrentDictionary<string, Grant> grants = new();
    private readonly object gate = new();

    public bool Prepare(string providerState, string returnUrl, string challenge)
    {
        if (string.IsNullOrWhiteSpace(providerState) || !ValidChallenge(challenge)) return false;
        lock (gate)
        {
            Prune(pending, x => x.ExpiresAt);
            pending[providerState] = new(returnUrl, challenge, timeProvider.GetUtcNow().AddMinutes(5));
            return true;
        }
    }

    public string? Complete(string providerState, AuthIdentity identity)
    {
        lock (gate)
        {
            if (!pending.TryRemove(providerState, out var flow) || flow.ExpiresAt <= timeProvider.GetUtcNow()) return null;
            Prune(grants, x => x.ExpiresAt);
            var code = Token(32);
            grants[Hash(code)] = new(identity, flow.Challenge, timeProvider.GetUtcNow().AddMinutes(1));
            return flow.ReturnUrl + (flow.ReturnUrl.Contains('#') ? "&" : "#") + "gpos_code=" + Uri.EscapeDataString(code);
        }
    }

    public AuthIdentity? Exchange(string? code, string? verifier)
    {
        if (string.IsNullOrWhiteSpace(code) || string.IsNullOrWhiteSpace(verifier) || verifier.Length is < 43 or > 128) return null;
        lock (gate)
        {
            if (!grants.TryRemove(Hash(code), out var grant) || grant.ExpiresAt <= timeProvider.GetUtcNow()) return null;
            var challenge = Base64Url(SHA256.HashData(Encoding.ASCII.GetBytes(verifier)));
            return CryptographicOperations.FixedTimeEquals(Encoding.ASCII.GetBytes(challenge), Encoding.ASCII.GetBytes(grant.Challenge)) ? grant.Identity : null;
        }
    }

    private void Prune<T>(ConcurrentDictionary<string, T> values, Func<T, DateTimeOffset> expiresAt)
    {
        var now = timeProvider.GetUtcNow();
        foreach (var entry in values.Where(x => expiresAt(x.Value) <= now)) values.TryRemove(entry.Key, out _);
        while (values.Count >= Capacity)
        {
            var oldest = values.MinBy(x => expiresAt(x.Value));
            if (!values.TryRemove(oldest.Key, out _)) break;
        }
    }

    private static bool ValidChallenge(string value) => value.Length == 43 && value.All(c => char.IsLetterOrDigit(c) || c is '-' or '_');
    private static string Token(int bytes) => Base64Url(RandomNumberGenerator.GetBytes(bytes));
    private static string Hash(string value) => Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(value)));
    private static string Base64Url(byte[] value) => Convert.ToBase64String(value).TrimEnd('=').Replace('+', '-').Replace('/', '_');
}

public sealed record ClientLoginRequest(string ReturnUrl, string CodeChallenge);
public sealed record ClientExchangeRequest(string Code, string CodeVerifier);

