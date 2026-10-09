package dev.patrickgold.florisboard.ime.nlp.latin.repli

import java.util.Locale

/** A bounded cursor window, never a retained typing history. Offsets use UTF-16 like Android. */
data class TypingContext(
    val before: String,
    val after: String,
    val selectionStart: Int,
    val selectionEnd: Int,
    val textLength: Int? = null,
)

data class WordPrediction(
    val word: String,
    val removeBefore: Int,
    val removeAfter: Int,
    val space: Boolean,
    val correction: Boolean = false,
) {
    val replacement: String get() = word + if (space) " " else ""
    val completion: Boolean get() = removeBefore > 0
}

/** Offline vocabulary, AOSP-derived language data, and phrase priors. No learning or persistence. */
class WordPredictionEngine {
    @Volatile private var lexicon: KeyboardLexicon? = null
    @Volatile private var adaptiveModel: AdaptiveLanguageModel? = null
    @Volatile private var adaptiveEnabled = true

    fun installLexicon(loaded: KeyboardLexicon) {
        lexicon = loaded
    }

    fun installAdaptiveModel(model: AdaptiveLanguageModel) {
        adaptiveModel = model
    }

    fun setAdaptiveEnabled(enabled: Boolean) {
        adaptiveEnabled = enabled
    }

    fun suggest(context: TypingContext, limit: Int? = null, includeAdaptive: Boolean = true): List<WordPrediction> {
        if (context.selectionStart < 0 || context.selectionStart != context.selectionEnd) return emptyList()
        val before = context.before
        val after = context.after
        val chunk = before.takeLastWhile { !it.isWhitespace() }
        if (chunk.contains('@') || chunk.contains("://") || chunk.startsWith("www.", true)) return emptyList()
        val prefix = before.takeLastWhile(::wordCharacter)
        val suffix = after.takeWhile(::wordCharacter)
        // Don't replace a word whose boundary lies beyond our bounded cursor window.
        if ((prefix.length == BEFORE_LIMIT && before.length == BEFORE_LIMIT) ||
            (suffix.length == AFTER_LIMIT && after.length == AFTER_LIMIT)) return emptyList()
        if (prefix.isEmpty() && suffix.isNotEmpty()) return emptyList()
        if (prefix.isNotEmpty() && !prefix.first().isLetter()) return emptyList()
        if (before.lastOrNull()?.isDigit() == true) return emptyList()
        val normalized = prefix.lowercase(Locale.ROOT).replace('’', '\'')
        val tokens = tokenPattern.findAll(before.lowercase(Locale.ROOT).replace('’', '\''))
            .map { it.value }.toList().takeLast(2)
        val sentenceStart = before.isBlank() || before.trimEnd().lastOrNull() in listOf('.', '!', '?') || before.endsWith('\n')
        var correctionWords = emptySet<String>()
        val words = if (prefix.isNotEmpty()) {
            val loadedLexicon = lexicon
            val adaptive = adaptiveModel.takeIf { adaptiveEnabled && includeAdaptive }
            if (loadedLexicon == null) {
                (adaptive?.completions(normalized, 3).orEmpty() +
                    vocabulary.filter { it.startsWith(normalized) && it != normalized })
                    .distinct()
                    .ifEmpty { if (normalized in vocabulary) listOf(normalized) else emptyList() }
            } else {
                val completions = (adaptive?.completions(normalized, 3).orEmpty() +
                    loadedLexicon.completions(normalized, 6).map(LexiconWord::word))
                    .distinctBy { it.lowercase(Locale.ROOT) }
                    .filterNot { it.equals(normalized, ignoreCase = true) }
                // Keep the contraction visible while typing, including when the bare word is valid.
                val contraction = AMBIGUOUS_CONTRACTIONS[normalized]?.let(::listOf).orEmpty()
                if (completions.isNotEmpty() || contraction.isNotEmpty()) contraction + completions else {
                    val corrections = if (normalized in PROTECTED_DIALECT_WORDS) emptyList() else (
                        adaptive?.corrections(normalized, 3).orEmpty() +
                            loadedLexicon.corrections(normalized, 3).map(LexiconWord::word)
                        ).distinctBy { it.lowercase(Locale.ROOT) }
                    corrections.also {
                        correctionWords = it.map { word -> word.lowercase(Locale.ROOT) }.toSet()
                    }.ifEmpty {
                        if (loadedLexicon.contains(normalized) || adaptive?.contains(normalized) == true) listOf(normalized)
                        else emptyList()
                    }
                }
            }
        } else if (sentenceStart) listOf("hello", "i", "thanks")
        else {
            val phraseWords = phrases[tokens.joinToString(" ")] ?: phrases[tokens.lastOrNull()]
            val adaptiveWords = adaptiveModel.takeIf { adaptiveEnabled && includeAdaptive }?.nextWords(tokens, 3).orEmpty()
            (adaptiveWords + phraseWords.orEmpty() +
                lexicon?.nextWords(tokens.lastOrNull().orEmpty(), 3).orEmpty().map(LexiconWord::word) +
                listOf("the", "and", "to"))
                .distinctBy { it.lowercase(Locale.ROOT) }
        }
        val tail = after.drop(suffix.length)
        val space = tail.isEmpty() || tail.startsWith(' ')
        val removeAfter = suffix.length + if (tail.startsWith(' ')) 1 else 0
        return words.distinct().map { word ->
            val display = when {
                prefix.length > 1 && prefix.filter(Char::isLetter).all(Char::isUpperCase) -> word.uppercase(Locale.ROOT)
                prefix.firstOrNull()?.isUpperCase() == true || (prefix.isEmpty() && sentenceStart) -> word.replaceFirstChar(Char::uppercaseChar)
                word == "i" || word.startsWith("i'") -> word.replaceFirstChar(Char::uppercaseChar)
                else -> word
            }
            WordPrediction(
                display,
                prefix.length,
                removeAfter,
                space,
                correction = word.lowercase(Locale.ROOT) in correctionWords,
            )
        }.filter { prediction ->
            limit == null || context.textLength?.let { it - prediction.removeBefore - prediction.removeAfter + prediction.replacement.length <= limit } != false
        }.take(3)
    }

