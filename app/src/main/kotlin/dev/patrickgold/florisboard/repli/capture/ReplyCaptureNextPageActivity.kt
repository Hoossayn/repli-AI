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

package dev.patrickgold.florisboard.repli.capture

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/** Notification actions open an Activity so Android closes the shade before the next frame. */
class ReplyCaptureNextPageActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val requestId = intent.getStringExtra(ReplyScreenCaptureService.EXTRA_REQUEST_ID).orEmpty()
        if (requestId.isNotBlank() && ReplyCaptureSession.state.value?.id == requestId) {
            startService(ReplyScreenCaptureService.nextViewIntent(this, requestId))
        }
        finish()
    }
}
