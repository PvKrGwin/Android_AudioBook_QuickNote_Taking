package com.praveen.ridernotes.desktop

import java.io.File
import java.security.MessageDigest

enum class DeltaEntityKind {
    TOPIC_GROUP,
    TOPIC_ITEM,
    TOPIC_PLACEMENT,
    TOPIC_LEGEND,
    RECORDING_AUDIO,
    RECORDING_METADATA,
    TRANSCRIPT,
    ATTACHMENT
}

enum class DeltaOperation { UPSERT, DELETE }

data class DeltaEntityRevision(
    val entityId: String,
    val kind: DeltaEntityKind,
    val revisionId: String,
    val parentRevisionId: String?,
    val ancestorRevisionIds: List<String> = emptyList(),
    val modifiedAtEpochMs: Long,
    val deviceId: String,
    val deviceName: String,
    val deviceType: String,
    val operation: DeltaOperation,
    val relativePath: String? = null,
    val sizeBytes: Long? = null,
    val sha256: String? = null,
    val payloadJson: String? = null
)

enum class DeltaSyncDecision { NONE, IDENTICAL, UPLOAD_LOCAL, DOWNLOAD_REMOTE, CONFLICT }

object DeltaSyncPlanner {
    fun decide(local: DeltaEntityRevision?, remote: DeltaEntityRevision?): DeltaSyncDecision {
        if (local == null && remote == null) return DeltaSyncDecision.NONE
        if (local == null) return DeltaSyncDecision.DOWNLOAD_REMOTE
        if (remote == null) return DeltaSyncDecision.UPLOAD_LOCAL
        return when {
            local.revisionId == remote.revisionId -> DeltaSyncDecision.IDENTICAL
            local.sameResultAs(remote) -> DeltaSyncDecision.IDENTICAL
            remote.revisionId in local.ancestorRevisionIds -> DeltaSyncDecision.UPLOAD_LOCAL
            local.revisionId in remote.ancestorRevisionIds -> DeltaSyncDecision.DOWNLOAD_REMOTE
            else -> DeltaSyncDecision.CONFLICT
        }
    }

    private fun DeltaEntityRevision.sameResultAs(other: DeltaEntityRevision): Boolean =
        entityId == other.entityId && kind == other.kind && operation == other.operation &&
            relativePath == other.relativePath && sizeBytes == other.sizeBytes &&
            sha256 == other.sha256 && payloadJson == other.payloadJson
}

object DeltaContentHash {
    fun sha256(file: File): String = file.inputStream().buffered().use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count > 0) digest.update(buffer, 0, count)
        }
        digest.digest().toHex()
    }

    fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).toHex()

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
