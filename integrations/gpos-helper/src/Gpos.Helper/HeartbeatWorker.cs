using Microsoft.Extensions.Options;

namespace Gpos.Helper;

public sealed class HeartbeatState
{
    private long _count;
    private long _lastTicks;
    public long Count => Interlocked.Read(ref _count);
    public DateTimeOffset? LastBeat => Interlocked.Read(ref _lastTicks) is var ticks && ticks > 0 ? new DateTimeOffset(ticks, TimeSpan.Zero) : null;
    public void Beat(DateTimeOffset now) { Interlocked.Increment(ref _count); Interlocked.Exchange(ref _lastTicks, now.UtcTicks); }
}

public sealed class HeartbeatWorker(HeartbeatState state, IOptions<HelperOptions> options, TimeProvider timeProvider, ILogger<HeartbeatWorker> logger) : BackgroundService
{
    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        using var timer = new PeriodicTimer(TimeSpan.FromSeconds(options.Value.HeartbeatSeconds), timeProvider);
        try
        {
            while (await timer.WaitForNextTickAsync(stoppingToken))
            {
                state.Beat(timeProvider.GetUtcNow());
                logger.LogDebug("Background heartbeat count={HeartbeatCount}", state.Count);
            }
        }
        catch (OperationCanceledException) when (stoppingToken.IsCancellationRequested) { }
    }
}

