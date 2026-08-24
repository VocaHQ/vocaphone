package com.vocahq.vocaphone.ime

/**
 * Geometry and scoring for swipe / glide typing.
 *
 * The previous matcher turned the finger into a string of keys it entered,
 * then compared that string's key centres to each word's key centres. That
 * throws away the curve the finger actually drew, which is the signal every
 * engine that feels good uses. This one keeps the points.
 *
 * Compared with five open-source keyboards that do this well:
 *
 * - **FlorisBoard** (`StatisticalGlideTypingClassifier`): prune by the two
 *   nearest keys to the start and end, prune by path length, resample the
 *   real gesture and the ideal word path, score bounding-box-normalised
 *   *shape* plus un-normalised *location*, then multiply by frequency.
 * - **AnySoftKeyboard** (`GestureTypingDetector`): keep curvature corners
 *   rather than every sample, penalise direction mismatch, and treat the
 *   start key as a hard-ish proximity filter with a softer end.
 * - **OpenSwipe** (HeliBoard / LeanType): 16-point DTW with a Sakoe-Chiba
 *   band, LB-style early outs, and a "does this letter lie on the path"
 *   check so fly-over keys do not have to be in the word.
 * - **AOSP LatinIME / HeliBoard**: the good decoder is the closed
 *   `libjni_latinimegoogle.so`; the open idea we keep is start/end anchors
 *   plus a language-model (frequency) tie-break, not a second word list.
 * - **FUTO / Slide**: a neural spatial model. Out of scope here — we stay
 *   on-device, allocation-light, and inside the existing 10k-word scan.
 *
 * Higher [Gesture.score] is better. The weights are tuned so the existing
 * letter-string tests still pick the same winners; real traces from the
 * keyboard supply denser [points] and get the DTW / shape terms for free.
 */
internal data class SwipeInput(
    val keys: String,
    val points: FloatArray = FloatArray(0),
    val previousWord: String = "",
)

internal object SwipeLayout {
    data class XY(val x: Float, val y: Float) {
        fun distanceTo(other: XY): Float {
            val dx = x - other.x
            val dy = y - other.y
            return kotlin.math.sqrt(dx * dx + dy * dy)
        }
    }

    const val SAMPLE_POINTS = 16

    /** Sakoe-Chiba band used by OpenSwipe; keeps DTW linear in the sample count. */
    private const val DTW_BAND = 3

    /**
     * How far a finger can sit from a key centre, in key units, and still
     * count as having meant that letter. ~0.85 is inside the next key's
     * edge without swallowing a whole extra column.
     */
    const val LETTER_ON_PATH_RADIUS = 0.85f

    /** Neighbour radius used for tap corrections and swipe end-key slack. */
    const val NEIGHBOUR_RADIUS = 1.55f

    /**
     * Grid travel from the press that turns a tap into a swipe. Half a key
     * is still a targeting correction; past this it is a gesture. FlorisBoard
     * uses a quarter-key as the *sample* step, not the activation.
     */
    const val ACTIVATION_DISTANCE = 0.6f

    const val MAX_TRACE_POINTS = 96

    val QWERTY: Map<Char, XY> = buildMap {
        fun row(y: Float, startX: Float, letters: String) {
            letters.forEachIndexed { index, letter -> put(letter, XY(startX + index, y)) }
        }
        row(0f, 0f, "qwertyuiop")
        row(1f, 0.5f, "asdfghjkl")
        row(2f, 1.5f, "zxcvbnm")
    }

    val NEARBY: Map<Char, Set<Char>> = QWERTY.mapValues { (letter, origin) ->
        QWERTY.entries
            .filter { (other, point) -> other != letter && origin.distanceTo(point) < NEIGHBOUR_RADIUS }
            .map { it.key }
            .toSet()
    }

    private val REACH: IntArray = IntArray(26) { offset ->
        val letter = 'a' + offset
        letterBits(NEARBY[letter].orEmpty() + letter)
    }

    fun nearby(letter: Char): Set<Char> = NEARBY[letter].orEmpty()

    fun qwerty(letter: Char): XY? = QWERTY[letter]

    /**
     * Map a touch that landed [nx], [ny] key-widths from [letter]'s centre
     * into the same unit grid the matcher scores in. A finger that cut the
     * corner of H toward Y is (5.5, 1) + a fraction, not "h" or "y".
     */
    fun gridPoint(letter: Char, nx: Float, ny: Float): XY? {
        val origin = QWERTY[letter] ?: return null
        return XY(origin.x + nx, origin.y + ny)
    }

