/*
 * Copyright (C) 2026 The Repli Keyboard Contributors
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

package dev.patrickgold.florisboard.ime.nlp.latin.repli

import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.ln1p
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

data class GlidePoint(val x: Float, val y: Float)
data class GlideKey(val letter: Char, val centerX: Float, val centerY: Float, val width: Float, val height: Float)

/** A geometric ranking cost, not a calibrated probability. Smaller values rank first. */
data class GlideCandidate(val word: String, val cost: Double)

/**
 * Original, offline letter-path matcher. Construct and invoke away from the UI thread.
 *
 * Coordinates are normalized using the current keys. Arc-length sampling removes timing and
 * recording-density differences; ordered, band-limited DTW permits local corner-timing differences.
 * Consecutive identical letters share a template point, so doubled letters need no special loop.
 * All retained state is static vocabulary and a small cache of word templates, never user traces.
 */
class GlideDecoder(vocabulary: List<LexiconWord>) {
    private data class Word(val text: String, val letters: String, val frequency: Int)
    private data class Point(val x: Double, val y: Double)
    private data class Template(val samples: List<Point>, val length: Double)
    private data class ShortlistEntry(val word: Word, val vertices: List<Point>, val length: Double, val coarse: Double)
    private data class Endpoints(val first: Char, val last: Char)

    private val wordsByEndpoints: Map<Endpoints, List<Word>> = vocabulary.take(MAX_VOCABULARY)
        .asSequence()
        .filter { it.word.length in 2..MAX_WORD_LENGTH }
        .map { it.copy(word = it.word.lowercase(Locale.ROOT)) }
        .filter { it.word.all { letter -> letter in 'a'..'z' } }
        .groupBy { it.word }
        .map { (text, copies) ->
            Word(text, text.filterIndexed { index, letter -> index == 0 || text[index - 1] != letter },
                copies.maxOf { it.frequency }.coerceAtLeast(0))
        }
        .filter { it.letters.length >= 2 }
        .groupBy { Endpoints(it.letters.first(), it.letters.last()) }
        .mapValues { (_, words) -> words.sortedWith(compareByDescending<Word> { it.frequency }.thenBy { it.text }) }

