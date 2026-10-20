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

package dev.patrickgold.florisboard.repli.notifications

import android.app.Notification
import android.app.Person
import android.os.Bundle
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import dev.patrickgold.florisboard.repli.data.RecentMessageRepository
import dev.patrickgold.florisboard.repli.profile.RecentMessage

class RecentMessageNotificationService : NotificationListenerService() {
    private val supportedPackages = setOf(
        "com.whatsapp",
        "com.whatsapp.w4b",
        "com.instagram.android",
        "com.twitter.android",
        "org.telegram.messenger",
        "org.telegram.messenger.web",
        "org.thunderdog.challegram",
        "com.facebook.orca",
        "org.thoughtcrime.securesms",
        "com.discord",
        "com.google.android.apps.messaging",
        "com.samsung.android.messaging",
    )

    override fun onNotificationPosted(notification: StatusBarNotification) {
        if (notification.packageName !in supportedPackages) return
        val content = extractContent(notification.notification) ?: return
        RecentMessageRepository(applicationContext).save(
            RecentMessage(
                sourcePackage = notification.packageName,
                sender = content.first,
                text = content.second,
                receivedAt = notification.postTime,
                notificationKey = notification.key,
                conversationId = notification.notification.shortcutId,
                isGroupConversation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    notification.notification.extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false)
                } else {
                    false
                },
            ),
        )
    }

    override fun onNotificationRemoved(
        notification: StatusBarNotification,
        rankingMap: RankingMap,
        reason: Int,
    ) {
        if (reason != REASON_CLICK || notification.packageName !in supportedPackages) return
        RecentMessageRepository(applicationContext).markOpened(
            notificationKey = notification.key,
            openedAt = System.currentTimeMillis(),
        )
    }

    private fun extractContent(notification: Notification): Pair<String, String>? {
        val extras = notification.extras
        val fromMessagingStyle = latestMessagingStyleMessage(extras)
        if (fromMessagingStyle != null) return fromMessagingStyle

        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        if (text.isBlank()) return null
        // For one-to-one chats the conversation title equals the contact name,
        // which keeps keyboard profile suggestions accurate even when the
        // notification omits a per-message sender.
        val sender = sequenceOf(
            extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim(),
            extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()?.trim(),
        ).firstOrNull { !it.isNullOrBlank() } ?: return null
        return sender to text
    }

    @Suppress("DEPRECATION")
    private fun latestMessagingStyleMessage(extras: Bundle): Pair<String, String>? {
        val messages = extras.getParcelableArray(Notification.EXTRA_MESSAGES) ?: return null
        for (item in messages.reversed()) {
            val bundle = item as? Bundle ?: continue
            val text = bundle.getCharSequence("text")?.toString()?.trim().orEmpty()
            val personName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                bundle.getParcelable<Person>("sender_person")?.name?.toString()?.trim()
            } else {
                null
            }
            val sender = bundle.getCharSequence("sender")?.toString()?.trim()
                ?: personName
                ?: extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
            if (sender.isNotBlank() && text.isNotBlank()) return sender to text
        }
        return null
    }
}
