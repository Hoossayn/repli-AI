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

package dev.patrickgold.florisboard.repli.review

import dev.patrickgold.florisboard.repli.capture.ReplyEditor
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FullScreenContextReviewSessionTest {
    private val requestId = "review-request"
    private val original = ReplyEditor("chat.app", 42, "composer")
    private val reviewEditor = ReplyEditor("repli.app", 0, "correction")

    @AfterTest fun clear() = FullScreenContextReviewSession.end(requestId)

    @Test fun `review editor never replaces the host chat editor on return`() {
        FullScreenContextReviewSession.begin(requestId)
        assertFalse(FullScreenContextReviewSession.acceptsEditor(requestId, original, reviewEditor, "repli.app"))
        FullScreenContextReviewSession.markReturning(requestId)
        assertFalse(FullScreenContextReviewSession.acceptsEditor(requestId, original, reviewEditor, "repli.app"))
        assertTrue(FullScreenContextReviewSession.acceptsEditor(
            requestId, original, original.copy(fieldId = 43), "repli.app"))
    }

    @Test fun `another chat cannot claim a review return`() {
        FullScreenContextReviewSession.begin(requestId)
        FullScreenContextReviewSession.markReturning(requestId)
        assertFalse(FullScreenContextReviewSession.acceptsEditor(
            requestId, original, ReplyEditor("other.app", 42, "composer"), "repli.app"))
    }
}