    private var cachedLayout: List<GlideKey> = emptyList()
    private val templates = object : LinkedHashMap<String, Template>(CACHE_SIZE, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Template>?): Boolean = size > CACHE_SIZE
    }

    /** Invalid, unsupported or implausible paths produce no candidates. Results are deterministic. */
    @Synchronized
    fun decode(
        points: List<GlidePoint>,
        keys: List<GlideKey>,
        limit: Int = 3,
        contextWords: List<String> = emptyList(),
    ): List<GlideCandidate> {
        if (limit <= 0 || points.size !in 2..MAX_INPUT_POINTS || keys.size !in 2..MAX_KEYS) return emptyList()
        if (points.any { !it.x.isFinite() || !it.y.isFinite() }) return emptyList()
        if (keys.any { !it.centerX.isFinite() || !it.centerY.isFinite() ||
                !it.width.isFinite() || !it.height.isFinite() || it.width <= 0f || it.height <= 0f }) return emptyList()
        val layout = keys.filter { it.letter.lowercaseChar() in 'a'..'z' }
            .map { it.copy(letter = it.letter.lowercaseChar()) }.sortedBy { it.letter }
        if (layout.size < 2 || layout.map { it.letter }.distinct().size != layout.size) return emptyList()
        val width = layout.map { it.width.toDouble() }.sorted()[layout.size / 2]
        val height = layout.map { it.height.toDouble() }.sorted()[layout.size / 2]
        val originX = layout.minOf { it.centerX.toDouble() }
        val originY = layout.minOf { it.centerY.toDouble() }
        fun normalized(x: Float, y: Float) = Point((x.toDouble() - originX) / width, (y.toDouble() - originY) / height)
        val centers = layout.associate { it.letter to normalized(it.centerX, it.centerY) }
        val right = centers.values.maxOf { it.x }
        val bottom = centers.values.maxOf { it.y }
        // Reject corrupt geometry as well as remote points before any distance/length arithmetic.
        if (!right.isFinite() || !bottom.isFinite() || right > 50.0 || bottom > 15.0) return emptyList()
        val trace = points.map { normalized(it.x, it.y) }
        if (trace.any { !it.x.isFinite() || !it.y.isFinite() ||
                it.x < -1.1 || it.x > right + 1.1 || it.y < -1.1 || it.y > bottom + 1.1 }) return emptyList()
        val clean = simplify(trace)
        val traceLength = pathLength(clean)
        if (traceLength < .8 || traceLength > MAX_PATH_LENGTH ||
            clean.maxOf { distance(it, clean.first()) } < .65) return emptyList()
        val sampled = resample(clean, SAMPLE_COUNT)
        val coarseTrace = resample(clean, COARSE_SAMPLE_COUNT)
        val startDistances = centers.mapValues { distance(clean.first(), it.value) }
        val endDistances = centers.mapValues { distance(clean.last(), it.value) }
        val endpointGroups = wordsByEndpoints.entries.filter {
            (startDistances[it.key.first] ?: Double.POSITIVE_INFINITY) <= ENDPOINT_RADIUS &&
                (endDistances[it.key.last] ?: Double.POSITIVE_INFINITY) <= ENDPOINT_RADIUS
        }.sortedWith(compareBy<Map.Entry<Endpoints, List<Word>>> {
            startDistances.getValue(it.key.first) + endDistances.getValue(it.key.last)
        }.thenBy { it.key.first }.thenBy { it.key.last })
        if (layout != cachedLayout) {
            templates.clear()
            cachedLayout = layout
        }
        val shortlist = ArrayList<ShortlistEntry>(MAX_SHORTLIST + 1)
        var examined = 0
        for ((_, words) in endpointGroups) {
            for (word in words) {
                if (++examined > MAX_EXAMINED) break
                val vertices = word.letters.mapNotNull { centers[it] }
                if (vertices.size != word.letters.length) continue
                val length = pathLength(vertices)
                if (length < .5 || traceLength / length !in .42..2.25) continue
                val coarseSamples = resample(vertices, COARSE_SAMPLE_COUNT)
                val shape = coarseTrace.indices.sumOf { squaredDistance(coarseTrace[it], coarseSamples[it]) } /
                    COARSE_SAMPLE_COUNT
                val endpoint = startDistances.getValue(word.letters.first()) + endDistances.getValue(word.letters.last())
                val coarse = shape + .2 * endpoint + .1 * abs(ln(traceLength / length))
                val entry = ShortlistEntry(word, vertices, length, coarse)
                val index = shortlist.indexOfFirst { coarse < it.coarse || (coarse == it.coarse && word.text < it.word.text) }
                    .let { if (it < 0) shortlist.size else it }
                if (index < MAX_SHORTLIST) shortlist.add(index, entry)
                if (shortlist.size > MAX_SHORTLIST) shortlist.removeAt(shortlist.lastIndex)
            }
            if (examined >= MAX_EXAMINED) break
        }
        val context = contextWords.takeLast(2).map { if (it.length <= MAX_WORD_LENGTH) it.lowercase(Locale.ROOT) else "" }
        val ranked = ArrayList<GlideCandidate>()
        for (entry in shortlist) {
            val template = templates.getOrPut(entry.word.text) { Template(resample(entry.vertices, SAMPLE_COUNT), entry.length) }
            val ordered = orderedDistance(sampled, template.samples)
            if (ordered > MAX_ORDERED_DISTANCE) continue
            // Reject excursions a candidate cannot explain, even if its average error is small.
            if (sampled.any { point -> entry.vertices.zipWithNext().minOf { (a, b) -> segmentDistance(point, a, b) } > 1.6 }) continue
            val endpoints = startDistances.getValue(entry.word.letters.first()) + endDistances.getValue(entry.word.letters.last())
            val lengthError = abs(ln(traceLength / template.length))
            val frequencyPrior = .05 * ln1p(entry.word.frequency.toDouble()) / ln1p(Int.MAX_VALUE.toDouble())
            val contextPrior = if (entry.word.text in STATIC_NEXT[context.lastOrNull()].orEmpty() ||
                (context == listOf("see", "you") && entry.word.text == "tomorrow")) .035 else 0.0
            val cost = ordered + .12 * endpoints + .15 * lengthError - frequencyPrior - contextPrior
            if (cost.isFinite()) ranked.add(GlideCandidate(entry.word.text, cost))
        }
        return ranked.sortedWith(compareBy<GlideCandidate> { it.cost }.thenBy { it.word }).take(limit.coerceAtMost(MAX_RESULTS))
    }

    private fun orderedDistance(trace: List<Point>, template: List<Point>): Double {
        var previous = DoubleArray(SAMPLE_COUNT + 1) { Double.POSITIVE_INFINITY }
        previous[0] = 0.0
        for (i in 1..SAMPLE_COUNT) {
            val current = DoubleArray(SAMPLE_COUNT + 1) { Double.POSITIVE_INFINITY }
            for (j in max(1, i - DTW_BAND)..min(SAMPLE_COUNT, i + DTW_BAND)) {
                current[j] = squaredDistance(trace[i - 1], template[j - 1]) +
                    min(previous[j - 1], min(previous[j] + .015, current[j - 1] + .015))
            }
            previous = current
        }
        return sqrt(previous[SAMPLE_COUNT] / SAMPLE_COUNT)
    }

    /**
     * Remove stationary jitter while retaining corners AND travel order. Perpendicular error alone
     * is insufficient: a collinear out-and-back motion (such as tomorrow's o-r-o) is real evidence.
     */
    private fun simplify(points: List<Point>): List<Point> {
        val keep = BooleanArray(points.size)
        keep[0] = true
        keep[points.lastIndex] = true
        val pending = ArrayDeque<Pair<Int, Int>>()
        pending.addLast(0 to points.lastIndex)
        while (pending.isNotEmpty()) {
            val (first, last) = pending.removeLast()
            var furthest = -1
            var error = .14
            val axisLength = distance(points[first], points[last])
            var greatestProgress = 0.0
            var greatestIndex = first
            for (i in first + 1 until last) {
                val candidate = segmentDistance(points[i], points[first], points[last])
                if (candidate > error) { error = candidate; furthest = i }
                if (axisLength > 0.0) {
                    val progress = ((points[i].x - points[first].x) * (points[last].x - points[first].x) +
                        (points[i].y - points[first].y) * (points[last].y - points[first].y)) / axisLength
                    val reversal = greatestProgress - progress
                    if (reversal > error && greatestIndex > first) {
                        error = reversal
                        furthest = greatestIndex
                    }
                    if (progress > greatestProgress) {
                        greatestProgress = progress
                        greatestIndex = i
                    }
                }
            }
            if (furthest >= 0) {
                keep[furthest] = true
                pending.addLast(first to furthest)
                pending.addLast(furthest to last)
            }
        }
        return points.filterIndexed { index, _ -> keep[index] }
    }

    private fun resample(points: List<Point>, count: Int): List<Point> {
        val lengths = DoubleArray(points.size)
        for (i in 1 until points.size) lengths[i] = lengths[i - 1] + distance(points[i - 1], points[i])
        val total = lengths.last()
        var segment = 1
        return List(count) { index ->
            val target = total * index / (count - 1)
            while (segment < points.lastIndex && lengths[segment] < target) segment++
            val span = lengths[segment] - lengths[segment - 1]
            val fraction = if (span > 0.0) (target - lengths[segment - 1]) / span else 0.0
            Point(points[segment - 1].x + fraction * (points[segment].x - points[segment - 1].x),
                points[segment - 1].y + fraction * (points[segment].y - points[segment - 1].y))
        }
    }

    private fun pathLength(points: List<Point>) = points.zipWithNext().sumOf { (a, b) -> distance(a, b) }
    private fun distance(a: Point, b: Point) = hypot(a.x - b.x, a.y - b.y)
    private fun squaredDistance(a: Point, b: Point) = (a.x - b.x) * (a.x - b.x) + (a.y - b.y) * (a.y - b.y)
    private fun segmentDistance(point: Point, a: Point, b: Point): Double {
        val lengthSquared = squaredDistance(a, b)
        if (lengthSquared == 0.0) return distance(point, a)
        val t = (((point.x - a.x) * (b.x - a.x) + (point.y - a.y) * (b.y - a.y)) / lengthSquared).coerceIn(0.0, 1.0)
        return distance(point, Point(a.x + t * (b.x - a.x), a.y + t * (b.y - a.y)))
    }

    private companion object {
        const val MAX_VOCABULARY = 150_000
        const val MAX_WORD_LENGTH = 32
        const val MAX_INPUT_POINTS = 2048
        const val MAX_KEYS = 64
        const val MAX_EXAMINED = 8192
        const val MAX_SHORTLIST = 128
        const val MAX_RESULTS = 10
        const val CACHE_SIZE = 192
        const val SAMPLE_COUNT = 48
        const val COARSE_SAMPLE_COUNT = 16
        const val DTW_BAND = 12
        const val ENDPOINT_RADIUS = 1.35
        const val MAX_PATH_LENGTH = 180.0
        const val MAX_ORDERED_DISTANCE = .72
        val STATIC_NEXT = mapOf(
            "hello" to setOf("world", "there"), "good" to setOf("morning", "night", "luck"),
            "thank" to setOf("you"), "see" to setOf("you"), "how" to setOf("are"),
            "i" to setOf("am", "have", "will"), "you" to setOf("are"), "talk" to setOf("soon"),
        )
    }
}
