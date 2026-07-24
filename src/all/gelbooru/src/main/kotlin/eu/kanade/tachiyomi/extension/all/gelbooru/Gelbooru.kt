package eu.kanade.tachiyomi.extension.all.gelbooru

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.util.concurrent.TimeUnit

class Gelbooru : HttpSource() {

    override val name = "Gelbooru"
    override val baseUrl = "https://gelbooru.com"
    override val lang = "all"
    override val supportsLatest = true

    override val client: OkHttpClient = network.client.newBuilder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val request = chain.request().newBuilder()
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.5")
                .build()
            chain.proceed(request)
        }
        .build()

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
        .add("Accept-Language", "en-US,en;q=0.5")

    // ─── Popular ────────────────────────────────────────────

    override suspend fun getPopularManga(page: Int): MangasPage {
        val pid = (page - 1) * 42
        val doc = fetchDocument("$baseUrl/index.php?page=post&s=list&pid=$pid")
        return parsePostList(doc)
    }

    // ─── Latest ─────────────────────────────────────────────

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val pid = (page - 1) * 42
        val doc = fetchDocument("$baseUrl/index.php?page=post&s=list&pid=$pid")
        return parsePostList(doc)
    }

    // ─── Search ─────────────────────────────────────────────

    override suspend fun getSearchManga(
        page: Int,
        query: String,
        filters: FilterList
    ): MangasPage {
        val tags = query.trim().replace(" ", "+")
        val pid = (page - 1) * 42
        val url = if (tags.isNotBlank()) {
            "$baseUrl/index.php?page=post&s=list&tags=$tags&pid=$pid"
        } else {
            "$baseUrl/index.php?page=post&s=list&pid=$pid"
        }
        val doc = fetchDocument(url)
        return parsePostList(doc)
    }

    // ─── Chapter list ───────────────────────────────────────

    override suspend fun getChapterList(manga: SManga, page: Int): List<SChapter> {
        return listOf(SChapter.create().apply {
            url = manga.url
            name = "Image"
            chapter_number = 1F
        })
    }

    // ─── Manga / chapter update ─────────────────────────────

    override suspend fun getMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean
    ): SMangaUpdate {
        val doc = fetchDocument(baseUrl + manga.url)
        val updatedManga = if (fetchDetails) parseMangaDetails(doc) else manga
        val updatedChapters = if (fetchChapters) {
            listOf(SChapter.create().apply {
                url = manga.url
                name = "Image"
                chapter_number = 1F
            })
        } else chapters
        return SMangaUpdate(updatedManga, updatedChapters)
    }

    private fun parseMangaDetails(doc: Document): SManga {
        return SManga.create().apply {
            val postId = extractPostId(doc.location())
            val tagLinks = doc.select("li.tag-list a")
            title = if (tagLinks.isNotEmpty()) {
                tagLinks.take(3).joinToString(", ") { it.text() }
            } else {
                "Post #$postId"
            }

            description = buildString {
                append("ID: $postId\n")
                append("Rating: ${doc.selectFirst("li.rating-info")?.text()?.replace("Rating:", "")?.trim()?.uppercase() ?: "?"}\n")
                val tagUl = doc.selectFirst("ul.tag-list")
                if (tagUl != null) {
                    for (li in tagUl.select("li")) {
                        val type = li.selectFirst("span.tag-type")?.text() ?: ""
                        val tag = li.selectFirst("a")?.text() ?: ""
                        if (tag.isNotBlank()) {
                            append("$type: $tag\n")
                        }
                    }
                }
            }

            val img = doc.selectFirst("#image")
            thumbnail_url = img?.attr("src")?.let { src ->
                when {
                    src.startsWith("//") -> "https:$src"
                    src.startsWith("/") -> "$baseUrl$src"
                    else -> src
                }
            }

            status = SManga.COMPLETED
            genre = tagLinks.joinToString(", ") { it.text() }
        }
    }

    // ─── Pages ──────────────────────────────────────────────

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val doc = fetchDocument(baseUrl + chapter.url)
        val img = doc.selectFirst("#image")
            ?: doc.selectFirst("img[src*=\"/images/\"]")
            ?: return emptyList()

        val src = img.attr("src").let { src ->
            when {
                src.startsWith("//") -> "https:$src"
                src.startsWith("/") -> "$baseUrl$src"
                else -> src
            }
        }

        return listOf(Page(0, imageUrl = src))
    }

    override suspend fun getImageUrl(page: Page): String = page.imageUrl ?: ""

    // ─── Filters ────────────────────────────────────────────

    override fun getFilterList(): FilterList = FilterList(
        Filter.Header("USE: tags in the search box")
    )

    // ─── Helpers ────────────────────────────────────────────

    private suspend fun fetchDocument(url: String): Document = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .headers(headers)
            .build()
        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: error("Empty body")
        response.close()
        Jsoup.parse(body, url)
    }

    private fun parsePostList(doc: Document): MangasPage {
        // Gelbooru uses a thumbnail grid: <span class="thumb"> with <a> and <img>
        val posts = doc.select("span.thumb")
        val mangaList = posts.mapNotNull { span ->
            val link = span.selectFirst("a") ?: return@mapNotNull null
            val img = span.selectFirst("img") ?: return@mapNotNull null
            val postUrl = link.attr("href")
            val fullUrl = if (postUrl.startsWith("/")) "$baseUrl$postUrl" else postUrl

            val previewSrc = img.attr("src").let { src ->
                when {
                    src.startsWith("//") -> "https:$src"
                    src.startsWith("/") -> "$baseUrl$src"
                    else -> src
                }
            }

            val altText = img.attr("title").ifBlank { img.attr("alt") }
            val tags = img.attr("data-tags").ifBlank { altText }

            SManga.create().apply {
                url = fullUrl.substringAfter(baseUrl)
                title = tags.take(80).ifBlank { "Post #${extractPostId(fullUrl)}" }
                thumbnail_url = previewSrc
                initialized = true
            }
        }

        // Pagination: check for "next" link
        val hasNext = doc.selectFirst("a.next") != null
        return MangasPage(mangaList, hasNext)
    }

    private fun extractPostId(url: String): String {
        val regex = Regex("""[?&]id=(\d+)""")
        return regex.find(url)?.groupValues?.getOrElse(1) { "?" } ?: "?"
    }

    // ─── Deprecated ─────────────────────────────────────────

    override fun popularMangaRequest(page: Int) = throw UnsupportedOperationException()
    override fun popularMangaParse(response: Response) = throw UnsupportedOperationException()
    override fun latestUpdatesRequest(page: Int) = throw UnsupportedOperationException()
    override fun latestUpdatesParse(response: Response) = throw UnsupportedOperationException()
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList) = throw UnsupportedOperationException()
    override fun searchMangaParse(response: Response) = throw UnsupportedOperationException()
    override fun chapterListParse(response: Response) = throw UnsupportedOperationException()
    override fun chapterListRequest(chapter: SChapter) = throw UnsupportedOperationException()
    override fun pageListParse(response: Response) = throw UnsupportedOperationException()
    override fun pageListRequest(chapter: SChapter) = throw UnsupportedOperationException()
    override fun imageUrlParse(response: Response) = throw UnsupportedOperationException()
}
