using System.ComponentModel.DataAnnotations;
using System.Text.Json;

namespace Gpos.Helper;

public sealed class HelperOptions
{
    public const string Section = "Helper";
    public string ListenAddress { get; set; } = "127.0.0.1";
    [Range(1, 65535)] public int Port { get; set; } = 47831;
    public string AppsScriptEndpoint { get; set; } = "";
    [Range(1, 120)] public int UpstreamTimeoutSeconds { get; set; } = 15;
    [Range(0, 5)] public int UpstreamMaxRetries { get; set; } = 2;
    [Range(5, 3600)] public int SessionMinutes { get; set; } = 60;
    [Range(5, 3600)] public int HeartbeatSeconds { get; set; } = 30;
    public bool DevelopmentMode { get; set; }
    public bool LaunchBrowser { get; set; } = true;
    public string[] AllowedOrigins { get; set; } = ["https://cokkles.github.io"];
    public string[] AllowedEmails { get; set; } = [];
    public GoogleOAuthOptions GoogleOAuth { get; set; } = new();
}

public sealed class GoogleOAuthOptions
{
    public string ClientId { get; set; } = "";
    public string ClientSecret { get; set; } = "";
    public string RedirectUri { get; set; } = "http://127.0.0.1:47831/api/v1/auth/callback";
    public string[] Scopes { get; set; } = ["openid", "email", "profile"];
}

public sealed record AuthIdentity(string Subject, string Email, string DisplayName);
public sealed record AuthResult(bool Success, AuthIdentity? Identity = null, string? Error = null);
public sealed record UpstreamResult(bool Success, int StatusCode, JsonElement? Body, string? ErrorCategory = null, string? Error = null, int RetryCount = 0, long DurationMs = 0);

