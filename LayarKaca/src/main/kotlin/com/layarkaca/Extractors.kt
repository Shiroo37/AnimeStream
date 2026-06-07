package com.layarkaca

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.extractors.Filesim
import com.lagradost.cloudstream3.utils.*

// Extractor untuk playeriframe.sbs
open class Playeriframe : ExtractorApi() {
    override val name = "Playeriframe"
    override val mainUrl = "https://playeriframe.sbs"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val response = app.get(url, referer = referer, allowRedirects = true)
        val doc = response.document
        val text = doc.html()

        // Cari m3u8 atau mp4 di script
        val videoRegex = Regex("""['"](https?://[^'"]+\.(?:m3u8|mp4)[^'"]*)['"']""")
        for (match in videoRegex.findAll(text)) {
            val videoUrl = match.groupValues[1]
            // Skip video iklan
            if (videoUrl.contains("stopjudi") || videoUrl.contains("donasi")) continue
            val isM3u8 = videoUrl.contains("m3u8")
            M3u8Helper.generateM3u8(name, videoUrl, url).forEach(callback)
            if (!isM3u8) {
                callback.invoke(
                    newExtractorLink(name, name, videoUrl, ExtractorLinkType.VIDEO) {
                        this.referer = url
                        this.quality = Qualities.Unknown.value
                    }
                )
            }
        }

        // Fallback: cek iframe di dalamnya
        val innerIframe = doc.selectFirst("iframe[src]")?.attr("src") ?: return
        if (innerIframe.startsWith("http") && !innerIframe.contains("playeriframe")) {
            loadExtractor(innerIframe, url, subtitleCallback, callback)
        }
    }
}

open class Emturbovid : ExtractorApi() {
    override val name = "Emturbovid"
    override val mainUrl = "https://emturbovid.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val response = app.get(url, referer = referer)
        val m3u8 = Regex("[\"'](.*?master\\.m3u8.*?)[\"']")
            .find(response.text)?.groupValues?.getOrNull(1)
        M3u8Helper.generateM3u8(name, m3u8 ?: return, mainUrl).forEach(callback)
    }
}

open class Hownetwork : ExtractorApi() {
    override val name = "Hownetwork"
    override val mainUrl = "https://cloud.hownetwork.xyz"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val id = url.substringAfter("id=")
        val res = app.post(
            "$mainUrl/api.php?id=$id",
            data = mapOf(
                "r" to "https://playeriframe.sbs/",
                "d" to "stream.hownetwork.xyz",
            ),
            referer = url,
            headers = mapOf("X-Requested-With" to "XMLHttpRequest")
        ).parsedSafe<Sources>() ?: return

        for (item in res.data) {
            callback.invoke(
                newExtractorLink(name, name, item.file, ExtractorLinkType.VIDEO) {
                    this.referer = url
                    this.quality = getQualityFromName(item.label)
                }
            )
        }
    }

    data class Sources(val data: List<Data>) {
        data class Data(val file: String, val label: String?)
    }
}

class FileMoon : Filesim() {
    override val name = "FileMoon"
    override var mainUrl = "https://filemoon.sx"
}
