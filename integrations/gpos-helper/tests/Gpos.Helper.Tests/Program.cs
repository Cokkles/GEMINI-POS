using System.Net;
using System.Net.Http.Json;
using System.Net.Sockets;
using System.Text.Json;
using System.Security.Cryptography;
using System.Text;
using Gpos.Helper;
using Microsoft.AspNetCore.Builder;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.DependencyInjection.Extensions;
using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Logging.Abstractions;
using Microsoft.Extensions.Options;

var tests = new List<(string Name, Func<Task> Run)>
{
    ("PWA-aligned control surface", ControlSurface), ("health endpoint", Health), ("liveness and readiness", Probes), ("capability endpoint", Capabilities), ("safe request activity", SafeActivity), ("safe setup readiness", SetupReadiness), ("config loading", Config),
    ("unknown origin rejection", UnknownOrigin), ("allowed origin", AllowedOrigin), ("PWA session preflight", PwaSessionPreflight),
    ("upstream Apps Script success", UpstreamSuccess), ("upstream Apps Script timeout", UpstreamTimeout),
    ("upstream 429", () => RetryStatus(HttpStatusCode.TooManyRequests)), ("upstream 503", () => RetryStatus(HttpStatusCode.ServiceUnavailable)),
    ("retry budget exhaustion", RetryBudget), ("mutation is not retried", MutationNotRetried), ("malformed upstream JSON", MalformedJson),
    ("fresh Google credential is reused", FreshCredential), ("expired Google credential refreshes", CredentialRefresh), ("failed Google refresh fails closed", CredentialRefreshFailure), ("portable secrets are encrypted", PortableSecrets),
    ("auth-required request", AuthRequired), ("development session", DevelopmentSession), ("PWA client session exchange", ClientSessionExchange), ("client origin rejection", ClientOriginRejection), ("invalid session", InvalidSession), ("session expiration", SessionExpiration),
    ("secret redaction", Redaction), ("no token appears in logs", NoTokenLogging), ("cancellation", Cancellation),
    ("graceful shutdown", GracefulShutdown), ("background worker does not busy-loop", WorkerBounded)
};

var failures = new List<string>();
foreach (var test in tests)
{
    try { await test.Run(); Console.WriteLine($"PASS  {test.Name}"); }
    catch (Exception ex) { failures.Add(test.Name); Console.WriteLine($"FAIL  {test.Name}: {ex.Message}"); }
}
Console.WriteLine($"RESULT: {tests.Count - failures.Count}/{tests.Count} passed");
return failures.Count == 0 ? 0 : 1;

static async Task ControlSurface() => await WithApp(async client =>
{
    var html = await client.GetStringAsync("/");
    Check(html.Contains("GPOS HELPER CONTROL") && html.Contains("AEGIS COMPANION") && html.Contains("/app.css") && html.Contains("calendarForm") && html.Contains("snapshotRefresh"), "control surface was not served");
    using var css = await client.GetAsync("/app.css"); using var js = await client.GetAsync("/app.js");
    Check(css.IsSuccessStatusCode && css.Content.Headers.ContentType?.MediaType == "text/css", "control surface stylesheet was not served");
    Check(js.IsSuccessStatusCode && js.Content.Headers.ContentType?.MediaType is "text/javascript" or "application/javascript", "control surface script was not served");
});

static async Task Health() => await WithApp(async client =>
{
    var json = await client.GetFromJsonAsync<JsonElement>("/api/v1/health");
    Check(json.GetProperty("status").GetString() == "AVAILABLE", "status was not AVAILABLE");
    Check(json.GetProperty("service").GetString() == "gpos-helper", "wrong service name");
});

static async Task Probes() => await WithApp(async client =>
{
    var live = await client.GetFromJsonAsync<JsonElement>("/api/v1/live");
    var ready = await client.GetFromJsonAsync<JsonElement>("/api/v1/ready");
    Check(live.GetProperty("status").GetString() == "ALIVE", "liveness probe was not alive");
    Check(ready.GetProperty("status").GetString() == "READY", "development readiness probe was not ready");
});

static async Task Capabilities() => await WithApp(async client =>
{
    var json = await client.GetFromJsonAsync<JsonElement>("/api/v1/capabilities");
    var values = json.GetProperty("capabilities").EnumerateArray().Select(x => x.GetString()).ToArray();
    Check(values.Contains("helper.health") && values.Contains("helper.readiness") && values.Contains("helper.activity") && values.Contains("aegis.proxy"), "required capabilities missing");
    Check(!values.Contains("calendar.write"), "unimplemented write capability advertised");
});