    fun reachMask(letter: Char): Int {
        val offset = letter - 'a'
        return if (offset in 0..25) REACH[offset] else 0
    }

    fun pathLength(keys: String): Float = polylineLength(keyCentres(keys))

    fun pathLength(points: FloatArray): Float {
        val n = points.size / 2
        if (n < 2) return 0f
        var length = 0f
        var i = 2
        while (i < points.size) {
            val dx = points[i] - points[i - 2]
            val dy = points[i + 1] - points[i - 1]
            length += kotlin.math.sqrt(dx * dx + dy * dy)
            i += 2
        }
        return length
    }

    fun sampleWord(word: String): FloatArray = samplePolyline(keyCentres(word, loops = false))

    fun sampleWordWithLoops(word: String): FloatArray = samplePolyline(keyCentres(word, loops = true))

    fun samplePoints(points: FloatArray): FloatArray {
        val n = points.size / 2
        if (n == 0) return FloatArray(0)
        val poly = ArrayList<XY>(n)
        var i = 0
        while (i < points.size - 1) {
            poly.add(XY(points[i], points[i + 1]))
            i += 2
        }
        return samplePolyline(poly)
    }

    /**
     * Evenly spaced samples along the straight lines through [letters], the
     * way a careful finger would move. Tests and the letter-string fallback
     * use this so a path of "hello" is a curve, not the six key centres.
     */
    fun interpolate(letters: String, stepsPerSegment: Int = 8): FloatArray {
        val centres = keyCentres(letters, loops = false)
        if (centres.size <= 1) {
            val point = centres.firstOrNull() ?: return FloatArray(0)
            val out = FloatArray(2)
            out[0] = point.x
            out[1] = point.y
            return out
        }
        val out = FloatArray((centres.size - 1) * stepsPerSegment * 2 + 2)
        var w = 0
        for (index in 1 until centres.size) {
            val from = centres[index - 1]
            val to = centres[index]
            for (step in 0 until stepsPerSegment) {
                val t = step / stepsPerSegment.toFloat()
                out[w++] = from.x + (to.x - from.x) * t
                out[w++] = from.y + (to.y - from.y) * t
            }
        }
        val last = centres.last()
        out[w++] = last.x
        out[w] = last.y
        return out
    }

    fun hasDoubleLetter(word: String): Boolean {
        var previous = 0.toChar()
        for (character in word) {
            val letter = character.lowercaseChar()
            if (letter == previous) return true
            if (letter in 'a'..'z') previous = letter
        }
        return false
    }

    /**
     * Every letter of [word] (collapsed) lies near some later segment of the
     * sampled path, in order. OpenSwipe's sequence match: a fly-over does
     * not have to *enter* the key, and a word that needs a key the finger
     * never approached is out.
     */
    fun lettersLieOnPath(word: String, samples: FloatArray, radius: Float = LETTER_ON_PATH_RADIUS): Boolean {
        val n = samples.size / 2
        if (n == 0) return false
        var segment = 0
        var last = 0.toChar()
        for (character in word) {
            if (character !in 'a'..'z' || character == last) continue
            last = character
            val target = QWERTY[character] ?: continue
            var found = false
            while (segment < n - 1) {
                if (distanceToSegment(target, samples, segment) <= radius) {
                    found = true
                    break
                }
                segment++
            }
            if (!found) {
                // Last point itself, for a one-sample leftover.
                val lastX = samples[samples.size - 2]
                val lastY = samples[samples.size - 1]
                if (target.distanceTo(XY(lastX, lastY)) > radius) return false
            }
        }
        return true
    }

    private fun keyCentres(word: String, loops: Boolean = false): List<XY> {
        val points = ArrayList<XY>(word.length + 4)
        var previous = 0.toChar()
        for (character in word) {
            val letter = character.lowercaseChar()
            val centre = QWERTY[letter] ?: continue
            if (loops && letter == previous) {
                points.add(XY(centre.x + 0.25f, centre.y + 0.25f))
                points.add(XY(centre.x + 0.25f, centre.y - 0.25f))
                points.add(XY(centre.x - 0.25f, centre.y - 0.25f))
                points.add(XY(centre.x - 0.25f, centre.y + 0.25f))
            } else if (letter != previous) {
                points.add(centre)
            }
            previous = letter
        }
        return points
    }

    private fun polylineLength(points: List<XY>): Float {
        if (points.size < 2) return 0f
        var length = 0f
        for (index in 1 until points.size) length += points[index - 1].distanceTo(points[index])
        return length
    }

