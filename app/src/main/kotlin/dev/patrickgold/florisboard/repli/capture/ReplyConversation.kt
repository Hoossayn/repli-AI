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

/** Source is review-only evidence; reply generation serializes only text and speaker. */
data class TurnSource(
    val frameIndex: Int,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val uncertain: Boolean = false,
)

data class ConversationTurn(val text: String, val fromMe: Boolean, val source: TurnSource? = null)
data class VisualBubble(val text: String, val fromMe: Boolean, val source: TurnSource)
enum class FrameDirection { OLDER, NEWER }

/** Geometry is a best-effort hint for one-to-one, left/right bubble layouts.
 * The keyboard exposes every inferred speaker and lets the user correct it. */
object ReplyConversation {
    private val timestampOnly = Regex(
        """^\d{1,2}:\d{2}(?:\s?[aApP][mM])?(?:\s*(?:[✓✔☑√]{1,4}|[vVnNyY/\\|!lI]{1,4}))?$""",
    )
    private val receiptOnly = Regex("""^(?:[✓✔☑√]{1,4}|[vVnNyY/\\|!lI]{2,4})$""")
    private val timestampWithReceiptSuffix = Regex(
        """\s+\d{1,2}:\d{2}(?:\s?[aApP][mM])?\s*(?:[✓✔☑√]{1,4}|[vVnNyY/\\|!lI]{1,4})$""",
    )
    private val comparisonTimestampSuffix = Regex(
        """\s+\d{1,2}:\d{2}(?:\s?[aApP][mM])?(?:\s*(?:[✓✔☑√]{1,4}|[vVnNyY/\\|!lI]{1,4}))?$""",
    )
    private val oneCharacterReceipt = Regex("""^[V/\\|]$""")
    private val numericDate = Regex("""^\d{1,4}[/.-]\d{1,2}[/.-]\d{1,4}$""")
    private val namedDate = Regex(
        """^(?:(?:\d{1,2}\s+)?(?:january|february|march|april|may|june|july|august|september|october|november|december)\s+\d{4}|(?:january|february|march|april|may|june|july|august|september|october|november|december)\s+\d{1,2},?\s+\d{4})$""",
        RegexOption.IGNORE_CASE,
    )
    private val ignored = setOf(
        "today", "yesterday", "online", "typing…", "typing...", "seen", "delivered",
        "read", "sent", "message", "message…", "message...", "write a message",
        "type a message", "send", "aa", "new messages", "end-to-end encrypted",
        "scroll to bottom",
    )
    private val captureUiActions = setOf(
        "message", "add contact", "view contact", "create contact", "create new contact",
    )

    fun extract(regions: List<OcrTextRegion>, width: Int, height: Int): List<ConversationTurn> {
        if (width <= 0 || height <= 0) return emptyList()
        val candidates = mutableListOf<Candidate>()
        val metadataGap = (height * METADATA_GAP_FRACTION).toInt().coerceAtLeast(12)

        regions.sortedWith(compareBy(OcrTextRegion::top, OcrTextRegion::left)).forEach { region ->
            val cleaned = clean(region.text) ?: cleanLeftAlignedTimeReply(region, width, candidates)
            if (cleaned == null) {
                // WhatsApp often gives the timestamp and delivery ticks their own OCR block.
                // Attach that block to the preceding message for geometry only: the union of
                // message text and its right-aligned timestamp is a much better bubble-side hint.
                if (isTimestampMetadata(region.text)) {
                    val previous = candidates.lastOrNull()
                    if (previous != null && region.top - previous.bottom <= metadataGap &&
                        region.bottom >= previous.top - metadataGap &&
                        region.left <= previous.right + (width * METADATA_HORIZONTAL_GAP_FRACTION).toInt()
                    ) {
                        candidates[candidates.lastIndex] = previous.copy(
                            left = minOf(previous.left, region.left),
                            right = maxOf(previous.right, region.right),
                            bottom = maxOf(previous.bottom, region.bottom),
                        )
                    }
                }
                return@forEach
            }
            candidates += Candidate(
                text = cleaned,
                left = region.left,
                top = region.top,
                right = region.right,
                bottom = region.bottom,
                fromMe = isFromMe(region.left, region.right, width),
            )
        }

        // Re-evaluate speaker after timestamp geometry has been folded into the candidate.
        val sided = candidates.map { it.copy(fromMe = isFromMe(it.left, it.right, width)) }
        return removeCaptureUiArtifacts(resolveSelfQuotes(sided, width, height)).takeLast(MAX_TURNS)
    }

