package com.olamovies

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class OlaMoviesProvider : MainAPI() {
    override var mainUrl = "https://n1.olamovies.info"
    override var name = "OlaMovies"
    override val hasMainPage = true
    override var lang = "hi"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/?s=$query").document
        return doc.select("article").mapNotNull {
            val title = it.selectFirst("h2 a")?.text() ?: return@mapNotNull null
            val href = it.selectFirst("a")?.attr("href") ?: return@mapNotNull null
            val poster = it.selectFirst("img")?.attr("src")
            MovieSearchResponse(title, href, name, TvType.Movie, poster)
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val title = doc.selectFirst("h1")?.text() ?: "OlaMovies"
        val plot = doc.selectFirst(".entry-content p")?.text()
        val poster = doc.selectFirst("article img")?.attr("src")
        return MovieLoadResponse(title, url, name, TvType.Movie, url, poster, null, plot)
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        callback(
            ExtractorLink(name, name, data, "", Qualities.Unknown.value, false)
        )
        return true
    }
}
