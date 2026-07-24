package eu.kanade.tachiyomi.extension.all.konachan

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

/**
 * Konachan — Moebooru-based booru.
 * Mirrors: konachan.com, konachan.net (SFW)
 */
class Konachan : HttpSource() {

    override val name = "Konachan"
    override val baseUrl = "https://konachan.com"
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

    override fun popularMangaRequest(page: Int) = GET("$baseUrl/post?page=$page", headers)

    override fun popularMangaParse(response: Response) = parsePostList(response.use { it.asJsoup() })

    // ─── Latest ─────────────────────────────────────────────

    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/post?page=$page", headers)

    override fun latestUpdatesParse(response: Response) = parsePostList(response.use { it.asJsoup() })

    // ─── Search ─────────────────────────────────────────────

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val tags = query.trim().replace(" ", "+")
        val url = if (tags.isNotBlank()) "$baseUrl/post?tags=$tags&page=$page"
                  else "$baseUrl/post?page=$page"
        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response) = parsePostList(response.use { it.asJsoup() })

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
            val tagLinks = doc.select("ul#tag-list li a")
            title = if (tagLinks.isNotEmpty()) {
                tagLinks.take(3).joinToString(", ") { it.text() }
            } else {
                "Post #$postId"
            }

            description = buildString {
                append("ID: $postId\n")
                val ratingEl = doc.selectFirst("li.rating")
                if (ratingEl != null) {
                    append("Rating: ${ratingEl.text().uppercase()}\n")
                }
                val tagList = doc.selectFirst("#tag-list")
                if (tagList != null) {
                    for (li in tagList.select("li")) {
                        val type = li.selectFirst(".type")?.text() ?: ""
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
            ?: doc.selectFirst("img#image")
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
        Filter.Header("USE: tags in the search box")
    )

    // ─── Helpers ────────────────────────────────────────────

    private fun parsePostList(doc: Document): MangasPage {
        val posts = doc.select("div.post-preview")
        val mangaList = posts.mapNotNull { div ->
            val link = div.selectFirst("a") ?: return@mapNotNull null
            val img = div.selectFirst("img") ?: return@mapNotNull null
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

        val hasNext = doc.selectFirst("a.next_page") != null
        return MangasPage(mangaList, hasNext)
    }

    private fun extractPostId(url: String): String {
        val regex = Regex("""/post/show/(\d+)""")
        return regex.find(url)?.groupValues?.getOrElse(1) { "?" } ?: "?"
    }
}
