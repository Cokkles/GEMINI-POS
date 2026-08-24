using System.Diagnostics;
using System.Net;
using System.Reflection;
using System.Runtime.InteropServices;
using Microsoft.AspNetCore.HttpOverrides;
using Microsoft.AspNetCore.WebUtilities;
using Microsoft.Extensions.Options;

namespace Gpos.Helper;

public partial class Program
{
    private static readonly DateTimeOffset StartedAt = DateTimeOffset.UtcNow;
    public static void Main(string[] args)
    {
        var app = Build(args);
        app.Run();
    }

    public static WebApplication Build(string[] args, Action<IServiceCollection>? configureTests = null)
    {
        var builder = WebApplication.CreateBuilder(args);
        builder.Logging.ClearProviders();
        builder.Logging.AddJsonConsole();
        builder.Configuration.AddJsonFile("config/appsettings.json", optional: true, reloadOnChange: true).AddEnvironmentVariables("GPOS_");
        builder.Services.AddOptions<HelperOptions>().Bind(builder.Configuration.GetSection(HelperOptions.Section)).ValidateDataAnnotations().Validate(o => IPAddress.TryParse(o.ListenAddress, out _), "ListenAddress must be an IP address.").ValidateOnStart();
        var early = builder.Configuration.GetSection(HelperOptions.Section).Get<HelperOptions>() ?? new();
        builder.WebHost.UseUrls($"http://{early.ListenAddress}:{early.Port}");
        builder.Services.AddSingleton(TimeProvider.System);
        builder.Services.AddSingleton<HelperSessionStore>();
        builder.Services.AddSingleton<HeartbeatState>();
        builder.Services.AddSingleton<RequestActivityStore>();
        builder.Services.AddSingleton<ClientSessionFlowStore>();
        builder.Services.AddHostedService<HeartbeatWorker>();
        builder.Services.AddSingleton<ISecretStore>(sp =>
        {
            if (OperatingSystem.IsWindows()) return ActivatorUtilities.CreateInstance<WindowsDpapiSecretStore>(sp);
            var configured = sp.GetRequiredService<IOptions<HelperOptions>>();
            return configured.Value.DevelopmentMode ? new EphemeralSecretStore() : new PortableAesSecretStore(configured);
        });
        builder.Services.AddSingleton<IGoogleCredentialProvider, GoogleCredentialProvider>();
        builder.Services.AddSingleton<IAuthProvider>(sp => sp.GetRequiredService<IOptions<HelperOptions>>().Value.DevelopmentMode ? new DevelopmentAuthProvider() : ActivatorUtilities.CreateInstance<GoogleOAuthProvider>(sp));
        builder.Services.AddHttpClient("apps-script", c => c.Timeout = Timeout.InfiniteTimeSpan);
        builder.Services.AddHttpClient("oauth", c => c.Timeout = TimeSpan.FromSeconds(20));
        builder.Services.AddSingleton<IAppsScriptGateway, AppsScriptGateway>();
        builder.Services.AddCors(o => o.AddPolicy("frontend", p =>
        {
            if (early.AllowedOrigins.Length > 0) p.WithOrigins(early.AllowedOrigins).AllowAnyHeader().AllowAnyMethod().AllowCredentials();
        }));
        configureTests?.Invoke(builder.Services);
        var app = builder.Build();

        app.Use(async (context, next) =>
        {
            var watch = Stopwatch.StartNew();
            var requestId = context.TraceIdentifier;
            context.Response.Headers["X-Request-ID"] = requestId;
            try { await next(); }
            catch (Exception ex)
            {
                app.Logger.LogError("Request failed request_id={RequestId} endpoint={Endpoint} method={Method} duration_ms={DurationMs} result={Result} error_category={Category} detail={Detail}", requestId, context.Request.Path.Value, context.Request.Method, watch.ElapsedMilliseconds, "failure", ex.GetType().Name, SecretRedactor.Redact(ex.Message));
                if (!context.Response.HasStarted) await Results.Problem(statusCode: 500, title: "Internal helper error").ExecuteAsync(context);
            }
            finally
            {
                var endpoint = context.Request.Path.Value ?? "/";
                app.Services.GetRequiredService<RequestActivityStore>().Record(context.Request.Method, endpoint, context.Response.StatusCode, watch.ElapsedMilliseconds);
                app.Logger.LogInformation("Request complete request_id={RequestId} endpoint={Endpoint} method={Method} duration_ms={DurationMs} result={Result}", requestId, endpoint, context.Request.Method, watch.ElapsedMilliseconds, context.Response.StatusCode);
            }
        });
        app.UseCors("frontend");
        app.UseDefaultFiles();
        app.UseStaticFiles();

        var api = app.MapGroup("/api/v1");
        api.MapGet("/health", (IAuthProvider auth) => Results.Ok(new
        {
            status = "AVAILABLE", service = "gpos-helper", version = Version(), uptime_seconds = (long)(DateTimeOffset.UtcNow - StartedAt).TotalSeconds,
            auth = auth.Mode, upstream = new { apps_script = string.IsNullOrWhiteSpace(early.AppsScriptEndpoint) ? "NOT_CONFIGURED" : "CONFIGURED", google = auth.Mode == "google_oauth" ? "CONFIGURED" : "DEVELOPMENT_MOCK" }
        }));
        api.MapGet("/capabilities", () => Results.Ok(new { api_version = "v1", capabilities = new[] { "helper.health", "helper.auth", "helper.background_jobs", "helper.setup", "helper.activity", "aegis.proxy", "calendar.read" } }));
        api.MapGet("/activity", (RequestActivityStore activity, int? limit) => Results.Ok(new { entries = activity.Recent(limit ?? 20) }));
        api.MapGet("/setup/status", (IOptions<HelperOptions> configured) =>
        {
            var value = configured.Value;
            var listenIsLoopback = IPAddress.TryParse(value.ListenAddress, out var address) && IPAddress.IsLoopback(address);
            var endpointIsHttps = Uri.TryCreate(value.AppsScriptEndpoint, UriKind.Absolute, out var endpoint) && endpoint.Scheme == Uri.UriSchemeHttps;
            var redirectIsLoopback = Uri.TryCreate(value.GoogleOAuth.RedirectUri, UriKind.Absolute, out var redirect) && IPAddress.TryParse(redirect.Host, out var redirectAddress) && IPAddress.IsLoopback(redirectAddress);
            var secretStoreReady = OperatingSystem.IsWindows() || value.DevelopmentMode || (Directory.Exists(value.SecretStorePath) && File.Exists(value.SecretStoreKeyFile));
            var checks = new[]
            {
                new SetupCheck("production_mode", !value.DevelopmentMode, "Development mock must be disabled."),
                new SetupCheck("loopback_listener", listenIsLoopback, "Listener must remain on loopback."),
                new SetupCheck("apps_script_endpoint", endpointIsHttps, "A secure Apps Script endpoint is required."),
                new SetupCheck("google_oauth_client", !string.IsNullOrWhiteSpace(value.GoogleOAuth.ClientId), "A Desktop OAuth client ID is required."),
                new SetupCheck("identity_allowlist", value.AllowedEmails.Length > 0, "At least one allowed Google identity is required."),
                new SetupCheck("loopback_callback", redirectIsLoopback, "OAuth callback must use a loopback host."),
                new SetupCheck("secret_store", secretStoreReady, OperatingSystem.IsWindows() ? "Windows DPAPI is available." : value.DevelopmentMode ? "Development secrets are memory-only." : "Encrypted storage and mounted key are required.")
            };
            return Results.Ok(new { production_ready = checks.All(x => x.Ready), mode = value.DevelopmentMode ? "DEVELOPMENT" : "PRODUCTION", checks });
        });
        api.MapGet("/diagnostics", (IAuthProvider auth, HeartbeatState heartbeat) => Results.Ok(new
        {
            service = "gpos-helper", version = Version(), runtime = Environment.Version.ToString(), os = Environment.OSVersion.Platform.ToString(), process_architecture = RuntimeInformation.ProcessArchitecture.ToString(),
            listen_address = early.ListenAddress, port = early.Port, development_mode = early.DevelopmentMode, auth = auth.Mode, allowed_origins = early.AllowedOrigins,
            heartbeat = new { heartbeat.Count, last_beat = heartbeat.LastBeat }, uptime_seconds = (long)(DateTimeOffset.UtcNow - StartedAt).TotalSeconds
        }));
        api.MapGet("/auth/status", async (HttpContext ctx, HelperSessionStore sessions, IAuthProvider auth, IGoogleCredentialProvider credentials, CancellationToken ct) =>
        {
            var identity = sessions.Validate(SessionToken(ctx));
            var credential = auth.Mode == "development_mock" ? "DEVELOPMENT_MOCK" : await credentials.GetStateAsync(ct);
            return Results.Ok(new { authenticated = identity is not null, provider = auth.Mode, credential, identity = identity is null ? null : new { identity.Email, identity.DisplayName } });
        });
        api.MapPost("/auth/login", async (IAuthProvider auth, CancellationToken ct) =>
        {
            var url = await auth.BeginLoginAsync(ct);
            if (early.LaunchBrowser && !early.DevelopmentMode && OperatingSystem.IsWindows()) Process.Start(new ProcessStartInfo(url) { UseShellExecute = true });
            return Results.Ok(new { status = "LOGIN_PENDING", authorization_url = url });
        });
        api.MapPost("/auth/client/start", async (ClientLoginRequest request, IAuthProvider auth, ClientSessionFlowStore flows, CancellationToken ct) =>
        {
            if (!AllowedReturnUrl(request.ReturnUrl, early.AllowedOrigins)) return Results.BadRequest(new { error = "return_origin_not_allowed" });
            var url = await auth.BeginLoginAsync(ct);
            var parsed = new Uri(new Uri("http://127.0.0.1"), url);
            var providerState = QueryHelpers.ParseQuery(parsed.Query)["state"].FirstOrDefault();
            if (providerState is null || !flows.Prepare(providerState, request.ReturnUrl, request.CodeChallenge)) return Results.BadRequest(new { error = "invalid_client_flow" });
            if (early.LaunchBrowser && !early.DevelopmentMode && OperatingSystem.IsWindows()) Process.Start(new ProcessStartInfo(url) { UseShellExecute = true });
            return Results.Ok(new { status = "LOGIN_PENDING", authorization_url = url, exchange = "/api/v1/auth/client/exchange" });
        });
        api.MapPost("/auth/client/exchange", (ClientExchangeRequest request, ClientSessionFlowStore flows, HelperSessionStore sessions) =>
        {
            var identity = flows.Exchange(request.Code, request.CodeVerifier);
            if (identity is null) return Results.Json(new { error = "invalid_or_expired_client_code" }, statusCode: 401);
            var session = sessions.Create(identity);
            return Results.Ok(new { session_token = session.Token, expires_at = session.ExpiresAt, identity = new { identity.Email, identity.DisplayName } });
        });
        api.MapGet("/auth/callback", async (string code, string state, HttpContext ctx, IAuthProvider auth, HelperSessionStore sessions, ClientSessionFlowStore flows, CancellationToken ct) =>
        {
            var result = await auth.CompleteLoginAsync(code, state, ct);
            if (!result.Success || result.Identity is null) return Results.Json(new { authenticated = false, error = result.Error }, statusCode: 401);
            var clientRedirect = flows.Complete(state, result.Identity);
            if (clientRedirect is not null) return Results.Redirect(clientRedirect);
            var session = sessions.Create(result.Identity);
            ctx.Response.Cookies.Append("gpos_session", session.Token, new CookieOptions { HttpOnly = true, SameSite = SameSiteMode.Strict, Secure = !early.DevelopmentMode, Expires = session.ExpiresAt, Path = "/api/v1" });
            if (ctx.Request.GetTypedHeaders().Accept?.Any(x => x.MediaType.Value?.Equals("text/html", StringComparison.OrdinalIgnoreCase) == true) == true) return Results.Redirect("/");
            return Results.Ok(new { authenticated = true, expires_at = session.ExpiresAt, identity = new { result.Identity.Email, result.Identity.DisplayName } });
        });
        api.MapPost("/auth/logout", async (HttpContext ctx, HelperSessionStore sessions, IGoogleCredentialProvider credentials, CancellationToken ct) =>
        {
            sessions.Revoke(SessionToken(ctx));
            await credentials.ClearAsync(ct);
            ctx.Response.Cookies.Delete("gpos_session", new CookieOptions { Path = "/api/v1" });
            return Results.Ok(new { authenticated = false, credential = "ABSENT" });
        });

        api.MapGet("/aegis/dashboard", (HttpContext c, IAppsScriptGateway g, HelperSessionStore s, CancellationToken ct) => Proxy(c, s, () => g.GetAsync("get_dashboard", ct)));
        api.MapGet("/aegis/health", (HttpContext c, IAppsScriptGateway g, HelperSessionStore s, CancellationToken ct) => Proxy(c, s, () => g.GetAsync("get_health", ct)));
        api.MapPost("/aegis/calendar/query", async (HttpContext c, CalendarQuery query, IAppsScriptGateway g, HelperSessionStore s, CancellationToken ct) =>
        {
            var token = SessionToken(c); if (s.Validate(token) is null) return Results.Json(new { error = "auth_required" }, statusCode: 401);
            if (string.IsNullOrWhiteSpace(query.Question) || query.Question.Length > 2000) return Results.BadRequest(new { error = "invalid_question" });
            return ToResult(await g.PostAsync(new { action = "calendar_ai", question = query.Question, history = query.History ?? Array.Empty<object>() }, false, ct));
        });
        app.MapGet("/health", () => Results.Redirect("/api/v1/health"));
        app.MapGet("/capabilities", () => Results.Redirect("/api/v1/capabilities"));
        return app;
    }

