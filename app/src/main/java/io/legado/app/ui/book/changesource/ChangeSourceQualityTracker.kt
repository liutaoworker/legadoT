package io.legado.app.ui.book.changesource

import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.quality.ChapterQuality
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.math.min

/**
 * 换源时各书源抽检章节的正文质量。
 * 前文参考取自当前书籍已缓存的章节，多源比对只在同一章节（按章节序号/纯章节名分组）之间进行。
 */
class ChangeSourceQualityTracker(
    private val oldBook: () -> Book?,
    private val fromReadBookActivity: () -> Boolean
) {

    private class Entry(
        val origin: String,
        val groupKey: String,
        val fingerprint: ChapterQuality.Fingerprint,
        val stats: ChapterQuality.TextStats,
        val continuity: Double?,
        val misplacedChapter: String?
    )

    private class Reference(
        val profile: ChapterQuality.BookProfile?,
        val chapters: List<Pair<BookChapter, ChapterQuality.Fingerprint>>
    )

    private val entries = ConcurrentHashMap<String, Entry>()
    private val version = AtomicInteger()

    @Volatile
    private var cache: Pair<Int, Map<String, ChapterQuality.Report>>? = null

    private val reference by lazy { loadReference() }

    fun add(key: String, origin: String, chapter: BookChapter, content: String) {
        val ref = reference
        val fingerprint = ChapterQuality.fingerprint(content)
        val chapterNum = BookHelp.getChapterNum(chapter.title)
        val misplaced = ref.chapters.firstOrNull { (refChapter, refFingerprint) ->
            !isSameChapter(refChapter, chapter, chapterNum) &&
                    refFingerprint.similarity(fingerprint) >= ChapterQuality.MISPLACED_SIMILARITY
        }?.first?.title
        entries[key] = Entry(
            origin = origin,
            groupKey = if (chapterNum > 0) "#$chapterNum" else BookHelp.getPureChapterName(chapter.title),
            fingerprint = fingerprint,
            stats = ChapterQuality.analyzeText(content),
            continuity = ref.profile?.continuity(content),
            misplacedChapter = misplaced
        )
        version.incrementAndGet()
    }

    fun remove(key: String) {
        if (entries.remove(key) != null) version.incrementAndGet()
    }

    fun removeOrigin(origin: String) {
        if (entries.values.removeIf { it.origin == origin }) version.incrementAndGet()
    }

    fun clear() {
        entries.clear()
        version.incrementAndGet()
    }

    fun contains(key: String): Boolean = entries.containsKey(key)

    fun report(key: String): ChapterQuality.Report? = reports()[key]

    private fun reports(): Map<String, ChapterQuality.Report> {
        val current = version.get()
        cache?.let { (cachedVersion, reports) ->
            if (cachedVersion == current) return reports
        }
        val snapshot = HashMap(entries)
        val consensus = HashMap<String, ChapterQuality.Consensus>()
        snapshot.entries.groupBy { it.value.groupKey }.values.forEach { group ->
            consensus.putAll(ChapterQuality.crossCheck(group.associate { it.key to it.value.fingerprint }))
        }
        val reports = snapshot.mapValues { (key, entry) ->
            ChapterQuality.evaluate(
                stats = entry.stats,
                continuity = entry.continuity,
                misplacedChapter = entry.misplacedChapter,
                consensus = consensus[key]
            )
        }
        cache = current to reports
        return reports
    }

    private fun isSameChapter(a: BookChapter, b: BookChapter, bNum: Int): Boolean {
        if (bNum > 0 && BookHelp.getChapterNum(a.title) == bNum) return true
        return BookHelp.getPureChapterName(a.title) == BookHelp.getPureChapterName(b.title)
    }

    private fun loadReference(): Reference {
        val book = oldBook() ?: return Reference(null, emptyList())
        val chapters = appDb.bookChapterDao.getChapterList(book.bookUrl)
        if (chapters.isEmpty()) return Reference(null, emptyList())
        val target = if (fromReadBookActivity()) {
            book.durChapterIndex.coerceIn(0, chapters.lastIndex)
        } else {
            chapters.lastIndex
        }
        val cached = (min(chapters.lastIndex, target + 5) downTo max(0, target - 30))
            .mapNotNull { index ->
                BookHelp.getContent(book, chapters[index])?.let { chapters[index] to it }
            }
        val profileTexts = cached
            .filter { it.first.index < target }
            .take(PROFILE_CHAPTERS)
            .map { it.second }
        return Reference(
            profile = ChapterQuality.BookProfile.build(profileTexts),
            chapters = cached.map { it.first to ChapterQuality.fingerprint(it.second) }
        )
    }

    companion object {
        private const val PROFILE_CHAPTERS = 8
    }
}
