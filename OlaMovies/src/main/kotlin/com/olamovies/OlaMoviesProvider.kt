package com.olamovies

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import org.jsoup.nodes.Element

/**
 * OlaMovies CloudStream Extension
 *
 * Smart domain resolution: olamovies.app acts as a permanent redirect
 * to the current live domain. This extension resolves the real domain
 * at runtime so it never breaks when the domain changes.
 *
 * Supports: Bollywood, Hollywood, South Indian, Hindi Dubbed, TV Series
 * Quality: 480p, 720p, 1080p, 4K UHD, BluRay, REMUX
 */
class OlaMoviesProvider : MainAPI() {

    // Permanent landing page — always redirects to the current live domain
    private val permanentUrl = "https://olamovies.app"

    // This will be resolved at runtime to the actual current domain
    override var mainUrl = "https://n1.olamovies.info"
    override var name = "OlaMovies"
    override val hasMainPage = true
    override val hasSearch = true
    override var lang = "hi"  // Primary language Hindi, but multi-language
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
    )

    // ---------------------------------------------------------------
    // DOMAIN RESOLVER
    // Hits olamovies.app, follows the redirect to get the live domain.
    // Called once before each session so we always have a fresh domain.
    // ---------------------------------------------------------------
    private suspend fun resolveMainUrl(): String {
        return try {
            // olamovies.app shows a page with a "View Main Site" button
            // The href of that button has the real domain
            val doc = app.get(permanentUrl, followRedirects = true).document
            val redirect = doc.selectFirst("a[href*=olamovies]")?.attr("href")
                ?: doc.selectFirst("a.btn[href]")?.attr("href")
            if (!redirect.isNullOrBlank()) {
                // Strip trailing slash & path, keep just scheme + host
                val uri = java.net.URI(redirect)
                "${uri.scheme}://${uri.host}"
            } else {
                mainUrl // fallback to last known domain
            }
        } catch (e: Exception) {
            mainUrl // fallback silently
        }
    }

    // Run domain resolution before any network call
    private suspend fun getBaseUrl(): String {
        val resolved = resolveMainUrl()
        mainUrl = resolved
        return resolved
    }

    // ---------------------------------------------------------------
    // HOME PAGE CATEGORIES
    // ---------------------------------------------------------------
    override val mainPage = mainPageOf(
        "/"                          to "Latest Uploads",
        "/category/bollywood-movies" to "Bollywood Movies",
        "/category/hollywood-movies" to "Hollywood Movies",
        "/category/south-indian"     to "South Indian Movies",
        "/category/hindi-dubbed"     to "Hindi Dubbed",
        "/category/tv-series"        to "TV Series",
        "/category/web-series"       to "Web Series",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val base = getBaseUrl()
        val pageUrl = if (page == 1) {
            "$base${request.data}"
        } else {
            "$base${request.data}/page/$page"
        }
        val doc = app.get(pageUrl).document
        val items = doc.select("article.post, div.post-item, div.item, .gridlove-posts article")
            .mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    // ---------------------------------------------------------------
    // SEARCH
    // ---------------------------------------------------------------
    override suspend fun search(query: String): List<SearchResponse> {
        val base = getBaseUrl()
        val url = "$base/?s=${query.encodeUri()}"
        val doc = app.get(url).document
        return doc.select("article.post, div.post-item, .gridlove-posts article")
            .mapNotNull { it.toSearchResult() }
    }

    // ---------------------------------------------------------------
    // ELEMENT → SEARCH RESULT
    // ---------------------------------------------------------------
    private fun Element.toSearchResult(): SearchResponse? {
        val title = selectFirst("h2.entry-title a, h3 a, .title a, a[rel=bookmark]")
            ?.text()?.trim() ?: return null
        val href = selectFirst("a[href]")?.attr("href") ?: return null
        val poster = selectFirst("img")?.let {
            it.attr("data-src").ifBlank { it.attr("src") }
        }
        val isMovie = !title.contains(Regex("(?i)season|episode|series|S\\d{2}"))
        return if (isMovie) {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = poster
            }
        } else {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = poster
            }
        }
    }

    // ---------------------------------------------------------------
    // LOAD RESULT PAGE (movie/series detail)
    // ---------------------------------------------------------------
    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document

        val title = doc.selectFirst("h1.entry-title, h1.post-title, h1")
            ?.text()?.trim() ?: return null
        val poster = doc.selectFirst(".post-thumbnail img, .featured-image img, article img")
            ?.let { it.attr("data-src").ifBlank { it.attr("src") } }
        val plot = doc.selectFirst(".entry-content p, .post-content p, .description p")
            ?.text()?.trim()
        val year = Regex("\\b(20\\d{2})\\b").find(title + " " + (plot ?: ""))
            ?.value?.toIntOrNull()
        val tags = doc.select(".tag a, .tags a, .cat-links a").map { it.text() }

        // Collect all download/stream links from the post content
        // OlaMovies posts links as direct <a> tags inside the content
        val content = doc.selectFirst(".entry-content, .post-content")

        // Check if it's a series by looking for season/episode markers
        val isSeries = title.contains(Regex("(?i)season|S\\d{2}|complete series|web.?series|tv.?series"))

        return if (isSeries) {
            // Group links into episodes
            val episodes = mutableListOf<Episode>()
            content?.select("a[href]")?.forEachIndexed { index, link ->
                val linkText = link.text().trim()
                val linkUrl = link.attr("href")
                if (linkUrl.isVideoLink() || linkText.isQualityLabel()) {
                    // Try to parse season/episode from surrounding text
                    val episodeNum = Regex("(?i)(?:episode|ep)[.\\s]*(\\d+)").find(linkText)
                        ?.groupValues?.get(1)?.toIntOrNull() ?: (index + 1)
                    val seasonNum = Regex("(?i)(?:season|s)[.\\s]*(\\d+)").find(linkText)
                        ?.groupValues?.get(1)?.toIntOrNull() ?: 1
                    episodes.add(
                        newEpisode(linkUrl) {
                            this.name = linkText.ifBlank { "Episode $episodeNum" }
                            this.season = seasonNum
                            this.episode = episodeNum
                        }
                    )
                }
            }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = tags
            }
        } else {
            // Movie — collect all quality links as data
            val links = content?.select("a[href]")
                ?.filter { it.attr("href").isVideoLink() || it.text().isQualityLabel() }
                ?.map { it.attr("href") }
                ?: emptyList()
            newMovieLoadResponse(title, url, TvType.Movie, links.toJson()) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = tags
            }
        }
    }

    // ---------------------------------------------------------------
    // LOAD VIDEO LINKS
    // ---------------------------------------------------------------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // data is either a direct URL (episode) or JSON array of URLs (movie)
        val urls: List<String> = try {
            parseJson<List<String>>(data)
        } catch (e: Exception) {
            listOf(data)
        }

        var found = false
        urls.forEach { rawUrl ->
            val url = rawUrl.trim()
            when {
                // Google Drive links
                url.contains("drive.google.com") || url.contains("docs.google.com") -> {
                    // Extract file ID and build a direct stream URL
                    val fileId = Regex("[/=]([a-zA-Z0-9_-]{28,})").find(url)
                        ?.groupValues?.get(1) ?: return@forEach
                    val quality = url.guessQuality()
                    callback(
                        ExtractorLink(
                            source = name,
                            name = "Google Drive [${quality}p]",
                            url = "https://drive.google.com/uc?export=download&id=$fileId",
                            referer = mainUrl,
                            quality = quality,
                            isM3u8 = false
                        )
                    )
                    found = true
                }
                // Direct video file links
                url.endsWith(".mkv") || url.endsWith(".mp4") || url.endsWith(".m3u8") -> {
                    val quality = url.guessQuality()
                    callback(
                        ExtractorLink(
                            source = name,
                            name = "$name [${quality}p]",
                            url = url,
                            referer = mainUrl,
                            quality = quality,
                            isM3u8 = url.endsWith(".m3u8")
                        )
                    )
                    found = true
                }
                // Other external hosters — try built-in extractors
                url.startsWith("http") -> {
                    found = found or loadExtractor(url, mainUrl, subtitleCallback, callback)
                }
            }
        }
        return found
    }

    // ---------------------------------------------------------------
    // HELPERS
    // ---------------------------------------------------------------
    private fun String.isVideoLink(): Boolean {
        val lower = lowercase()
        return lower.contains("drive.google.com") ||
                lower.contains("mega.nz") ||
                lower.contains("mediafire") ||
                lower.contains("direct") ||
                lower.endsWith(".mkv") ||
                lower.endsWith(".mp4") ||
                lower.endsWith(".m3u8") ||
                lower.contains("download") ||
                lower.contains("stream")
    }

    private fun String.isQualityLabel(): Boolean {
        return contains(Regex("(?i)480p|720p|1080p|2160p|4k|uhd|bluray|remux|hevc|x265|hdr"))
    }

    private fun String.guessQuality(): Int {
        return when {
            contains("2160") || contains("4K", ignoreCase = true) || contains("UHD", ignoreCase = true) -> 2160
            contains("1080") -> 1080
            contains("720")  -> 720
            contains("480")  -> 480
            else             -> Qualities.Unknown.value
        }
    }

    private fun String.encodeUri(): String =
        java.net.URLEncoder.encode(this, "UTF-8")
}
