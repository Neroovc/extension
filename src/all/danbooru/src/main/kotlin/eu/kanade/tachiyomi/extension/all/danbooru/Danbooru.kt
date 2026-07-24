package eu.kanade.tachiyomi.extension.all.danbooru

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import rx.Observable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

class Danbooru : HttpSource() {

    override val name = "Danbooru"
    override val baseUrl = "https://danbooru.donmai.us"
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
        val doc = fetchDocument("$baseUrl/posts?page=$page")
        return parsePostList(doc)
    }

    // ─── Latest ─────────────────────────────────────────────

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val doc = fetchDocument("$baseUrl/posts?page=$page")
        return parsePostList(doc)
    }

    // ─── Search ─────────────────────────────────────────────

    override suspend fun getSearchManga(
        page: Int,
        query: String,
        filters: FilterList
    ): MangasPage {
        val tags = buildTags(query, filters)
        val doc = if (tags.isNotBlank()) {
            fetchDocument("$baseUrl/posts?tags=$tags&page=$page")
        } else {
            fetchDocument("$baseUrl/posts?page=$page")
        }
        return parsePostList(doc)
    }

    private fun buildTags(query: String, filters: FilterList): String {
        val parts = mutableListOf<String>()
        if (query.isNotBlank()) parts.add(query.trim())

        for (filter in filters) {
            when (filter) {
                is RatingFilter -> {
                    val selected = filter.state
                    if (selected != 0) {
                        parts.add("rating:" + when (selected) {
                            1 -> "s"
                            2 -> "q"
                            3 -> "e"
                            else -> ""
                        })
                    }
                }
                is SortFilter -> {
                    val selected = filter.state
                    if (selected != 0 && query.isBlank()) {
                        parts.add("order:" + when (selected) {
                            1 -> "rank"
                            else -> ""
                        })
                    }
                }
                else -> {}
            }
        }

        return parts.joinToString(" ")
    }

    // ─── Chapter list (1 chapter per post) ──────────────────

    override suspend fun getChapterList(manga: SManga, page: Int): List<SChapter> {
        val chapter = SChapter.create().apply {
            url = manga.url
            name = "Image"
            chapter_number = 1F
            date_upload = 0
        }
        return listOf(chapter)
    }

    // ─── Manga details + chapter list ───────────────────────

    override fun fetchMangaDetails(manga: SManga): Observable<SManga> {
        return client.newCall(mangaDetailsRequest(manga))
            .asObservableSuccess()
            .map { response ->
                val doc = Jsoup.parse(response.body?.string() ?: "", baseUrl + manga.url)
                mangaDetailsParse(doc)
            }
    }

    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> {
        return client.newCall(chapterListRequest(manga))
            .asObservableSuccess()
            .map { response ->
                val doc = Jsoup.parse(response.body?.string() ?: "", baseUrl + manga.url)
                listOf(SChapter.create().apply {
                    url = manga.url
                    name = "Image"
                    chapter_number = 1F
                    date_upload = 0
                })
            }
    }

    private fun mangaDetailsParse(doc: Document): SManga {
        return SManga.create().apply {
            // Title: first few tags or post ID
            val postId = extractPostId(doc.location())
            val tagLinks = doc.select("ul.tag-list a.search-tag")
            title = if (tagLinks.isNotEmpty()) {
                tagLinks.take(3).joinToString(", ") { it.text() }
            } else {
                "Post #$postId"
            }

            description = buildString {
                append("ID: $postId\n")
                append("Rating: ${doc.selectFirst("#post-info-rating a")?.text()?.uppercase() ?: "?"}\n")
                val tagSection = doc.selectFirst("#tag-list")
                if (tagSection != null) {
                    val groups = tagSection.select("li")
                    for (group in groups) {
                        val type = group.selectFirst(".tag-type")?.text() ?: ""
                        val tags = group.select("a.search-tag").joinToString(", ") { it.text() }
                        if (tags.isNotBlank()) {
                            append("$type: $tags\n")
                        }
                    }
                }
            }

            // Thumbnail from the post page
            val img = doc.selectFirst("#image")
            thumbnail_url = img?.attr("src")?.let { src ->
                if (src.startsWith("//")) "https:$src"
                else if (src.startsWith("/")) "$baseUrl$src"
                else src
            }

            status = SManga.COMPLETED
            artist = doc.selectFirst("#post-info-uploader a")?.text() ?: ""
            val tagText = doc.select("ul.tag-list a.search-tag").joinToString(", ") { it.text() }
            genre = tagText
        }
    }

    private fun chapterListParse(doc: Document): List<SChapter> {
        return listOf(SChapter.create().apply {
            url = doc.location().substringAfter(baseUrl)
            name = "Image"
            chapter_number = 1F
        })
    }

    override fun mangaDetailsRequest(manga: SManga): Request {
        return GET(baseUrl + manga.url, headers)
    }

    override fun chapterListRequest(manga: SManga): Request {
        return GET(baseUrl + manga.url, headers)
    }

    // ─── Pages ──────────────────────────────────────────────

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val doc = fetchDocument(baseUrl + chapter.url)
        return pageListParse(doc)
    }

    private fun pageListParse(doc: Document): List<Page> {
        val img = doc.selectFirst("#image")
            ?: doc.selectFirst("img[src*=\"/data/\"], img[src*=\"/preview/\"]")
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

    override suspend fun getImageUrl(page: Page): String {
        return page.imageUrl ?: ""
    }

    // ─── Filters ────────────────────────────────────────────

    override fun getFilterList(): FilterList = FilterList(
        Filter.Header("USE: tags in the search box"),
        Filter.Separator(),
        RatingFilter(),
        SortFilter(),
    )

    class RatingFilter : Filter.Select("Rating", arrayOf("Any", "Safe", "Questionable", "Explicit"))
    class SortFilter : Filter.Select("Sort", arrayOf("Default", "Popular (24h)"))

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
        val posts = doc.select("article.post-preview")
        val mangaList = posts.mapNotNull { article ->
            val link = article.selectFirst("a[href]") ?: return@mapNotNull null
            val img = article.selectFirst("img") ?: return@mapNotNull null
            val postUrl = link.attr("href")
            val fullUrl = if (postUrl.startsWith("/")) "$baseUrl$postUrl" else postUrl

            val previewSrc = img.attr("src").let { src ->
                when {
                    src.startsWith("//") -> "https:$src"
                    src.startsWith("/") -> "$baseUrl$src"
                    else -> src
                }
            }

            // Try to get tags from data attribute or title
            val dataTags = article.attr("data-tags")
            val altText = img.attr("title").ifBlank { img.attr("alt") }
            val tags = dataTags.ifBlank { altText }

            SManga.create().apply {
                url = fullUrl.substringAfter(baseUrl)
                title = tags.take(80).ifBlank { extractPostId(fullUrl).let { "Post #$it" } }
                thumbnail_url = previewSrc
                initialized = true
            }
        }

        val hasNext = doc.selectFirst("a.paginator-next") != null
        return MangasPage(mangaList, hasNext)
    }

    private fun extractPostId(url: String): String {
        val regex = Regex("""/posts/(\d+)""")
        return regex.find(url)?.groupValues?.getOrElse(1) { "?" } ?: "?"
    }

    // Deprecated overrides — not used with suspend API
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