    private fun clean(raw: String): String? {
        val lines = raw.lines().mapNotNull { source ->
            val line = source.replace(Regex("\\s+"), " ").trim()
            val key = line.lowercase()
            when {
                line.isEmpty() || key in ignored -> null
                timestampOnly.matches(line) || receiptOnly.matches(line) ||
                    numericDate.matches(line) || namedDate.matches(line) -> null
                else -> timestampWithReceiptSuffix.replace(line, "").trim().takeIf(String::isNotEmpty)
            }
        }
        val text = lines.joinToString(" ").replace(Regex("\\s+"), " ").trim().take(2_000)
        return text.takeIf { it.isNotEmpty() && it.lowercase() !in ignored && it.any(Char::isLetter) }
    }

    private fun isTimestampMetadata(raw: String): Boolean {
        val lines = raw.lines().map { it.replace(Regex("\\s+"), " ").trim() }.filter(String::isNotEmpty)
        return lines.isNotEmpty() && lines.all { timestampOnly.matches(it) || receiptOnly.matches(it) }
    }

    /** A reply whose entire body is a time (for example "2:30") looks exactly like WhatsApp's
     * timestamp to OCR. Incoming message text is left-aligned; the bubble timestamp is not. */
    private fun cleanLeftAlignedTimeReply(
        region: OcrTextRegion,
        width: Int,
        preceding: List<Candidate>,
    ): String? {
        val value = region.text.replace(Regex("\\s+"), " ").trim()
        val plainTime = Regex("""^\d{1,2}:\d{2}(?:\s?[aApP][mM])?$""")
        return value.takeIf {
            plainTime.matches(it) &&
                region.left <= width * 0.30 &&
                region.right <= width * 0.55 &&
                preceding.takeLast(MAX_QUOTE_LINES + 1).any { candidate ->
                    candidate.text.removeSuffix(":").equals("you", ignoreCase = true)
                }
        }
    }

    private fun isFromMe(left: Int, right: Int, width: Int): Boolean {
        val leftMargin = left.coerceAtLeast(0)
        val rightMargin = (width - right).coerceAtLeast(0)
        // Comparing the two outer margins remains reliable when a long bubble crosses
        // the center line. Do not add a center dead-zone: it caused wide outgoing
        // WhatsApp bubbles to be labelled as incoming.
        return rightMargin < leftMargin
    }

    /**
     * WhatsApp renders a quoted message as an indented sender label and preview
     * inside a bubble. `You` previews originated from Me; a contact-name preview
     * originated from Them. If that message is already visible, omit the duplicate.
     * A following, less-indented line remains the actual reply in the outer bubble.
     */
    private fun resolveSelfQuotes(candidates: List<Candidate>, width: Int, height: Int): List<ConversationTurn> {
        val turns = mutableListOf<ConversationTurn>()
        val nearbyGap = (height * QUOTE_GAP_FRACTION).toInt().coerceAtLeast(18)
        val indentTolerance = (width * QUOTE_INDENT_FRACTION).toInt().coerceAtLeast(8)
        var index = 0
        while (index < candidates.size) {
            val header = candidates[index]
            val selfHeader = header.text.equals("you", ignoreCase = true) ||
                header.text.equals("you:", ignoreCase = true)
            if (!selfHeader && !looksLikeQuoteSender(header.text)) {
                turns += ConversationTurn(header.text, header.fromMe)
                index++
                continue
            }

            val firstQuote = candidates.getOrNull(index + 1)
            // Short quote headers are often centered enough for OCR geometry to
            // assign them the opposite side from the preview/outer bubble.
            val isQuotedPreview = firstQuote != null &&
                firstQuote.top - header.bottom in -indentTolerance..nearbyGap &&
                firstQuote.left >= header.left - indentTolerance
            if (!isQuotedPreview) {
                turns += ConversationTurn(header.text, header.fromMe)
                index++
                continue
            }
            val preview = requireNotNull(firstQuote)

            val matchedPartCount = matchingVisibleQuotePartCount(
                turns = turns,
                candidates = candidates,
                start = index + 1,
                quotedFromMe = selfHeader,
                nearbyGap = nearbyGap,
                indentTolerance = indentTolerance,
            )
            val quoteParts = if (matchedPartCount != null) {
                candidates.subList(index + 1, index + 1 + matchedPartCount).toMutableList()
            } else {
                mutableListOf(preview)
            }
            val quoteIndent = minOf(header.left, preview.left)
            var cursor = index + 1 + quoteParts.size
            if (matchedPartCount == null) while (cursor < candidates.size) {
                val next = candidates[cursor]
                val previous = quoteParts.last()
                if (next.fromMe != preview.fromMe || next.top - previous.bottom !in -indentTolerance..nearbyGap) break
                if (next.left < quoteIndent - indentTolerance) break
                if (next.text.equals("you", ignoreCase = true) || next.text.equals("you:", ignoreCase = true) ||
                    looksLikeQuoteSender(next.text)) break
                quoteParts += next
                cursor++
            }

            // A contact name by itself can be a real chat message. Only classify it
            // as quote chrome when a less-indented outer-bubble body follows.
            val body = candidates.getOrNull(cursor)
            val hasOuterBody = body != null &&
                body.top - quoteParts.last().bottom in -indentTolerance..nearbyGap &&
                (body.fromMe != preview.fromMe || body.left < quoteIndent - indentTolerance)
            if (!selfHeader && !hasOuterBody) {
                turns += ConversationTurn(header.text, header.fromMe)
                index++
                continue
            }

            val quotedText = quoteParts.joinToString(" ") { it.text }
                .replace(Regex("\\s+"), " ").trim().take(2_000)
            if (quotedText.isNotEmpty() && turns.none { sameText(it.text, quotedText) }) {
                turns += ConversationTurn(quotedText, fromMe = selfHeader)
            }
            // A less-indented nearby candidate is the reply body. Leave it for the
            // normal path so it keeps the containing bubble's speaker.
            index = cursor
        }
        return turns
    }