    fun samplePolyline(points: List<XY>): FloatArray {
        val count = SAMPLE_POINTS
        if (points.isEmpty()) return FloatArray(0)
        if (points.size == 1 || count <= 1) {
            val out = FloatArray(count * 2)
            val point = points.first()
            for (index in 0 until count) {
                out[index * 2] = point.x
                out[index * 2 + 1] = point.y
            }
            return out
        }
        val prefix = FloatArray(points.size)
        for (index in 1 until points.size) {
            prefix[index] = prefix[index - 1] + points[index - 1].distanceTo(points[index])
        }
        val total = prefix.last().coerceAtLeast(0.0001f)
        val out = FloatArray(count * 2)
        for (sample in 0 until count) {
            val target = total * sample / (count - 1)
            var index = 1
            while (index < prefix.lastIndex && prefix[index] < target) index++
            val start = prefix[index - 1]
            val span = (prefix[index] - start).coerceAtLeast(0.0001f)
            val t = ((target - start) / span).coerceIn(0f, 1f)
            val from = points[index - 1]
            val to = points[index]
            out[sample * 2] = from.x + (to.x - from.x) * t
            out[sample * 2 + 1] = from.y + (to.y - from.y) * t
        }
        return out
    }

    private fun distanceToSegment(point: XY, samples: FloatArray, segment: Int): Float {
        val ax = samples[segment * 2]
        val ay = samples[segment * 2 + 1]
        val bx = samples[segment * 2 + 2]
        val by = samples[segment * 2 + 3]
        val dx = bx - ax
        val dy = by - ay
        val lengthSq = dx * dx + dy * dy
        val t = if (lengthSq < 1e-9f) {
            0f
        } else {
            (((point.x - ax) * dx + (point.y - ay) * dy) / lengthSq).coerceIn(0f, 1f)
        }
        val closestX = ax + t * dx
        val closestY = ay + t * dy
        val ex = point.x - closestX
        val ey = point.y - closestY
        return kotlin.math.sqrt(ex * ex + ey * ey)
    }

    private fun letterBits(letters: Iterable<Char>): Int {
        var mask = 0
        for (letter in letters) mask = mask or SuggestionEngine.letterBit(letter)
        return mask
    }

