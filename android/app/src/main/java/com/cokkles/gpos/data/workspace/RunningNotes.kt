package com.cokkles.gpos.data.workspace

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class NoteRevision(val id: String = UUID.randomUUID().toString(), val text: String, val time: Long = System.currentTimeMillis(), val kind: String, val submissionId: String? = null)
data class RunningNotesDocument(val text: String = "", val updatedAt: Long = 0,
    val pendingId: String? = null, val pendingText: String? = null, val pendingAt: Long = 0,
    val lastSyncedAt: Long = 0, val revisions: List<NoteRevision> = emptyList()) {
    fun checkpoint(kind: String = "Checkpoint"): RunningNotesDocument = if (text.isBlank()) this else
        copy(revisions = (listOf(NoteRevision(text = text, kind = kind)) + revisions).take(30))
    fun beginSync(): RunningNotesDocument = if (pendingId != null) this else checkpoint("Before sync").copy(
        pendingId = UUID.randomUUID().toString(), pendingText = text, pendingAt = System.currentTimeMillis())
    fun confirmed(): RunningNotesDocument {
        val submitted = pendingText ?: return this
        val remaining = when {
            text == submitted -> ""
            text.startsWith(submitted) -> text.removePrefix(submitted).trimStart()
            else -> text
        }
        return copy(text = remaining, updatedAt = System.currentTimeMillis(),
            revisions = (listOf(NoteRevision(text = submitted, kind = "Synced", submissionId = pendingId)) + revisions).take(30),
            pendingId = null, pendingText = null, pendingAt = 0, lastSyncedAt = System.currentTimeMillis())
    }
    fun restore(id: String): RunningNotesDocument {
        val revision = revisions.firstOrNull { it.id == id } ?: return this
        return checkpoint("Before restore").copy(text = revision.text, updatedAt = System.currentTimeMillis())
    }
    fun clearPendingKeepDraft(): RunningNotesDocument {
        val pending = pendingText ?: return this
        val preserved = when {
            text.isBlank() -> pending
            text.contains(pending) -> text
            else -> "$pending\n\n$text"
        }
        return copy(
            text = preserved,
            updatedAt = System.currentTimeMillis(),
            pendingId = null,
            pendingText = null,
            pendingAt = 0,
            revisions = (
                listOf(NoteRevision(text = pending, kind = "Pending cleared", submissionId = pendingId)) +
                    revisions
                ).take(30),
        )
    }
    fun toJson(): String = JSONObject().put("text", text).put("updated_at", updatedAt)
        .put("pending_id", pendingId).put("pending_text", pendingText).put("pending_at", pendingAt)
        .put("last_synced_at", lastSyncedAt).put("revisions", JSONArray().apply {
            revisions.forEach { put(JSONObject().put("id", it.id).put("text", it.text).put("time", it.time).put("kind", it.kind).put("submission_id", it.submissionId)) }
        }).toString()
    companion object {
        fun parse(raw: String): RunningNotesDocument {
            val j = JSONObject(raw)
            return RunningNotesDocument(j.string("text"), j.optLong("updated_at"),
                j.string("pending_id").takeIf { it.isNotBlank() }, j.string("pending_text").takeIf { j.has("pending_text") && !j.isNull("pending_text") },
                j.optLong("pending_at"), j.optLong("last_synced_at"), j.optJSONArray("revisions").objects().map {
                    NoteRevision(it.string("id"), it.string("text"), it.optLong("time"), it.string("kind"), it.string("submission_id").takeIf { id -> id.isNotBlank() })
                })
        }
    }
}
internal fun runningNotesPayload(document: RunningNotesDocument): JSONObject {
    require(!document.pendingId.isNullOrBlank() && !document.pendingText.isNullOrBlank())
    require(document.pendingText.length <= 48000)
    val message = "/note [RUNNING_NOTES]\nSource: AEGIS Android Running Notes\nRecorded: ${java.time.Instant.ofEpochMilli(document.pendingAt)}\nSubmission-ID: ${document.pendingId}\n\n${document.pendingText}\n[/RUNNING_NOTES]"
    return JSONObject().put("message", message).put("submission_id", document.pendingId)
}