    /** When a visible message is being replied to, its exact text is the strongest boundary
     * between the quoted preview and the actual reply body—even when both have the same indent. */
    private fun matchingVisibleQuotePartCount(
        turns: List<ConversationTurn>,
        candidates: List<Candidate>,
        start: Int,
        quotedFromMe: Boolean,
        nearbyGap: Int,
        indentTolerance: Int,
    ): Int? {
        if (turns.none { it.fromMe == quotedFromMe }) return null
        val parts = mutableListOf<Candidate>()
        for (cursor in start until minOf(candidates.size, start + MAX_QUOTE_LINES)) {
            val next = candidates[cursor]
            val previous = parts.lastOrNull()
            if (previous != null && (
                    next.fromMe != previous.fromMe ||
                        next.top - previous.bottom !in -indentTolerance..nearbyGap ||
                        next.text.equals("you", ignoreCase = true) ||
                        next.text.equals("you:", ignoreCase = true) ||
                        looksLikeQuoteSender(next.text)
                    )
            ) break
            parts += next
            val joined = parts.joinToString(" ") { it.text }.replace(Regex("\\s+"), " ").trim()
            if (turns.any { it.fromMe == quotedFromMe && sameText(it.text, joined) }) return parts.size
        }
        return null
    }

    private fun sameText(first: String, second: String): Boolean =
        first.lowercase().replace(Regex("\\s+"), " ").trim() ==
            second.lowercase().replace(Regex("\\s+"), " ").trim()

    private fun looksLikeQuoteSender(text: String): Boolean {
        val value = text.removeSuffix(":").trim()
        val words = value.split(Regex("\\s+")).filter(String::isNotEmpty)
        return value.length in 2..60 && words.size in 1..4 && words.all { word ->
            word.firstOrNull()?.isUpperCase() == true && word.all { it.isLetter() || it in "-'’" }
        }
    }

    private data class Candidate(
        val text: String,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
        val fromMe: Boolean,
    )

    /** Only remove overlapping sequences at frame boundaries. Repeated messages
     * inside a conversation (e.g. two separate "okay" replies) remain intact. */
    fun merge(earlier: List<ConversationTurn>, later: List<ConversationTurn>): List<ConversationTurn> {
        val overlap = overlapSize(earlier, later)
        return (earlier + later.drop(overlap)).takeLast(MAX_TURNS)
    }

