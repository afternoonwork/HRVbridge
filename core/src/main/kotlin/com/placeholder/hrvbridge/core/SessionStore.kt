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

    /** "Delete all measurements": removes every session file (and leftover temp files). Returns the count. */
    fun deleteAll(): Int {
        val files = dir.listFiles { f -> f.name.startsWith("session-") } ?: return 0
        files.filter { it.name.endsWith(".tmp") }.forEach { it.delete() }
        return files.count { it.name.endsWith(".json") && it.delete() }
    }

    /** Newest first; unreadable files are skipped. */
    fun list(): List<SessionRecord> =
        (dir.listFiles { f -> f.name.startsWith("session-") && f.name.endsWith(".json") } ?: emptyArray())
            .mapNotNull { f -> runCatching { SessionRecord.fromJson(f.readText()) }.getOrNull() }
            .sortedByDescending { it.startedAt }

    /**
     * What to send in the bulk retry: for each date only the newest submitted
     * session, and only if it is not uploaded yet. An older submitted session
     * never overwrites a newer submitted one; a newer session the user did not
     * submit does not block it. Newest date first.
     */
    fun retryBatch(): List<SessionRecord> =
        list().filter { it.result != null && it.uploadOverride }
            .groupBy { it.date }.values
            .map { sameDay -> sameDay.maxBy { it.startedAt } }
            .filter { it.uploadedAt == null }
            .sortedByDescending { it.date }

    /**
     * Sessions to retry on next app open: never uploaded, with a result, and
     * submitted by the user ("Upload" or "Upload anyway"). A session the user
     * never submitted is never uploaded, reliable or not.
     */
    fun pendingUploads(): List<SessionRecord> = list().filter(::isPending)

    private fun isPending(s: SessionRecord): Boolean {
        return s.result != null && s.uploadedAt == null && s.uploadOverride
    }
}
