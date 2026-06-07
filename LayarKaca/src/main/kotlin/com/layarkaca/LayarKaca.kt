package com.layarkaca

import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addQuality
import com.lagradost.cloudstream3.addSub
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class LayarKaca : MainAPI() {

    override var mainUrl = "https://tv3.lk21online.mom"
    private var seriesUrl = "https://tv3.lk21online.mom"

    override var name = "LayarKaca"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.AsianDrama)

    override val mainPage = mainPageOf(
        "$mainUrl/populer/page/" to "Film Terpopuler",
        "$mainUrl/rating/page/" to "Film Berdasarkan IMDb Rating",
        "$mainUrl/latest/page/" to "Film Upload Terbaru",
        "$mainUrl/country/south-korea/page/" to "Drama Korea",
        "$mainUrl/country/china/page/" to "Series China",
        "$mainUrl/series/west/page/" to "Series West",
        "$mainUrl/populer/page/" to "Series Terpopuler",
        "$mainUrl/latest-series/page/" to "Series Terbaru",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data + page).document
        // Selector baru: article tanpa class
        val home = document.select("article").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        // Ambil title dari h1/h2/h3 atau dari attribute title di tag a
        val title = this.selectFirst("h1, h2, h3")?.text()?.trim()
            ?: this.selectFirst("a")?.attr("title")
                ?.replace(Regex("(?i)nonton\\s+(film|series|movie)?\\s*"), "")
                ?.replace(Regex("\\s*streaming gratis.*", RegexOption.IGNORE_CASE), "")
                ?.trim()
            ?: return null
        val href = fixUrl(this.selectFirst("a")?.attr("href") ?: return null)
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("src"))
        // Deteksi tipe: kalau ada .episode = series, kalau tidak = movie
        val type = if (this.selectFirst(".episode") != null) TvType.TvSeries else TvType.Movie
        return if (type == TvType.TvSeries) {
            val episode = this.selectFirst(".episode strong")?.text()?.toIntOrNull()
            newAnimeSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
                addSub(episode)
            }
        } else {
            val quality = this.selectFirst(".quality")?.text()?.trim() ?: ""
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
                addQuality(quality)
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        // Pakai WordPress standard search
        val document = app.get("$mainUrl/?s=$query").document
        return document.select("article").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("h1[itemprop=name], h1.entry-title, h1")
            ?.text()?.trim() ?: return null
        val poster = fixUrlNull(
            document.selectFirst(".wp-post-image, img[itemprop=image], .poster img")
                ?.attr("src")
        )
        val tags = document.select("a[rel=tag], .genres a, .genre a").map { it.text() }
        val year = document.selectFirst(".year, [itemprop=datePublished]")
            ?.text()?.trim()?.toIntOrNull()
        val tvType = if (document.select(".episode-list, .eps-list, div.serial-wrapper").isNotEmpty())
            TvType.TvSeries else TvType.Movie
        val description = document.selectFirst("[itemprop=description], .desc, .sinopsis")
            ?.text()?.trim()
        val rating = document.selectFirst(".rating, [itemprop=ratingValue]")?.text()
        val actors = document.select("[itemprop=actor] a, .cast a").map { it.text() }

        return if (tvType == TvType.TvSeries) {
            val episodes = document.select(".episode-list a, .eps-list a, div.episode-list > a")
                .mapNotNull { ep ->
                    val epHref = fixUrl(ep.attr("href"))
                    val epNum = ep.text().filter { it.isDigit() }.toIntOrNull()
                    val season = ep.attr("href")
                        .substringAfter("season-").substringBefore("-").toIntOrNull()
                    newEpisode(epHref) {
                        this.name = "Episode $epNum"
                        this.season = season
                        this.episode = epNum
                    }
                }.reversed()
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                addScore(rating)
                addActors(actors)
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                addScore(rating)
                addActors(actors)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
    isCasting: Boolean,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
): Boolean {
    val document = app.get(data).document

    document.select("ul#player-list li a").forEach { server ->
        val playerUrl = server.attr("data-url").ifEmpty { server.attr("href") }
        if (playerUrl.startsWith("http")) {
            loadExtractor(playerUrl, mainUrl, subtitleCallback, callback)
        }
    }

    return true
    }
}
