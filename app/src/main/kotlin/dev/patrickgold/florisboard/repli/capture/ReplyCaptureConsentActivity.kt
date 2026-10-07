package dev.patrickgold.florisboard.repli.capture

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts

/** A short-lived translucent permission host, launched by an explicit IME tap.
 * Finishing returns to the existing chat instead of opening another chat app. */
class ReplyCaptureConsentActivity : ComponentActivity() {
    private var requestId = ""
    private var promptLaunched = false
    private val preferences by lazy { getSharedPreferences("reply_capture_setup", MODE_PRIVATE) }

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        // Notification denial must not block MediaProjection. Android still exposes
        // the foreground service in its active-app controls; capture stops itself.
        launchCapturePrompt()
    }

    private val capturePermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (ReplyCaptureSession.state.value?.id != requestId) { finish(); return@registerForActivityResult }
        val data = result.data
        if (result.resultCode != RESULT_OK || data == null) {
            cancelCapture("Screen sharing was cancelled. Tap Suggest replies to try again.")
        } else {
            ReplyCaptureSession.update(requestId) {
                it.copy(
                    phase = ReplyPhase.RETURNING,
                    viewport = it.viewport?.copy(readyAt = SystemClock.elapsedRealtime() + RETURN_SETTLE_MS),
                    message = "Capturing the expanded chat view…",
                )
            }
            try {
                startForegroundService(Intent(this, ReplyScreenCaptureService::class.java)
                    .putExtra(ReplyScreenCaptureService.EXTRA_REQUEST_ID, requestId)
                    .putExtra(ReplyScreenCaptureService.EXTRA_CONSENT, data))
            } catch (_: Exception) {
                ReplyCaptureSession.fail(requestId, "Screen capture couldn't start. Tap Suggest replies to retry.")
            }
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestId = intent.getStringExtra(EXTRA_REQUEST_ID).orEmpty()
        val request = ReplyCaptureSession.state.value
        if (requestId.isBlank() || request?.id != requestId || request.phase != ReplyPhase.CONSENT) {
            finish(); return
        }
        ReplyCaptureSession.update(requestId) { it.copy(consentActivityOpened = true) }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = cancelCapture("Capture cancelled. Nothing was read.")
        })
        promptLaunched = savedInstanceState?.getBoolean("prompt_launched") ?: false
        if (promptLaunched) return
        if (preferences.getBoolean("disclosure_accepted", false)) continueAfterDisclosure()
        else AlertDialog.Builder(this)
            .setTitle("Read this chat to suggest replies")
            .setMessage("Repli closes its keyboard and reads the visible chat after Android asks you to allow screen sharing. You can add another page from Review chats if more context is needed.\n\nRepli never writes captured images to device storage. If cloud replies are enabled, the bounded image is sent for AI reading as explained in Settings → Privacy & FAQ; otherwise text recognition stays on your phone. Captured context clears when you leave the conversation or tap Clear.\n\nYou can review the text and correct who said what before generating replies. Screen sharing stops as soon as capture finishes.")
            .setPositiveButton("Continue") { _, _ ->
                preferences.edit().putBoolean("disclosure_accepted", true).apply()
                continueAfterDisclosure()
            }
            .setNegativeButton("Not now") { _, _ -> cancelCapture("Capture cancelled. Nothing was read.") }
            .setOnCancelListener { cancelCapture("Capture cancelled. Nothing was read.") }
            .showWithVisibleActions()
    }

    private fun continueAfterDisclosure() {
        if (ReplyCaptureSession.state.value?.singleView == true) launchCapturePrompt()
        else confirmCaptureOrder()
    }

    private fun AlertDialog.Builder.showWithVisibleActions(): AlertDialog = show().also { dialog ->
        val colors = obtainStyledAttributes(intArrayOf(android.R.attr.textColorPrimary))
        val actionColor = colors.getColor(0, Color.WHITE)
        colors.recycle()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(actionColor)
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(actionColor)
    }

    private fun confirmCaptureOrder() {
        val addingPage = (ReplyCaptureSession.state.value?.frames ?: 0) > 0
        AlertDialog.Builder(this)
            .setTitle(if (addingPage) "Capture a newer page?" else "Is this the oldest page you want?")
            .setMessage(
                if (addingPage) "Move to a newer part of the same chat, keeping a little overlap with the last view. Repli will add it after the pages you already reviewed."
                else "Repli captures the currently visible page first. For more context, move only toward newer messages and tap Capture view for each next page. Pages stay in exactly the order you capture them.\n\nIf this is not the oldest page you want to include, cancel, move there, and start again.",
            )
            .setPositiveButton(if (addingPage) "Capture newer page" else "Yes, start capture") { _, _ -> askForNotificationPermission() }
            .setNegativeButton("Cancel") { _, _ ->
                cancelCapture("Capture cancelled. Start again from the oldest page you want to include.")
            }
            .setOnCancelListener {
                cancelCapture("Capture cancelled. Start again from the oldest page you want to include.")
            }
            .showWithVisibleActions()
    }

    private fun askForNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !preferences.getBoolean("notification_asked", false)
        ) {
            AlertDialog.Builder(this)
                .setTitle("Show a capture stop control?")
                .setMessage("Allow notifications to see a Stop button while Repli captures. You can continue without notifications; screen sharing will still stop automatically.")
                .setPositiveButton("Allow notifications") { _, _ ->
                    preferences.edit().putBoolean("notification_asked", true).apply()
                    promptLaunched = true
                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                .setNegativeButton("Continue without") { _, _ ->
                    preferences.edit().putBoolean("notification_asked", true).apply()
                    launchCapturePrompt()
                }
                .setOnCancelListener { cancelCapture("Capture cancelled. Nothing was read.") }
                .showWithVisibleActions()
        } else launchCapturePrompt()
    }

    private fun launchCapturePrompt() {
        if (ReplyCaptureSession.state.value?.id != requestId) { finish(); return }
        promptLaunched = true
        try {
            val manager = getSystemService(MediaProjectionManager::class.java)
            // A single-app picker could select a different conversation/app. Request
            // the display and crop to the foreground editor's visible chat area.
            val prompt = if (Build.VERSION.SDK_INT >= 34) {
                manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
            } else manager.createScreenCaptureIntent()
            capturePermission.launch(prompt)
        } catch (_: Exception) {
            cancelCapture("Screen sharing is unavailable on this device.")
        }
    }

    private fun cancelCapture(message: String) {
        ReplyCaptureSession.fail(requestId, message)
        finish()
    }

    override fun onDestroy() {
        if (!isChangingConfigurations &&
            ReplyCaptureSession.state.value?.let { it.id == requestId && it.phase == ReplyPhase.CONSENT } == true) {
            ReplyCaptureSession.fail(requestId,
                "Screen-sharing permission closed before capture. Tap Suggest replies to retry.")
        }
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("prompt_launched", promptLaunched)
        super.onSaveInstanceState(outState)
    }

    companion object {
        const val EXTRA_REQUEST_ID = "reply_request_id"
        private const val RETURN_SETTLE_MS = 800L
    }
}
