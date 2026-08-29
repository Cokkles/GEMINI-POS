package com.cokkles.gpos.domain

import java.time.Instant

object PreviewFixtures {
    val snapshot = CanonicalSnapshot(
        generatedAt = Instant.parse("2026-08-29T16:45:00Z"),
        briefing = BriefingSummary(
            title = "Daily Executive Briefing",
            summary = "Fixture-backed Android validation data. No live backend request was made.",
            canonicalVersion = "A0-preview",
        ),
        calendar = listOf(
            CalendarItem(
                id = "cal-1",
                title = "Morning planning block",
                startsAt = Instant.parse("2026-08-29T13:00:00Z"),
                endsAt = Instant.parse("2026-08-29T13:30:00Z"),
            ),
            CalendarItem(
                id = "cal-2",
                title = "Project review",
                startsAt = Instant.parse("2026-08-29T18:00:00Z"),
                endsAt = Instant.parse("2026-08-29T18:45:00Z"),
            ),
        ),
        tasks = listOf(
            TaskItem(
                id = "task-1",
                title = "Validate Android checkpoint",
                status = TaskStatus.IN_PROGRESS,
                priority = TaskPriority.HIGH,
            ),
            TaskItem(
                id = "task-2",
                title = "Review canonical contract mapping",
                status = TaskStatus.PENDING,
            ),
        ),
        followUps = listOf(
            FollowUpItem(
                id = "follow-1",
                title = "Review next Android integration increment",
                dueAt = Instant.parse("2026-08-30T16:00:00Z"),
            ),
        ),
        finances = FinanceSummary(
            displayCurrency = "USD",
            headline = "Finance surface ready for canonical summary",
            detail = "A0 preview only — no live financial data is loaded.",
        ),
        system = SystemSummary(
            backendReachable = false,
            compatibilityState = CompatibilityState.UNKNOWN,
            message = "Offline A0 preview mode",
        ),
    )
}
