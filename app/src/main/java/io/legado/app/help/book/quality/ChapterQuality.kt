package io.legado.app.help.book.quality

import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 章节正文质量检测，纯 JVM 实现，便于单测。
 *
 * 评分信号：
 * 1. 单章文本特征：长度、乱码、重复段落、广告行、防盗占位文案；
 * 2. 与本书前文的连续性：前文高频专有词在本章的命中率，以及是否与本书其他章节重复（错位）。
 *
 * 多源比对只用于把内容相同的书源归为疑似同源组，不参与评分：
 * 很多书源转载自同一站点，错误文本也一模一样，按多数投票会把错误内容判为可信。
 */
object ChapterQuality {

    private const val SIGNATURE_SIZE = 64
    private const val SHINGLE_SIZE = 3

    /** 同一章不同来源的正文相似度一般远高于此值，不同章节一般远低于此值 */
    const val SAME_CONTENT_SIMILARITY = 0.3

    /** 与本书另一章节相似度超过此值视为错位重复 */
    const val MISPLACED_SIMILARITY = 0.5

    private val seeds = LongArray(SIGNATURE_SIZE) { mix64(0x9E3779B97F4A7C15uL.toLong() * (it + 1)) }

    class Fingerprint internal constructor(
        internal val signature: LongArray,
        val effectiveLength: Int
    ) {
        val isEmpty: Boolean get() = effectiveLength < SHINGLE_SIZE

        /** MinHash 估计的 3-gram Jaccard 相似度 */
        fun similarity(other: Fingerprint): Double {
            if (isEmpty || other.isEmpty) return 0.0
            var same = 0
            for (i in 0 until SIGNATURE_SIZE) {
                if (signature[i] == other.signature[i]) same++
            }
            return same.toDouble() / SIGNATURE_SIZE
        }
    }

    class TextStats(
        val effectiveLength: Int,
        val garbledRatio: Double,
        val duplicateLineRatio: Double,
        val adLineRatio: Double,
        val placeholder: Boolean
    )

    class Report(val score: Int, val issues: List<String>) {
        val summary: String
            get() = buildString {
                append("质量：").append(score)
                if (issues.isNotEmpty()) {
                    append('\n').append(issues.joinToString("；"))
                }
            }
    }

    fun fingerprint(content: String): Fingerprint {
        val chars = normalize(content)
        val signature = LongArray(SIGNATURE_SIZE) { Long.MAX_VALUE }
        if (chars.length >= SHINGLE_SIZE) {
            for (i in 0..chars.length - SHINGLE_SIZE) {
                var h = 1125899906842597L
                for (j in i until i + SHINGLE_SIZE) {
                    h = 31 * h + chars[j].code
                }
                for (k in 0 until SIGNATURE_SIZE) {
                    val v = mix64(h xor seeds[k])
                    if (v < signature[k]) signature[k] = v
                }
            }
        }
        return Fingerprint(signature, chars.length)
    }

    private val adLineRegex = Regex(
        "(https?://|www\\.|\\.(com|net|org|cc|cn|la|info|xyz|top)\\b|请收藏|收藏本站|加入书签|" +
                "最新章节|最快更新|手机阅读|手机用户请|笔趣阁|百度搜索|天才一秒|一秒记住|请记住本|" +
                "本章未完|点击下一页|继续阅读|免费阅读|无弹窗|全文阅读|求月票|求推荐票|求订阅|" +
                "請收藏|最新章節|手機閱讀|筆趣閣|請記住|點擊下一頁|免費閱讀)",
        RegexOption.IGNORE_CASE
    )

    private val placeholderRegex = Regex(
        "(正在手打|手打中|章节内容正在|内容正在更新|内容更新中|稍后刷新|稍后再看|请稍后|" +
                "防盗章节|防盗章|本章为防盗|替换中|内容加载失败|暂无内容|章節內容正在|請稍後|防盜章)"
    )

