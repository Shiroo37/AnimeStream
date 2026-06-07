package com.layarkaca

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class LayarKaca : MainAPI() {
    override var mainUrl = "https://tv3.lk21online.mom"
    override var name = "LayarKaca"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.AsianDrama)

    override val mainPage = mainPageOf(
        "$mainUrl/populer/page/" to "Film Terpopuler",
        "$mainUrl/rating/page/" to "Film Rating Tertinggi",
        "$mainUrl/latest/page/" to "Film Upload Terbaru",
        "$mainUrl/country/south-korea/page/" to "Drama Korea",
        "$mainUrl/country/china/page/" to "Series China",
        "$mainUrl/series/west/page/" to "Series West",
        "$mainUrl/latest-series/page/" to "Series Terbaru",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data + page).document
        val home = document.select("article").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("h3.poster-title, h1, h2, h3")?.text()?.trim()
            ?: this.selectFirst("a")?.attr("title")
                ?.replace(Regex("(?i)nonton\\s+(film|series|movie)?\\s*"), "")
                ?.replace(Regex("(?i)\\s*streaming gratis.*"), "")
                ?.trim()
            ?: return null
        val href = fixUrl(this.selectFirst("a")?.attr("href") ?: return null)
        // Poster pakai data-src karena lazyload
        val posterUrl = fixUrlNull(
            this.selectFirst("img")?.attr("data-src")?.ifEmpty { null }
                ?: this.selectFirst("img")?.attr("src")
        )
        val type = if (this.selectFirst(".episode") != null) TvType.TvSeries else TvType.Movie
        return if (type == TvType.TvSeries) {
            val episode = this.selectFirst(".episode strong")?.text()?.toIntOrNull()
            newAnimeSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
                addSub(episode)
            }
        } else {
            val quality = this.selectFirst(".quality, .label")?.text()?.trim() ?: ""
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
                addQuality(quality)
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/search?s=$query").document
        return document.select("article").mapNotNull { it.toSearchResult() }
    }

    data class WatchHistoryData(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("rating") val rating: String? = null,
        @JsonProperty("poster") val poster: String? = null,
        @JsonProperty("slug") val slug: String? = null,
        @JsonProperty("year") val year: Int? = null,
        @JsonProperty("runtime") val runtime: String? = null
    )

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        // Ambil data dari JSON embed di halaman
        val watchData = tryParseJson<WatchHistoryData>(
            document.selectFirst("script#watch-history-data")?.data()
        )

        val title = watchData?.title
            ?: document.selectFirst("h1")?.text()
                ?.replace(Regex("(?i)nonton\\s+"), "")
                ?.replace(Regex("(?i)\\s+sub indo.*"), "")
                ?.replace(Regex("(?i)\\s+di lk21.*"), "")
                ?.trim()
            ?: return null

        val poster = watchData?.poster
            ?: document.selectFirst("img.lazyload[data-src*='poster']")?.attr("data-src")

        val tags = document.select("div.tag-list span.tag a").map { it.text() }
        val year = watchData?.year
        val description = document.selectFirst("div.synopsis")?.text()?.trim()
        val rating = watchData?.rating
        val actors = document.select("div.detail a[href*='/artist/']").map { it.text() }

        // Deteksi tipe: series punya episode list
        val tvType = if (document.select(".episode-list, .eps-list").isNotEmpty())
            TvType.TvSeries else TvType.Movie

        return if (tvType == TvType.TvSeries) {
            val episodes = document.select(".episode-list a, .eps-list a")
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

        // Ambil semua server dari player-list
        document.select("ul#player-list li a").forEach { server ->
            val playerUrl = server.attr("data-url").ifEmpty { server.attr("href") }
            if (playerUrl.startsWith("http")) {
                loadExtractor(playerUrl, data, subtitleCallback, callback)
            }
        }

        return true
    }
}