    /** AI reads each screenshot independently. Only adjacent frame boundaries can overlap;
     * a single short repeated reply is not enough evidence to silently remove a turn. */
    fun mergeVisualBubbles(
        bubbles: List<VisualBubble>,
        frameCount: Int,
    ): List<ConversationTurn> {
        require(frameCount in 1..4)
        require(bubbles.all { it.source.frameIndex in 0 until frameCount })
        val frames = (0 until frameCount).map { frameIndex ->
            removeCaptureUiArtifacts(bubbles.asSequence()
                .filter { it.source.frameIndex == frameIndex }
                .sortedWith(compareBy<VisualBubble> { it.source.top }.thenBy { it.source.left })
                .map(::conversationTurnFromVisualBubble)
                .toList())
        }
        var merged = emptyList<ConversationTurn>()
        for (frameIndex in 0 until frameCount) {
            val current = frames[frameIndex]
            if (current.isEmpty()) continue
            if (merged.isEmpty()) {
                merged = current
                continue
            }
            val overlap = visualOverlapSize(merged, current)
            val next = if (overlap == 0) {
                // No verified shared bubble: retain both pages, but flag the seam for review.
                current.mapIndexed { index, turn ->
                    if (index == 0) turn.copy(source = turn.source?.copy(uncertain = true)) else turn
                }
            } else current.drop(overlap)
            merged = merged + next
        }
        return removeCaptureUiArtifacts(merged).takeLast(MAX_TURNS)
    }

    private fun conversationTurnFromVisualBubble(bubble: VisualBubble): ConversationTurn {
        val outerSide = when {
            bubble.source.left - (1000 - bubble.source.right) >= 100 -> true
            (1000 - bubble.source.right) - bubble.source.left >= 100 -> false
            else -> null
        }
        return ConversationTurn(
            bubble.text,
            outerSide ?: bubble.fromMe,
            bubble.source.copy(uncertain = outerSide != null && outerSide != bubble.fromMe),
        )
    }

    private fun visualOverlapSize(earlier: List<ConversationTurn>, later: List<ConversationTurn>): Int =
        (minOf(12, earlier.size, later.size) downTo 1).firstOrNull { size ->
            val tail = earlier.takeLast(size)
            val head = later.take(size)
            tail.zip(head).all { (first, second) -> sameVisualBubble(first, second) } &&
                (size > 1 || visualKey(tail.single().text).length >= 20)
        } ?: 0

    private fun sameVisualBubble(first: ConversationTurn, second: ConversationTurn): Boolean {
        if (first.fromMe != second.fromMe) return false
        val a = visualKey(first.text)
        val b = visualKey(second.text)
        if (a == b) return true
        val shorter = minOf(a.length, b.length)
        return shorter >= 12 && (a.contains(b) || b.contains(a)) &&
            shorter * 100 >= maxOf(a.length, b.length) * 75
    }

    private fun visualKey(text: String): String = comparableText(text)
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    /** Infers how the next captured view sits relative to the previous view from their shared boundary. */
    fun frameDirection(
        previous: List<ConversationTurn>,
        current: List<ConversationTurn>,
    ): FrameDirection? {
        val towardNewer = overlapSize(previous, current)
        val towardOlder = overlapSize(current, previous)
        return when {
            towardNewer > towardOlder -> FrameDirection.NEWER
            towardOlder > towardNewer -> FrameDirection.OLDER
            else -> null
        }
    }

    fun mergeFrame(
        existing: List<ConversationTurn>,
        current: List<ConversationTurn>,
        direction: FrameDirection,
    ): List<ConversationTurn> = when (direction) {
        FrameDirection.OLDER -> merge(current, existing)
        FrameDirection.NEWER -> merge(existing, current)
    }

    /** Merges an additional capture on either side of previously reviewed context. */
    fun mergeCapture(
        existing: List<ConversationTurn>,
        captured: List<ConversationTurn>,
    ): List<ConversationTurn> = when {
        existing.isEmpty() -> captured.takeLast(MAX_TURNS)
        captured.isEmpty() -> existing.takeLast(MAX_TURNS)
        frameDirection(existing, captured) == FrameDirection.OLDER -> merge(captured, existing)
        else -> merge(existing, captured)
    }

