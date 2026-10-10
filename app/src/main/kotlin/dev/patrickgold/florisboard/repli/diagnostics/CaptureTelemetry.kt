package dev.patrickgold.florisboard.repli.diagnostics

import android.content.Context
import android.os.SystemClock

/**
 * Local, content-free counters for the reply pipeline so capture problems can be seen on a
 * test device without reading any chat text. Nothing here leaves the device; the numbers
 * are shown under Settings → Repli → Diagnostics and can be reset there.
 */
class CaptureTelemetry(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun count(event: String, by: Int = 1) {
        if (by <= 0) return
        preferences.edit().putInt(event, preferences.getInt(event, 0) + by).apply()
    }

    /** Records one completed reply flow (from the user's first tap to replies ready). */
    fun recordReplyLatency(startedAtElapsedMs: Long?, quick: Boolean) {
        val started = startedAtElapsedMs ?: return
        val elapsed = (SystemClock.elapsedRealtime() - started).coerceIn(0, MAX_LATENCY_MS)
        val prefix = if (quick) "quick" else "capture"
        preferences.edit()
            .putInt("${prefix}_reply_count", preferences.getInt("${prefix}_reply_count", 0) + 1)
            .putLong("${prefix}_reply_ms_total", preferences.getLong("${prefix}_reply_ms_total", 0) + elapsed)
            .putLong("${prefix}_reply_ms_last", elapsed)
            .apply()
    }

    fun recordLastField(summary: String) {
        preferences.edit().putString(LAST_FIELD, summary.take(200)).apply()
    }

    fun snapshot(): Snapshot = Snapshot(
        lastField = preferences.getString(LAST_FIELD, null),
        capturesStarted = preferences.getInt(CAPTURE_STARTED, 0),
        captureConsentFailed = preferences.getInt(CAPTURE_CONSENT_FAILED, 0),
        framesCaptured = preferences.getInt(FRAMES_CAPTURED, 0),
        visionRead = preferences.getInt(VISION_READ, 0),
        visionEmpty = preferences.getInt(VISION_EMPTY, 0),
        visionFailed = preferences.getInt(VISION_FAILED, 0),
        visionUnavailable = preferences.getInt(VISION_UNAVAILABLE, 0),
        turnsRead = preferences.getInt(TURNS_READ, 0),
        speakerCorrections = preferences.getInt(SPEAKER_CORRECTIONS, 0),
        turnsRemoved = preferences.getInt(TURNS_REMOVED, 0),
        generationsSucceeded = preferences.getInt(GENERATION_OK, 0),
        generationsFailed = preferences.getInt(GENERATION_FAILED, 0),
        quickReplies = preferences.getInt(QUICK_REPLY, 0),
        repliesInserted = preferences.getInt(REPLY_INSERTED, 0),
        rewritesRequested = preferences.getInt(REWRITE_REQUESTED, 0),
        rewritesFailed = preferences.getInt(REWRITE_FAILED, 0),
        rewritesApplied = preferences.getInt(REWRITE_APPLIED, 0),
        captureReplyCount = preferences.getInt("capture_reply_count", 0),
        captureReplyMsTotal = preferences.getLong("capture_reply_ms_total", 0),
        captureReplyMsLast = preferences.getLong("capture_reply_ms_last", 0),
        quickReplyCount = preferences.getInt("quick_reply_count", 0),
        quickReplyMsTotal = preferences.getLong("quick_reply_ms_total", 0),
        quickReplyMsLast = preferences.getLong("quick_reply_ms_last", 0),
    )

    fun reset() = preferences.edit().clear().apply()

    data class Snapshot(
        val lastField: String?,
        val capturesStarted: Int,
        val captureConsentFailed: Int,
        val framesCaptured: Int,
        val visionRead: Int,
        val visionEmpty: Int,
        val visionFailed: Int,
        val visionUnavailable: Int,
        val turnsRead: Int,
        val speakerCorrections: Int,
        val turnsRemoved: Int,
        val generationsSucceeded: Int,
        val generationsFailed: Int,
        val quickReplies: Int,
        val repliesInserted: Int,
        val rewritesRequested: Int,
        val rewritesFailed: Int,
        val rewritesApplied: Int,
        val captureReplyCount: Int,
        val captureReplyMsTotal: Long,
        val captureReplyMsLast: Long,
        val quickReplyCount: Int,
        val quickReplyMsTotal: Long,
        val quickReplyMsLast: Long,
    ) {
        val captureReplyMsAverage: Long get() = if (captureReplyCount == 0) 0 else captureReplyMsTotal / captureReplyCount
        val quickReplyMsAverage: Long get() = if (quickReplyCount == 0) 0 else quickReplyMsTotal / quickReplyCount
    }

    companion object {
        const val CAPTURE_STARTED = "capture_started"
        const val CAPTURE_CONSENT_FAILED = "capture_consent_failed"
        const val FRAMES_CAPTURED = "frames_captured"
        const val VISION_READ = "vision_read"
        const val VISION_EMPTY = "vision_empty"
        const val VISION_FAILED = "vision_failed"
        const val VISION_UNAVAILABLE = "vision_unavailable"
        const val TURNS_READ = "turns_read"
        const val SPEAKER_CORRECTIONS = "speaker_corrections"
        const val TURNS_REMOVED = "turns_removed"
        const val GENERATION_OK = "generation_ok"
        const val GENERATION_FAILED = "generation_failed"
        const val QUICK_REPLY = "quick_reply"
        const val REPLY_INSERTED = "reply_inserted"
        const val REWRITE_REQUESTED = "rewrite_requested"
        const val REWRITE_FAILED = "rewrite_failed"
        const val REWRITE_APPLIED = "rewrite_applied"
        private const val LAST_FIELD = "last_field"
        private const val PREFERENCES = "repli_capture_telemetry"
        private const val MAX_LATENCY_MS = 10 * 60 * 1_000L
    }
}
