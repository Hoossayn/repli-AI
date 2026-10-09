package dev.patrickgold.florisboard.repli.capture

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.view.WindowManager
import android.view.WindowInsets
import android.widget.Toast
import dev.patrickgold.florisboard.lib.devtools.flogDebug
import dev.patrickgold.florisboard.lib.devtools.flogError
import dev.patrickgold.florisboard.repli.identity.CapturedContactName
import dev.patrickgold.florisboard.repli.suggestions.ServerMediatedContextEngine
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** One consent token and one VirtualDisplay for a bounded 1–4-page capture.
 * The user explicitly chooses each additional view; distinct views are retained in memory only. */
class ReplyScreenCaptureService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private var recognizerUsed = false
    private var requestId = ""
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var processing = false
    private var captureResultDelivered = false
    private var captureReadyAt = 0L
    private var framesCaptured = 0
    private var capturedTurns: List<ConversationTurn> = emptyList()
    private var capturedContactName: String? = null
    private val capturedImages = mutableListOf<ByteArray>()
    private var manualCaptureStartedAt = 0L
    private var lastCapturedSignature: IntArray? = null
    private var manualCaptureActive = false
    private var capturePageRequested = false
    private var destroyed = false
    private var width = 0
    private var height = 0
    private val captureAttempt = Runnable { captureWhenReady() }
    private val timeout = Runnable {
        if (framesCaptured > 0) completeCapture(ManualCaptureDecision.TIME_LIMIT)
        else fail("Capture timed out. Return to the same chat and try again.")
    }
    private val manualTimeout = Runnable {
        if (manualCaptureActive && !captureResultDelivered) {
            completeCapture(ManualCaptureDecision.TIME_LIMIT)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CAPTURE_VIEW -> {
                if (intent.getStringExtra(EXTRA_REQUEST_ID) == requestId) requestAdditionalView()
                return START_NOT_STICKY
            }
            ACTION_STOP -> {
                if (intent.getStringExtra(EXTRA_REQUEST_ID) == requestId && framesCaptured > 0) {
                    completeCapture(ManualCaptureDecision.USER_DONE)
                } else if (requestId.isNotEmpty()) {
                    fail("Capture stopped. Tap Suggest replies when you're ready.")
                } else {
                    stopSelf()
                }
                return START_NOT_STICKY
            }
        }
        if (requestId.isNotEmpty()) return START_NOT_STICKY
        requestId = intent?.getStringExtra(EXTRA_REQUEST_ID).orEmpty()
        @Suppress("DEPRECATION")
        val consent: Intent? = if (Build.VERSION.SDK_INT >= 33) intent?.getParcelableExtra(EXTRA_CONSENT, Intent::class.java)
            else intent?.getParcelableExtra(EXTRA_CONSENT)
        if (requestId.isBlank() || consent == null || ReplyCaptureSession.state.value?.id != requestId) {
            flogError { "RepliCapture: rejecting start (blank=${requestId.isBlank()} consent=${consent != null})" }
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            startCaptureForeground()
            val wm = getSystemService(WindowManager::class.java)
            if (Build.VERSION.SDK_INT >= 30) {
                val bounds = wm.maximumWindowMetrics.bounds
                width = bounds.width(); height = bounds.height()
            } else {
                @Suppress("DEPRECATION")
                val metrics = android.util.DisplayMetrics().also(wm.defaultDisplay::getRealMetrics)
                width = metrics.widthPixels; height = metrics.heightPixels
            }
            val imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
            reader = imageReader
            imageReader.setOnImageAvailableListener({ captureWhenReady() }, handler)
            projection = getSystemService(MediaProjectionManager::class.java)
                .getMediaProjection(Activity.RESULT_OK, consent)
            val capture = requireNotNull(projection)
            capture.registerCallback(projectionCallback, handler)
            display = capture.createVirtualDisplay(
                "Repli reply capture", width, height, resources.configuration.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, imageReader.surface, null, handler,
            )
            handler.postDelayed(timeout, ReplyManualCapturePolicy.MAX_DURATION_MS + 15_000L)
            scope.launch {
                ReplyCaptureSession.state.collect { state ->
                    when {
                        state?.id != requestId -> stopSelf()
                        state.phase == ReplyPhase.ERROR -> stopSelf()
                        state.phase == ReplyPhase.RETURNING && state.viewport != null -> captureWhenReady()
                    }
                }
            }
        } catch (_: Exception) {
            fail("Android couldn't start screen sharing. Tap Suggest replies and allow a new capture session.")
        }
        return START_NOT_STICKY
    }

    private fun captureWhenReady() {
        if (processing || destroyed) return
        val state = ReplyCaptureSession.state.value?.takeIf { it.id == requestId } ?: return
        val viewport = state.viewport ?: return
        if (state.phase != ReplyPhase.RETURNING) return
        if (viewport.width != width || viewport.height != height) {
            flogError { "RepliCapture: size mismatch viewport=${viewport.width}x${viewport.height} display=${width}x${height}" }
            fail("The screen size changed. Keep the phone in the same orientation and capture again.")
            return
        }
        handler.removeCallbacks(captureAttempt)
        val remaining = maxOf(viewport.readyAt, captureReadyAt) - SystemClock.elapsedRealtime()
        if (remaining > 0) { handler.postDelayed(captureAttempt, remaining); return }
        if (manualCaptureActive && !capturePageRequested) return
        val image = runCatching { reader?.acquireLatestImage() }.getOrNull()
        if (image == null) { handler.postDelayed(captureAttempt, 100); return }
        val frameAcquiredAt = SystemClock.elapsedRealtime()
        processing = true
        val bitmaps = try {
            image.captureBitmaps(viewport.contentBottom, capturedContactName == null)
        } catch (_: Exception) {
            null
        } finally { image.close() }
        if (bitmaps == null) { fail("Not enough of the conversation is visible. Close extra panels and try again."); return }
        val bitmap = bitmaps.chat
        val headerBitmap = bitmaps.header
        val signature = bitmap.visualSignature()
        if (manualCaptureActive) {
            capturePageRequested = false
            val previous = lastCapturedSignature
            if (previous != null && visualDifference(previous, signature) < VISUAL_CHANGE_THRESHOLD) {
                bitmap.recycle()
                headerBitmap?.recycle()
                processing = false
                val message = "That view is already captured · scroll to another page or tap Done"
                ReplyCaptureSession.update(requestId) { it.copy(phase = ReplyPhase.RETURNING, message = message) }
                updateCaptureNotification(message)
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
                return
            }
        }
        ReplyCaptureSession.update(requestId) { it.copy(phase = ReplyPhase.READING, message = "Reading visible messages on your phone…") }
        val readStartedAt = SystemClock.elapsedRealtime()
        val imageBytes = bitmap.privateVisionPng()
        val imageEncodingMs = SystemClock.elapsedRealtime() - readStartedAt
        recognizerUsed = true
        var recognizedTurns: List<ConversationTurn> = emptyList()
        var contactName: String? = null
        var chatReadComplete = false
        var headerReadComplete = headerBitmap == null
        var frameCompleted = false

        fun finishWhenBothReady() {
            if (frameCompleted || !chatReadComplete || !headerReadComplete) return
            frameCompleted = true
            bitmap.recycle()
            headerBitmap?.recycle()
            if (capturedContactName == null) capturedContactName = contactName
            processing = false
            flogDebug {
                "RepliCapture: frame read framePreparationMs=${readStartedAt - frameAcquiredAt} " +
                    "imageEncodingMs=$imageEncodingMs " +
                    "localRecognitionMs=${SystemClock.elapsedRealtime() - readStartedAt - imageEncodingMs}"
            }
            finishFrame(recognizedTurns, imageBytes, signature)
        }

        try {
            recognizer.process(InputImage.fromBitmap(bitmap, 0))
                .addOnCompleteListener { task ->
                    if (!destroyed && ReplyCaptureSession.state.value?.id == requestId && task.isSuccessful) {
                        recognizedTurns = runCatching {
                            val text = task.result
                            val regions = text.textBlocks.flatMap { block ->
                                val lines = block.lines
                                val isSelfQuote = lines.firstOrNull()?.text
                                    ?.trim()?.removeSuffix(":")?.equals("you", ignoreCase = true) == true
                                if (isSelfQuote && lines.size >= 2) {
                                    lines.mapNotNull { line ->
                                        val bounds = line.boundingBox ?: return@mapNotNull null
                                        OcrTextRegion(line.text, bounds.left, bounds.top, bounds.right, bounds.bottom)
                                    }
                                } else {
                                    val bounds = block.boundingBox
                                    if (bounds == null) emptyList()
                                    else listOf(OcrTextRegion(block.text, bounds.left, bounds.top, bounds.right, bounds.bottom))
                                }
                            }
                            ReplyConversation.extract(regions, bitmap.width, bitmap.height)
                        }.getOrDefault(emptyList())
                    }
                    chatReadComplete = true
                    finishWhenBothReady()
                }
        } catch (_: Exception) {
            chatReadComplete = true
            finishWhenBothReady()
        }
        if (headerBitmap != null) {
            try {
                recognizer.process(InputImage.fromBitmap(headerBitmap, 0))
                    .addOnCompleteListener { task ->
                        if (!destroyed && ReplyCaptureSession.state.value?.id == requestId && task.isSuccessful) {
                            contactName = runCatching {
                                val lines = task.result.textBlocks.flatMap { it.lines }
                                    .sortedBy { it.boundingBox?.top ?: Int.MAX_VALUE }
                                    .map { it.text }
                                CapturedContactName.fromHeaderLines(lines)
                            }.getOrNull()
                        }
                        headerReadComplete = true
                        finishWhenBothReady()
                    }
            } catch (_: Exception) {
                headerReadComplete = true
                finishWhenBothReady()
            }
        }
    }

    private fun finishFrame(
        frameTurns: List<ConversationTurn>,
        imageBytes: ByteArray?,
        signature: IntArray,
    ) {
        if (destroyed || ReplyCaptureSession.state.value?.id != requestId) {
            imageBytes?.fill(0)
            return
        }
        // Capture order is the chronology contract. The user starts on the oldest requested page
        // and advances only toward newer messages, so neither OCR nor image similarity may reorder it.
        capturedTurns = if (framesCaptured == 0) frameTurns
        else ReplyConversation.merge(capturedTurns, frameTurns)
        if (imageBytes != null) capturedImages += imageBytes
        framesCaptured += 1
        lastCapturedSignature = signature
        val now = SystemClock.elapsedRealtime()
        val decision = if (ReplyCaptureSession.state.value?.singleView == true) {
            ManualCaptureDecision.USER_DONE
        } else ReplyManualCapturePolicy.afterFrame(
            framesCaptured = (ReplyCaptureSession.state.value?.frames ?: 0) + framesCaptured,
            totalTurns = capturedTurns.size,
            elapsedMs = if (manualCaptureStartedAt == 0L) 0L else now - manualCaptureStartedAt,
        )
        if (decision == ManualCaptureDecision.CONTINUE) beginOrContinueManualCapture()
        else completeCapture(decision)
    }

    private fun beginOrContinueManualCapture() {
        val state = ReplyCaptureSession.state.value?.takeIf { it.id == requestId }
            ?: return completeCapture(ManualCaptureDecision.USER_DONE)
        state.viewport ?: return completeCapture(ManualCaptureDecision.USER_DONE)
        val now = SystemClock.elapsedRealtime()
        if (!manualCaptureActive) {
            val overlayAvailable = showManualCaptureGuide(state.editor.packageName)
            val notificationAvailable = Build.VERSION.SDK_INT < 33 ||
                checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!overlayAvailable && !notificationAvailable) {
                Toast.makeText(
                    this,
                    "For more pages, enable Repli guided capture or capture notifications in Settings.",
                    Toast.LENGTH_LONG,
                ).show()
                completeCapture(ManualCaptureDecision.USER_DONE)
                return
            }
            manualCaptureActive = true
            manualCaptureStartedAt = now
            handler.postDelayed(manualTimeout, ReplyManualCapturePolicy.MAX_DURATION_MS)
            if (!overlayAvailable) {
                Toast.makeText(this, "Scroll to a newer page, then use Capture view in the Repli notification.", Toast.LENGTH_LONG).show()
            }
        }
        ReplyCaptureSession.update(requestId) {
            it.copy(
                phase = ReplyPhase.RETURNING,
                message = "Move to the next newer page, then tap Capture view · keep a little overlap",
            )
        }
        updateCaptureNotification(
            "${state.frames + framesCaptured}/${ReplyManualCapturePolicy.MAX_FRAMES} views · continue oldest → newest",
        )
        ReplyAutoScrollBridge.updateGuide(state.frames + framesCaptured, ReplyManualCapturePolicy.MAX_FRAMES)
    }

    private fun requestAdditionalView() {
        if (destroyed || captureResultDelivered || !manualCaptureActive || processing) return
        val elapsed = SystemClock.elapsedRealtime() - manualCaptureStartedAt
        if (elapsed >= ReplyManualCapturePolicy.MAX_DURATION_MS) {
            completeCapture(ManualCaptureDecision.TIME_LIMIT)
            return
        }
        capturePageRequested = true
        captureReadyAt = SystemClock.elapsedRealtime() + PAGE_CAPTURE_SETTLE_MS
        ReplyCaptureSession.update(requestId) {
            it.copy(phase = ReplyPhase.RETURNING, message = "Capturing this view…")
        }
        val previousViews = ReplyCaptureSession.state.value?.frames ?: 0
        updateCaptureNotification("Capturing view ${previousViews + framesCaptured + 1}/${ReplyManualCapturePolicy.MAX_FRAMES}…")
        handler.removeCallbacks(captureAttempt)
        handler.postDelayed(captureAttempt, PAGE_CAPTURE_SETTLE_MS)
    }

    private fun completeCapture(decision: ManualCaptureDecision) {
        if (captureResultDelivered || destroyed) return
        val state = ReplyCaptureSession.state.value?.takeIf { it.id == requestId } ?: return
        val baseTurns = state.turns
        captureResultDelivered = true
        flogDebug { "RepliCapture: complete decision=$decision images=${capturedImages.size} turns=${capturedTurns.size}" }
        ReplyAutoScrollBridge.hideGuide()
        if (capturedImages.isNotEmpty()) {
            val images = capturedImages.toList()
            capturedImages.clear()
            PendingVisionCaptureStore.put(PendingVisionCapture(requestId, images, baseTurns, capturedTurns, capturedContactName))
            ReplyCaptureSession.update(requestId) {
                it.copy(
                    phase = ReplyPhase.CAPTURE_REVIEW,
                    turns = ReplyConversation.mergeCapture(baseTurns, capturedTurns),
                    frames = it.frames + framesCaptured,
                    viewport = null,
                    message = when (decision) {
                        ManualCaptureDecision.SHORT_CHAT -> "Short chat found · preparing AI reading…"
                        else -> "Captured $framesCaptured view${if (framesCaptured == 1) "" else "s"} · preparing AI reading…"
                    },
                )
            }
        } else if (capturedTurns.isNotEmpty()) {
            ReplyCaptureSession.update(requestId) {
                it.copy(
                    phase = ReplyPhase.REVIEW,
                    turns = ReplyConversation.mergeCapture(baseTurns, capturedTurns),
                    frames = it.frames + framesCaptured,
                    viewport = null,
                    message = "Review the captured messages, then generate replies",
                )
            }
        } else {
            ReplyCaptureSession.fail(requestId, "This looks like a new or empty chat. Send the first message before capturing context.")
        }
        releaseProjection()
        stopSelf()
    }

    private fun showManualCaptureGuide(targetPackage: String): Boolean {
        val guideShown = ReplyAutoScrollBridge.showGuide(
                expectedPackage = targetPackage,
                requestId = requestId,
                captured = (ReplyCaptureSession.state.value?.frames ?: 0) + framesCaptured,
                maximum = ReplyManualCapturePolicy.MAX_FRAMES,
            )
        if (guideShown) {
            Toast.makeText(
                this,
                "Continue toward newer messages only, then tap Capture view. Keep a little overlap.",
                Toast.LENGTH_LONG,
            ).show()
        }
        return guideShown
    }

    private fun Bitmap.privateVisionPng(): ByteArray? {
        val dimensions = listOf(VISION_MAX_DIMENSION, 2_560, 2_240, 1_920).distinct()
        for (maximumDimension in dimensions) {
            val longest = maxOf(width, height)
            val scale = minOf(1f, maximumDimension.toFloat() / longest.toFloat())
            val prepared = if (scale < 1f) Bitmap.createScaledBitmap(
                this,
                (width * scale).toInt().coerceAtLeast(1),
                (height * scale).toInt().coerceAtLeast(1),
                true,
            ) else this
            try {
                val output = ByteArrayOutputStream()
                if (prepared.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                    val bytes = output.toByteArray()
                    if (bytes.size <= VISION_FRAME_BYTE_BUDGET) return bytes
                    bytes.fill(0)
                }
            } finally {
                if (prepared !== this) prepared.recycle()
            }
        }
        return null
    }

    private fun Bitmap.visualSignature(): IntArray {
        val columns = 8
        val rows = 12
        return IntArray(columns * rows) { index ->
            val column = index % columns
            val row = index / columns
            val x = ((column + 0.5f) * width / columns).toInt().coerceIn(0, width - 1)
            val y = ((row + 0.5f) * height / rows).toInt().coerceIn(0, height - 1)
            val color = getPixel(x, y)
            (android.graphics.Color.red(color) * 3 +
                android.graphics.Color.green(color) * 6 +
                android.graphics.Color.blue(color)) / 10
        }
    }

    private data class CaptureBitmaps(val chat: Bitmap, val header: Bitmap?)

    private fun Image.captureBitmaps(contentBottom: Int, readHeader: Boolean): CaptureBitmaps? {
        // Exclude the status/header area and host app composer. Repli's keyboard
        // was already hidden before consent, leaving the expanded chat in between.
        val cropTop = dp(96).coerceAtMost(height / 3)
        val cropBottom = contentBottom.coerceAtMost(height)
        if (cropBottom - cropTop < dp(72)) return null
        val plane = planes.first()
        val paddedWidth = width + (plane.rowStride - plane.pixelStride * width) / plane.pixelStride
        val padded = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
        return try {
            plane.buffer.rewind()
            padded.copyPixelsFromBuffer(plane.buffer)
            val chat = Bitmap.createBitmap(padded, 0, cropTop, width, cropBottom - cropTop)
            val statusBarBottom = if (Build.VERSION.SDK_INT >= 30) {
                runCatching {
                    getSystemService(WindowManager::class.java).maximumWindowMetrics.windowInsets
                        .getInsetsIgnoringVisibility(WindowInsets.Type.statusBars()).top
                }.getOrDefault(dp(24))
            } else dp(24)
            val headerTop = (statusBarBottom + dp(4)).coerceAtMost(cropTop)
            val header = if (readHeader && cropTop - headerTop >= dp(24)) {
                runCatching { Bitmap.createBitmap(padded, 0, headerTop, width, cropTop - headerTop) }.getOrNull()
            } else null
            CaptureBitmaps(chat, header)
        } finally { padded.recycle() }
    }

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            if (!destroyed && !captureResultDelivered) fail("Android ended screen sharing before capture. Tap Suggest replies to retry.")
        }

        override fun onCapturedContentResize(newWidth: Int, newHeight: Int) {
            if (!processing && (newWidth != width || newHeight != height)) {
                fail("Capture needs the entire screen at its current size. Try again and choose Entire screen if asked.")
            }
        }
    }

    private fun fail(message: String) {
        if (destroyed) return
        flogError { "RepliCapture: fail: $message" }
        ReplyCaptureSession.fail(requestId, message)
        stopSelf()
    }

    private fun releaseProjection() {
        ReplyAutoScrollBridge.hideGuide()
        reader?.setOnImageAvailableListener(null, null)
        display?.release(); display = null
        reader?.close(); reader = null
        projection?.let { capture ->
            runCatching { capture.unregisterCallback(projectionCallback) }
            runCatching { capture.stop() }
        }
        projection = null
    }

    override fun onDestroy() {
        destroyed = true
        handler.removeCallbacksAndMessages(null)
        scope.cancel()
        releaseProjection()
        if (recognizerUsed) recognizer.close()
        stopForeground(STOP_FOREGROUND_REMOVE)
        capturedImages.forEach { it.fill(0) }
        capturedImages.clear()
        capturedContactName = null
        val current = ReplyCaptureSession.state.value
        if (current?.id == requestId && shouldReportCaptureInterruption(captureResultDelivered, current.phase)) {
            ReplyCaptureSession.fail(requestId, "Capture was interrupted. Tap Suggest replies to try again.")
        }
        super.onDestroy()
    }

    private fun startCaptureForeground() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Reply screen capture", NotificationManager.IMPORTANCE_LOW),
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("Repli is capturing chat context")
            .setContentText("Keep the keyboard closed. The first visible view is being captured.")
            .setOngoing(true).setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .addAction(Notification.Action.Builder(null, "Done", doneAction()).build())
            .build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(9821, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(9821, notification)
    }

    private fun updateCaptureNotification(message: String) {
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("Repli is capturing chat context")
            .setContentText(message)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
        if (manualCaptureActive && (ReplyCaptureSession.state.value?.frames ?: 0) + framesCaptured < ReplyManualCapturePolicy.MAX_FRAMES) {
            builder.addAction(Notification.Action.Builder(null, "Capture view", captureViewAction()).build())
        }
        builder.addAction(Notification.Action.Builder(null, "Done", doneAction()).build())
        getSystemService(NotificationManager::class.java).notify(9821, builder.build())
    }

    private fun captureViewAction(): PendingIntent = PendingIntent.getActivity(
        this,
        9822,
        Intent(this, ReplyCaptureNextPageActivity::class.java)
            .putExtra(EXTRA_REQUEST_ID, requestId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_HISTORY),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun doneAction(): PendingIntent = PendingIntent.getService(
        this,
        9821,
        Intent(this, ReplyScreenCaptureService::class.java)
            .setAction(ACTION_STOP)
            .putExtra(EXTRA_REQUEST_ID, requestId),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        const val EXTRA_REQUEST_ID = "request_id"
        const val EXTRA_CONSENT = "consent"
        private const val ACTION_CAPTURE_VIEW = "dev.patrickgold.florisboard.repli.reply.CAPTURE_VIEW"

        fun nextViewIntent(context: Context, requestId: String): Intent =
            Intent(context, ReplyScreenCaptureService::class.java)
                .setAction(ACTION_CAPTURE_VIEW)
                .putExtra(EXTRA_REQUEST_ID, requestId)
        private const val ACTION_STOP = "dev.patrickgold.florisboard.repli.reply.STOP"
        private const val CHANNEL_ID = "reply_capture"
        private const val PAGE_CAPTURE_SETTLE_MS = 1_200L
        private const val VISION_MAX_DIMENSION = 3_072
        private const val VISION_FRAME_BYTE_BUDGET =
            ServerMediatedContextEngine.MAX_TOTAL_IMAGE_BYTES /
                ServerMediatedContextEngine.MAX_IMAGES
        private const val VISUAL_CHANGE_THRESHOLD = 6

        internal fun requestCaptureView(context: Context, requestId: String) {
            context.startService(
                Intent(context, ReplyScreenCaptureService::class.java)
                    .setAction(ACTION_CAPTURE_VIEW)
                    .putExtra(EXTRA_REQUEST_ID, requestId),
            )
        }

        internal fun finishCapture(context: Context, requestId: String) {
            context.startService(
                Intent(context, ReplyScreenCaptureService::class.java)
                    .setAction(ACTION_STOP)
                    .putExtra(EXTRA_REQUEST_ID, requestId),
            )
        }
    }
}

internal fun visualDifference(first: IntArray, second: IntArray): Int {
    if (first.size != second.size || first.isEmpty()) return Int.MAX_VALUE
    return first.indices.sumOf { kotlin.math.abs(first[it] - second[it]) } / first.size
}

internal fun shouldReportCaptureInterruption(resultDelivered: Boolean, phase: ReplyPhase?): Boolean =
    !resultDelivered && phase in setOf(ReplyPhase.RETURNING, ReplyPhase.READING)

private fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
