package dev.patrickgold.florisboard.repli.review

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import dev.patrickgold.florisboard.repli.capture.ConversationTurn
import dev.patrickgold.florisboard.repli.capture.ReplyCaptureSession
import dev.patrickgold.florisboard.repli.capture.ReplyConversation
import dev.patrickgold.florisboard.repli.capture.ReplyPhase
import dev.patrickgold.florisboard.repli.capture.ReviewEvidenceStore
import dev.patrickgold.florisboard.repli.capture.TurnSource
import dev.patrickgold.florisboard.repli.suggestions.PreparedRemoteReplyRequest
import dev.patrickgold.florisboard.repli.suggestions.RemoteReplyPrivacyPolicy
import dev.patrickgold.florisboard.repli.suggestions.SharedSpeaker
import dev.patrickgold.florisboard.repli.diagnostics.CaptureTelemetry

/** Editable full-screen review. Corrections revoke the old approval and prepare a new payload. */
class FullScreenContextReviewActivity : ComponentActivity() {
    private var requestId = ""
    private lateinit var edits: ContextReviewEdits
    private lateinit var rows: LinearLayout
    private lateinit var countLabel: TextView
    private lateinit var finishButton: TextView
    private var expandedIndex: Int? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestId = intent.getStringExtra(EXTRA_REQUEST_ID).orEmpty()
        val state = ReplyCaptureSession.state.value
        val payload = ReviewPayload.from(intent, state?.turns.orEmpty())
        if (requestId.isBlank() || state?.id != requestId || state.phase !in setOf(ReplyPhase.REVIEW, ReplyPhase.APPROVAL) || payload == null) {
            FullScreenContextReviewSession.end(requestId)
            finish()
            return
        }
        FullScreenContextReviewSession.begin(requestId)
        val restored = savedInstanceState?.let(::restoreTurns)
        edits = ContextReviewEdits(restored ?: payload.messages, initiallyChanged = restored != null)
        expandedIndex = savedInstanceState?.getInt(SAVED_EXPANDED_INDEX, -1)?.takeIf { it in edits.turns.indices }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = confirmDiscardOrFinish()
        })
        window.statusBarColor = PAPER
        window.navigationBarColor = PAPER
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = if (isDarkMode) 0 else
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        val content = reviewView(payload)
        content.setOnApplyWindowInsetsListener { view, insets ->
            val (top, bottom) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                bars.top to bars.bottom
            } else {
                @Suppress("DEPRECATION")
                insets.systemWindowInsetTop to insets.systemWindowInsetBottom
            }
            view.setPadding(0, top, 0, bottom)
            insets
        }
        setContentView(content)
        content.requestApplyInsets()
        renderRows()
    }

    private fun reviewView(payload: ReviewPayload) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(PAPER)

        addView(LinearLayout(this@FullScreenContextReviewActivity).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            addView(actionButton("Back", filled = false).apply {
                contentDescription = "Return to reply approval"
                setOnClickListener { confirmDiscardOrFinish() }
            }, LinearLayout.LayoutParams(dp(82), dp(44)))
            addView(label("Review chats", 20f, INK, bold = true), LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f,
            ).apply { marginStart = dp(12) })
            countLabel = label("${payload.messages.size} messages", 12f, ACCENT, bold = true).apply {
                setPadding(dp(10), dp(5), dp(10), dp(5))
                background = roundedBackground(ACCENT_SOFT, 14)
            }
            addView(countLabel)
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ))

        addView(ScrollView(this@FullScreenContextReviewActivity).apply {
            isFillViewport = true
            addView(LinearLayout(this@FullScreenContextReviewActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(8), dp(16), dp(20))

                payload.instructions?.let { direction ->
                    addView(detailCard("How do you want to Repli?", direction), sectionParams(bottom = 12))
                }
                addView(label("Recent chat · tap a message to correct it", 13f, MUTED, bold = true), sectionParams(bottom = 7))
                rows = LinearLayout(this@FullScreenContextReviewActivity).apply {
                    orientation = LinearLayout.VERTICAL
                }
                addView(rows)

                val style = buildString {
                    append(payload.preset.replaceFirstChar { it.titlecase() })
                    payload.summary?.let { append(" · ").append(it) }
                }
                addView(detailCard("Reply style", style), sectionParams(top = 5, bottom = 12))
                if (payload.examples.isNotEmpty()) {
                    addView(
                        detailCard(
                            "Writing examples (${payload.examples.size})",
                            payload.examples.joinToString("\n") { "“$it”" },
                        ),
                        sectionParams(bottom = 8),
                    )
                }
            })
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        finishButton = actionButton("Back to Generate replies").apply {
            setOnClickListener { saveOrReturn() }
        }
        addView(finishButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52)).apply {
            marginStart = dp(16)
            marginEnd = dp(16)
            bottomMargin = dp(12)
        })
    }

    private fun detailCard(title: String, value: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(11), dp(14), dp(12))
        background = roundedBackground(CARD, 14, LINE_SOFT)
        addView(label(title, 12f, MUTED, bold = true))
        addView(label(value, 14f, INK), sectionParams(top = 3))
    }

    private fun renderRows() {
        rows.removeAllViews()
        countLabel.text = "${edits.turns.size} messages"
        finishButton.text = if (edits.changed) "Save corrected context" else "Back to Generate replies"
        edits.turns.forEachIndexed { index, message ->
            rows.addView(chatBubble(index, message), sectionParams(bottom = 7))
        }
    }

    private fun chatBubble(index: Int, message: ConversationTurn) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = if (message.fromMe) Gravity.END else Gravity.START
        addView(LinearLayout(this@FullScreenContextReviewActivity).apply {
            orientation = LinearLayout.VERTICAL
            minimumWidth = dp(96)
            setPadding(dp(12), dp(8), dp(12), dp(9))
            background = roundedBackground(
                if (message.fromMe) ACCENT_SOFT else CARD,
                14,
                if (message.fromMe) null else LINE_SOFT,
            )
            addView(label(if (message.fromMe) "You" else "Them", 11f, MUTED, bold = true))
            addView(label(message.text, 14f, INK), sectionParams(top = 2))
            if (message.source?.uncertain == true) {
                addView(label("Check this message against its source", 11f, ACCENT), sectionParams(top = 4))
            }
            contentDescription = "${if (message.fromMe) "You" else "Them"}: ${message.text}. Tap to correct"
            setOnClickListener {
                expandedIndex = if (expandedIndex == index) null else index
                renderRows()
            }
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ))
        if (expandedIndex == index) {
            addView(LinearLayout(this@FullScreenContextReviewActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(LinearLayout(this@FullScreenContextReviewActivity).apply {
                    addView(correctionButton("Edit") { editText(index) }, LinearLayout.LayoutParams(0, dp(38), 1f))
                    addView(correctionButton("You/Them") { if (edits.flipSpeaker(index)) CaptureTelemetry(this@FullScreenContextReviewActivity).count(CaptureTelemetry.SPEAKER_CORRECTIONS); renderRows() }, LinearLayout.LayoutParams(0, dp(38), 1f))
                    if (message.source != null) addView(correctionButton("Source") { showSource(message) }, LinearLayout.LayoutParams(0, dp(38), 1f))
                })
                addView(LinearLayout(this@FullScreenContextReviewActivity).apply {
                    addView(correctionButton("Merge ↑") {
                        if (edits.mergeWithPrevious(index)) { expandedIndex = null; renderRows() }
                        else Toast.makeText(this@FullScreenContextReviewActivity, "Merge with a previous message from the same sender", Toast.LENGTH_SHORT).show()
                    }, LinearLayout.LayoutParams(0, dp(38), 1f))
                    addView(correctionButton("Add ↓") { addMessageAfter(index, message.fromMe) }, LinearLayout.LayoutParams(0, dp(38), 1f))
                    addView(correctionButton("Delete") {
                        if (edits.remove(index)) { expandedIndex = null; renderRows() }
                        else Toast.makeText(this@FullScreenContextReviewActivity, "Keep at least one message", Toast.LENGTH_SHORT).show()
                    }, LinearLayout.LayoutParams(0, dp(38), 1f))
                })
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(76)))
        }
    }

    private fun correctionButton(title: String, action: () -> Unit) = label(title, 11f, ACCENT, bold = true).apply {
        gravity = Gravity.CENTER
        setPadding(dp(2), 0, dp(2), 0)
        setOnClickListener { action() }
    }

    private fun editText(index: Int) {
        val current = edits.turns.getOrNull(index) ?: return
        val input = EditText(this).apply {
            setText(current.text)
            setSelection(text.length)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            filters = arrayOf(InputFilter.LengthFilter(RemoteReplyPrivacyPolicy.MAX_CONTEXT_CHARACTERS))
            minLines = 2
            maxLines = 8
        }
        AlertDialog.Builder(this)
            .setTitle("Edit message")
            .setView(input)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                if (edits.edit(index, input.text.toString())) renderRows()
                else Toast.makeText(this, "Message cannot be empty", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun addMessageAfter(index: Int, fromMe: Boolean) {
        val input = EditText(this).apply {
            hint = "Missing message text"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            filters = arrayOf(InputFilter.LengthFilter(RemoteReplyPrivacyPolicy.MAX_CONTEXT_CHARACTERS))
            minLines = 2
            maxLines = 8
        }
        AlertDialog.Builder(this)
            .setTitle("Add message after this one")
            .setMessage("It will start as ${if (fromMe) "You" else "Them"}; tap You/Them on the new message to change it.")
            .setView(input)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Add") { _, _ ->
                if (edits.insertAfter(index, input.text.toString(), fromMe)) {
                    expandedIndex = index + 1
                    renderRows()
                } else Toast.makeText(this, "Enter a message, or remove one to stay within the limit", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun showSource(message: ConversationTurn) {
        val bitmap = message.source?.let { ReviewEvidenceStore.crop(requestId, it) }
        if (bitmap == null) {
            Toast.makeText(this, "Source image is no longer available", Toast.LENGTH_SHORT).show()
            return
        }
        val image = ImageView(this).apply {
            setImageBitmap(bitmap)
            adjustViewBounds = true
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        AlertDialog.Builder(this)
            .setTitle("Captured view ${message.source.frameIndex + 1}")
            .setView(image)
            .setPositiveButton("Close", null)
            .create().apply {
                setOnDismissListener { image.setImageDrawable(null); bitmap.recycle() }
                show()
            }
    }

    private fun saveOrReturn() {
        if (!edits.changed) { returnToKeyboard(); return }
        val current = ReplyCaptureSession.state.value
        if (current?.id != requestId || current.phase !in setOf(ReplyPhase.REVIEW, ReplyPhase.APPROVAL)) {
            FullScreenContextReviewSession.end(requestId)
            finish()
            return
        }
        FullScreenContextReviewSession.markReturning(requestId)
        ReplyCaptureSession.update(requestId) {
            it.copy(
                turns = edits.turns,
                phase = if (it.phase == ReplyPhase.REVIEW) ReplyPhase.REVIEW else ReplyPhase.CONTEXT,
                replies = emptyList(),
                message = if (it.phase == ReplyPhase.REVIEW) "Review corrected context, then generate replies"
                    else "Preparing corrected context review…",
            )
        }
        finish()
    }

    private fun confirmDiscardOrFinish() {
        if (!edits.changed) { returnToKeyboard(); return }
        AlertDialog.Builder(this)
            .setTitle("Discard corrections?")
            .setMessage("Your chat edits have not been saved yet.")
            .setNegativeButton("Keep editing", null)
            .setPositiveButton("Discard") { _, _ -> returnToKeyboard() }
            .show()
    }

    private fun returnToKeyboard() {
        FullScreenContextReviewSession.markReturning(requestId)
        finish()
    }

    private fun sectionParams(top: Int = 0, bottom: Int = 0) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    ).apply {
        topMargin = dp(top)
        bottomMargin = dp(bottom)
    }

    override fun onDestroy() {
        if (!isChangingConfigurations && !FullScreenContextReviewSession.isReturning(requestId)) {
            FullScreenContextReviewSession.end(requestId)
        }
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (!::edits.isInitialized) { super.onSaveInstanceState(outState); return }
        val turns = edits.turns
        if (edits.changed) {
            outState.putStringArrayList(SAVED_TEXTS, ArrayList(turns.map { it.text }))
            outState.putBooleanArray(SAVED_SPEAKERS, turns.map(ConversationTurn::fromMe).toBooleanArray())
            outState.putIntArray(SAVED_SOURCES, turns.flatMap { turn ->
                turn.source?.let {
                    listOf(it.frameIndex, it.left, it.top, it.right, it.bottom, if (it.uncertain) 1 else 0)
                } ?: listOf(-1, 0, 0, 0, 0, 0)
            }.toIntArray())
        }
        outState.putInt(SAVED_EXPANDED_INDEX, expandedIndex ?: -1)
        super.onSaveInstanceState(outState)
    }

    private fun restoreTurns(saved: Bundle): List<ConversationTurn>? {
        val texts = saved.getStringArrayList(SAVED_TEXTS) ?: return null
        val speakers = saved.getBooleanArray(SAVED_SPEAKERS) ?: return null
        val sources = saved.getIntArray(SAVED_SOURCES) ?: return null
        if (texts.isEmpty() || texts.size > ReplyConversation.MAX_TURNS ||
            speakers.size != texts.size || sources.size != texts.size * 6 ||
            texts.any { it.isBlank() || it.length > RemoteReplyPrivacyPolicy.MAX_CONTEXT_CHARACTERS }
        ) return null
        return texts.mapIndexed { index, text ->
            val offset = index * 6
            val source = if (sources[offset] < 0) null else TurnSource(
                sources[offset], sources[offset + 1], sources[offset + 2],
                sources[offset + 3], sources[offset + 4], sources[offset + 5] == 1,
            )
            ConversationTurn(text, speakers[index], source)
        }
    }

    private data class ReviewPayload(
        val messages: List<ConversationTurn>,
        val preset: String,
        val summary: String?,
        val examples: List<String>,
        val instructions: String?,
    ) {
        companion object {
            fun from(intent: Intent, sourceTurns: List<ConversationTurn>): ReviewPayload? {
                val texts = intent.getStringArrayListExtra(EXTRA_CONTEXT_TEXTS) ?: return null
                val speakers = intent.getBooleanArrayExtra(EXTRA_CONTEXT_SPEAKERS) ?: return null
                if (texts.isEmpty() || texts.size != speakers.size ||
                    texts.size > ReplyConversation.MAX_TURNS
                ) return null
                val alignedSources = sourceTurns.takeLast(texts.size)
                val messages = texts.mapIndexed { index, text ->
                    if (text.isBlank() || text.length > 1_000) return null
                    val sourceTurn = alignedSources.getOrNull(index)
                    val source = sourceTurn?.source?.takeIf {
                        sourceTurn.text.trim().replace(Regex("\\s+"), " ").take(1_000) == text &&
                            sourceTurn.fromMe == speakers[index]
                    }
                    ConversationTurn(text, speakers[index], source)
                }
                val preset = intent.getStringExtra(EXTRA_PRESET)?.takeIf(String::isNotBlank) ?: return null
                val examples = intent.getStringArrayListExtra(EXTRA_EXAMPLES).orEmpty()
                return ReviewPayload(
                    messages = messages,
                    preset = preset,
                    summary = intent.getStringExtra(EXTRA_SUMMARY)?.takeIf(String::isNotBlank),
                    examples = examples,
                    instructions = intent.getStringExtra(EXTRA_INSTRUCTIONS)?.takeIf(String::isNotBlank),
                )
            }
        }
    }

    // Local view helpers (Views-based, no theme references; same values as the source palette).
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun roundedBackground(color: Int, radiusDp: Int, strokeColor: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
            strokeColor?.let { setStroke(dp(1), it) }
        }

    private fun pressableRoundedBackground(
        color: Int,
        pressedColor: Int,
        radiusDp: Int,
        strokeColor: Int? = null,
    ): RippleDrawable {
        val content = roundedBackground(color, radiusDp, strokeColor)
        val mask = roundedBackground(Color.BLACK, radiusDp)
        return RippleDrawable(ColorStateList.valueOf(pressedColor), content, mask)
    }

    private fun label(text: String, sizeSp: Float, color: Int, bold: Boolean = false): TextView =
        TextView(this).apply {
            this.text = text
            textSize = sizeSp
            setTextColor(color)
            includeFontPadding = false
            setLineSpacing(dp(3).toFloat(), 1f)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    private fun actionButton(text: String, filled: Boolean = true): Button = Button(this).apply {
        this.text = text
        isAllCaps = false
        textSize = 14f
        setTextColor(if (filled) ON_ACCENT else ACCENT)
        background = pressableRoundedBackground(
            color = if (filled) ACCENT else CARD,
            pressedColor = if (filled) ACCENT_PRESSED else ACCENT_SOFT,
            radiusDp = 16,
            strokeColor = if (filled) null else LINE_STRONG,
        )
        minHeight = dp(48)
        minimumHeight = dp(48)
        setPadding(dp(16), dp(12), dp(16), dp(12))
    }

    companion object {
        private const val EXTRA_REQUEST_ID = "request_id"
        private const val EXTRA_CONTEXT_TEXTS = "context_texts"
        private const val EXTRA_CONTEXT_SPEAKERS = "context_speakers"
        private const val EXTRA_PRESET = "preset"
        private const val EXTRA_SUMMARY = "summary"
        private const val EXTRA_EXAMPLES = "examples"
        private const val EXTRA_INSTRUCTIONS = "instructions"
        private const val SAVED_TEXTS = "saved_texts"
        private const val SAVED_SPEAKERS = "saved_speakers"
        private const val SAVED_SOURCES = "saved_sources"
        private const val SAVED_EXPANDED_INDEX = "saved_expanded_index"

        fun intent(
            context: Context,
            requestId: String,
            turns: List<ConversationTurn>,
            instructions: String?,
            preset: String,
        ) = Intent(context, FullScreenContextReviewActivity::class.java).apply {
            putExtra(EXTRA_REQUEST_ID, requestId)
            putStringArrayListExtra(EXTRA_CONTEXT_TEXTS, ArrayList(turns.map { it.text }))
            putExtra(EXTRA_CONTEXT_SPEAKERS, turns.map(ConversationTurn::fromMe).toBooleanArray())
            putExtra(EXTRA_PRESET, preset)
            putStringArrayListExtra(EXTRA_EXAMPLES, arrayListOf())
            putExtra(EXTRA_INSTRUCTIONS, instructions)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_HISTORY)
        }

        fun intent(context: Context, requestId: String, request: PreparedRemoteReplyRequest) =
            Intent(context, FullScreenContextReviewActivity::class.java).apply {
                putExtra(EXTRA_REQUEST_ID, requestId)
                putStringArrayListExtra(EXTRA_CONTEXT_TEXTS, ArrayList(request.context.map { it.text }))
                putExtra(EXTRA_CONTEXT_SPEAKERS, request.context.map { it.speaker == SharedSpeaker.ME }.toBooleanArray())
                putExtra(EXTRA_PRESET, request.style.preset)
                putExtra(EXTRA_SUMMARY, request.style.summary)
                putStringArrayListExtra(EXTRA_EXAMPLES, ArrayList(request.style.examples))
                putExtra(EXTRA_INSTRUCTIONS, request.instructions)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_HISTORY)
            }

    }

    private val isDarkMode: Boolean get() =
        resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    private val PAPER: Int get() = if (isDarkMode) 0xFF17151E.toInt() else 0xFFFAF8F5.toInt()
    private val CARD: Int get() = if (isDarkMode) 0xFF25212F.toInt() else Color.WHITE
    private val INK: Int get() = if (isDarkMode) 0xFFF4F1FA.toInt() else 0xFF27243A.toInt()
    private val MUTED: Int get() = if (isDarkMode) 0xFFB8B1C6.toInt() else 0xFF6E6A80.toInt()
    private val ACCENT: Int get() = if (isDarkMode) 0xFFAB9BFF.toInt() else 0xFF6654D1.toInt()
    private val ON_ACCENT: Int get() = if (isDarkMode) 0xFF21183E.toInt() else Color.WHITE
    private val ACCENT_SOFT: Int get() = if (isDarkMode) 0xFF383050.toInt() else 0xFFEEEAFE.toInt()
    private val ACCENT_PRESSED: Int get() = if (isDarkMode) 0xFF8C79EE.toInt() else 0xFF4C3BA8.toInt()
    private val LINE_SOFT: Int get() = if (isDarkMode) 0xFF453E52.toInt() else 0xFFE7E2EE.toInt()
    private val LINE_STRONG: Int get() = if (isDarkMode) 0xFF655A73.toInt() else 0xFFD7CEE5.toInt()
}
