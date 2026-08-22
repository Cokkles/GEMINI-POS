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

Known V2.6 defect to remediate: AEGIS calendar rendering has been observed displaying stale historical events as current-day schedule state. Calendar UI must fail stale/unavailable rather than reuse old event payloads.

## Priority 3 — Gmail / Mail Gateway and Mail Client

AEGIS should provide a controlled mail client experience backed by Gmail as the authoritative mail store rather than maintaining a competing mailbox database.

Initial read capabilities:

- inbox/thread browsing;
- bounded search by sender, subject, recency, label/category, and relevance;
- read full messages and thread context;
- unread/read and label visibility;
- attachment awareness and safe retrieval where supported;
- action/relevance surfacing without relying on unread-only filtering.

Controlled write capabilities should include:

- compose new email;
- create and edit drafts;
- reply and reply-all;
- forward;
- archive;
- mark read/unread;
- apply/remove labels;
- Trash/delete where supported;
- future attachment sending when an approved backend path exists.

Conversational integration should support requests such as:

- "Show me the important email from today."
- "Find the latest thread from Lightbridge."
- "Draft a reply to this message."
- "Reply that Friday works."
- "Archive these order confirmations."

Safety / authority invariants:

- Gmail remains canonical; AEGIS stores no independent mailbox truth.
- Gemini/GPOS may summarize and propose actions but may not fabricate message state or recipients.
- Sending, deleting/Trashing, forwarding, label changes, or other consequential mutations must route through an explicit Gmail action boundary.
- Ambiguous recipients or destructive actions require clarification or confirmation as appropriate.
- The AI Query Gateway should use minimum-sufficient mail context rather than dumping an entire mailbox into Gemini.
- HORIZON continues to consume bounded relevant Gmail/logistics information; the AEGIS mail client does not redefine HORIZON ingestion authority.

Longer-term UX may combine Mail, Calendar, and GPOS AI into a unified personal communications/workflow surface while keeping the underlying Google services authoritative.

## Priority 4 — Unified Journal / SPARK UX

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
