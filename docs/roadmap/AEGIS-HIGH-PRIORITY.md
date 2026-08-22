# AEGIS High-Priority GPOS Integration Roadmap

Status: PLANNED / HIGH PRIORITY
Execution timing: after SPARK + KINETIC V2 reaches a stable runtime checkpoint.

## Priority 1 — Conversational Query Gateway

AEGIS should provide a natural-language GPOS query surface backed by server-side Gemini API access.

Requirements:

- API credentials remain backend-only and are never exposed in the PWA/browser.
- Quick Ask mode for one-shot questions.
- Contextual Ask mode using minimum-sufficient approved GPOS domain context.
- Bounded Conversation mode with session IDs, recent-turn retention, and/or summarized history.
- user-visible indication of which domains/context are being supplied.
- no automatic chat-to-canonical-state persistence.
- domain routing should eventually support SPARK, KINETIC, HORIZON, SENTINEL-FIN, SENTINEL-ATLAS, and the career/COMPASS domain.

## Priority 2 — Google Calendar Gateway

AEGIS conversational and structured UI flows should support controlled Google Calendar operations:

- read/search events;
- check availability/free-busy;
- create events;
- edit/reschedule events;
- cancel/delete events;
- recurring-event handling;
- future attendee/invitation support where appropriate.

Calendar remains the authoritative schedule store. AEGIS does not maintain a competing calendar database.

Consequential or ambiguous mutations should be presented as an explicit action proposal and confirmed before execution.

## Priority 3 — Unified Journal / SPARK UX

Journal area should expose clear modes instead of requiring command memorization:

- Journal
- Vent
- Reflect
- Check-In
- Assess

Additional GPOS actions should include:

- Pulse
- Triage
- Focus
- Reset
- Debrief
- Review
- Horizon
- Status
- Why?

AEGIS routes intent; domain engines own interpretation and canonical state.

## Future control-plane rule

All surfaces should eventually use one canonical GPOS command registry containing command identity, owner, intent, arguments, read/write scope, confirmation requirements, and input/output contracts.
