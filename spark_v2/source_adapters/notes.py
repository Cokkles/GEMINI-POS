import re

ACTIVE = re.compile(r"^\s*(ACTIVE|TODO|OPEN|FOLLOW_UP)\s*:\s*", re.I)
TOMBSTONE = re.compile(r"^\s*(DONE|COMPLETED|MARK_DONE|MARK_DOWN|IGNORE|TEST)\s*:\s*", re.I)

def classify_note_entry(text: str) -> str:
    """Classify only the leading lifecycle marker; body collisions are ignored."""
    if ACTIVE.match(text):
        return "ACTIVE_CANDIDATE"
    if TOMBSTONE.match(text):
        return "TOMBSTONE"
    return "ARCHIVAL_UNMARKED"
