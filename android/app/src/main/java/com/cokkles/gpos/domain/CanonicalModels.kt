package com.cokkles.gpos.domain

import java.time.Instant

data class CanonicalSnapshot(
    val generatedAt: Instant,
    val briefing: BriefingSummary? = null,
    val calendar: List<CalendarItem> = emptyList(),
    val tasks: List<TaskItem> = emptyList(),
    val followUps: List<FollowUpItem> = emptyList(),
    val finances: FinanceSummary? = null,
    val system: SystemSummary = SystemSummary(),
)

data class BriefingSummary(
    val title: String,
    val summary: String,
    val canonicalVersion: String? = null,
)

data class CalendarItem(
    val id: String,
    val title: String,
    val startsAt: Instant,
    val endsAt: Instant? = null,
)

data class TaskItem(
    val id: String,
    val title: String,
    val status: TaskStatus,
    val priority: TaskPriority = TaskPriority.NORMAL,
)

enum class TaskStatus {
    PENDING,
    IN_PROGRESS,
    COMPLETED,
}

enum class TaskPriority {
    LOW,
    NORMAL,
    HIGH,
}

data class FollowUpItem(
    val id: String,
    val title: String,
    val dueAt: Instant? = null,
    val overdue: Boolean = false,
)

data class FinanceSummary(
    val displayCurrency: String,
    val headline: String,
    val detail: String? = null,
)

data class SystemSummary(
    val backendReachable: Boolean = false,
    val compatibilityState: CompatibilityState = CompatibilityState.UNKNOWN,
    val message: String? = null,
)

enum class CompatibilityState {
    UNKNOWN,
    COMPATIBLE,
    DEGRADED,
    INCOMPATIBLE,
}
