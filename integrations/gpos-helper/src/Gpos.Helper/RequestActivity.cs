namespace Gpos.Helper;

public sealed record RequestActivity(DateTimeOffset Timestamp, string Method, string Endpoint, int Status, long DurationMs);

public sealed class RequestActivityStore(TimeProvider time)
{
    private const int Capacity = 50;
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

    public RequestActivity[] Recent(int limit = 20)
    {
        var bounded = Math.Clamp(limit, 1, Capacity);
        lock (gate) return entries.Reverse().Take(bounded).ToArray();
    }
}
