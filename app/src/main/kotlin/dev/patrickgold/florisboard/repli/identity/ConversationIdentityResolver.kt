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

package dev.patrickgold.florisboard.repli.identity

import dev.patrickgold.florisboard.repli.profile.ProfileMatcher
import dev.patrickgold.florisboard.repli.profile.RecentMessage
import dev.patrickgold.florisboard.repli.profile.VoiceProfile

enum class IdentityConfidence {
    OPENED_NOTIFICATION,
    RECENT_NOTIFICATION,
}

sealed interface ConversationIdentityResolution {
    data object None : ConversationIdentityResolution

    data class Ambiguous(val conversationCount: Int) : ConversationIdentityResolution

    /** A suggestion is deliberately not trusted context. The user must confirm it
     * before its message can be used for generation or attached to a profile. */
    data class Suggestion(
        val message: RecentMessage,
        val confidence: IdentityConfidence,
    ) : ConversationIdentityResolution
}

@ConsistentCopyVisibility
data class ConfirmedConversationIdentity internal constructor(
    val message: RecentMessage,
    val profileId: String,
)

object ConversationIdentityResolver {
    private val supportedPackages = setOf(
        "com.whatsapp",
        "com.whatsapp.w4b",
        "org.telegram.messenger",
        "org.telegram.messenger.web",
        "org.thunderdog.challegram",
    )

    fun resolve(
        sourcePackage: String?,
        messages: List<RecentMessage>,
        now: Long = System.currentTimeMillis(),
    ): ConversationIdentityResolution {
        val packageName = sourcePackage?.lowercase()?.trim()
            ?.takeIf { it in supportedPackages }
            ?: return ConversationIdentityResolution.None
        val conversations = messages.asSequence()
            .filter { it.sourcePackage.lowercase().trim() == packageName }
            .filterNot(RecentMessage::isGroupConversation)
            .filter { it.sender.isNotBlank() && it.text.isNotBlank() }
            .filter { message ->
                age(now, message.receivedAt) in 0..RECENT_MESSAGE_WINDOW_MS ||
                    message.wasOpenedRecently(now)
            }
            .groupBy(::conversationKey)
            .values
            .mapNotNull { entries ->
                entries.filter { it.wasOpenedRecently(now) }.maxByOrNull { it.openedAt ?: Long.MIN_VALUE }
                    ?: entries.maxByOrNull(RecentMessage::receivedAt)
            }

        if (conversations.isEmpty()) return ConversationIdentityResolution.None

        val opened = conversations.filter { message ->
            message.wasOpenedRecently(now)
        }
        return when {
            opened.size == 1 -> ConversationIdentityResolution.Suggestion(
                opened.single(),
                IdentityConfidence.OPENED_NOTIFICATION,
            )
            opened.size > 1 -> ConversationIdentityResolution.Ambiguous(opened.size)
            conversations.size == 1 -> ConversationIdentityResolution.Suggestion(
                conversations.single(),
                IdentityConfidence.RECENT_NOTIFICATION,
            )
            else -> ConversationIdentityResolution.Ambiguous(conversations.size)
        }
    }

    fun confirm(
        suggestion: ConversationIdentityResolution.Suggestion,
        profile: VoiceProfile,
    ): ConfirmedConversationIdentity? = profile.takeIf {
        ProfileMatcher.matches(suggestion.message.sender, it)
    }?.let {
        ConfirmedConversationIdentity(suggestion.message, it.id)
    }

    /** Confirmation is editor-local, not a permanent exemption from freshness checks. */
    fun trustedIncomingText(
        confirmed: ConfirmedConversationIdentity?,
        selectedProfileId: String?,
        sourcePackage: String?,
        messages: List<RecentMessage>,
        now: Long = System.currentTimeMillis(),
    ): String? {
        if (confirmed == null || confirmed.profileId != selectedProfileId) return null
        val current = resolve(sourcePackage, messages, now) as? ConversationIdentityResolution.Suggestion
            ?: return null
        return confirmed.message.text.takeIf { current.message == confirmed.message }
    }

    private fun conversationKey(message: RecentMessage): String =
        message.conversationId?.trim()?.lowercase()?.takeIf(String::isNotBlank)
            ?: message.sender.trim().lowercase()

    private fun age(now: Long, then: Long): Long = now - then

    private fun RecentMessage.wasOpenedRecently(now: Long): Boolean =
        openedAt?.let { age(now, it) in 0..OPENED_NOTIFICATION_WINDOW_MS } == true

    const val OPENED_NOTIFICATION_WINDOW_MS = 2 * 60 * 1_000L
    const val RECENT_MESSAGE_WINDOW_MS = 5 * 60 * 1_000L
}
