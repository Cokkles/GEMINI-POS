# AEGIS Integration

AEGIS is the user-facing GPOS interaction and display client. It remains independently deployable and must not become a competing canonical state store.

## High-priority planned capabilities

### Conversational Query Gateway

- Gemini API key remains backend-only.
- Modes: Quick Ask, Contextual Ask, bounded Conversation.
- Route only minimum sufficient approved context.
- Show users which domains are included.
- Do not automatically persist chat output into canonical state.

### Calendar Gateway

- Read/search Google Calendar.
- Create, edit, reschedule, and cancel events.
- Free/busy and natural-language scheduling.
- Require confirmation for consequential or ambiguous writes.
- Calendar remains authoritative; AEGIS does not maintain a competing calendar.

### Command UX

Capture/reflection: Journal, Vent, Reflect, Check-In, Note, Task, Calories.

Executive assistance: Assess, Pulse, Triage, Focus, Reset, Debrief, Review.

System/transparency: Horizon, Status, Why?

AEGIS should expose these as discoverable UI actions rather than requiring command memorization.