    private static async Task<IResult> Proxy(HttpContext context, HelperSessionStore sessions, Func<Task<UpstreamResult>> operation)
    { return sessions.Validate(SessionToken(context)) is null ? Results.Json(new { error = "auth_required" }, statusCode: 401) : ToResult(await operation()); }
    private static IResult ToResult(UpstreamResult r) => r.Success ? Results.Json(r.Body) : Results.Json(new { error = r.ErrorCategory, message = r.Error, upstream_status = r.StatusCode, retry_count = r.RetryCount }, statusCode: r.StatusCode is >= 400 and <= 599 ? r.StatusCode : 502);
    private static string? SessionToken(HttpContext c) => c.Request.Headers["X-GPOS-Session"].FirstOrDefault() ?? c.Request.Cookies["gpos_session"];
    private static bool AllowedReturnUrl(string value, string[] allowedOrigins)
    {
        if (!Uri.TryCreate(value, UriKind.Absolute, out var uri) || uri.Scheme != Uri.UriSchemeHttps || !string.IsNullOrEmpty(uri.UserInfo)) return false;
        return allowedOrigins.Any(origin => Uri.TryCreate(origin, UriKind.Absolute, out var allowed) && string.Equals(uri.GetLeftPart(UriPartial.Authority), allowed.GetLeftPart(UriPartial.Authority), StringComparison.OrdinalIgnoreCase));
    }
    private static string Version() => Assembly.GetExecutingAssembly().GetName().Version?.ToString(3) ?? "0.1.0";
    public sealed record CalendarQuery(string Question, object[]? History);
    public sealed record SetupCheck(string Id, bool Ready, string Detail);
}
