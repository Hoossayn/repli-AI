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

package dev.patrickgold.florisboard.repli.practice

import android.app.Activity
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.WindowCompat

/** Real editable chat surface with clearly labelled sample content. Nothing sends. */
class PracticeChatActivity : Activity() {
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        window.statusBarColor = PAPER
        window.navigationBarColor = PAPER
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !isDarkMode
            isAppearanceLightNavigationBars = !isDarkMode
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(PAPER)
        }
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(10))
            addView(title("Alex · practice chat"))
        })
        val messages = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.BOTTOM
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        fun bubble(text: String, fromMe: Boolean) {
            messages.addView(TextView(this).apply {
                this.text = text
                textSize = 17f
                setTextColor(INK)
                setPadding(dp(14), dp(12), dp(14), dp(12))
                background = rounded(if (fromMe) ACCENT_SOFT else CARD)
                maxWidth = dp(260)
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = if (fromMe) Gravity.END else Gravity.START
                topMargin = dp(10)
            })
        }
        bubble("Hey, how did your presentation go yesterday?", false)
        bubble("Better than I expected. Thanks for checking in!", true)
        bubble("Of course. You put a lot of work into it.", false)
        bubble("The questions were tough, but the team seemed interested.", true)
        bubble("That's great news. Did they say what happens next?", false)
        bubble("They'll send feedback by Friday, so I'm trying not to overthink it.", true)
        bubble("Fair enough. We should celebrate either way.", false)
        bubble("A quiet weekend sounds perfect after all that.", true)
        bubble("Maybe a walk and something good to eat?", false)
        bubble("I'd be up for that. Let's pick a place later.", true)
        bubble("I saw a café near the park that might work.", false)
        bubble("Send me the name when you get a chance.", true)
        bubble("Morning! Did you get a chance to look at the new café?", false)
        bubble("I did. The little place by the park looks great.", true)
        bubble("I heard their pastries are worth trying.", false)
        bubble("That's a strong reason to go.", true)
        bubble("Would Saturday work for you?", false)
        bubble("Saturday might be busy, but I'm free later in the week.", true)
        bubble("No rush. We can find a day that works for both of us.", false)
        bubble("Sounds good! Let me check my schedule tonight.", true)
        bubble("Hey! How is your day going?", true)
        bubble("Pretty good, thanks! Want to grab coffee tomorrow?", false)
        val scroll = ScrollView(this).apply { isFillViewport = true; addView(messages) }
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        val composer = EditText(this).apply {
            id = android.R.id.edit
            hint = "Type here, or try the reply icon"
            textSize = 16f
            setTextColor(INK)
            setHintTextColor(MUTED)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            maxLines = 3
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = rounded(CARD)
            setOnFocusChangeListener { _, focused -> if (focused) scroll.postDelayed({ scroll.fullScroll(View.FOCUS_DOWN) }, 450) }
        }
        root.addView(composer, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            setMargins(dp(12), dp(6), dp(12), dp(6))
        })
        root.setOnApplyWindowInsetsListener { view, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            } else {
                @Suppress("DEPRECATION")
                view.setPadding(0, insets.systemWindowInsetTop, 0, insets.systemWindowInsetBottom.coerceAtMost(dp(48)))
            }
            insets
        }
        setContentView(root)
        root.requestApplyInsets()
        composer.requestFocus()
        composer.postDelayed({
            getSystemService(InputMethodManager::class.java).showSoftInput(composer, InputMethodManager.SHOW_IMPLICIT)
        }, 250)
    }

    private fun title(text: String) = TextView(this).apply {
        this.text = text
        textSize = 20f
        setTextColor(INK)
        setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    private fun subtitle(text: String) = TextView(this).apply {
        this.text = text
        textSize = 13f
        setTextColor(MUTED)
    }

    private fun rounded(color: Int) = android.graphics.drawable.GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(16).toFloat()
    }

    private val isDarkMode: Boolean get() =
        resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    private val PAPER: Int get() = if (isDarkMode) 0xFF17151E.toInt() else 0xFFFAF8F5.toInt()
    private val CARD: Int get() = if (isDarkMode) 0xFF25212F.toInt() else Color.WHITE
    private val INK: Int get() = if (isDarkMode) 0xFFF4F1FA.toInt() else 0xFF27243A.toInt()
    private val MUTED: Int get() = if (isDarkMode) 0xFFB8B1C6.toInt() else 0xFF6E6A80.toInt()
    private val ACCENT_SOFT: Int get() = if (isDarkMode) 0xFF383050.toInt() else 0xFFEEEAFE.toInt()
}
