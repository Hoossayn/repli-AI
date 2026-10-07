package dev.patrickgold.florisboard.repli.identity

import dev.patrickgold.florisboard.repli.profile.RecentMessage
import dev.patrickgold.florisboard.repli.profile.VoiceProfile

data class SavedChatAutoSuggestion(val profile: VoiceProfile, val message: RecentMessage) {
    val key: String get() = "${profile.id}:${message.notificationKey}:${message.receivedAt}"
}

/** Only a just-opened notification can identify a chat without reading another app's screen. */
object SavedChatAutoSuggestionPolicy {
    private const val OPEN_WINDOW_MS = 15_000L

    fun select(
        resolution: ConversationIdentityResolution,
        profiles: List<VoiceProfile>,
        recentMessages: List<RecentMessage>,
        composerEmpty: Boolean,
        now: Long = System.currentTimeMillis(),
    ): SavedChatAutoSuggestion? {
        if (!composerEmpty) return null
        val opened = resolution as? ConversationIdentityResolution.Suggestion ?: return null
        if (opened.confidence != IdentityConfidence.OPENED_NOTIFICATION) return null
        val message = opened.message
        val openedAt = message.openedAt ?: return null
        if (now - openedAt !in 0..OPEN_WINDOW_MS || message.notificationKey == null) return null
        val senderKey = CapturedContactName.identityKey(message.sender)
        val matches = profiles.filter { profile ->
            (listOf(profile.name) + profile.nameAliases).any {
                CapturedContactName.identityKey(it) == senderKey
            }
        }
        if (matches.size != 1) return null
        val sameConversation = message.conversationId?.takeIf(String::isNotBlank)
        if (recentMessages.any { other ->
                other.sourcePackage == message.sourcePackage &&
                    other.receivedAt > message.receivedAt &&
                    (if (sameConversation != null) other.conversationId == sameConversation
                    else CapturedContactName.identityKey(other.sender) == senderKey)
            }) return null
        return SavedChatAutoSuggestion(matches.single(), message)
    }
}