static async Task SafeActivity() => await WithApp(async client =>
{
    await client.GetAsync("/api/v1/health?token=never-expose-this");
    var json = await client.GetFromJsonAsync<JsonElement>("/api/v1/activity?limit=50&include_routine=true"); var serialized = json.ToString();
    Check(json.GetProperty("entries").GetArrayLength() > 0 && serialized.Contains("/api/v1/health"), "request activity was not recorded");
    Check(!serialized.Contains("never-expose-this") && !serialized.Contains("token"), "request activity exposed a query or secret");
});

static async Task SetupReadiness() => await WithApp(async client =>
{
    var json = await client.GetFromJsonAsync<JsonElement>("/api/v1/setup/status"); var serialized = json.ToString();
    Check(!json.GetProperty("production_ready").GetBoolean() && json.GetProperty("checks").GetArrayLength() == 7, "setup readiness was incorrect");
    Check(!serialized.Contains("client-secret") && !serialized.Contains("@example.com"), "setup readiness exposed secret configuration");
});

static async Task Config() => await WithAppServices((client, app) =>
{
    var value = app.Services.GetRequiredService<IOptions<HelperOptions>>().Value;
    Check(value.Port > 0 && value.DevelopmentMode && value.UpstreamMaxRetries == 2, "configuration precedence failed");
    return Task.CompletedTask;
});

static Task UnknownOrigin() => Cors("https://unknown.example", false);
static Task AllowedOrigin() => Cors("https://cokkles.github.io", true);
static async Task PwaSessionPreflight() => await WithApp(async client =>
{
    using var request = new HttpRequestMessage(HttpMethod.Options, "/api/v1/aegis/dashboard");
    request.Headers.Add("Origin", "https://cokkles.github.io"); request.Headers.Add("Access-Control-Request-Method", "GET"); request.Headers.Add("Access-Control-Request-Headers", "X-GPOS-Session");
    using var response = await client.SendAsync(request);
    Check(response.Headers.TryGetValues("Access-Control-Allow-Origin", out var origins) && origins.Single() == "https://cokkles.github.io", "PWA origin was not returned exactly");
    Check(response.Headers.TryGetValues("Access-Control-Allow-Headers", out var headers) && headers.Any(x => x.Contains("X-GPOS-Session", StringComparison.OrdinalIgnoreCase)), "helper session header was not allowed");
    Check(response.Headers.TryGetValues("Access-Control-Allow-Credentials", out var credentials) && credentials.Contains("true"), "credentialed PWA preflight was incomplete");
});
static async Task Cors(string origin, bool expected) => await WithApp(async client =>
{
    using var request = new HttpRequestMessage(HttpMethod.Options, "/api/v1/health");
    request.Headers.Add("Origin", origin); request.Headers.Add("Access-Control-Request-Method", "GET");
    using var response = await client.SendAsync(request);
    var present = response.Headers.TryGetValues("Access-Control-Allow-Origin", out var values) && values.Contains(origin);
    Check(present == expected, "CORS decision was incorrect");
});

static async Task AuthRequired() => await WithApp(async client => Check((await client.GetAsync("/api/v1/aegis/health")).StatusCode == HttpStatusCode.Unauthorized, "protected route did not require auth"));
static async Task InvalidSession() => await WithApp(async client =>
{
    client.DefaultRequestHeaders.Add("X-GPOS-Session", "invalid");
    Check((await client.GetAsync("/api/v1/aegis/health")).StatusCode == HttpStatusCode.Unauthorized, "invalid session accepted");
});

static async Task DevelopmentSession() => await WithApp(async client =>
{
    using var response = await client.GetAsync("/api/v1/auth/callback?code=development&state=development");
    Check(response.StatusCode == HttpStatusCode.OK && response.Headers.TryGetValues("Set-Cookie", out _), "development session was not created");
});

