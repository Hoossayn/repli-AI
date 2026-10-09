package dev.patrickgold.florisboard.ime.editor

import android.text.InputType
import kotlin.test.Test
import kotlin.test.assertEquals

class InputAttributesCapsTest {
    @Test fun `ordinary text and message fields start sentences with a capital`() {
        listOf(
            InputType.TYPE_CLASS_TEXT,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_LONG_MESSAGE,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS,
        ).forEach { inputType ->
            assertEquals(
                InputAttributes.CapsMode.SENTENCES,
                InputAttributes.CapsMode.fromFlags(InputAttributes.wrap(inputType).cursorCapsFlags),
            )
        }
    }

    @Test fun `host capitalization modes and non prose fields are preserved`() {
        val wordCaps = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
        assertEquals(wordCaps, InputAttributes.wrap(wordCaps).cursorCapsFlags)

        listOf(
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            InputType.TYPE_CLASS_NUMBER,
        ).forEach { inputType ->
            assertEquals(
                InputAttributes.CapsMode.NONE,
                InputAttributes.CapsMode.fromFlags(InputAttributes.wrap(inputType).cursorCapsFlags),
            )
        }
    }
}
