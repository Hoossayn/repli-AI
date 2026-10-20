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

package dev.patrickgold.florisboard.ime.nlp.latin.repli

import android.content.Context

class KeyboardLearningPreferences(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = preferences.getBoolean(KEY_ENABLED, true)
        set(value) { preferences.edit().putBoolean(KEY_ENABLED, value).apply() }

    val revision: Int get() = preferences.getInt(KEY_REVISION, 0)

    fun markCleared() {
        preferences.edit().putInt(KEY_REVISION, revision + 1).apply()
    }

    private companion object {
        const val PREFERENCES = "keyboard_learning"
        const val KEY_ENABLED = "enabled"
        const val KEY_REVISION = "revision"
    }
}
