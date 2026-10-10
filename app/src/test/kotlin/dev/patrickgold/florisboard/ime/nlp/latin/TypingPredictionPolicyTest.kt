package dev.patrickgold.florisboard.ime.nlp.latin

import android.text.InputType
import dev.patrickgold.florisboard.ime.editor.InputAttributes
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TypingPredictionPolicyTest {
    @Test
    fun `chat fields can show suggestions without host autocorrect`() {
        assertTrue(TypingPredictionPolicy.allows(InputAttributes.wrap(
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE,
        )))
        assertTrue(TypingPredictionPolicy.allows(InputAttributes.wrap(
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS,
        )))
    }

    @Test
    fun `auto-complete composers get autocorrect, name and search fields only suggestions`() {
        // Tinder's composer: text/normal+autocomplete. Chat apps use the flag for mentions.
        val autoComplete = InputAttributes.wrap(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_AUTO_COMPLETE)
        assertTrue(TypingPredictionPolicy.allows(autoComplete))
        assertTrue(TypingPredictionPolicy.allowsAutocorrect(autoComplete))
        val search = InputAttributes.wrap(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_FILTER)
        assertTrue(TypingPredictionPolicy.allows(search))
        assertFalse(TypingPredictionPolicy.allowsAutocorrect(search))
        val name = InputAttributes.wrap(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PERSON_NAME)
        assertTrue(TypingPredictionPolicy.allows(name))
        assertFalse(TypingPredictionPolicy.allowsAutocorrect(name))
        val chat = InputAttributes.wrap(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE or InputType.TYPE_TEXT_FLAG_AUTO_CORRECT)
        assertTrue(TypingPredictionPolicy.allowsAutocorrect(chat))
        assertTrue(TypingPredictionPolicy.describe(chat).startsWith("text/short_message+autocorrect · suggestions on · autocorrect on"))
        assertTrue(TypingPredictionPolicy.describe(InputAttributes.wrap(InputType.TYPE_CLASS_NUMBER)).contains("suggestions off (type number)"))
    }

    @Test
    fun `special fields do not show prose predictions`() {
        assertFalse(TypingPredictionPolicy.allows(InputAttributes.wrap(
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
        )))
        assertFalse(TypingPredictionPolicy.allows(InputAttributes.wrap(
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
        )))
        assertFalse(TypingPredictionPolicy.allows(InputAttributes.wrap(InputType.TYPE_CLASS_NUMBER)))
    }
}
