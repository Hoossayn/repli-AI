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

package dev.patrickgold.florisboard.repli.data

import android.content.Context

class AutoScrollPreferences(context: Context) {
    private val preferences = context.applicationContext
        .getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = preferences.getBoolean(KEY_ENABLED, false)
        set(value) { preferences.edit().putBoolean(KEY_ENABLED, value).apply() }

    private companion object {
        const val FILE_NAME = "reply_auto_scroll"
        const val KEY_ENABLED = "enabled"
    }
}