    fun analyzeText(content: String): TextStats {
        var effective = 0
        var garbled = 0
        content.forEach { c ->
            when {
                Character.isLetterOrDigit(c) -> {
                    effective++
                    if (c in '\uE000'..'\uF8FF') garbled++
                }

                c == '\uFFFD' || c == '□' || c == '■' || c in '\uE000'..'\uF8FF' -> garbled++
                Character.isISOControl(c) && c != '\n' && c != '\r' && c != '\t' -> garbled++
            }
        }
        val lines = content.lineSequence()
            .map { it.trim { c -> c.isWhitespace() || c == '\u3000' } }
            .filter { it.isNotEmpty() }
            .toList()
        val seen = HashSet<String>()
        var longLineChars = 0
        var duplicateChars = 0
        var adLines = 0
        lines.forEach { line ->
            if (adLineRegex.containsMatchIn(line) && line.length < 80) adLines++
            val key = normalize(line)
            if (key.length >= 8) {
                longLineChars += key.length
                if (!seen.add(key)) duplicateChars += key.length
            }
        }
        val total = max(1, effective + garbled)
        return TextStats(
            effectiveLength = effective,
            garbledRatio = garbled.toDouble() / total,
            duplicateLineRatio = if (longLineChars == 0) 0.0 else duplicateChars.toDouble() / longLineChars,
            adLineRatio = if (lines.isEmpty()) 0.0 else adLines.toDouble() / lines.size,
            placeholder = effective < 1500 && placeholderRegex.containsMatchIn(content)
        )
    }

    /**
     * 把同一章节的候选正文按内容相同（疑似同源转载）聚类，相似关系可传递。
     * 只返回成员数不少于 2 的组，组内顺序与输入顺序一致。
     */
    fun <K> sameContentGroups(
        fingerprints: Map<K, Fingerprint>,
        threshold: Double = SAME_CONTENT_SIMILARITY
    ): List<List<K>> {
        val keys = fingerprints.keys.filter { !fingerprints.getValue(it).isEmpty }
        if (keys.size < 2) return emptyList()
        val parent = IntArray(keys.size) { it }
        fun find(i: Int): Int {
            var x = i
            while (parent[x] != x) {
                parent[x] = parent[parent[x]]
                x = parent[x]
            }
            return x
        }
        for (i in keys.indices) {
            for (j in i + 1 until keys.size) {
                if (find(i) == find(j)) continue
                if (fingerprints.getValue(keys[i]).similarity(fingerprints.getValue(keys[j])) >= threshold) {
                    parent[find(j)] = find(i)
                }
            }
        }
        return keys.indices.groupBy { find(it) }.values
            .filter { it.size >= 2 }
            .map { group -> group.map { keys[it] } }
    }

    /**
     * @param continuity 与本书前文的连续性，0~1，越接近 1 越像同一本书；null 表示没有前文可参考
     * @param misplacedChapter 与本书另一章节重复时，该章节的标题
     */
    fun evaluate(
        stats: TextStats,
        continuity: Double? = null,
        misplacedChapter: String? = null
    ): Report {
        var score = 100.0
        val issues = arrayListOf<String>()
        if (stats.placeholder) {
            score -= 70
            issues.add("疑似防盗/占位章节")
        }
        when {
            stats.effectiveLength < 300 -> {
                score -= 40
                issues.add("正文过短")
            }

            stats.effectiveLength < 800 -> {
                score -= 15
                issues.add("正文偏短")
            }
        }
        if (stats.garbledRatio > 0.01) {
            score -= min(50.0, stats.garbledRatio * 1000)
            issues.add("含乱码")
        }
        if (stats.duplicateLineRatio > 0.15) {
            score -= min(40.0, stats.duplicateLineRatio * 80)
            issues.add("段落重复")
        }
        if (stats.adLineRatio > 0.03) {
            score -= min(20.0, stats.adLineRatio * 200)
            issues.add("广告较多")
        }
        if (continuity != null && continuity < 0.5) {
            score -= min(50.0, (0.5 - continuity) * 120)
            issues.add("与前文关联度低，可能不是本书内容")
        }
        if (misplacedChapter != null) {
            score -= 60
            issues.add("与「$misplacedChapter」内容重复")
        }
        return Report(score.coerceIn(0.0, 100.0).roundToInt(), issues)
    }

