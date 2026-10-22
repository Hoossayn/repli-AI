/*
 * Copyright (C) 2026 The Repli Contributors
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

package dev.patrickgold.florisboard.ime.nlp.latin.repli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GlideEditingTest {
    @Test fun `consecutive words gain a single separator without a trailing space`() {
        assertEquals("hello", GlideEditing.insertion("", "", "hello"))
        assertEquals(" world", GlideEditing.insertion("hello", "", "world"))
        assertEquals("world", GlideEditing.insertion("hello ", "", "world"))
        assertEquals("hello", GlideEditing.insertion("say ", "", "hello"))
        assertEquals(" world", GlideEditing.insertion("hello,", "", "world"))
    }
    @Test fun `opening delimiters keep the word adjacent`() {
        assertEquals("hello", GlideEditing.insertion("(", ")", "hello"))
        assertEquals("hello", GlideEditing.insertion("\"", "\"", "hello"))
    }
    @Test fun `word interiors and address tokens are not overwritten`() {
        assertNull(GlideEditing.insertion("he", "llo", "world"))
        assertNull(GlideEditing.insertion("user@", "", "world"))
        assertNull(GlideEditing.insertion("https://", "", "world"))
        assertNull(GlideEditing.insertion("www.", "", "world"))
    }
    @Test fun `unusable decoder output is rejected`() {
        for (word in listOf("", "x", " ", "hello world", "\nhello", "a".repeat(33))) {
            assertNull(GlideEditing.insertion("", "", word))
        }
    }
}
