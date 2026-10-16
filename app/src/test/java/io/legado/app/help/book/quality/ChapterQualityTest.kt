package io.legado.app.help.book.quality

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ChapterQualityTest {

    private val hanzi = "天地玄黄宇宙洪荒日月盈昃辰宿列张寒来暑往秋收冬藏闰余成岁律吕调阳云腾致雨露结为霜金生丽水玉出昆冈" +
            "剑号巨阙珠称夜光果珍李柰菜重芥姜海咸河淡鳞潜羽翔龙师火帝鸟官人皇始制文字乃服衣裳推位让国有虞陶唐"

    /** 生成一章伪正文，[names] 会在文中反复出现，模拟人名地名 */
    private fun chapter(seed: Int, names: List<String>, paragraphs: Int = 30): String {
        val rnd = Random(seed)
        return (0 until paragraphs).joinToString("\n") {
            val sb = StringBuilder("　　")
            repeat(rnd.nextInt(3, 6)) {
                if (rnd.nextInt(3) == 0) sb.append(names[rnd.nextInt(names.size)])
                repeat(rnd.nextInt(8, 16)) { sb.append(hanzi[rnd.nextInt(hanzi.length)]) }
                sb.append(if (rnd.nextBoolean()) "，" else "。")
            }
            sb.toString()
        }
    }

    private val bookA = listOf("林动", "绫清竹", "青檀", "炎城", "小貂", "岩大师")
    private val bookB = listOf("萧炎", "药尘", "纳兰嫣然", "乌坦城", "云岚宗", "美杜莎")

    @Test
    fun normalChapterScoresHigh() {
        val report = ChapterQuality.evaluate(ChapterQuality.analyzeText(chapter(1, bookA)))
        assertEquals(100, report.score)
        assertTrue(report.issues.isEmpty())
    }

    @Test
    fun placeholderChapterIsPenalized() {
        val stats = ChapterQuality.analyzeText("　　本章节内容正在手打中，请稍后刷新阅读。\n　　请收藏本站 www.example.com")
        assertTrue(stats.placeholder)
        val report = ChapterQuality.evaluate(stats)
        assertTrue(report.score < 30)
    }

    @Test
    fun garbledAndDuplicatedTextIsPenalized() {
        val garbled = chapter(2, bookA).map { c ->
            if (c != '\n' && c.code % 7 == 0) '\uE123' else c
        }.joinToString("")
        assertTrue(ChapterQuality.analyzeText(garbled).garbledRatio > 0.05)
        assertTrue(ChapterQuality.evaluate(ChapterQuality.analyzeText(garbled)).score < 60)

        val half = chapter(3, bookA, paragraphs = 15)
        val stats = ChapterQuality.analyzeText(half + "\n" + half)
        assertTrue(stats.duplicateLineRatio > 0.4)
        assertTrue(ChapterQuality.evaluate(stats).issues.contains("段落重复"))
    }

    @Test
    fun fingerprintToleratesFormattingButSeparatesChapters() {
        val original = chapter(4, bookA)
        val reformatted = original.replace("，", ",").replace("　　", "  ") +
                "\n请收藏本站最新章节 www.example.com"
        val a = ChapterQuality.fingerprint(original)
        assertTrue(a.similarity(ChapterQuality.fingerprint(reformatted)) > 0.8)
        assertTrue(a.similarity(ChapterQuality.fingerprint(chapter(5, bookA))) < 0.1)
    }

    @Test
    fun sameContentSourcesAreGrouped() {
        val copied = chapter(6, bookA)
        val another = chapter(7, bookA)
        val fingerprints = linkedMapOf(
            "s1" to ChapterQuality.fingerprint(copied),
            "x1" to ChapterQuality.fingerprint(another),
            "s2" to ChapterQuality.fingerprint(copied.replace("。", ".")),
            "lone" to ChapterQuality.fingerprint(chapter(8, bookA)),
            "s3" to ChapterQuality.fingerprint(copied + "\n求月票"),
            "x2" to ChapterQuality.fingerprint(another.replace("，", ",")),
            "empty" to ChapterQuality.fingerprint("")
        )
        val groups = ChapterQuality.sameContentGroups(fingerprints).map { it.toSet() }.toSet()
        assertEquals(setOf(setOf("s1", "s2", "s3"), setOf("x1", "x2")), groups)
        assertTrue(ChapterQuality.sameContentGroups(mapOf("a" to ChapterQuality.fingerprint(copied))).isEmpty())
    }

    @Test
    fun sameContentGroupIsTransitive() {
        val base = chapter(9, bookA, paragraphs = 40).lines()
        val a = base.subList(0, 30).joinToString("\n")
        val b = base.subList(5, 35).joinToString("\n")
        val c = base.subList(10, 40).joinToString("\n")
        val fa = ChapterQuality.fingerprint(a)
        val fc = ChapterQuality.fingerprint(c)
        val groups = ChapterQuality.sameContentGroups(
            mapOf("a" to fa, "b" to ChapterQuality.fingerprint(b), "c" to fc),
            threshold = 0.5
        )
        assertTrue(fa.similarity(fc) < 0.5)
        assertEquals(listOf(setOf("a", "b", "c")), groups.map { it.toSet() })
    }

    @Test
    fun profileDetectsChapterFromAnotherBook() {
        val profile = ChapterQuality.BookProfile.build((10..15).map { chapter(it, bookA) })
        assertNotNull(profile)
        val sameBook = profile!!.continuity(chapter(16, bookA))
        val otherBook = profile.continuity(chapter(17, bookB))
        assertTrue("sameBook=$sameBook", sameBook > 0.7)
        assertTrue("otherBook=$otherBook", otherBook < 0.3)

        val report = ChapterQuality.evaluate(
            ChapterQuality.analyzeText(chapter(17, bookB)),
            continuity = otherBook
        )
        assertTrue(report.issues.any { it.startsWith("与前文关联度低") })
    }

    @Test
    fun profileNeedsEnoughReferences() {
        assertNull(ChapterQuality.BookProfile.build(listOf(chapter(18, bookA))))
        assertNull(ChapterQuality.BookProfile.build(emptyList()))
    }

    @Test
    fun misplacedChapterIsReported() {
        val report = ChapterQuality.evaluate(
            ChapterQuality.analyzeText(chapter(19, bookA)),
            misplacedChapter = "第998章 旧账"
        )
        assertTrue(report.score <= 40)
        assertTrue(report.summary.contains("第998章 旧账"))
    }
}
