package eu.kanade.tachiyomi.extension.all.danbooru

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
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

    override fun popularMangaRequest(page: Int) = GET("$baseUrl/posts?page=$page", headers)

    override fun popularMangaParse(response: Response) = parsePostList(response.use { it.asJsoup() })

    // ─── Latest ─────────────────────────────────────────────

    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/posts?page=$page", headers)

    override fun latestUpdatesParse(response: Response) = parsePostList(response.use { it.asJsoup() })

    // ─── Search ─────────────────────────────────────────────

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val tags = buildTags(query, filters)
        val url = if (tags.isNotBlank()) "$baseUrl/posts?tags=$tags&page=$page"
                  else "$baseUrl/posts?page=$page"
        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response) = parsePostList(response.use { it.asJsoup() })

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

    // ─── Manga details ──────────────────────────────────────

    override fun mangaDetailsRequest(manga: SManga): Request {
        return GET(baseUrl + manga.url, headers)
    }

    override fun mangaDetailsParse(response: Response): SManga {
        return parseMangaDetails(response.use { it.asJsoup() })
    }

    private fun parseMangaDetails(doc: Document): SManga {
        return SManga.create().apply {
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

    // ─── Chapter list (1 chapter per post) ──────────────────

    override fun chapterListRequest(manga: SManga): Request {
        return GET(baseUrl + manga.url, headers)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val doc = response.use { it.asJsoup() }
        return listOf(SChapter.create().apply {
            url = doc.location().substringAfter(baseUrl)
            name = "Image"
            chapter_number = 1F
        })
    }

    // ─── Pages ──────────────────────────────────────────────

    override fun pageListRequest(chapter: SChapter): Request {
        return GET(baseUrl + chapter.url, headers)
    }

    override fun pageListParse(response: Response): List<Page> {
        val doc = response.use { it.asJsoup() }
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

    override fun imageUrlParse(response: Response): String = ""

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
}
