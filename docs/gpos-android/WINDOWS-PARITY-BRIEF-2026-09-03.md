> Supplied Windows/Android parity requirements, preserved September 4, 2026. This is a requirements reference, not a claim that every item is implemented in Android 0.6.1. Current phase status: `0.6.1-RESPONSIVENESS-STATUS.md`.

# AEGIS Personal Workspace — Android Feature-Parity Brief

Build the Android application as a mobile version of the AEGIS personal workspace. It should use the same canonical AEGIS/Apps Script backend as the PWA and Windows application. Google Calendar, Google Tasks, Google Docs, HORIZON, SPARK, KINETIC, and other upstream sources remain authoritative.

The application should prioritize an approachable daily workspace rather than exposing raw backend payloads.

## Implemented/shared capabilities

### Authentication

- Google OAuth authentication using the appropriate Android OAuth client ID.
- Support the backend’s trusted multi-client audience configuration:
  - PWA OAuth client
  - Windows desktop OAuth client
  - Android OAuth client
- Keep the user signed in securely until explicit logout or credential expiration.
- Store credentials using Android Keystore-backed encrypted storage.
- Never embed Google client secrets in the application.
- Display authenticated identity and meaningful login errors.
- Preserve compatibility with existing PWA and Windows authentication.

### Home dashboard

- Daily greeting and rotating attributed quote at the top.
- Current HORIZON data/report date.
- Clear freshness status:
  - Current
  - Previous day
  - Stale
  - Unavailable
- Explain when a newer HORIZON report has not been observed.
- Button to check for the latest HORIZON data.
- Button to open the formatted daily briefing.
- Calendar glance for today and upcoming appointments.
- Active task/follow-up glance.
- KINETIC nutrition glance:
  - Calories
  - Target status using human-readable labels such as “Under Target”
  - Protein, carbohydrates, and fat when available
- News and itinerary highlights.
- Offline/last-known-good indicators where cached data is being displayed.

### HORIZON

- Read the canonical daily HORIZON briefing.
- Display formatted sections rather than raw JSON.
- Keep raw payload and source metadata in Diagnostics.
- Show source document title, document ID, last-updated time, inferred report date, freshness, generation state, and recent generation error.
- Provide a confirmed **Generate today’s HORIZON** action.
- If today’s report already exists, display an additional replacement warning.
- If Running Notes contain meaningful unsynchronized text, ask whether to:
  - Sync notes and then generate
  - Generate without synchronizing
  - Cancel
- Never automatically retry a HORIZON generation mutation after an ambiguous timeout.

### Calendar

- Month calendar populated from Google Calendar.
- Last-known-good encrypted cache for offline or failed refreshes.
- Previous, Today, and Next navigation.
- Larger, readable month cells.
- Tap a date to open a complete day schedule from 12:00 AM through 11:59 PM.
- Show all-day events separately.
- Show event title, date, start/end time, location, and description.
- Tap an event to inspect its details.
- Tap an empty time slot to prepare a new event using the selected date and time.
- Support manual event fields:
  - Title
  - Date
  - Start/end
  - All-day
  - Location
  - Description
- Support natural-language Calendar queries.
- Support create, edit, and delete operations.
- All mutations must follow:
  1. Prepare
  2. Display exact preview
  3. Explicit confirmation
  4. Apply
  5. Refresh Calendar immediately
- When multiple events match an edit/delete request, show selectable candidates with complete identifying details.
- Prefer the deterministic structured Calendar contract over requiring phrases such as “Google Calendar.”
- Provide visible button feedback: pressed, working, success, failure.

### Google Tasks personal workspace

Uses the additive Apps Script 2.7.0 task contracts.

- Display active tasks across all Google Tasks lists.
- Filter tasks by list.
- Preserve task-list identity throughout every operation.
- Directly create tasks with:
  - Title
  - Notes
  - Due date
  - Target list
- Edit existing tasks.
- Confirm before immediately deleting a task.
- Create new Google Tasks lists.
- Rename lists.
- Do not expose task-list deletion initially because it can remove the entire list and its contents.
- Checking a task should stage completion locally with a five-minute grace period.
- Allow Undo during the grace period.
- Synchronize due completions in the background.
- Provide a **Sync queued** action.
- Report clearly when synchronization completes with zero changes.
- Keep an encrypted last-known-good task cache.
- Maintain completed-task history for at least:
  - Past 7 days
  - Past 30 days
- Allow completed tasks to be restored/uncompleted into their original lists.

### HORIZON/Notes follow-ups

- Display durable follow-ups identified from canonical Notes/HORIZON data.
- Include stable follow-up ID, title, summary, and provenance where available.
- Never silently convert a detected follow-up into a Google Task.
- Provide explicit actions:
  - Promote to a selected Google Tasks list
  - Resolve
  - Dismiss
- Promotion should preserve a reference to the originating follow-up.
- Resolve/dismiss should record lifecycle state without erasing the original Notes provenance.

### Capture workspace

Support canonical capture destinations:

- Notes
- Journal
- Vent
- Reflect
- Assess
- Calories/KINETIC
- Receipts/financial capture

Every submission should provide:

