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
 * 前文参考取自当前书籍已缓存的章节；疑似同源分组只在同一章节（按章节序号/纯章节名）之间进行。
 */
class ChangeSourceQualityTracker(
    private val oldBook: () -> Book?,
    private val fromReadBookActivity: () -> Boolean
) {

    private class Entry(
        val origin: String,
        val groupKey: String,
        val fingerprint: ChapterQuality.Fingerprint,
        val report: ChapterQuality.Report
    )

    private class Reference(
        val profile: ChapterQuality.BookProfile?,
        val chapters: List<Pair<BookChapter, ChapterQuality.Fingerprint>>
    )

    private val entries = ConcurrentHashMap<String, Entry>()
    private val version = AtomicInteger()

    @Volatile
    private var groupCache: Pair<Int, Map<String, String>>? = null

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
            report = ChapterQuality.evaluate(
                stats = ChapterQuality.analyzeText(content),
                continuity = ref.profile?.continuity(content),
                misplacedChapter = misplaced
            )
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

    fun report(key: String): ChapterQuality.Report? = entries[key]?.report

    /**
     * 疑似同源分组：抽检章节内容相同的书源归为一组。
     * @return 书源 key → 组 id，只包含成员数不少于 2 的组
     */
    fun sameSourceGroups(): Map<String, String> {
        val current = version.get()
        groupCache?.let { (cachedVersion, groups) ->
            if (cachedVersion == current) return groups
        }
        val groups = HashMap<String, String>()
        HashMap(entries).entries.groupBy { it.value.groupKey }.forEach { (chapterKey, chapterEntries) ->
            val ordered = chapterEntries
                .sortedWith(compareByDescending<Map.Entry<String, Entry>> { it.value.report.score }.thenBy { it.key })
            ChapterQuality.sameContentGroups(ordered.associateTo(LinkedHashMap()) { it.key to it.value.fingerprint })
                .forEach { members ->
                    val groupId = chapterKey + "|" + members.min()
                    members.forEach { groups[it] = groupId }
                }
        }
        groupCache = current to groups
        return groups
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

/** 换源列表中疑似同源组的展示状态 */
class SourceGroupItem(
    val groupId: String,
    val size: Int,
    val isHead: Boolean,
    val expanded: Boolean
)