static async Task ClientSessionExchange() => await WithApp(async client =>
{
    var verifier = new string('v', 64); var challenge = Convert.ToBase64String(SHA256.HashData(Encoding.ASCII.GetBytes(verifier))).TrimEnd('=').Replace('+', '-').Replace('/', '_');
    var start = await (await client.PostAsJsonAsync("/api/v1/auth/client/start", new { returnUrl = "https://cokkles.github.io/aegis-itinerary-project/", codeChallenge = challenge })).Content.ReadFromJsonAsync<JsonElement>();
    Check(start.GetProperty("authorization_url").GetString()!.Contains("state=development"), "client login did not start");
    using var callback = await client.GetAsync("/api/v1/auth/callback?code=development&state=development");
    var location = callback.Headers.Location?.ToString() ?? ""; var code = location.Split("gpos_code=").LastOrDefault();
    Check(callback.StatusCode == HttpStatusCode.Redirect && location.StartsWith("https://cokkles.github.io/") && !string.IsNullOrWhiteSpace(code), "client callback did not return a one-time code");
    var exchange = await client.PostAsJsonAsync("/api/v1/auth/client/exchange", new { code, codeVerifier = verifier }); var body = await exchange.Content.ReadFromJsonAsync<JsonElement>();
    Check(exchange.IsSuccessStatusCode && body.GetProperty("session_token").GetString()!.Length > 30, "client code exchange failed");
    Check((await client.PostAsJsonAsync("/api/v1/auth/client/exchange", new { code, codeVerifier = verifier })).StatusCode == HttpStatusCode.Unauthorized, "client code replay was accepted");
});

static async Task ClientOriginRejection() => await WithApp(async client =>
{
    var response = await client.PostAsJsonAsync("/api/v1/auth/client/start", new { returnUrl = "https://evil.example/callback", codeChallenge = new string('a', 43) });
    Check(response.StatusCode == HttpStatusCode.BadRequest, "unknown client return origin was accepted");
});

static Task SessionExpiration()
{
    var time = new MutableTimeProvider(DateTimeOffset.UtcNow); var store = new HelperSessionStore(Options.Create(new HelperOptions { SessionMinutes = 5 }), time);
    var token = store.Create(new("subject", "a@example.com", "A")).Token; time.Advance(TimeSpan.FromMinutes(6)); Check(store.Validate(token) is null, "expired session accepted"); return Task.CompletedTask;
}

static Task Redaction()
{
    var output = SecretRedactor.Redact("Bearer abc.def {\"refresh_token\":\"secret-value\"} api_key=topsecret");
    Check(!output.Contains("abc.def") && !output.Contains("secret-value") && !output.Contains("topsecret") && output.Contains("[REDACTED]"), "secret redaction failed"); return Task.CompletedTask;
}

static async Task NoTokenLogging()
{
    const string token = "never-log-this-token"; var sink = new LogSink();
    var gateway = Gateway(new SequenceHandler(SequenceHandler.Json(HttpStatusCode.OK, "{\"ok\":true}")), logger: sink, credential: token);
    await gateway.GetAsync("get_health", default);
    Check(!sink.Text.Contains(token, StringComparison.Ordinal), "token appeared in logs");
}

static async Task UpstreamSuccess()
{
    var handler = new SequenceHandler(SequenceHandler.Json(HttpStatusCode.OK, "{\"ok\":true}")); var result = await Gateway(handler).GetAsync("get_health", default);
    Check(result.Success && result.Body!.Value.GetProperty("ok").GetBoolean() && handler.Calls == 1, "successful response not returned");
}

static async Task UpstreamTimeout()
{
    var handler = new SequenceHandler(async (_, ct) => { await Task.Delay(TimeSpan.FromSeconds(5), ct); return new(HttpStatusCode.OK); });
    var result = await Gateway(handler, timeout: 1).GetAsync("get_health", default); Check(result.ErrorCategory == "timeout", "timeout not terminal");
}

static async Task RetryStatus(HttpStatusCode status)
{
    var handler = new SequenceHandler(SequenceHandler.Json(status, "{}")); var result = await Gateway(handler, retries: 0).GetAsync("get_health", default);
    Check(!result.Success && result.StatusCode == (int)status && handler.Calls == 1, $"HTTP {(int)status} not handled");
}

static async Task RetryBudget()
{
    var handler = new SequenceHandler(SequenceHandler.Json(HttpStatusCode.ServiceUnavailable, "{}")); var result = await Gateway(handler).GetAsync("get_health", default);
    Check(result.ErrorCategory == "retry_budget_exhausted" && handler.Calls == 3, "retry budget was not bounded");
}

static async Task MutationNotRetried()
{
    var handler = new SequenceHandler(SequenceHandler.Json(HttpStatusCode.ServiceUnavailable, "{}"));
    var result = await Gateway(handler).PostAsync(new { action = "calendar_ai", question = "tomorrow" }, false, default);
    Check(!result.Success && handler.Calls == 1, "mutation was retried");
}

static async Task MalformedJson()
{
    var handler = new SequenceHandler(SequenceHandler.Json(HttpStatusCode.OK, "not-json")); var result = await Gateway(handler).GetAsync("get_health", default);
    Check(result.ErrorCategory == "malformed_response" && handler.Calls == 1, "malformed JSON not terminal");
}