    /** Returns only high-confidence, one-edit corrections suitable for committing on a separator. */
    fun autocorrection(context: TypingContext, includeAdaptive: Boolean = true): WordPrediction? {
        if (context.selectionStart < 0 || context.selectionStart != context.selectionEnd || context.after.firstOrNull()?.let(::wordCharacter) == true) {
            return null
        }
        val chunk = context.before.takeLastWhile { !it.isWhitespace() }
        if (chunk.contains('@') || chunk.contains("://") || chunk.startsWith("www.", true)) return null
        val prefix = context.before.takeLastWhile(::wordCharacter)
        if (prefix.length !in 3..20 || prefix.any { !wordCharacter(it) }) return null
        val normalized = prefix.lowercase(Locale.ROOT).replace('’', '\'')
        // Common Nigerian Pidgin words should never be "fixed" into a different English word.
        if (normalized in PROTECTED_DIALECT_WORDS) return null
        // Repli prefers the contraction in chat; immediate Backspace can restore the possessive.
        if (normalized == "its") {
            return WordPrediction(preserveCase("it's", prefix, false), prefix.length, 0, space = false, correction = true)
        }
        // Correct other missing apostrophes only where the bare spelling is uncommon.
        UNAMBIGUOUS_CONTRACTIONS[normalized]?.let { contraction ->
            return WordPrediction(preserveCase(contraction, prefix, false), prefix.length, 0, space = false, correction = true)
        }
        val adaptive = adaptiveModel.takeIf { adaptiveEnabled && includeAdaptive }
        if (adaptive?.contains(normalized) == true) return null
        val loadedLexicon = lexicon ?: return null
        if (loadedLexicon.contains(normalized)) return null
        val best = loadedLexicon.corrections(normalized, 2)
        val winner = best.firstOrNull() ?: return null
        // One edit is one edit: single-edit typos share the transposition confidence bar.
        // Calibrated against the shipped list, where everyday words sit well below the old
        // 145 bar (hello 120, thanks 122, morning 136) while the candidate floor stays 100.
        val confident = winner.frequency >= CONFIDENT_FREQUENCY ||
            winner.word.lowercase(Locale.ROOT) in ALWAYS_CORRECT
        if (!confident) return null
        val display = preserveCase(winner.word, prefix, sentenceStart = false)
        return WordPrediction(display, prefix.length, 0, space = false, correction = true)
    }

