/*
 * Copyright (C) 2026 The FlorisBoard Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.patrickgold.florisboard.ime.keyboard3

import android.content.Context
import android.icu.text.BreakIterator
import android.view.KeyEvent
import android.view.inputmethod.InputConnection
import androidx.compose.runtime.staticCompositionLocalOf
import dev.patrickgold.florisboard.FlorisImeService
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.ime.ImeUiMode
import dev.patrickgold.florisboard.ime.editor.FlorisEditorInfo
import dev.patrickgold.florisboard.ime.editor.ImeOptions
import dev.patrickgold.florisboard.ime.editor.InputAttributes
import dev.patrickgold.florisboard.ime.input.InputShiftState
import dev.patrickgold.florisboard.ime.keyboard.IncognitoMode
import dev.patrickgold.florisboard.ime.keyboard3.touch.TouchModelCache
import dev.patrickgold.florisboard.ime.media.emoji.EmojiSuggestionType
import dev.patrickgold.florisboard.ime.nlp.BreakIterators
import dev.patrickgold.florisboard.ime.nlp.latin.TypingPredictionPolicy
import dev.patrickgold.florisboard.ime.nlp.latin.repli.RepliInputAssistant
import dev.patrickgold.florisboard.ime.nlp.latin.repli.TypingContext
import dev.patrickgold.florisboard.ime.nlp.latin.repli.WordPrediction
import dev.patrickgold.florisboard.ime.nlp.latin.repli.WordPredictionEngine
import dev.patrickgold.florisboard.repli.ime.RepliReplyOrchestrator
import dev.patrickgold.florisboard.ime.text.key.KeyVariation
import dev.patrickgold.florisboard.lib.FlorisLocale
import dev.patrickgold.florisboard.lib.devtools.flogDebug
import dev.patrickgold.florisboard.lib.devtools.flogError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.florisboard.lib.kotlin.collectIn
import org.k3lp.lib.text.K3Descriptor
import org.k3lp.lib.text.K3String
import org.k3lp.lib.text.asK3String
import org.k3lp.model.K3Model
import org.k3lp.model.key.K3Key
import org.k3lp.model.layer.K3LayerId
import org.k3lp.runtime.K3Content
import org.k3lp.runtime.K3InputMethod
import org.k3lp.runtime.K3SurroundingText
import org.k3lp.runtime.K3TextRange
import java.lang.ref.WeakReference

/**
 * Provides the [ImeController] instance this composition tree is associated with.
 */
val LocalImeController = staticCompositionLocalOf<ImeController> {
    error("No IME controller is associated with this composition tree.")
}