static async Task FreshCredential()
{
    var time = new MutableTimeProvider(DateTimeOffset.UtcNow); var store = new MemorySecretStore();
    await store.SaveAsync("google-token", JsonSerializer.Serialize(new StoredGoogleToken("access", "refresh", "fresh-id-token", time.GetUtcNow().AddMinutes(30))), default);
    var handler = new SequenceHandler(SequenceHandler.Json(HttpStatusCode.InternalServerError, "{}"));
    var provider = CredentialProvider(store, handler, time); var token = await provider.GetIdTokenAsync(default);
    Check(token == "fresh-id-token" && handler.Calls == 0, "fresh token was not reused");
}

static async Task CredentialRefresh()
{
    var time = new MutableTimeProvider(DateTimeOffset.UtcNow); var store = new MemorySecretStore();
    await store.SaveAsync("google-token", JsonSerializer.Serialize(new StoredGoogleToken("old-access", "refresh-value", "expired-id-token", time.GetUtcNow().AddMinutes(-1))), default);
    var handler = new SequenceHandler(SequenceHandler.Json(HttpStatusCode.OK, "{\"access_token\":\"new-access\",\"id_token\":\"new-id-token\",\"expires_in\":3600}"));
    var provider = CredentialProvider(store, handler, time); var token = await provider.GetIdTokenAsync(default);
    var saved = JsonSerializer.Deserialize<StoredGoogleToken>((await store.GetAsync("google-token", default))!);
    Check(token == "new-id-token" && saved?.RefreshToken == "refresh-value" && saved.ExpiresAt > time.GetUtcNow() && handler.Calls == 1, "credential refresh did not rotate safely");
}

static async Task CredentialRefreshFailure()
{
    var time = new MutableTimeProvider(DateTimeOffset.UtcNow); var store = new MemorySecretStore();
    await store.SaveAsync("google-token", JsonSerializer.Serialize(new StoredGoogleToken("old-access", "refresh-value", "expired-id-token", time.GetUtcNow().AddMinutes(-1))), default);
    var handler = new SequenceHandler(SequenceHandler.Json(HttpStatusCode.ServiceUnavailable, "{}"));
    var token = await CredentialProvider(store, handler, time).GetIdTokenAsync(default);
    Check(token is null && handler.Calls == 1, "failed refresh did not fail closed");
}

static async Task PortableSecrets()
{
    var root = Path.Combine(AppContext.BaseDirectory, "portable-secret-test-" + Guid.NewGuid().ToString("N")); Directory.CreateDirectory(root);
    var keyFile = Path.Combine(root, "key"); var storePath = Path.Combine(root, "store"); var secret = "refresh-token-never-plaintext";
    await File.WriteAllTextAsync(keyFile, Convert.ToBase64String(RandomNumberGenerator.GetBytes(32)));
    try
    {
        using var store = new PortableAesSecretStore(Options.Create(new HelperOptions { SecretStorePath = storePath, SecretStoreKeyFile = keyFile }));
        await store.SaveAsync("google-token", secret, default); var file = Directory.GetFiles(storePath).Single(); var bytes = await File.ReadAllBytesAsync(file);
        Check(!Encoding.UTF8.GetString(bytes).Contains(secret) && await store.GetAsync("google-token", default) == secret, "portable secret was not encrypted and recovered");
        await store.DeleteAsync("google-token", default); Check(!File.Exists(file), "portable secret was not deleted");
    }
    finally { Directory.Delete(root, true); }
}

static async Task Cancellation()
{
    var handler = new SequenceHandler(async (_, ct) => { await Task.Delay(TimeSpan.FromSeconds(5), ct); return new(HttpStatusCode.OK); }); using var cts = new CancellationTokenSource(20);
    try { await Gateway(handler, timeout: 30).GetAsync("get_health", cts.Token); throw new Exception("cancellation did not propagate"); }
    catch (OperationCanceledException) when (cts.IsCancellationRequested) { }
}

static async Task GracefulShutdown() => await WithAppServices((_, _) => Task.CompletedTask);

static async Task WorkerBounded() => await WithAppServices(async (_, app) =>
{
    await Task.Delay(150); var heartbeat = app.Services.GetRequiredService<HeartbeatState>(); Check(heartbeat.Count == 0, "heartbeat worker is busy-looping");
});

static AppsScriptGateway Gateway(HttpMessageHandler handler, int timeout = 1, int retries = 2, ILogger<AppsScriptGateway>? logger = null, string credential = "test-google-id-token")
{
    return new(new SingleClientFactory(new HttpClient(handler)), Options.Create(new HelperOptions { AppsScriptEndpoint = "https://example.invalid/exec", UpstreamTimeoutSeconds = timeout, UpstreamMaxRetries = retries }), new FixedCredentialProvider(credential), logger ?? NullLogger<AppsScriptGateway>.Instance);
}

