/*
 * Copyright (C) 2025-2026 The Repli Keyboard Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

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
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import android.view.WindowManager
import dev.patrickgold.florisboard.repli.data.PendingCapture
import dev.patrickgold.florisboard.repli.data.PendingCaptureRepository
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.Executor

class LearnByScrollingService : Service() {
    private lateinit var recognizer: TextRecognizer
    private lateinit var workerThread: HandlerThread
    private lateinit var workerHandler: Handler
    private lateinit var notificationManager: NotificationManager

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var collector: ScrollingTextCollector? = null
    private var profileId: String? = null
    private var profileName: String? = null
    private var messageSide = MessageSide.RIGHT
    private var readyAt = 0L
    private var lastProcessedAt = 0L
    private var captureStarted = false
    @Volatile private var processing = false
    @Volatile private var finished = false

    override fun onCreate() {
        super.onCreate()
        recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        notificationManager = getSystemService(NotificationManager::class.java)
        createNotificationChannel()
        workerThread = HandlerThread("assisted-scroll-ocr").apply { start() }
        workerHandler = Handler(workerThread.looper)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> workerHandler.post { finishCapture(CaptureFinishReason.USER_STOPPED) }
            ACTION_START -> startCapture(intent)
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startCapture(intent: Intent) {
        if (mediaProjection != null || finished) return
        profileId = intent.getStringExtra(EXTRA_PROFILE_ID)
        profileName = intent.getStringExtra(EXTRA_PROFILE_NAME)
        messageSide = intent.getStringExtra(EXTRA_MESSAGE_SIDE)
            ?.let { runCatching { MessageSide.valueOf(it) }.getOrNull() }
            ?: MessageSide.RIGHT
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        val resultData = intent.intentExtra(EXTRA_RESULT_DATA)
        if (profileId.isNullOrBlank() || profileName.isNullOrBlank() ||
            resultCode != Activity.RESULT_OK || resultData == null
        ) {
            stopSelf()
            return
        }

        PendingCaptureRepository(this).clear()
        collector = ScrollingTextCollector(messageSide)
        startCaptureForeground(0)

        runCatching {
            val (width, height) = captureDimensions()
            val density = resources.configuration.densityDpi
            val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            imageReader = reader
            reader.setOnImageAvailableListener(::onImageAvailable, workerHandler)

            val manager = getSystemService(MediaProjectionManager::class.java)
            val projection = manager.getMediaProjection(resultCode, resultData)
                ?: error("Android did not provide a screen-capture session.")
            mediaProjection = projection
            projection.registerCallback(projectionCallback, workerHandler)
            virtualDisplay = projection.createVirtualDisplay(
                "Repli learn by scrolling",
                width,
                height,
                density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                workerHandler,
            )
            readyAt = SystemClock.elapsedRealtime() + INITIAL_DELAY_MS
            captureStarted = true
        }.onFailure {
            finishCapture(CaptureFinishReason.START_FAILED)
        }
    }

    private fun onImageAvailable(reader: ImageReader) {
        val image = reader.acquireLatestImage() ?: return
        val now = SystemClock.elapsedRealtime()
        if (finished || processing || now < readyAt || now - lastProcessedAt < SAMPLE_INTERVAL_MS) {
            image.close()
            return
        }
        processing = true
        lastProcessedAt = now
        val bitmap = runCatching { image.toBitmap() }.getOrNull()
        image.close()
        if (bitmap == null) {
            processing = false
            return
        }

        val workerExecutor = Executor { task -> workerHandler.post(task) }
        recognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener(workerExecutor) { text ->
                if (finished) return@addOnSuccessListener
                val regions = text.textBlocks.flatMap { block -> block.lines }.mapNotNull { line ->
                    val bounds = line.boundingBox ?: return@mapNotNull null
                    OcrTextRegion(line.text, bounds.left, bounds.top, bounds.right, bounds.bottom)
                }
                val added = collector?.addFrame(regions, bitmap.width, bitmap.height) ?: 0
                if (added > 0) startCaptureForeground(collector?.messages()?.size ?: 0)
            }
            .addOnCompleteListener(workerExecutor) {
                bitmap.recycle()
                processing = false
            }
    }

    private fun finishCapture(reason: CaptureFinishReason) {
        if (finished) return
        finished = true
        val messages = collector?.messages().orEmpty()
        val id = profileId
        val name = profileName
        if (captureStarted && reason != CaptureFinishReason.START_FAILED &&
            !id.isNullOrBlank() && !name.isNullOrBlank()
        ) {
            PendingCaptureRepository(this).save(PendingCapture(id, name, messageSide, messages))
        }

        imageReader?.setOnImageAvailableListener(null, null)
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        val projection = mediaProjection
        runCatching { projection?.unregisterCallback(projectionCallback) }
        if (reason != CaptureFinishReason.PROJECTION_ENDED) {
            runCatching { projection?.stop() }
        }
        mediaProjection = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        if (!id.isNullOrBlank()) {
            when (reason) {
                CaptureFinishReason.USER_STOPPED -> showCompletionNotification(messages.size)
                CaptureFinishReason.PROJECTION_ENDED -> showInterruptedNotification(messages.size)
                CaptureFinishReason.START_FAILED -> showErrorNotification()
                CaptureFinishReason.SERVICE_DESTROYED -> Unit
            }
        }
        stopSelf()
    }

    override fun onDestroy() {
        if (!finished) finishCapture(CaptureFinishReason.SERVICE_DESTROYED)
        recognizer.close()
        workerThread.quitSafely()
        super.onDestroy()
    }

    private fun captureDimensions(): Pair<Int, Int> {
        val windowManager = getSystemService(WindowManager::class.java)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.maximumWindowMetrics.bounds
            bounds.width() to bounds.height()
        } else {
            @Suppress("DEPRECATION")
            val metrics = android.util.DisplayMetrics().also(windowManager.defaultDisplay::getRealMetrics)
            metrics.widthPixels to metrics.heightPixels
        }
    }

    private fun Image.toBitmap(): Bitmap {
        val plane = planes.first()
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val paddedWidth = width + (rowStride - pixelStride * width) / pixelStride
        val padded = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
        plane.buffer.rewind()
        padded.copyPixelsFromBuffer(plane.buffer)
        if (paddedWidth == width) return padded
        return Bitmap.createBitmap(padded, 0, 0, width, height).also { padded.recycle() }
    }

    private fun startCaptureForeground(messageCount: Int) {
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, LearnByScrollingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("Learning from visible messages")
            .setContentText("Scroll the chat manually · $messageCount possible messages found")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(Notification.Action.Builder(null, "Stop and review", stopIntent).build())
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                CAPTURE_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            )
        } else {
            startForeground(CAPTURE_NOTIFICATION_ID, notification)
        }
    }

    private fun showCompletionNotification(messageCount: Int) {
        notifyWithContent(
            COMPLETE_NOTIFICATION_ID,
            title = "Scrolling session finished",
            text = "$messageCount possible messages found · tap to review",
        )
    }

    private fun showInterruptedNotification(messageCount: Int) {
        val text = if (messageCount == 0) {
            "No messages were captured before Android ended sharing · tap to retry"
        } else {
            "$messageCount possible messages preserved · tap to review"
        }
        notifyWithContent(
            COMPLETE_NOTIFICATION_ID,
            title = "Screen capture ended early",
            text = text,
        )
    }

    /** Distinct from the completion notice: shown when a capture session could not start. */
    private fun showErrorNotification() {
        notifyWithContent(
            COMPLETE_NOTIFICATION_ID,
            title = "Screen capture didn't start",
            text = "Tap to try Learn by scrolling again",
        )
    }

    private fun notifyWithContent(notificationId: Int, title: String, text: String) {
        // Fork adaptation: the source opened its own MainActivity here. The fork has no
        // equivalent review activity yet, so tapping re-opens the host app via its launch
        // intent. TODO: point this at the FlorisBoard settings/learn-by-scrolling review
        // screen once it exists.
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP) ?: return
        val contentIntent = PendingIntent.getActivity(
            this,
            2,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        notificationManager.notify(
            notificationId,
            Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(contentIntent)
                .setAutoCancel(true)
                .build(),
        )
    }

    private fun createNotificationChannel() {
        notificationManager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Learn by scrolling",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Visible indicator and stop control for user-started chat learning"
            },
        )
    }

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            workerHandler.post { finishCapture(CaptureFinishReason.PROJECTION_ENDED) }
        }
    }

    @Suppress("DEPRECATION")
    private fun Intent.intentExtra(key: String): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(key, Intent::class.java)
        } else {
            getParcelableExtra(key)
        }

    companion object {
        private const val ACTION_START = "dev.patrickgold.florisboard.repli.capture.START"
        private const val ACTION_STOP = "dev.patrickgold.florisboard.repli.capture.STOP"
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_RESULT_DATA = "result_data"
        private const val EXTRA_PROFILE_ID = "profile_id"
        private const val EXTRA_PROFILE_NAME = "profile_name"
        private const val EXTRA_MESSAGE_SIDE = "message_side"
        private const val CHANNEL_ID = "learn_by_scrolling"
        private const val CAPTURE_NOTIFICATION_ID = 7811
        private const val COMPLETE_NOTIFICATION_ID = 7812
        private const val INITIAL_DELAY_MS = 4_000L
        private const val SAMPLE_INTERVAL_MS = 700L

        fun start(
            context: Context,
            resultCode: Int,
            resultData: Intent,
            profileId: String,
            profileName: String,
            messageSide: MessageSide,
        ) {
            val intent = Intent(context, LearnByScrollingService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, resultData)
                .putExtra(EXTRA_PROFILE_ID, profileId)
                .putExtra(EXTRA_PROFILE_NAME, profileName)
                .putExtra(EXTRA_MESSAGE_SIDE, messageSide.name)
            context.startForegroundService(intent)
        }
    }

    private enum class CaptureFinishReason {
        USER_STOPPED,
        PROJECTION_ENDED,
        START_FAILED,
        SERVICE_DESTROYED,
    }
}
