package dev.patrickgold.florisboard.repli.openai

import org.json.JSONArray
import org.json.JSONObject

/**
 * Per-profile chat memory, now kept on the phone instead of the backend. Semantics are a
 * straight port of memory.mjs: saved turns are the approved context from earlier replies,
 * recall excludes anything already visible in the current context, and remember appends
 * without duplicating an overlapping tail.
 */
data class MemoryTurn(val fromMe: Boolean, val text: String) {
    internal val key: String get() = (if (fromMe) "me" else "them") + "\u0000" + text
}

data class ProfileRecall(val history: List<MemoryTurn>, val styleSummary: String?, val styleExamples: List<String>)

object ProfileMemory {
    const val MAX_TURNS = 120
    const val MAX_TURN_CHARACTERS = 1_000
    const val MAX_BYTES = 160 * 1_024
    const val MAX_MODEL_HISTORY_TURNS = 24
    const val MAX_MODEL_STYLE_EXAMPLES = 5
    private const val MAX_EXAMPLE_CHARACTERS = 280

    /** What a reply request may see from [saved], excluding turns already in [context]. */
    fun recall(saved: List<MemoryTurn>, context: List<MemoryTurn>): ProfileRecall {
        val current = context.map { it.key }.toHashSet()
        val prior = saved.filter { it.key !in current }
        val outgoing = prior.filter { it.fromMe }.map { it.text }
        return ProfileRecall(
            history = prior.takeLast(MAX_MODEL_HISTORY_TURNS),
            styleSummary = summarizeStyle(outgoing),
            styleExamples = outgoing.filter { it.length <= MAX_EXAMPLE_CHARACTERS }.takeLast(MAX_MODEL_STYLE_EXAMPLES),
        )
    }

    /** [saved] plus the part of [context] not already at its tail; null when nothing changes. */
    fun merge(saved: List<MemoryTurn>, context: List<MemoryTurn>): List<MemoryTurn>? {
        val valid = context.filter { it.text.isNotEmpty() && it.text.length <= MAX_TURN_CHARACTERS }
        if (valid.isEmpty() || containsSequence(saved, valid)) return null
        val overlap = largestOverlap(saved, valid)
        return (saved + valid.drop(overlap)).takeLast(MAX_TURNS)
    }

    /** Merges recalled cues into the style the keyboard prepared, the way the backend did before calling the model. */
    fun styleWithRecall(style: PromptStyle, recall: ProfileRecall): PromptStyle = style.copy(
        summary = listOfNotNull(style.summary, recall.styleSummary).joinToString(" ").take(500).takeIf(String::isNotEmpty),
        examples = (recall.styleExamples + style.examples).distinct().takeLast(MAX_MODEL_STYLE_EXAMPLES),
    )

    internal fun largestOverlap(saved: List<MemoryTurn>, current: List<MemoryTurn>): Int {
        for (length in minOf(saved.size, current.size) downTo 1) {
            val tail = saved.subList(saved.size - length, saved.size)
            if (tail.indices.all { tail[it].key == current[it].key }) return length
        }
        return 0
    }

    internal fun containsSequence(saved: List<MemoryTurn>, current: List<MemoryTurn>): Boolean {
        if (current.isEmpty() || current.size > saved.size) return false
        return (0..saved.size - current.size).any { start ->
            current.indices.all { saved[start + it].key == current[it].key }
        }
    }

    internal fun summarizeStyle(messages: List<String>): String? {
        if (messages.size < 3) return null
        val sample = messages.takeLast(40)
        val averageWords = Math.round(sample.sumOf { it.trim().split(Regex("\\s+")).size }.toDouble() / sample.size)
        val half = sample.size / 2.0
        val mostlyLowercase = sample.count { it.firstOrNull()?.let { c -> c in 'a'..'z' } == true } > half
        val usesFinalPunctuation = sample.count { TextCues.endsWithPunctuation(it) } > half
        val usesEmoji = sample.count { TextCues.containsEmoji(it) } > sample.size / 3.0
        val usesDashes = sample.count { TextCues.containsDash(it) }.toDouble() / sample.size >= 0.1
        return "Past outgoing messages average $averageWords words; " +
            (if (mostlyLowercase) "often start lowercase" else "often start uppercase") + "; " +
            (if (usesFinalPunctuation) "often end with punctuation" else "often omit final punctuation") + "; " +
            (if (usesEmoji) "often include emoji" else "rarely include emoji") + "; " +
            (if (usesDashes) "often use em dashes" else "rarely use em dashes") +
            ". Preserve the user's own phrasing and rhythm."
    }

    // ---- codec: JSON array of {speaker, text}, bounded, oldest turns dropped first ----------

    fun encode(turns: List<MemoryTurn>): ByteArray {
        var retained = turns.takeLast(MAX_TURNS)
        while (true) {
            val bytes = toJson(retained).toString().toByteArray(Charsets.UTF_8)
            if (bytes.size <= MAX_BYTES || retained.size <= 1) return bytes
            retained = retained.drop(1)
        }
    }

    /** Null (treated as empty) for anything that is not a well-formed, bounded turn list. */
    fun decode(bytes: ByteArray?): List<MemoryTurn>? {
        if (bytes == null || bytes.isEmpty() || bytes.size > MAX_BYTES) return null
        val array = runCatching { JSONArray(String(bytes, Charsets.UTF_8)) }.getOrNull() ?: return null
        if (array.length() > MAX_TURNS) return null
        return (0 until array.length()).map { index ->
            val item = array.optJSONObject(index) ?: return null
            val speaker = item.optString("speaker")
            val text = item.opt("text") as? String ?: return null
            if (speaker !in setOf("me", "them") || text.isEmpty() || text.length > MAX_TURN_CHARACTERS) return null
            MemoryTurn(speaker == "me", text)
        }
    }

    private fun toJson(turns: List<MemoryTurn>) = JSONArray().apply {
        turns.forEach { put(JSONObject().put("speaker", if (it.fromMe) "me" else "them").put("text", it.text)) }
    }
}

/** Where encoded memory bytes live; the Android implementation wraps the Keystore-backed EncryptedFileStore. */
interface ProfileMemoryStorage {
    fun read(profileId: String): ByteArray?
    fun write(profileId: String, bytes: ByteArray)
    fun delete(profileId: String)
}

/** In-memory storage for tests and for the "don't remember chats" setting. */
class TransientProfileMemoryStorage : ProfileMemoryStorage {
    private val files = HashMap<String, ByteArray>()
    override fun read(profileId: String): ByteArray? = files[profileId]
    override fun write(profileId: String, bytes: ByteArray) { files[profileId] = bytes }
    override fun delete(profileId: String) { files.remove(profileId) }
}

/** Serialises recall/remember per process the way the backend's FileProfileMemory did. */
class ProfileMemoryStore(private val storage: ProfileMemoryStorage) {
    private val lock = Any()

    fun recall(profileId: String, context: List<MemoryTurn>): ProfileRecall = synchronized(lock) {
        ProfileMemory.recall(load(profileId), context)
    }

    /** Returns true when new turns were saved. */
    fun remember(profileId: String, context: List<MemoryTurn>): Boolean = synchronized(lock) {
        val next = ProfileMemory.merge(load(profileId), context) ?: return false
        storage.write(profileId, ProfileMemory.encode(next))
        true
    }

    fun turnCount(profileId: String): Int = synchronized(lock) { load(profileId).size }

    fun forget(profileId: String) = synchronized(lock) { storage.delete(profileId) }

    private fun load(profileId: String): List<MemoryTurn> = ProfileMemory.decode(storage.read(profileId)) ?: emptyList()
}
