namespace Gpos.Helper;

public sealed record RequestActivity(DateTimeOffset Timestamp, string Method, string Endpoint, int Status, long DurationMs);

public sealed class RequestActivityStore(TimeProvider time)
{
    private const int Capacity = 50;
    private static readonly HashSet<string> RoutineEndpoints = new(StringComparer.OrdinalIgnoreCase)
    {
        "/api/v1/health", "/api/v1/live", "/api/v1/ready", "/api/v1/capabilities",
        "/api/v1/diagnostics", "/api/v1/setup/status", "/api/v1/activity"
    };
    private readonly object gate = new();
    private readonly Queue<RequestActivity> entries = new();

    public void Record(string method, string endpoint, int status, long durationMs)
    {
        var safeEndpoint = endpoint.StartsWith('/') ? endpoint : "/";
        lock (gate)
        {
            entries.Enqueue(new(time.GetUtcNow(), method, safeEndpoint, status, durationMs));
            while (entries.Count > Capacity) entries.Dequeue();
        }
    }

    public RequestActivity[] Recent(int limit = 20, bool includeRoutine = false)
    {
        var bounded = Math.Clamp(limit, 1, Capacity);
        lock (gate) return entries.Reverse().Where(x => includeRoutine || !RoutineEndpoints.Contains(x.Endpoint)).Take(bounded).ToArray();
    }
}

