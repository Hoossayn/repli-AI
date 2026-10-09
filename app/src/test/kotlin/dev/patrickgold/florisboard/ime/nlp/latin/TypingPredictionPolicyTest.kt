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
