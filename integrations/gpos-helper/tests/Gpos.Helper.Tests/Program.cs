using System.Net;
using System.Net.Http.Json;
using System.Net.Sockets;
using System.Text.Json;
using Gpos.Helper;
using Microsoft.AspNetCore.Builder;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.DependencyInjection.Extensions;
using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Logging.Abstractions;
using Microsoft.Extensions.Options;

var tests = new List<(string Name, Func<Task> Run)>
{
    ("health endpoint", Health), ("capability endpoint", Capabilities), ("config loading", Config),
    ("unknown origin rejection", UnknownOrigin), ("allowed origin", AllowedOrigin),
    ("upstream Apps Script success", UpstreamSuccess), ("upstream Apps Script timeout", UpstreamTimeout),
    ("upstream 429", () => RetryStatus(HttpStatusCode.TooManyRequests)), ("upstream 503", () => RetryStatus(HttpStatusCode.ServiceUnavailable)),
    ("retry budget exhaustion", RetryBudget), ("mutation is not retried", MutationNotRetried), ("malformed upstream JSON", MalformedJson),
    ("auth-required request", AuthRequired), ("development session", DevelopmentSession), ("invalid session", InvalidSession), ("session expiration", SessionExpiration),
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

static async Task Health() => await WithApp(async client =>
{
    var json = await client.GetFromJsonAsync<JsonElement>("/api/v1/health");
    Check(json.GetProperty("status").GetString() == "AVAILABLE", "status was not AVAILABLE");
    Check(json.GetProperty("service").GetString() == "gpos-helper", "wrong service name");
});

static async Task Capabilities() => await WithApp(async client =>
{
    var json = await client.GetFromJsonAsync<JsonElement>("/api/v1/capabilities");
    var values = json.GetProperty("capabilities").EnumerateArray().Select(x => x.GetString()).ToArray();
    Check(values.Contains("helper.health") && values.Contains("aegis.proxy"), "required capabilities missing");
    Check(!values.Contains("calendar.write"), "unimplemented write capability advertised");
});

static async Task Config() => await WithAppServices((client, app) =>
{
    var value = app.Services.GetRequiredService<IOptions<HelperOptions>>().Value;
    Check(value.Port > 0 && value.DevelopmentMode && value.UpstreamMaxRetries == 2, "configuration precedence failed");
    return Task.CompletedTask;
});

static Task UnknownOrigin() => Cors("https://unknown.example", false);
static Task AllowedOrigin() => Cors("https://cokkles.github.io", true);
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
    var secretStore = new MemorySecretStore(); secretStore.SaveAsync("google-id-token", credential, default).GetAwaiter().GetResult();
    return new(new SingleClientFactory(new HttpClient(handler)), Options.Create(new HelperOptions { AppsScriptEndpoint = "https://example.invalid/exec", UpstreamTimeoutSeconds = timeout, UpstreamMaxRetries = retries }), secretStore, logger ?? NullLogger<AppsScriptGateway>.Instance);
}

static async Task WithApp(Func<HttpClient, Task> action) => await WithAppServices((client, _) => action(client));
static async Task WithAppServices(Func<HttpClient, WebApplication, Task> action)
{
    var port = FreePort();
    var args = new[] { $"--Helper:Port={port}", "--Helper:ListenAddress=127.0.0.1", "--Helper:DevelopmentMode=true", "--Helper:LaunchBrowser=false", "--Helper:HeartbeatSeconds=5", "--Helper:SessionMinutes=60", "--Helper:UpstreamMaxRetries=2", "--Helper:AllowedOrigins:0=https://cokkles.github.io" };
    await using var app = Gpos.Helper.Program.Build(args, services => { services.RemoveAll<ISecretStore>(); services.AddSingleton<ISecretStore, MemorySecretStore>(); });
    await app.StartAsync();
    try { using var client = new HttpClient { BaseAddress = new Uri($"http://127.0.0.1:{port}") }; await action(client, app); }
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
sealed class MutableTimeProvider(DateTimeOffset now) : TimeProvider { public override DateTimeOffset GetUtcNow() => now; public void Advance(TimeSpan amount) => now += amount; }
sealed class LogSink : ILogger<AppsScriptGateway>
{
    public string Text { get; private set; } = "";
    public IDisposable? BeginScope<TState>(TState state) where TState : notnull => null;
    public bool IsEnabled(LogLevel logLevel) => true;
    public void Log<TState>(LogLevel logLevel, EventId eventId, TState state, Exception? exception, Func<TState, Exception?, string> formatter) => Text += formatter(state, exception);
}