static GoogleCredentialProvider CredentialProvider(MemorySecretStore store, HttpMessageHandler handler, TimeProvider time) => new(
    store,
    new SingleClientFactory(new HttpClient(handler)),
    Options.Create(new HelperOptions { GoogleOAuth = new GoogleOAuthOptions { ClientId = "client-id", ClientSecret = "client-secret" } }),
    time,
    NullLogger<GoogleCredentialProvider>.Instance);

static async Task WithApp(Func<HttpClient, Task> action) => await WithAppServices((client, _) => action(client));
static async Task WithAppServices(Func<HttpClient, WebApplication, Task> action)
{
    var port = FreePort();
    var contentRoot = Path.GetFullPath(Path.Combine(AppContext.BaseDirectory, "..", "..", "..", "..", "..", "src", "Gpos.Helper"));
    var args = new[] { $"--contentRoot={contentRoot}", $"--Helper:Port={port}", "--Helper:ListenAddress=127.0.0.1", "--Helper:DevelopmentMode=true", "--Helper:LaunchBrowser=false", "--Helper:HeartbeatSeconds=5", "--Helper:SessionMinutes=60", "--Helper:UpstreamMaxRetries=2", "--Helper:AllowedOrigins:0=https://cokkles.github.io" };
    await using var app = Gpos.Helper.Program.Build(args, services => { services.RemoveAll<ISecretStore>(); services.AddSingleton<ISecretStore, MemorySecretStore>(); });
    await app.StartAsync();
    try { using var client = new HttpClient(new HttpClientHandler { AllowAutoRedirect = false }) { BaseAddress = new Uri($"http://127.0.0.1:{port}") }; await action(client, app); }
    finally { using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(5)); await app.StopAsync(cts.Token); }
}

static int FreePort() { var listener = new TcpListener(IPAddress.Loopback, 0); listener.Start(); var port = ((IPEndPoint)listener.LocalEndpoint).Port; listener.Stop(); return port; }
static void Check(bool condition, string message) { if (!condition) throw new Exception(message); }

sealed class MemorySecretStore : ISecretStore
{
    private readonly Dictionary<string, string> _values = [];
    public Task SaveAsync(string name, string value, CancellationToken cancellationToken) { _values[name] = value; return Task.CompletedTask; }
    public Task<string?> GetAsync(string name, CancellationToken cancellationToken) => Task.FromResult(_values.GetValueOrDefault(name));
    public Task DeleteAsync(string name, CancellationToken cancellationToken) { _values.Remove(name); return Task.CompletedTask; }
}

sealed class SequenceHandler(params Func<HttpRequestMessage, CancellationToken, Task<HttpResponseMessage>>[] responses) : HttpMessageHandler
{
    private int _calls; public int Calls => _calls;
    protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken) { var index = Interlocked.Increment(ref _calls) - 1; return responses[Math.Min(index, responses.Length - 1)](request, cancellationToken); }
    public static Func<HttpRequestMessage, CancellationToken, Task<HttpResponseMessage>> Json(HttpStatusCode code, string json) => (_, _) => Task.FromResult(new HttpResponseMessage(code) { Content = new StringContent(json) });
}

sealed class SingleClientFactory(HttpClient client) : IHttpClientFactory { public HttpClient CreateClient(string name) => client; }
sealed class FixedCredentialProvider(string? token) : IGoogleCredentialProvider
{
    public Task<string?> GetIdTokenAsync(CancellationToken cancellationToken) => Task.FromResult(token);
    public Task<string> GetStateAsync(CancellationToken cancellationToken) => Task.FromResult(token is null ? "ABSENT" : "VALID");
    public Task ClearAsync(CancellationToken cancellationToken) => Task.CompletedTask;
}
sealed class MutableTimeProvider(DateTimeOffset now) : TimeProvider { public override DateTimeOffset GetUtcNow() => now; public void Advance(TimeSpan amount) => now += amount; }
sealed class LogSink : ILogger<AppsScriptGateway>
{
    public string Text { get; private set; } = "";
    public IDisposable? BeginScope<TState>(TState state) where TState : notnull => null;
    public bool IsEnabled(LogLevel logLevel) => true;
    public void Log<TState>(LogLevel logLevel, EventId eventId, TState state, Exception? exception, Func<TState, Exception?, string> formatter) => Text += formatter(state, exception);
}