- Visible working state
- Durable receipt
- Clear success result
- Clear failure or timeout result
- System notification for success/failure where appropriate
- Protection against accidental duplicate submission
- No blind retry of ambiguous mutations

### Running Notes

- Large local-first writing workspace for continuous notes.
- Autosave continuously without requiring a Submit button.
- Encrypt local content using Android Keystore-backed storage.
- Bind protected content to the authenticated identity.
- Preserve bounded recovery revisions.
- Provide:
  - Manual checkpoint
  - Restore as a new working copy
  - Copy
  - Share/export as plain text
  - Explicit Sync to canonical Notes
- Sync writes a clearly marked `[RUNNING_NOTES]` section to the existing canonical Notes destination.
- Use a stable submission ID to prevent duplicate synchronization.
- Keep the synchronized revision locally for recovery.
- Begin a fresh working section after confirmed synchronization.

### Nutrition/KINETIC

- Submit natural-language meal descriptions for Gemini-assisted nutrition parsing.
- Show the returned parsed result:
  - Calories
  - Protein
  - Carbohydrates
  - Fat
  - Recognized food/meal description
- Immediately update today’s displayed totals from a confirmed response.
- Reconcile against canonical KINETIC totals in the background.
- Treat timeouts as uncertain/pending rather than assuming success or failure.
- Nutrition insights window:
  - 7-day view
  - 30-day view
  - Average calories
  - Average protein/carbohydrates/fat
  - Logged-day count
  - Calorie trend
  - Meal history
  - Search/filter
  - Simple habit signals

### News and RSS

- Present a small curated Headliners section first.
- Only categories marked as headliner-eligible should contribute headlines.
- Local, regional, health, and selected gaming news may be eligible.
- Deals, bundles, and giveaways should normally be excluded from Headliners.
- Group remaining stories into collapsible category sections.
- Open stories in the device’s default browser.
- RSS configuration interface:
  - Add feed
  - Remove/disable feed
  - Edit feed
  - Create category
  - Assign feeds to categories
  - Mark a category as headliner-eligible
  - Test feed
  - Show feed health and latest refresh
- Preserve the most recent successful feed results when refresh fails.
- Avoid repeatedly alerting the user about the same unhealthy feed.

### Alerts and receipts

- Central Alerts & Receipts page.
- Failed Notes, Journal, KINETIC, HORIZON, Calendar, Tasks, or Running Notes operations generate an alert.
- Show recent confirmed, pending, and failed operations.
- Allow alerts to be dismissed/acknowledged.
- Use Android notifications for significant submission results.
- Keep sensitive content encrypted locally.
- Avoid flooding activity history with routine authentication/status polling.

### Diagnostics

Diagnostics should hold technical information that does not belong in the daily workspace:

- Authentication state
- Backend version and capabilities
- Helper/backend availability
- Cache state and age
- HORIZON raw metadata
- Raw briefing payload
- RSS source health
- Recent non-routine activity
- Recent errors
- Safe configuration readiness
- No tokens, secrets, OAuth client values, or complete allowlists should be displayed.

## Planned capabilities

These are planned for Windows and should be architected for Android parity, but should not be assumed to have completed backend contracts yet.

### Customizable dashboard

- Dashboard cards that can be reordered.
- Saved per-user layout.
- Reset layout action.
- Mobile layout should use reorder mode or drag handles.
- Allow cards to be shown/hidden.
- Potential synchronization of layout preferences between Windows and Android.

### Theme system

- Existing AEGIS dark theme remains the default.
- Additional carefully adapted light and dark themes.
- Possible inspirations:
  - Linear
  - GitHub Primer
  - Nord
  - Dracula
  - Catppuccin Mocha
  - Gruvbox
  - Solarized
  - Tokyo Night
  - Monokai-style palettes
- Do not copy protected application assets or trademarked theme branding without verification.
- Implement themes as original color-token mappings.
- Support user-selected background images:
  - Local image import
  - Cropping/scaling
  - Blur/darkening overlay
  - Contrast protection
  - Remove/reset background
- Potential original Dragon Ball-inspired themes should use user-provided/licensed imagery or original artwork—not copied franchise assets.

### Cross-device synchronization

Evaluate synchronizing only useful user preferences and local workspace state:

- Dashboard layout
- Theme
- News categories/feed configuration
- Headliner eligibility
- Running Notes drafts or handoff state
- Pending task action awareness
- Read/dismissed news state

Do not synchronize OAuth tokens, Android Keystore material, Windows DPAPI material, or unencrypted sensitive files between devices.

## Mobile UX guidance

- Use bottom navigation for the highest-frequency areas:
  - Home
  - Calendar
  - Tasks
  - Capture
  - More
- Place Running Notes, News, Alerts, and Diagnostics under More or configurable shortcuts.
- Use bottom sheets for quick Calendar/task creation.
- Use clear progress indicators and disable mutation buttons while requests are active.
- Prefer compact formatted results over raw JSON.
- Support pull-to-refresh while preserving explicit mutation confirmation.
- Clearly label cached/offline data and its timestamp.
- Maintain accessible touch targets, dynamic text sizing, screen-reader labels, and strong contrast.
- Use the system browser for external links and OAuth.
- Keep platform-specific implementation native while sharing backend contracts and behavior with Windows/PWA.