    /**
     * One gesture, with the samples and length worked out once.
     *
     * Scoring used to resample the user's path per candidate. A ten thousand
     * word list did that ten thousand times; the gesture does not change.
     */
    internal class Gesture(
        val keys: String,
        points: FloatArray,
        val previousWord: String = "",
    ) {
        private val samples: FloatArray
        private val length: Float
        private val firstMask: Int
        private val lastMask: Int
        private val dtwCost = Array(SAMPLE_POINTS) { FloatArray(SAMPLE_POINTS) }

        init {
            val raw = if (points.size >= 4) points else interpolate(keys)
            samples = samplePoints(raw)
            length = pathLength(raw)
            firstMask = letterBits(nearby(keys.first()) + keys.first())
            lastMask = letterBits(nearby(keys.last()) + keys.last())
        }

        fun endsAreReachable(first: Char, last: Char): Boolean =
            SuggestionEngine.letterBit(first) and firstMask != 0 &&
                SuggestionEngine.letterBit(last) and lastMask != 0

        fun hasGeometry(): Boolean = samples.size == SAMPLE_POINTS * 2

        fun lettersOnPath(word: String): Boolean =
            samples.size >= 4 && lettersLieOnPath(word, samples)

        fun score(
            compactWord: String,
            originalWord: String,
            frequencyRank: Int,
            approximate: Boolean,
            predictedWord: Boolean,
            ideal: FloatArray,
            idealLength: Float,
        ): Float {
            var best = scoreAgainst(ideal, idealLength, compactWord, frequencyRank, approximate, predictedWord)
            // FlorisBoard scores both the collapsed path and a small loop on
            // doubled letters so "good" and "god" are not the same shape.
            if (hasDoubleLetter(originalWord)) {
                val looped = sampleWordWithLoops(originalWord)
                if (looped.size == samples.size) {
                    val withLoops = scoreAgainst(
                        looped,
                        pathLength(looped).coerceAtLeast(idealLength),
                        compactWord,
                        frequencyRank,
                        approximate,
                        predictedWord,
                    )
                    if (withLoops > best) best = withLoops
                }
            }
            return best
        }

        private fun scoreAgainst(
            ideal: FloatArray,
            idealLength: Float,
            compactWord: String,
            frequencyRank: Int,
            approximate: Boolean,
            predictedWord: Boolean,
        ): Float {
            if (samples.size != SAMPLE_POINTS * 2 || ideal.size != SAMPLE_POINTS * 2) {
                return Float.NEGATIVE_INFINITY
            }
            val location = meanDistance(samples, ideal)
            val shape = meanDistance(normalize(samples), normalize(ideal))
            val dtw = dtwNorm(samples, ideal)
            val lengthGap = kotlin.math.abs(length - idealLength)
            // Collinear words like "or" / "our" have the same path length
            // because U sits on the O→R line. The collapsed letter count still
            // differs, and that is what the finger actually spelled.
            val letterGap = kotlin.math.abs(compactWord.length - keys.length)
            val endPenalty =
                (if (compactWord.first() == keys.first()) 0f else 0.7f) +
                    (if (compactWord.last() == keys.last()) 0f else 0.7f)
            // Rank 0 → 1, rank 1500 → 0.5, rank 2420 ("hello") → 0.38. The old
            // /400 scale made anything past the first few hundred words a
            // rounding error against shape, so a careful "hello" lost to a
            // common word with a vaguely similar silhouette.
            val frequency = 1f / (1f + frequencyRank / 1500f)
            val nearby = if (approximate) NEARBY_KEY_PENALTY else 0f
            val context = if (predictedWord) 0.45f else 0f
            return frequency * 1.4f + context -
                dtw * 3.2f -
                location * 1.8f -
                shape * 1.2f -
                lengthGap * 0.4f -
                letterGap * 0.65f -
                endPenalty -
                nearby
        }

        private fun meanDistance(left: FloatArray, right: FloatArray): Float {
            if (left.size != right.size || left.isEmpty()) return Float.MAX_VALUE
            var sum = 0f
            var i = 0
            while (i < left.size) {
                val dx = left[i] - right[i]
                val dy = left[i + 1] - right[i + 1]
                sum += kotlin.math.sqrt(dx * dx + dy * dy)
                i += 2
            }
            return sum / (left.size / 2)
        }

        private fun normalize(points: FloatArray): FloatArray {
            var minX = Float.MAX_VALUE
            var minY = Float.MAX_VALUE
            var maxX = -Float.MAX_VALUE
            var maxY = -Float.MAX_VALUE
            var i = 0
            while (i < points.size) {
                val x = points[i]
                val y = points[i + 1]
                if (x < minX) minX = x
                if (y < minY) minY = y
                if (x > maxX) maxX = x
                if (y > maxY) maxY = y
                i += 2
            }
            val width = maxX - minX
            val height = maxY - minY
            val scale = maxOf(width, height, 0.0001f)
            val cx = (width / 2f + minX) / scale
            val cy = (height / 2f + minY) / scale
            val out = FloatArray(points.size)
            i = 0
            while (i < points.size) {
                out[i] = points[i] / scale - cx
                out[i + 1] = points[i + 1] / scale - cy
                i += 2
            }
            return out
        }

        private fun dtwNorm(left: FloatArray, right: FloatArray): Float {
            val n = SAMPLE_POINTS
            val band = DTW_BAND
            val cost = dtwCost
            for (row in 0 until n) {
                java.util.Arrays.fill(cost[row], Float.MAX_VALUE)
            }
            for (i in 0 until n) {
                val jMin = (i - band).coerceAtLeast(0)
                val jMax = (i + band).coerceAtMost(n - 1)
                val ix = left[i * 2]
                val iy = left[i * 2 + 1]
                for (j in jMin..jMax) {
                    val dx = ix - right[j * 2]
                    val dy = iy - right[j * 2 + 1]
                    val dist = dx * dx + dy * dy
                    val prev = when {
                        i == 0 && j == 0 -> 0f
                        else -> {
                            var best = Float.MAX_VALUE
                            if (i > 0) best = minOf(best, cost[i - 1][j])
                            if (j > 0) best = minOf(best, cost[i][j - 1])
                            if (i > 0 && j > 0) best = minOf(best, cost[i - 1][j - 1])
                            best
                        }
                    }
                    if (prev < Float.MAX_VALUE / 4f) cost[i][j] = dist + prev
                }
            }
            val total = cost[n - 1][n - 1]
            if (total >= Float.MAX_VALUE / 4f) return 8f
            val denom = (length + 0.001f)
            return kotlin.math.sqrt(total) / denom
        }
    }

    /**
     * How much worse a word is for having been matched through a neighbouring
     * key rather than the one the finger crossed. Same plateau the previous
     * matcher measured: 1–1.5; 1.5 keeps an exactly-spelled word in front.
     */
    private const val NEARBY_KEY_PENALTY = 1.5f
}
