package com.earl.cleanlisten

/**
 * Port of the Mac CleanListen matcher: whole-word exact entries, "stem*"
 * prefix entries, and multi-word phrases. Normalization strips everything
 * outside a-z, so "Damn," -> "damn". No substring matching ("ass" never
 * fires on "class").
 */
class Wordlist(text: String, tier: Int) {
    val exact = HashSet<String>()
    val prefixes = ArrayList<String>()
    val phrases = ArrayList<List<Pair<String, Boolean>>>()
    val maxPhrase: Int

    init {
        var current = 2
        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val sec = Regex("""\[tier([123])\]""").matchEntire(line)
            if (sec != null) { current = sec.groupValues[1].toInt(); continue }
            if (current > tier) continue
            val toks = line.split(Regex("\\s+")).map { normalize(it) to it.endsWith("*") }
            if (toks.any { it.first.isEmpty() }) continue
            if (toks.size > 1) phrases.add(toks)
            else if (toks[0].second) prefixes.add(toks[0].first) else exact.add(toks[0].first)
        }
        maxPhrase = maxOf(1, phrases.maxOfOrNull { it.size } ?: 1)
    }

    companion object {
        fun normalize(s: String) = s.lowercase().replace(Regex("[^a-z]+"), "")
    }
}

data class Word(val text: String, val start: Double, val end: Double)

/** Returns (start, end) seconds spans to mute for a list of words in order. */
fun findMatches(list: Wordlist, words: List<Word>): List<Pair<Double, Double>> {
    val out = ArrayList<Pair<Double, Double>>()
    val norms = words.map { Wordlist.normalize(it.text) }
    for (i in words.indices) {
        val n = norms[i]
        if (n.isEmpty()) continue
        if (n in list.exact || list.prefixes.any { n.startsWith(it) }) {
            out.add(words[i].start to words[i].end)
        }
        for (ph in list.phrases) {
            val k = ph.size
            if (i + 1 < k) continue
            var ok = true
            for (j in 0 until k) {
                val w = norms[i - k + 1 + j]
                val (stem, pre) = ph[j]
                if (!(if (pre) w.startsWith(stem) else w == stem)) { ok = false; break }
            }
            if (ok) out.add(words[i - k + 1].start to words[i].end)
        }
    }
    return out
}
