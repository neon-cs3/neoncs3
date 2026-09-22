package com.neoncs3

import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class HDFilmCehennemi : MainAPI() {
    override var mainUrl                = "https://www.hdfilmcehennemi.nl"
    override var name                   = "HDFilmCehennemi"
    override val hasMainPage            = true
    override var lang                   = "tr"
    override val hasQuickSearch         = true
    override val supportedTypes         = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        mainUrl to "Yeni Eklenen Filmler",
        "${mainUrl}/yabancidiziizle-5"                        to "Yeni Eklenen Diziler",
        "${mainUrl}/category/tavsiye-filmler-izle3"        to "Tavsiye Filmler",
        "${mainUrl}/imdb-7-puan-uzeri-filmle-2r"          to "IMDB 7+ Filmler",
        "${mainUrl}/en-cok-yorumlananlar-2"                to "En Çok Yorumlananlar",
        "${mainUrl}/en-cok-begenilen-filmleri-izle-4"      to "En Çok Beğenilenler",
        "${mainUrl}/tur/aile-filmleri-izleyin-7"          to "Aile Filmleri",
        "${mainUrl}/tur/aksiyon-filmleri-izleyin-8"        to "Aksiyon Filmleri",
        "${mainUrl}/tur/animasyon-filmlerini-izleyin-5"   to "Animasyon Filmleri",
        "${mainUrl}/tur/belgesel-filmlerini-izle-2"        to "Belgesel Filmleri",
        "${mainUrl}/tur/bilim-kurgu-filmlerini-izleyin-5" to "Bilim Kurgu Filmleri",
        "${mainUrl}/tur/komedi-filmlerini-izleyin-2"       to "Komedi Filmleri",
        "${mainUrl}/tur/korku-filmlerini-izle-9/"         to "Korku Filmleri",
        "${mainUrl}/tur/romantik-filmleri-izle-3"          to "Romantik Filmleri"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data).document
        val home: List<SearchResponse> = document.select("div.section-content a.poster").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title   = this.selectFirst("strong.poster-title")?.text() ?: return null
        val href    = fixUrlNull(this.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("data-src"))

        return newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val response = app.get(
            "${mainUrl}/search?q=${query}",
            headers = mapOf("X-Requested-With" to "fetch")
        ).parsedSafe<Results>() ?: return emptyList()
        val searchResults = mutableListOf<SearchResponse>()

        response.results.forEach { resultHtml ->
            val document = Jsoup.parse(resultHtml)

            val title   = document.selectFirst("h4.title")?.text() ?: return@forEach
            val href    = fixUrlNull(document.selectFirst("a")?.attr("href")) ?: return@forEach
            val posterUrl = fixUrlNull(document.selectFirst("img")?.attr("src")) ?: fixUrlNull(document.selectFirst("img")?.attr("data-src"))

            searchResults.add(
                newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl?.replace("/thumb/", "/list/") }
            )
        }

        return searchResults
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title       = document.selectFirst("h1.section-title")?.text()?.substringBefore(" izle") ?: return null
        val poster      = fixUrlNull(document.select("aside.post-info-poster img.lazyload").lastOrNull()?.attr("data-src"))
        val tags        = document.select("div.post-info-genres a").map { it.text() }
        val year        = document.selectFirst("div.post-info-year-country a")?.text()?.trim()?.toIntOrNull()
        val tvType      = if (document.select("div.seasons").isEmpty()) TvType.Movie else TvType.TvSeries
        val description = document.selectFirst("article.post-info-content > p")?.text()?.trim()
        
        val actors      = document.select("div.post-info-cast a").mapNotNull {
            val actorName = it.selectFirst("strong")?.text() ?: return@mapNotNull null
            val actorImg  = it.selectFirst("img")?.attr("data-src")
            Actor(actorName, actorImg)
        }

        val recommendations = document.select("div.section-slider-container div.slider-slide").mapNotNull {
            val recName      = it.selectFirst("a")?.attr("title") ?: return@mapNotNull null
            val recHref      = fixUrlNull(it.selectFirst("a")?.attr("href")) ?: return@mapNotNull null
            val recPosterUrl = fixUrlNull(it.selectFirst("img")?.attr("data-src")) ?: fixUrlNull(it.selectFirst("img")?.attr("src"))

            newTvSeriesSearchResponse(recName, recHref, TvType.TvSeries) {
                this.posterUrl = recPosterUrl
            }
        }

        return if (tvType == TvType.TvSeries) {
            val trailer  = document.selectFirst("div.post-info-trailer button")?.attr("data-modal")?.substringAfter("trailer/")?.let { "https://www.youtube.com/embed/$it" }
            val episodes = document.select("div.seasons-tab-content a").mapNotNull {
                val epName    = it.selectFirst("h4")?.text()?.trim() ?: return@mapNotNull null
                val epHref    = fixUrlNull(it.attr("href")) ?: return@mapNotNull null
                val epEpisode = Regex("""(\d+)\. ?Bölüm""").find(epName)?.groupValues?.get(1)?.toIntOrNull()
                val epSeason  = Regex("""(\d+)\. ?Sezon""").find(epName)?.groupValues?.get(1)?.toIntOrNull() ?: 1

                newEpisode(epHref) {
                    this.name = epName
                    this.season = epSeason
                    this.episode = epEpisode
                }
            }

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl       = poster
                this.year            = year
                this.plot            = description
                this.tags            = tags
                this.recommendations = recommendations
                addActors(actors)
                addTrailer(trailer)
            }
        } else {
            val trailer = document.selectFirst("div.post-info-trailer button")?.attr("data-modal")?.substringAfter("trailer/")?.let { "https://www.youtube.com/embed/$it" }

            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl       = poster
                this.year            = year
                this.plot            = description
                this.tags            = tags
                this.recommendations = recommendations
                addActors(actors)
                addTrailer(trailer)
            }
        }
    }

    private suspend fun invokeLocalSource(
        source: String,
        url: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val script    = app.get(url, referer = "${mainUrl}/").document.select("script").find { it.data().contains("sources:") }?.data() ?: return
        val videoData = getAndUnpack(script).substringAfter("file_link=\"").substringBefore("\";")
        val subData   = script.substringAfter("tracks: [").substringBefore("]")

        callback.invoke(
            newExtractorLink(
                source  = source,
                name    = source,
                url     = base64Decode(videoData),
                type    = INFER_TYPE
            ) {
                this.referer = "${mainUrl}/"
                this.quality = Qualities.Unknown.value
            }
        )

        AppUtils.tryParseJson<List<SubSource>>("[${subData}]")?.filter { it.kind == "captions" }?.forEach { sub ->
            subtitleCallback.invoke(
                SubtitleFile(sub.label ?: "", fixUrl(sub.file ?: ""))
            )
        }
    }

   override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("HDCH", "data » $data")
        val document = app.get(data).document

        document.select("div.alternative-links").forEach { element ->
            val langCode = element.attr("data-lang").uppercase()

            element.select("button.alternative-link").forEach { button ->
                val source = button.text().replace(Regex("\\(.*?\\)"), "").trim() + " $langCode"
                val videoID = button.attr("data-video")
                if (videoID.isBlank()) return@forEach

                // Video ID ile AJAX isteği atıp iframe kaynağını alıyoruz
                val apiGet = app.get(
                    "${mainUrl}/video/$videoID/",
                    headers = mapOf(
                        "X-Requested-With" to "fetch",
                        "Referer" to data,
                        "User-Agent" to USER_AGENT
                    )
                ).text

                val cleanHtml = apiGet.replace("\\\"", "\"").replace("\\/", "/")
                val iframeTag = Jsoup.parse(cleanHtml).selectFirst("iframe")
                val rawIframe = iframeTag?.attr("data-src")?.takeIf { it.isNotBlank() }
                    ?: iframeTag?.attr("src")?.takeIf { it.isNotBlank() }
                    ?: return@forEach

                var iframe = fixUrl(rawIframe)
                if (iframe.contains("rapidrame_id=")) {
                    val rapidId = iframe.substringAfter("rapidrame_id=").substringBefore("&")
                    iframe = "$mainUrl/playerr/$rapidId"
                }

                Log.d("HDCH", "$source » Video ID: $videoID » Iframe: $iframe")

                // CloudStream'in güçlü yerleşik extractor'ı ile videoları otomatik çözüyoruz
                loadExtractor(iframe, "$mainUrl/", subtitleCallback, callback)
            }
        }

        return true
    }