    /**
     * 只保留文字和数字，去掉空白、标点，用于指纹和重复判断，
     * 避免不同书源排版、标点差异影响比对。
     */
    internal fun normalize(text: String): String {
        val sb = StringBuilder(text.length)
        text.forEach { c ->
            if (Character.isLetterOrDigit(c) && c !in '\uE000'..'\uF8FF') {
                sb.append(c.lowercaseChar())
            }
        }
        return sb.toString()
    }

    private fun mix64(value: Long): Long {
        var z = value
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
        return z xor (z ushr 31)
    }

    /**
     * 本书前文特征：从已缓存章节中提取高频、跨章节反复出现的词（多为人名、地名、专有名词），
     * 再看新章节命中多少。使用留一法估计本书自身的命中率基线，得到相对连续性。
     */
    class BookProfile private constructor(
        private val terms: Map<String, Double>,
        private val baseline: Double
    ) {

        /** 返回 0~1 的相对连续性 */
        fun continuity(content: String): Double {
            val hit = hitRate(terms, content)
            return if (baseline <= 0.0) hit else min(1.0, hit / baseline)
        }

        companion object {
            private const val MAX_TERMS = 80

            fun build(references: List<String>): BookProfile? {
                val counts = references.filter { it.length >= 200 }.map { termCounts(it) }
                if (counts.size < 2) return null
                val terms = selectTerms(counts)
                if (terms.size < 10) return null
                val baseline = if (counts.size >= 3) {
                    val samples = counts.indices.map { i ->
                        val others = counts.filterIndexed { j, _ -> j != i }
                        hitRate(selectTerms(others), counts[i].keys)
                    }.sorted()
                    samples[samples.size / 2]
                } else {
                    0.0
                }
                return BookProfile(terms, baseline)
            }

            private fun selectTerms(counts: List<Map<String, Int>>): Map<String, Double> {
                val tf = HashMap<String, Int>()
                val df = HashMap<String, Int>()
                counts.forEach { map ->
                    map.forEach { (term, n) ->
                        tf[term] = (tf[term] ?: 0) + n
                        df[term] = (df[term] ?: 0) + 1
                    }
                }
                val minDf = min(counts.size, max(2, (counts.size + 1) / 2))
                return tf.entries
                    .filter { (term, n) -> n >= 3 && (df[term] ?: 0) >= minDf }
                    .map { (term, n) -> term to ln(1.0 + n) * (df[term] ?: 0) }
                    .sortedByDescending { it.second }
                    .take(MAX_TERMS)
                    .toMap()
            }

            private fun hitRate(terms: Map<String, Double>, content: String): Double {
                return hitRate(terms, termCounts(content).keys)
            }

            private fun hitRate(terms: Map<String, Double>, present: Set<String>): Double {
                val total = terms.values.sum()
                if (total <= 0.0) return 0.0
                return terms.entries.sumOf { (term, w) -> if (term in present) w else 0.0 } / total
            }

            private fun termCounts(text: String): Map<String, Int> {
                val map = HashMap<String, Int>()
                val run = StringBuilder()
                fun flush() {
                    for (n in 2..3) {
                        for (i in 0..run.length - n) {
                            val term = run.substring(i, i + n)
                            if (term.any { it !in commonChars }) {
                                map[term] = (map[term] ?: 0) + 1
                            }
                        }
                    }
                    run.setLength(0)
                }
                text.forEach { c ->
                    if (c in '\u4E00'..'\u9FFF') run.append(c) else if (run.isNotEmpty()) flush()
                }
                if (run.isNotEmpty()) flush()
                return map
            }

            /** 虚词、代词、数词等高频字，只由这些字组成的词不具备区分度 */
            private val commonChars: Set<Char> = (
                    "的一是不了在人有我他这个们中来上大为和到以说时要就出会可也你对能而子那得于着下自之过后里" +
                            "道所然家事成多么去都当没起看还好小些样心她前开但因只从想已与把此两间问很最并手什等或己" +
                            "身见又知将点长向头被话回老给正现无再听儿声便却叫走吗呢吧啊呀哪谁怎让面用发经比口眼才" +
                            "這個們來為說時會對於著過後裡沒還麼從與兩間問見頭話給現無聽兒聲卻嗎讓發經" +
                            "曰乃矣乎焉哉其者亦遂皆即耳兮" +
                            "零〇一二两三四五六七八九十百千万亿第"
                    ).toSet()
        }
    }
}