    /** Uses on-device bubble geometry as the speaker authority while keeping any extra text the
     * AI could read. Sequence alignment also restores locally read turns omitted by vision. */
    fun reconcileAiWithLocal(
        ai: List<ConversationTurn>,
        local: List<ConversationTurn>,
    ): List<ConversationTurn> {
        val cleanLocal = removeCaptureUiArtifacts(local)
        val localTimeReplies = cleanLocal
            .filter { timestampOnly.matches(it.text) }
            .map { normalizedText(it.text) }
            .toSet()
        val cleanAi = removeCaptureUiArtifacts(ai).filterNot { turn ->
            timestampOnly.matches(turn.text) && normalizedText(turn.text) !in localTimeReplies
        }
        if (cleanAi.isEmpty()) return cleanLocal.takeLast(MAX_TURNS)
        if (cleanLocal.isEmpty()) return cleanAi.takeLast(MAX_TURNS)
        val matches = lcsMatches(cleanLocal, cleanAi)
        if (matches.isEmpty()) return cleanAi.takeLast(MAX_TURNS)

        val merged = mutableListOf<ConversationTurn>()
        var localIndex = 0
        var aiIndex = 0
        matches.forEach { match ->
            merged += cleanLocal.subList(localIndex, match.localIndex)
            merged += cleanAi.subList(aiIndex, match.aiIndex)
            // Local OCR is the geometry authority and does not include a bubble's
            // adjacent timestamp/delivery mark. Prefer it for an aligned turn so
            // AI text such as "Oh okay 22:22" cannot leak metadata into review.
            merged += cleanLocal[match.localIndex]
            localIndex = match.localIndex + 1
            aiIndex = match.aiIndex + 1
        }
        merged += cleanLocal.subList(localIndex, cleanLocal.size)
        merged += cleanAi.subList(aiIndex, cleanAi.size)
        return merged.takeLast(MAX_TURNS)
    }

    /** Drops exact WhatsApp controls and receipt-glyph OCR. A contact-card action also
     * removes the immediately preceding title, while ordinary chat names stay intact. */
    private fun removeCaptureUiArtifacts(turns: List<ConversationTurn>): List<ConversationTurn> {
        val result = mutableListOf<ConversationTurn>()
        turns.forEach { turn ->
            val text = turn.text.replace(Regex("\\s+"), " ").trim().take(2_000)
            if (text.isEmpty() || oneCharacterReceipt.matches(text)) return@forEach
            val key = text.lowercase()
            if (key in captureUiActions) {
                if (key == "add contact" && result.lastOrNull()?.text?.let(::looksLikeQuoteSender) == true) {
                    result.removeAt(result.lastIndex)
                }
                return@forEach
            }
            result += turn.copy(text = text)
        }
        return result
    }

    private fun lcsMatches(
        local: List<ConversationTurn>,
        ai: List<ConversationTurn>,
    ): List<TurnMatch> {
        val lengths = Array(local.size + 1) { IntArray(ai.size + 1) }
        for (localIndex in local.indices.reversed()) {
            for (aiIndex in ai.indices.reversed()) {
                lengths[localIndex][aiIndex] = if (sameComparableText(local[localIndex].text, ai[aiIndex].text)) {
                    1 + lengths[localIndex + 1][aiIndex + 1]
                } else {
                    maxOf(lengths[localIndex + 1][aiIndex], lengths[localIndex][aiIndex + 1])
                }
            }
        }
        val matches = mutableListOf<TurnMatch>()
        var localIndex = 0
        var aiIndex = 0
        while (localIndex < local.size && aiIndex < ai.size) {
            when {
                sameComparableText(local[localIndex].text, ai[aiIndex].text) -> {
                    matches += TurnMatch(localIndex, aiIndex)
                    localIndex++
                    aiIndex++
                }
                lengths[localIndex + 1][aiIndex] >= lengths[localIndex][aiIndex + 1] -> localIndex++
                else -> aiIndex++
            }
        }
        return matches
    }

    private data class TurnMatch(val localIndex: Int, val aiIndex: Int)

    private fun sameComparableText(first: String, second: String): Boolean =
        comparableText(first) == comparableText(second)

    private fun comparableText(value: String): String = normalizedText(
        comparisonTimestampSuffix.replace(value.replace(Regex("\\s+"), " ").trim(), "").trim(),
    )

    private fun normalizedText(value: String): String = value.lowercase().replace(Regex("\\s+"), " ").trim()

    private fun overlapSize(earlier: List<ConversationTurn>, later: List<ConversationTurn>): Int =
        (minOf(earlier.size, later.size) downTo 1).firstOrNull { size ->
            earlier.takeLast(size).zip(later.take(size)).all { (first, second) ->
                first.fromMe == second.fromMe && first.text == second.text
            }
        } ?: 0

    const val MAX_TURNS = 60
    private const val METADATA_GAP_FRACTION = 0.025
    private const val METADATA_HORIZONTAL_GAP_FRACTION = 0.08
    private const val QUOTE_GAP_FRACTION = 0.022
    private const val QUOTE_INDENT_FRACTION = 0.012
    private const val MAX_QUOTE_LINES = 6
}