    private fun preserveCase(word: String, prefix: String, sentenceStart: Boolean): String = when {
        prefix.length > 1 && prefix.filter(Char::isLetter).all(Char::isUpperCase) -> word.uppercase(Locale.ROOT)
        prefix.firstOrNull()?.isUpperCase() == true || (prefix.isEmpty() && sentenceStart) -> word.replaceFirstChar(Char::uppercaseChar)
        word.equals("i", true) || word.startsWith("i'", true) -> word.replaceFirstChar(Char::uppercaseChar)
        else -> word
    }

    companion object {
        const val BEFORE_LIMIT = 96
        const val AFTER_LIMIT = 48
        private const val CONFIDENT_FREQUENCY = 110
        private fun wordCharacter(c: Char) = c.isLetter() || c == '\'' || c == '’'
        fun isProtectedDialectWord(word: String): Boolean =
            word.lowercase(Locale.ROOT).replace('’', '\'') in PROTECTED_DIALECT_WORDS
        private val tokenPattern = Regex("[a-z]+(?:'[a-z]+)?")
        private val ALWAYS_CORRECT = setOf("the", "and", "you", "that", "with", "this", "have", "for")
        private val AMBIGUOUS_CONTRACTIONS = mapOf("its" to "it's")
        private val UNAMBIGUOUS_CONTRACTIONS = mapOf(
            "dont" to "don't", "doesnt" to "doesn't", "didnt" to "didn't",
            "cant" to "can't", "wont" to "won't", "isnt" to "isn't",
            "arent" to "aren't", "youre" to "you're", "theyre" to "they're",
            "weve" to "we've", "ive" to "I've", "thats" to "that's",
        )
        private val PROTECTED_DIALECT_WORDS = setOf(
            "abeg", "abi", "dey", "don", "na", "oya", "sef", "sha", "una", "wahala", "wetin",
        )

        private val vocabulary = """
            the to and you that have for with this not are but what can all your will from just
            about like know good would there when time how want some thanks thank please yes no
            hello help here hope happy home hey hear heard helpful helping held health healthy
            i i'm i'll i've i'd it it's its in into is isn't if on one only our out over of or
            we we're we'll we've were was wasn't they they're their them then than these those
            he her his him she should shouldn't could couldn't don't doesn't didn't do did done
            be been being because before back by am an as at after again always also already
            any anyone anything ask asked away another actually absolutely amazing alright
            beautiful better best big birthday busy buy call called calling calls care chat check
            coffee come coming cool conversation day days dear definitely details different dinner
            direction discuss down early easy else email enjoy enough even evening ever every
            everyone everything example excited expect family fantastic far feel feeling few fine
            first follow food forward found free friend friends friday fun funny getting get give
            glad go goes going gone great guess guy guys happened happens hard has haven't having
            hi honestly hour hours however important information interested interesting invite
            job join keep kind later last late learn leave left let let's life line little live
            long look looking love lovely lunch made make makes making many maybe mean meeting
            message messages might mind minute minutes miss monday more morning most much must
            my myself name need needed needs never new news next nice night nothing now number
            okay once online open order other otherwise own parents part person phone picture
            plan plans point possible probably problem project question quick quite read ready
            really reason remember reply response right room run running same saturday say saying
            school see seeing seems send sending sent serious share short since sister sleep small
            so someone something sometimes soon sorry sounds speak specific start stay still stop
            story stuff sure talk talking tell texting text thing things think thinking thought
            though through thursday today together tomorrow tonight too totally travel true try
            tuesday understand unless until update use usually very voice wait waiting walk warm
            wasn't watch way wednesday week weekend welcome well went where which while who why
            wish without wonderful work working works world worry write wrong year yesterday yet
            yours yourself polite politely decline suggest shorter longer friendly casual formal
            context detail fact facts tone instruction instructions clarify clarify clarification
            opportunity appreciate appreciated apologies apologize available availability thankyou
            schedule scheduled scheduling reschedule confirm confirmed confirmation meet month
            goodness luck instead unavailable promise promised avoid honest kindly
        """.trimIndent().split(Regex("\\s+")).distinct()

        private val phrases = mapOf(
            "i" to listOf("am", "will", "can"), "i am" to listOf("going", "not", "here"),
            "i will" to listOf("be", "send", "let"), "i can" to listOf("do", "help", "come"),
            "i'm" to listOf("going", "sorry", "available"), "i'll" to listOf("be", "send", "call"),
            "you" to listOf("are", "can", "have"), "you are" to listOf("welcome", "right", "kind"),
            "are" to listOf("you", "we", "they"), "we" to listOf("can", "are", "should"),
            "we can" to listOf("meet", "talk", "try"), "they" to listOf("are", "will", "have"),
            "how" to listOf("are", "is", "about"), "how are" to listOf("you", "they", "things"),
            "what" to listOf("is", "do", "time"), "what do" to listOf("you", "we", "they"),
            "thank" to listOf("you", "everyone", "goodness"), "thank you" to listOf("so", "for", "again"),
            "thanks" to listOf("for", "so", "again"), "thanks for" to listOf("your", "the", "helping"),
            "hello" to listOf("there", "how", "everyone"), "hi" to listOf("there", "how", "everyone"),
            "good" to listOf("morning", "night", "luck"), "good morning" to listOf("hope", "everyone", "how"),
            "see" to listOf("you", "the", "what"), "see you" to listOf("soon", "tomorrow", "later"),
            "let" to listOf("me", "us", "them"), "let me" to listOf("know", "check", "think"),
            "let's" to listOf("meet", "talk", "try"), "please" to listOf("let", "send", "confirm"),
            "hope" to listOf("you", "we", "everything"), "hope you" to listOf("are", "have", "feel"),
            "would" to listOf("you", "be", "like"), "would you" to listOf("like", "be", "mind"),
            "can" to listOf("you", "we", "help"), "can you" to listOf("send", "help", "please"),
            "want" to listOf("to", "a", "some"), "want to" to listOf("meet", "talk", "know"),
            "going" to listOf("to", "home", "out"), "going to" to listOf("be", "call", "meet"),
            "sounds" to listOf("good", "great", "like"), "happy" to listOf("birthday", "to", "for"),
            "sorry" to listOf("about", "for", "i"), "sorry for" to listOf("the", "being", "not"),
            "busy" to listOf("tomorrow", "today", "right"), "available" to listOf("tomorrow", "today", "after"),
            "looking" to listOf("forward", "for", "at"), "looking forward" to listOf("to", "already", "now"),
            "the" to listOf("same", "next", "meeting"), "to" to listOf("the", "you", "meet"),
            "for" to listOf("the", "your", "you"), "your" to listOf("help", "time", "message"),
            "next" to listOf("week", "time", "month"), "tomorrow" to listOf("morning", "evening", "instead"),
            "decline" to listOf("politely", "the", "and"), "politely" to listOf("and", "but", "because"),
            "suggest" to listOf("next", "another", "a"), "keep" to listOf("it", "the", "things"),
        )
    }
}
