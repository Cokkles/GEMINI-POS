package com.cokkles.gpos.platform.sync

import android.content.Context
import androidx.room.Room
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.cokkles.gpos.data.CanonicalCachePolicy
import com.cokkles.gpos.data.local.GposDatabase
import com.cokkles.gpos.data.remote.CalendarRangeEvent
import com.cokkles.gpos.data.remote.CalendarRangePayloadMapper
import com.cokkles.gpos.data.remote.CalendarRangeSnapshot
import com.cokkles.gpos.platform.notifications.GposDeepLinkTarget
import com.cokkles.gpos.platform.notifications.GposNotificationPublisher
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.TimeUnit
import org.json.JSONObject

object AppointmentReminderPolicy {
    const val LOOKAHEAD_MS = 7L * 24L * 60L * 60L * 1_000L
    val thresholdsMinutes = listOf(60, 15)

    fun triggerAt(startEpochMs: Long, thresholdMinutes: Int): Long =
        startEpochMs - TimeUnit.MINUTES.toMillis(thresholdMinutes.toLong())

    fun shouldSchedule(startEpochMs: Long, triggerAtEpochMs: Long, nowEpochMs: Long): Boolean =
        startEpochMs > nowEpochMs &&
            startEpochMs <= nowEpochMs + LOOKAHEAD_MS &&
            triggerAtEpochMs > nowEpochMs + 30_000L
}

/** Mirrors already-fetched timed Calendar events into best-effort local Android reminders. */
class AppointmentReminderScheduler(context: Context) {
    private val appContext = context.applicationContext
    private val workManager = WorkManager.getInstance(appContext)
    private val preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun replaceUpcoming(snapshot: CalendarRangeSnapshot, nowEpochMs: Long = System.currentTimeMillis()) {
        val currentIds = snapshot.events.mapTo(mutableSetOf()) { it.id }
        val tracked = readTracked()
        tracked.keys().asSequence().toList().forEach { workName ->
            val record = tracked.optJSONObject(workName) ?: return@forEach
            val localDate = record.optString("local_date")
            val eventId = record.optString("event_id")
            if (localDate >= snapshot.startDate && localDate < snapshot.endDate && eventId !in currentIds) {
                workManager.cancelUniqueWork(workName)
                tracked.remove(workName)
            }
        }

        snapshot.events.asSequence()
            .filterNot(CalendarRangeEvent::allDay)
            .forEach eventLoop@{ event ->
                val startEpochMs = event.start?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
                    ?: return@eventLoop
                val localDate = event.localDate ?: event.start?.take(10) ?: return@eventLoop
                AppointmentReminderPolicy.thresholdsMinutes.forEach thresholdLoop@{ threshold ->
                    val triggerAt = AppointmentReminderPolicy.triggerAt(startEpochMs, threshold)
                    if (!AppointmentReminderPolicy.shouldSchedule(startEpochMs, triggerAt, nowEpochMs)) return@thresholdLoop
                    val workName = uniqueName(event.id, threshold)
                    val request = OneTimeWorkRequestBuilder<AppointmentReminderWorker>()
                        .setInitialDelay(triggerAt - nowEpochMs, TimeUnit.MILLISECONDS)
                        .setInputData(
                            workDataOf(
                                KEY_EVENT_ID to event.id,
                                KEY_START_EPOCH_MS to startEpochMs,
                                KEY_LOCAL_DATE to localDate,
                                KEY_THRESHOLD_MINUTES to threshold,
                            ),
                        )
                        .addTag(TAG)
                        .build()
                    workManager.enqueueUniqueWork(workName, ExistingWorkPolicy.REPLACE, request)
                    tracked.put(
                        workName,
                        JSONObject()
                            .put("event_id", event.id)
                            .put("local_date", localDate)
                            .put("start_epoch_ms", startEpochMs)
                            .put("threshold_minutes", threshold),
                    )
                }
            }
        preferences.edit().putString(KEY_TRACKED, tracked.toString()).apply()
    }

    fun cancelAll() {
        workManager.cancelAllWorkByTag(TAG)
        preferences.edit().remove(KEY_TRACKED).apply()
    }

    private fun readTracked(): JSONObject = runCatching {
        JSONObject(preferences.getString(KEY_TRACKED, null) ?: "{}")
    }.getOrDefault(JSONObject())

    companion object {
        const val KEY_EVENT_ID = "event_id"
        const val KEY_START_EPOCH_MS = "start_epoch_ms"
        const val KEY_LOCAL_DATE = "local_date"
        const val KEY_THRESHOLD_MINUTES = "threshold_minutes"
        private const val PREFERENCES_NAME = "gpos_calendar_reminders"
        private const val KEY_TRACKED = "tracked"
        private const val TAG = "aegis-calendar-reminder"

        private fun uniqueName(eventId: String, thresholdMinutes: Int): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest("$eventId|$thresholdMinutes".toByteArray())
                .take(12)
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            return "aegis-calendar-reminder-$digest"
        }
    }
}

class AppointmentReminderWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val eventId = inputData.getString(AppointmentReminderScheduler.KEY_EVENT_ID) ?: return Result.success()
        val expectedStart = inputData.getLong(AppointmentReminderScheduler.KEY_START_EPOCH_MS, -1L)
        val localDate = inputData.getString(AppointmentReminderScheduler.KEY_LOCAL_DATE).orEmpty()
        val threshold = inputData.getInt(AppointmentReminderScheduler.KEY_THRESHOLD_MINUTES, 0)
        if (expectedStart <= 0L || threshold !in AppointmentReminderPolicy.thresholdsMinutes) return Result.success()

        val cached = Room.databaseBuilder(
            applicationContext,
            GposDatabase::class.java,
            CanonicalCachePolicy.DATABASE_NAME,
        ).build().use { database -> database.canonicalSnapshotDao().read(CanonicalCachePolicy.CALENDAR_RANGE_KEY) }

        var title: String? = null
        if (cached != null) {
            val json = runCatching { JSONObject(cached.payloadJson) }.getOrNull()
            val startDate = json?.optString("_android_start_date").orEmpty()
            val endDate = json?.optString("_android_end_date").orEmpty()
            if (json != null && startDate.isNotBlank() && endDate.isNotBlank()) {
                val snapshot = runCatching { CalendarRangePayloadMapper.map(json, startDate, endDate) }.getOrNull()
                val event = snapshot?.events?.firstOrNull { it.id == eventId }
                if (event != null) {
                    val currentStart = event.start?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
                    if (event.allDay || currentStart != expectedStart) return Result.success()
                    title = event.title
                } else if (localDate >= startDate && localDate < endDate) {
                    return Result.success()
                }
            }
        }

        val remaining = expectedStart - System.currentTimeMillis()
        if (remaining <= 0L || remaining > TimeUnit.MINUTES.toMillis((threshold + 15).toLong())) return Result.success()
        val body = title?.let { "$it begins in about $threshold minutes." }
            ?: "A Calendar event begins in about $threshold minutes."
        GposNotificationPublisher(applicationContext).publishOutcome(
            stableEventId = "calendar-$eventId-$threshold",
            title = "Appointment reminder",
            body = body,
            target = GposDeepLinkTarget.CALENDAR,
            isError = false,
        )
        return Result.success()
    }
}
