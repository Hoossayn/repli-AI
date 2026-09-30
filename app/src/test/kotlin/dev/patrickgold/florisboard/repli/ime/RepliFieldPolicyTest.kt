package dev.patrickgold.florisboard.repli.ime

import android.text.InputType
import dev.patrickgold.florisboard.ime.editor.InputAttributes
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RepliFieldPolicyTest {
    @Test fun `chat capture remains available when a host disables dictionary suggestions`() {
        val noSuggestions = InputAttributes.wrap(InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
        val autoComplete = InputAttributes.wrap(InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_FLAG_AUTO_COMPLETE)
        assertTrue(RepliFieldPolicy.allows(noSuggestions))
        assertTrue(RepliFieldPolicy.allows(autoComplete))
    }

    @Test fun `chat capture stays unavailable in credentials and non-chat fields`() {
        listOf(
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI,
            InputType.TYPE_CLASS_NUMBER,
        ).forEach { assertFalse(RepliFieldPolicy.allows(InputAttributes.wrap(it))) }
    }
}
