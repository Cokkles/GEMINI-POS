using System.Diagnostics;
using System.Net;
using System.Text;
using System.Text.Json;
using Microsoft.Extensions.Options;

namespace Gpos.Helper;

public interface IAppsScriptGateway
{
    Task<UpstreamResult> GetAsync(string action, CancellationToken cancellationToken);
    Task<UpstreamResult> PostAsync(object payload, bool idempotent, CancellationToken cancellationToken);
}

public sealed class AppsScriptGateway(IHttpClientFactory clients, IOptions<HelperOptions> options, IGoogleCredentialProvider credentials, ILogger<AppsScriptGateway> logger) : IAppsScriptGateway
{
    public Task<UpstreamResult> GetAsync(string action, CancellationToken cancellationToken) =>
        SendAsync(new Dictionary<string, object?> { ["action"] = action }, true, cancellationToken);

    public Task<UpstreamResult> PostAsync(object payload, bool idempotent, CancellationToken cancellationToken) => SendAsync(payload, idempotent, cancellationToken);

    private async Task<UpstreamResult> SendAsync(object payload, bool idempotent, CancellationToken cancellationToken)
    {
        if (string.IsNullOrWhiteSpace(options.Value.AppsScriptEndpoint)) return new(false, 503, null, "not_configured", "Apps Script endpoint is not configured.");
        var authToken = await credentials.GetIdTokenAsync(cancellationToken);
        if (string.IsNullOrWhiteSpace(authToken)) return new(false, 401, null, "upstream_auth_required", "Google authentication is required.");
        var envelope = JsonSerializer.Deserialize<Dictionary<string, object?>>(JsonSerializer.Serialize(payload)) ?? [];
        envelope["auth_token"] = authToken;
        var watch = Stopwatch.StartNew(); var retries = 0;
        while (true)
        {
            using var timeout = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
            timeout.CancelAfter(TimeSpan.FromSeconds(options.Value.UpstreamTimeoutSeconds));
            try
            {
                using var request = new HttpRequestMessage(HttpMethod.Post, options.Value.AppsScriptEndpoint)
                { Content = new StringContent(JsonSerializer.Serialize(envelope), Encoding.UTF8, "text/plain") };
                using var response = await clients.CreateClient("apps-script").SendAsync(request, HttpCompletionOption.ResponseHeadersRead, timeout.Token);
                var retryable = response.StatusCode is HttpStatusCode.TooManyRequests or HttpStatusCode.ServiceUnavailable;
                if (retryable && idempotent && retries < options.Value.UpstreamMaxRetries)
                {
                    retries++; await DelayAsync(retries, cancellationToken); continue;
                }
                var text = await ReadBoundedAsync(response.Content, options.Value.MaxUpstreamResponseBytes, timeout.Token);
                if (text is null)
                {
                    Log("upstream_response_too_large", response.StatusCode, watch.ElapsedMilliseconds, retries);
                    return new(false, 502, null, "upstream_response_too_large", "Upstream response exceeded the configured limit.", retries, watch.ElapsedMilliseconds);
                }
                JsonElement? body;
                try { body = JsonSerializer.Deserialize<JsonElement>(text); }
                catch (JsonException)
                {
                    Log("malformed_response", response.StatusCode, watch.ElapsedMilliseconds, retries);
                    return new(false, (int)response.StatusCode, null, "malformed_response", "Upstream returned malformed JSON.", retries, watch.ElapsedMilliseconds);
                }
                var success = response.IsSuccessStatusCode;
                var category = success ? null : retryable && retries >= options.Value.UpstreamMaxRetries ? "retry_budget_exhausted" : "upstream_http_error";
                Log(category ?? "success", response.StatusCode, watch.ElapsedMilliseconds, retries);
                return new(success, (int)response.StatusCode, body, category, success ? null : "Upstream request failed.", retries, watch.ElapsedMilliseconds);
            }
            catch (OperationCanceledException) when (!cancellationToken.IsCancellationRequested)
            {
                Log("timeout", 0, watch.ElapsedMilliseconds, retries);
                return new(false, 504, null, "timeout", "Upstream request timed out.", retries, watch.ElapsedMilliseconds);
            }
            catch (HttpRequestException ex)
            {
                if (idempotent && retries < options.Value.UpstreamMaxRetries) { retries++; await DelayAsync(retries, cancellationToken); continue; }
                logger.LogWarning("Upstream request unavailable category={Category} duration_ms={DurationMs} retry_count={RetryCount} detail={Detail}", "upstream_unavailable", watch.ElapsedMilliseconds, retries, SecretRedactor.Redact(ex.Message));
                return new(false, 503, null, "upstream_unavailable", "Apps Script is unavailable.", retries, watch.ElapsedMilliseconds);
            }
        }
    }

    private static Task DelayAsync(int retry, CancellationToken token) => Task.Delay(TimeSpan.FromMilliseconds(Math.Min(2000, 100 * Math.Pow(2, retry - 1) + Random.Shared.Next(25, 125))), token);
    private static async Task<string?> ReadBoundedAsync(HttpContent content, int maximumBytes, CancellationToken cancellationToken)
    {
        if (content.Headers.ContentLength > maximumBytes) return null;
        await using var source = await content.ReadAsStreamAsync(cancellationToken);
        using var buffer = new MemoryStream();
        var chunk = new byte[8192];
        while (true)
        {
            var read = await source.ReadAsync(chunk.AsMemory(0, chunk.Length), cancellationToken);
            if (read == 0) break;
            if (buffer.Length + read > maximumBytes) return null;
            await buffer.WriteAsync(chunk.AsMemory(0, read), cancellationToken);
        }
        return Encoding.UTF8.GetString(buffer.ToArray());
    }
    private void Log(string result, HttpStatusCode status, long duration, int retries) => logger.LogInformation("Upstream request service={UpstreamService} result={Result} status={Status} upstream_duration_ms={DurationMs} retry_count={RetryCount}", "apps_script", result, (int)status, duration, retries);
}

