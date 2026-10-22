/* Copyright (C) 2026 The Repli Keyboard Contributors. Licensed under Apache-2.0. */
package dev.patrickgold.florisboard.ime.nlp.latin.repli

/** Explicit Repli insertion policy. A swipe never overwrites a partially typed following word. */
object GlideEditing {
    fun insertion(before: String, after: String, word: String): String? {
        if (word.length !in 2..32 || word.any { !it.isLetter() || it.code > 127 }) return null
        if (after.firstOrNull()?.let { it.isLetterOrDigit() || it == '\'' || it == '’' } == true) return null
        val chunk = before.takeLastWhile { !it.isWhitespace() }
        if (chunk.contains('@') || chunk.contains("://") || chunk.startsWith("www.", true)) return null
        val last = before.lastOrNull()
        val needsSpace = last != null && !last.isWhitespace() && last !in "([{\"“"
        return (if (needsSpace) " " else "") + word
    }
}
