package dev.patrickgold.florisboard.repli.ime

import android.content.Context
import android.content.Intent
import dev.patrickgold.florisboard.repli.capture.ReplyEditor
import dev.patrickgold.florisboard.repli.diagnostics.CaptureTelemetry
import dev.patrickgold.florisboard.repli.persona.Persona
import dev.patrickgold.florisboard.repli.profile.VoiceProfile
import kotlinx.coroutines.CoroutineScope

/**
 * What the reply flow controllers need from [RepliReplyOrchestrator]. The orchestrator stays
 * the only owner of editor tracking, identity, review state and the published UI state; the
 * controllers own one concern each (guidance + voice, cloud generation) and reach back through
 * this interface instead of sharing fields.
 */
internal interface ReplyFlowHost {
    val appContext: Context
    val scope: CoroutineScope
    val editor: ReplyEditor?
    val sensitive: Boolean
    val telemetry: CaptureTelemetry

    fun selectedProfile(): VoiceProfile?
    fun selectedPersona(): Persona

    /** Recomputes and republishes the UI state from session + controller state. */
    fun publish()

    /** Sets the idle status line (and marks the panel active when [activate]). */
    fun setStatus(status: String, activate: Boolean = false)

    /** Abandons the whole flow (session, drafts, recorders) and returns to idle. */
    fun clearFlow()

    /** Called once per completed first batch of replies, for time-to-reply diagnostics. */
    fun onRepliesReady()

    fun launchActivity(intent: Intent)
    fun showTyping()
    fun showReplies()
}