class ImeController(
    initialState: ImeState = ImeState(),
    val touchModelCache: TouchModelCache = TouchModelCache(),
    context: Context? = null,
) : K3InputMethod<ImeState, ImeEditor, ImeController.UpdateImeStateScope>(
    initialState = initialState,
) {
    private val prefs by FlorisPreferenceStore
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val breakIterators = BreakIterators()
    private val expectedContentQueue = ExpectedContentQueue()
    private val emojiCommitGuard = Mutex()
    private val _repliSuggestions = MutableStateFlow<List<WordPrediction>>(emptyList())
    val repliSuggestions = _repliSuggestions.asStateFlow()
    val repliAssistant = context?.let { RepliInputAssistant(it) { refreshRepliSuggestions() } }
    val repliReply = context?.let { ctx ->
        val app = ctx.applicationContext
        RepliReplyOrchestrator(
            appContext = app,
            insertText = { commitRepliReply(it) },
            hideKeyboard = { FlorisImeService.hideUi() },
            showKeyboard = { FlorisImeService.showUi() },
            startActivity = { app.startActivity(it) },
            showTypingPanel = {
                scope.launch(Dispatchers.Main) {
                    updateState {
                        state = state.copy(flags = state.flags.withImeUiMode(ImeUiMode.TEXT))
                    }
                }
            },
            showRepliesPanel = {
                scope.launch(Dispatchers.Main) {
                    updateState {
                        state = state.copy(flags = state.flags.withImeUiMode(ImeUiMode.REPLI))
                    }
                }
            },
        )
    }

    fun refreshRepliSuggestions() = refreshRepliSuggestions(activeState.value)

    private fun refreshRepliSuggestions(current: ImeState) {
        val assistant = repliAssistant
        _repliSuggestions.value = if (assistant != null && isRepliAllowed(current)) {
            assistant.suggest(current.typingContext())
        } else emptyList()
    }

    private fun isRepliAllowed(current: ImeState): Boolean =
        current.editor !== ImeEditor.Disconnected &&
            current.model.locales.firstOrNull()?.startsWith("en") == true &&
            !current.flags.isIncognitoMode && prefs.suggestion.enabled.get() &&
            TypingPredictionPolicy.allows(current.editor.info) &&
            current.content.selection.isCollapsed()

    private fun ImeState.typingContext(): TypingContext = TypingContext(
        before = content.surroundingText.textBefore.takeLast(WordPredictionEngine.BEFORE_LIMIT),
        after = content.surroundingText.textAfter.take(WordPredictionEngine.AFTER_LIMIT),
        selectionStart = content.selection.start,
        selectionEnd = content.selection.end,
    )

    fun commitRepliSuggestion(prediction: WordPrediction) {
        if (repliReply?.isEditingInlineGuidance() == true) return
        updateStateBlocking {
            if (!isRepliAllowed(state) || repliAssistant?.suggest(state.typingContext())?.contains(prediction) != true) return@updateStateBlocking
            val before = state.content.surroundingText.textBefore
            val previous = before.dropLast(prediction.removeBefore)
            replaceRepliWord(prediction)
            repliAssistant.learn(previous, prediction.word)
            refreshRepliSuggestions(state)
        }
    }

    fun commitRepliReply(text: String) {
        if (text.isEmpty()) return
        updateStateBlocking {
            val conn = state.editor.ic.get()
            if (conn == null) {
                flogError { "RepliReply: commitRepliReply dropped, no input connection" }
                return@updateStateBlocking
            }
            val selection = state.content.selection
            flogDebug { "RepliReply: commitRepliReply sel=${selection.start}..${selection.end}" }
            if (selection.start < 0 || selection.end < selection.start) return@updateStateBlocking
            conn.beginBatchEdit()
            conn.commitText(text, 1)
            conn.endBatchEdit()
            val cursor = selection.start + text.length
            val cursorRange = K3TextRange(cursor, cursor)
            resetContent(cursorRange, state.editor.getSurroundingText(WordPredictionEngine.BEFORE_LIMIT, WordPredictionEngine.AFTER_LIMIT))
            expectedContentQueue.push(state.content)
            refreshRepliSuggestions(state)
        }
    }

    /** Inserts emoji without running the text transform while the editor may call back into the IME. */
    suspend fun commitEmoji(text: String) {
        if (text.isEmpty()) return
        emojiCommitGuard.withLock {
            if (repliReply?.isEditingInlineGuidance() == true) {
                repliReply.typeGuidance(text)
                return@withLock
            }
            val previous = snapshotState()
            val selection = previous.content.selection
            val connection = previous.editor.ic.get() ?: return@withLock
            if (selection.start < 0 || selection.end < selection.start) return@withLock

            // InputConnection calls can synchronously report a new cursor position. Never hold
            // K3InputMethod's state mutex across them: that callback uses updateStateBlocking.
            connection.finishComposingText()
            if (!connection.commitText(text, 1)) return@withLock

            val cursor = selection.start + text.length
            updateState {
                if (state.editor !== previous.editor) return@updateState
                resetContent(
                    K3TextRange(cursor, cursor),
                    state.editor.getSurroundingText(WordPredictionEngine.BEFORE_LIMIT, WordPredictionEngine.AFTER_LIMIT),
                )
                expectedContentQueue.push(state.content)
                refreshRepliSuggestions(state)
            }
        }
    }

    init {
        combine(
            prefs.devtools.enabled.asFlow(),
            prefs.devtools.showDragAndDropHelpers.asFlow(),
        ) { devtoolsEnabled, showDragAndDropHelpers ->
            devtoolsEnabled && showDragAndDropHelpers
        }.collectIn(scope) { showDragAndDropHelpers ->
            updateState {
                state = state.copy(
                    flags = state.flags
                        .withDebugShowDragAndDropHelpers(showDragAndDropHelpers),
                )
            }
        }
    }

    fun onHardwareKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (event == null) {
            return false
        }
        return when (keyCode) {
            KeyEvent.KEYCODE_DEL -> true
            KeyEvent.KEYCODE_FORWARD_DEL -> true
            else -> false
        }
    }

    fun onHardwareKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (event == null) {
            return false
        }
        return when (keyCode) {
            KeyEvent.KEYCODE_DEL -> {
                updateStateBlocking {
                    emitBackspace()
                }
                true
            }
            KeyEvent.KEYCODE_FORWARD_DEL -> {
                updateStateBlocking {
                    emitForwardDelete()
                }
                true
            }
            else -> false
        }
    }

    fun snapshotState(): ImeState = activeState.value

    // TODO evaluate if we can move to a clean coroutine-based approach in FlorisBoard
    //  for now this helper can be used to update the state from non-suspending contexts
    inline fun updateStateBlocking(crossinline function: UpdateImeStateScope.() -> Unit) {
        runBlocking {
            updateState(function)
        }
    }

    override fun updateStateScopeOf(state: ImeState): UpdateImeStateScope {
        return UpdateImeStateScope(state)
    }

    inner class UpdateImeStateScope(
        state: ImeState,
    ) : UpdateStateScope<ImeState, ImeEditor>(state) {
        fun handleStartInputView(
            ic: WeakReference<InputConnection>,
            info: FlorisEditorInfo,
        ) {
            val touchLayerId: K3LayerId
            val keyVariation: KeyVariation
            when (info.inputAttributes.type) {
                InputAttributes.Type.NUMBER -> {
                    keyVariation = KeyVariation.NORMAL
                    touchLayerId = ImeLayerIds.Numpad
                }
                InputAttributes.Type.PHONE -> {
                    keyVariation = KeyVariation.NORMAL
                    touchLayerId = ImeLayerIds.Telpad
                }
                InputAttributes.Type.TEXT -> {
                    keyVariation = when (info.inputAttributes.variation) {
                        InputAttributes.Variation.EMAIL_ADDRESS,
                        InputAttributes.Variation.WEB_EMAIL_ADDRESS,
                            -> {
                            KeyVariation.EMAIL_ADDRESS
                        }
                        InputAttributes.Variation.PASSWORD,
                        InputAttributes.Variation.VISIBLE_PASSWORD,
                        InputAttributes.Variation.WEB_PASSWORD,
                            -> {
                            KeyVariation.PASSWORD
                        }
                        InputAttributes.Variation.URI -> {
                            KeyVariation.URI
                        }
                        else -> {
                            KeyVariation.NORMAL
                        }
                    }
                    touchLayerId = ImeLayerIds.Base
                }
                else -> {
                    keyVariation = KeyVariation.NORMAL
                    touchLayerId = ImeLayerIds.Base
                }
            }
            val initialSelection = info.initialSelection2
            val initialSurrounding = K3SurroundingText(
                textBefore = info.getInitialTextBeforeCursor(20)?.toString() ?: "",
                textSelected = info.getInitialSelectedText()?.toString() ?: "",
                textAfter = info.getInitialTextAfterCursor(20)?.toString() ?: "",
            )

            state = state.copy(
                editor = ImeEditor(ic, info),
                touchLayerId = touchLayerId,
                flags = state.flags
                    .withKeyVariation(keyVariation)
                    .withImeUiMode(
                        if (repliReply?.onStartInput(info) == true) {
                            ImeUiMode.REPLI
                        } else if (state.flags.imeUiMode != ImeUiMode.CLIPBOARD || prefs.clipboard.historyHideOnNextTextField.get()) {
                            ImeUiMode.TEXT
                        } else {
                            state.flags.imeUiMode
                        }
                    )
                    .withActionsOverflowVisible(false)
                    .withActionsEditorVisible(false)
                    .withInputShiftState(
                        if (prefs.correction.rememberCapsLockState.get()) {
                            state.flags.inputShiftState
                        } else {
                            InputShiftState.UNSHIFTED
                        }
                    )
                    .withComposingEnabled(
                        when (touchLayerId) {
                            ImeLayerIds.Numpad, ImeLayerIds.Telpad -> false
                            else -> keyVariation != KeyVariation.PASSWORD &&
                                prefs.suggestion.enabled.get()// &&
                            //!instance.inputAttributes.flagTextAutoComplete &&
                            //!instance.inputAttributes.flagTextNoSuggestions
                        }
                    )
                    .withIncognitoMode(
                        when (prefs.suggestion.incognitoMode.get()) {
                            IncognitoMode.FORCE_OFF -> false
                            IncognitoMode.FORCE_ON -> true
                            IncognitoMode.DYNAMIC_ON_OFF -> {
                                info.imeOptions.flagNoPersonalizedLearning ||
                                    prefs.suggestion.forceIncognitoModeFromDynamic.get()
                            }
                        }
                    )
            )
            resetContent(initialSelection, initialSurrounding)
            expectedContentQueue.clear()
            refreshRepliSuggestions(state)
        }

        fun handleUpdateSelection(newSelection: K3TextRange) {
            val content = expectedContentQueue.popUntilOrNull { it.selection == newSelection }
            if (content != null) {
                flogDebug { "DEDUPLICATED!!1" }
                return
            }
            resetContent(newSelection, state.editor.getSurroundingText(50, 10))
            expectedContentQueue.push(state.content)
            refreshRepliSuggestions(state)
        }

        override fun emitText(value: K3String) {
            val committedText = value.toText()
            if (repliReply?.isEditingInlineGuidance() == true) {
                repliReply.typeGuidance(committedText)
                return
            }
            val before = state.content.surroundingText.textBefore
            val after = state.content.surroundingText.textAfter
            val word = before.takeLastWhile { it.isLetter() || it == '\'' || it == '’' }
            val canLearn = word.isNotEmpty() && committedText.firstOrNull()?.let { !it.isLetter() && it != '\'' && it != '’' } == true &&
                after.firstOrNull()?.let { it.isLetter() || it == '\'' || it == '’' } != true &&
                !before.takeLastWhile { !it.isWhitespace() }.let { it.contains('@') || it.contains("://") || it.startsWith("www.", true) } &&
                isRepliAllowed(state)
            var learnedWord = word
            if (canLearn && committedText == " ") {
                val correction = repliAssistant?.autocorrection(state.typingContext())
                    ?.takeIf { it.removeBefore == word.length && it.removeAfter == 0 }
                if (correction != null) {
                    replaceRepliWord(correction)
                    learnedWord = correction.word
                }
            }
            super.emitText(value)
            expectedContentQueue.push(state.content)
            if (canLearn) repliAssistant?.learn(before.dropLast(word.length), learnedWord)
            refreshRepliSuggestions(state)
        }

        fun replaceRepliWord(prediction: WordPrediction) {
            val start = state.content.selection.start - prediction.removeBefore
            val end = state.content.selection.end + prediction.removeAfter
            val replacement = prediction.replacement
            val selection = K3TextRange(start + replacement.length, start + replacement.length)
            state.editor.replaceText(start until end, replacement, selection, null)
            resetContent(selection, state.editor.getSurroundingText(WordPredictionEngine.BEFORE_LIMIT, WordPredictionEngine.AFTER_LIMIT))
            expectedContentQueue.push(state.content)
        }

        override fun emitDescriptor(descriptor: K3Descriptor) {
            if (repliReply?.isEditingInlineGuidance() == true && descriptor in setOf(
                    ImeActions.ArrowDown, ImeActions.ArrowLeft, ImeActions.ArrowRight,
                    ImeActions.ArrowUp, ImeActions.Delete,
                )) return
            val windowController = FlorisImeService.windowControllerOrNull()
            when (descriptor) {
                // TODO evaluate use of modern cursor anchor API instead of sending raw key events
                ImeActions.ArrowDown -> state.editor.sendDownUpKeyEvent(KeyEvent.KEYCODE_DPAD_DOWN)
                ImeActions.ArrowLeft -> state.editor.sendDownUpKeyEvent(KeyEvent.KEYCODE_DPAD_LEFT)
                ImeActions.ArrowRight -> state.editor.sendDownUpKeyEvent(KeyEvent.KEYCODE_DPAD_RIGHT)
                ImeActions.ArrowUp -> state.editor.sendDownUpKeyEvent(KeyEvent.KEYCODE_DPAD_UP)
                ImeActions.Delete -> emitForwardDelete()
                ImeActions.Settings -> FlorisImeService.launchSettings()
                ImeActions.ShowTextPanel -> {
                    state = state.copy(
                        flags = state.flags
                            .withImeUiMode(ImeUiMode.TEXT),
                    )
                }
                ImeActions.ShowMediaPanel -> {
                    state = state.copy(
                        flags = state.flags
                            .withImeUiMode(ImeUiMode.MEDIA),
                    )
                }
                ImeActions.ShowClipboardPanel -> {
                    state = state.copy(
                        flags = state.flags
                            .withImeUiMode(ImeUiMode.CLIPBOARD),
                    )
                }
                ImeActions.SuggestReplies -> {
                    state = state.copy(
                        flags = state.flags
                            .withImeUiMode(ImeUiMode.REPLI),
                    )
                    repliReply?.beginSuggestion()
                }
                ImeActions.ShowImeWindow -> FlorisImeService.showUi()
                ImeActions.HideImeWindow -> FlorisImeService.hideUi()
                ImeActions.ToggleActionsEditor -> {
                    state = state.copy(
                        flags = state.flags
                            .withActionsEditorVisible(!state.flags.isActionsEditorVisible),
                    )
                }
                ImeActions.ToggleActionsOverflow -> {
                    state = state.copy(
                        flags = state.flags
                            .withActionsOverflowVisible(!state.flags.isActionsOverflowVisible),
                    )
                }
                ImeActions.ToggleCompactLayout -> windowController?.actions?.toggleCompactLayout()
                ImeActions.ToggleFloatingWindow -> windowController?.actions?.toggleFloatingWindow()
                ImeActions.ToggleResizeMode -> windowController?.editor?.toggleEnabled()
                ImeActions.CompactLayoutToLeft -> windowController?.actions?.compactLayoutToLeft()
                ImeActions.CompactLayoutToRight -> windowController?.actions?.compactLayoutToRight()
                ImeActions.ExternalVoiceInput -> FlorisImeService.switchToVoiceInputMethod()
                else -> super.emitDescriptor(descriptor)
            }
        }

        override fun emitBackspace() {
            if (repliReply?.isEditingInlineGuidance() == true) {
                repliReply.deleteGuidance()
                return
            }
            super.emitBackspace()
            expectedContentQueue.push(state.content)
            refreshRepliSuggestions(state)
        }

        override fun emitEnter() {
            if (repliReply?.isEditingInlineGuidance() == true) {
                repliReply.typeGuidance("\n")
                return
            }
            val info = state.editor.info
            val isShiftPressed = false // TODO inputEventDispatcher.isPressed(KeyCode.SHIFT)
            if (info.imeOptions.flagNoEnterAction || info.inputAttributes.flagTextMultiLine && isShiftPressed) {
                emitEnterKey()
            } else {
                when (val action = info.imeOptions.action) {
                    ImeOptions.Action.UNSPECIFIED,
                    ImeOptions.Action.DONE,
                    ImeOptions.Action.GO,
                    ImeOptions.Action.NEXT,
                    ImeOptions.Action.PREVIOUS,
                    ImeOptions.Action.SEARCH,
                    ImeOptions.Action.SEND -> emitEnterAction(action)
                    else -> emitEnterKey()
                }
            }
        }

        fun emitEnterKey() {
            val info = state.editor.info
            if (info.isRawInputEditor) {
                state.editor.sendDownUpKeyEvent(KeyEvent.KEYCODE_ENTER)
            } else {
                emitText(NEWLINE_SEQ)
            }
        }

        fun emitEnterAction(action: ImeOptions.Action) {
            state.editor.performEditorAction(action)
        }

        fun emitForwardDelete() {
            if (repliReply?.isEditingInlineGuidance() == true) return
            // TODO request additional text if too low on context length
            if (state.content.selection.isNotCollapsed()) {
                emitBackspace()
            } else {
                val newSurroundingText = state.content.surroundingText.copy(
                    textAfter = state.content.surroundingText.textAfter.let { text ->
                        // TODO unicode
                        if (text.isEmpty()) text else text.substring(1)
                    },
                )
                state = state.copy(
                    content = state.content.copy(
                        surroundingText = newSurroundingText,
                    ),
                )
                state.editor.deleteSurroundingText(
                    charsBefore = 0,
                    charsAfter = 1, // TODO
                )
            }
        }

        fun handleFinishInputView() {
            resetContent()
            state = state.copy(editor = ImeEditor.Disconnected)
            expectedContentQueue.clear()
            refreshRepliSuggestions(state)
        }

        override fun evaluateCompositionOf(
            model: K3Model,
            selection: K3TextRange,
            surroundingText: K3SurroundingText
        ): K3TextRange? {
            if (selection.isNotCollapsed()) {
                return null
            }
            // TODO rework how we get the primary locale
            val locale = FlorisLocale.fromTag(model.locales.getOrElse(0) { "" })
            return breakIterators.word(locale) {
                it.setText(surroundingText.textBefore)
                val end = it.last()
                val isWord = it.ruleStatus != BreakIterator.WORD_NONE
                if (isWord) {
                    val start = it.previous().let { pos ->
                        // Include Emoji indicator in local composing. This is required so that emoji suggestion indicator
                        // can be detected in the composing text.
                        (pos - 1).takeIf { updatedPos ->
                            surroundingText.textBefore.getOrNull(updatedPos) == EmojiSuggestionType.LEADING_COLON.prefix.first()
                        } ?: pos
                    }
                    val offset = (selection.min - surroundingText.textBefore.length).coerceAtLeast(0)
                    K3TextRange(start + offset, end + offset)
                } else {
                    null
                }
            }
        }

        private fun K3Key.isShiftKey(): Boolean {
            return when (state.touchLayerId) {
                ImeLayerIds.Base -> layerId == ImeLayerIds.Shift || layerId == ImeLayerIds.Caps
                ImeLayerIds.Shift -> layerId == ImeLayerIds.Base || layerId == ImeLayerIds.Caps
                ImeLayerIds.Caps -> layerId == ImeLayerIds.Base || layerId == ImeLayerIds.Shift
                else -> false
            }
        }
    }

    companion object {
        private val NEWLINE_SEQ = "\n".asK3String()
    }
}

private class ExpectedContentQueue {
    private val list = mutableListOf<K3Content>()

    fun popUntilOrNull(predicate: (K3Content) -> Boolean): K3Content? {
        while (list.isNotEmpty()) {
            val item = list[0]
            if (predicate(item)) return list.removeAt(0)
            list.removeAt(0)
        }
        return null
    }

    fun push(item: K3Content) {
        list.add(item)
    }

    fun clear() {
        list.clear()
    }
}
