package com.placeholder.hrvbridge.core

import java.io.File

/** One JSON file per session in [dir]. Writes go via a temp file and rename. */
class SessionStore(private val dir: File) {
    fun save(record: SessionRecord) {
        dir.mkdirs()
        val target = File(dir, record.fileName)
        val tmp = File(dir, record.fileName + ".tmp")
        tmp.writeText(record.toJson())
        if (!tmp.renameTo(target)) {
            target.delete()
            check(tmp.renameTo(target)) { "cannot write ${target.name}" }
        }
    }

    /** Newest first; unreadable files are skipped. */
    fun list(): List<SessionRecord> =
        (dir.listFiles { f -> f.name.startsWith("session-") && f.name.endsWith(".json") } ?: emptyArray())
            .mapNotNull { f -> runCatching { SessionRecord.fromJson(f.readText()) }.getOrNull() }
            .sortedByDescending { it.startedAt }

    /**
     * Sessions to retry on next app open: never uploaded, with a result that
     * is reliable or that the user explicitly chose to upload anyway.
     * Unreliable results without that override are never retried.
     */
    fun pendingUploads(): List<SessionRecord> = list().filter {
        val r = it.result
        r != null && it.uploadedAt == null && (r.reliable || it.uploadOverride)
    }
}
