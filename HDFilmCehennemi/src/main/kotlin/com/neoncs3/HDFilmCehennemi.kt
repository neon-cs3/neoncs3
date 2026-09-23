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

    override var mainUrl = "https://www.hdfilmcehennemi.nl"
    override var name = "HDFilmCehennemi"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    override val mainPage = mainPageOf(
        mainUrl to "Yeni Eklenen Filmler",
        "${mainUrl}/yabancidiziizle-5" to "Yeni Eklenen Diziler",
        "${mainUrl}/category/tavsiye-filmler-izle3" to "Tavsiye Filmler",
        "${mainUrl}/imdb-7-puan-uzeri-filmle-2" to "IMDB 7+ Filmler",
        "${mainUrl}/en-cok-yorumlananlar-2" to "En Çok Yorumlananlar",
        "${mainUrl}/en-cok-begenilen-filmleri-izle-4" to "En Çok Beğenilenler",
        "${mainUrl}/tur/aile-filmleri-izleyin-7" to "Aile Filmleri",
        "${mainUrl}/tur/aksiyon-filmleri-izleyin-8" to "Aksiyon Filmleri",
        "${mainUrl}/tur/animasyon-filmlerini-izleyin-5" to "Animasyon Filmleri",
        "${mainUrl}/tur/belgesel-filmlerini-izle-2" to "Belgesel Filmleri",
        "${mainUrl}/tur/bilim-kurgu-filmlerini-izleyin-5" to "Bilim Kurgu Filmleri",
        "${mainUrl}/tur/komedi-filmleri-izleyin-2" to "Komedi Filmleri",
        "${mainUrl}/tur/korku-filmenini-izle-9/" to "Korku Filmleri",
        "${mainUrl}/tur/romantik-filmleri-izle-3" to "Romantik Filmleri"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val document = app.get(request.data).document

        val home = document
            .select("div.section-content a.poster")
            .mapNotNull { it.toSearchResult() }

        return newHomePageResponse(
            request.name,
            home
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {

        val title = selectFirst("strong.poster-title")
            ?.text()
            ?: return null

        val href = fixUrlNull(
            attr("href")
        ) ?: return null

        val posterUrl = fixUrlNull(
            selectFirst("img")
                ?.attr("data-src")
        )

        return newMovieSearchResponse(
            title,
            href,
            TvType.Movie
        ) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun quickSearch(
        query: String
    ): List<SearchResponse> {
        return search(query)
    }

    override suspend fun search(
        query: String
    ): List<SearchResponse> {

        val response = app.get(
            "$mainUrl/search?q=$query",
            headers = mapOf(
                "X-Requested-With" to "fetch",
                "User-Agent" to USER_AGENT,
                "Referer" to mainUrl
            )
        ).parsedSafe<Results>() ?: return emptyList()

        val searchResults = mutableListOf<SearchResponse>()

        response.results.forEach { resultHtml ->

            val document = Jsoup.parse(resultHtml)

            val title = document
                .selectFirst("h4.title")
                ?.text()
                ?: return@forEach

            val href = fixUrlNull(
                document.selectFirst("a")
                    ?.attr("href")
            ) ?: return@forEach

            val posterUrl =
                fixUrlNull(
                    document.selectFirst("img")
                        ?.attr("src")
                )
                    ?: fixUrlNull(
                        document.selectFirst("img")
                            ?.attr("data-src")
                    )

            searchResults.add(
                newMovieSearchResponse(
                    title,
                    href,
                    TvType.Movie
                ) {
                    this.posterUrl = posterUrl?.replace(
                        "/thumb/",
                        "/list/"
                    )
                }
            )
        }

        return searchResults
    }

    override suspend fun load(
        url: String
    ): LoadResponse? {

        val document = app.get(
            url,
            headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to mainUrl
            )
        ).document

        val title = document
            .selectFirst("h1.section-title")
            ?.text()
            ?.substringBefore(" izle")
            ?.trim()
            ?: return null

        val poster = fixUrlNull(
            document
                .select("aside.post-info-poster img.lazyload")
                .lastOrNull()
                ?.attr("data-src")
        )

        val tags = document
            .select("div.post-info-genres a")
            .map { it.text() }

        val year = document
            .selectFirst("div.post-info-year-country a")
            ?.text()
            ?.trim()
            ?.toIntOrNull()

        val tvType =
            if (document.select("div.seasons").isEmpty()) {
                TvType.Movie
            } else {
                TvType.TvSeries
            }

        val description = document
            .selectFirst("article.post-info-content > p")
            ?.text()
            ?.trim()

        val actors = document
            .select("div.post-info-cast a")
            .mapNotNull {

                val actorName = it
                    .selectFirst("strong")
                    ?.text()
                    ?: return@mapNotNull null

                val actorImg = it
                    .selectFirst("img")
                    ?.attr("data-src")

                Actor(
                    actorName,
                    actorImg
                )
            }

        val recommendations = document
            .select(
                "div.section-slider-container div.slider-slide"
            )
            .mapNotNull {

                val recName = it
                    .selectFirst("a")
                    ?.attr("title")
                    ?: return@mapNotNull null

                val recHref = fixUrlNull(
                    it.selectFirst("a")
                        ?.attr("href")
                ) ?: return@mapNotNull null

                val recPosterUrl =
                    fixUrlNull(
                        it.selectFirst("img")
                            ?.attr("data-src")
                    )
                        ?: fixUrlNull(
                            it.selectFirst("img")
                                ?.attr("src")
                        )

                newTvSeriesSearchResponse(
                    recName,
                    recHref,
                    TvType.TvSeries
                ) {
                    this.posterUrl = recPosterUrl
                }
            }

        return if (tvType == TvType.TvSeries) {

            val trailer = getTrailer(document)

            val episodes = document
                .select("div.seasons-tab-content a")
                .mapNotNull {

                    val epName = it
                        .selectFirst("h4")
                        ?.text()
                        ?.trim()
                        ?: return@mapNotNull null

                    val epHref = fixUrlNull(
                        it.attr("href")
                    ) ?: return@mapNotNull null

                    val epEpisode = Regex(
                        """(\d+)\. ?Bölüm"""
                    )
                        .find(epName)
                        ?.groupValues
                        ?.get(1)
                        ?.toIntOrNull()

                    val epSeason = Regex(
                        """(\d+)\. ?Sezon"""
                    )
                        .find(epName)
                        ?.groupValues
                        ?.get(1)
                        ?.toIntOrNull()
                        ?: 1

                    newEpisode(epHref) {
                        this.name = epName
                        this.season = epSeason
                        this.episode = epEpisode
                    }
                }

            newTvSeriesLoadResponse(
                title,
                url,
                TvType.TvSeries,
                episodes
            ) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.recommendations = recommendations

                addActors(actors)
                addTrailer(trailer)
            }

        } else {

            val trailer = getTrailer(document)

            newMovieLoadResponse(
                title,
                url,
                TvType.Movie,
                url
            ) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.recommendations = recommendations

                addActors(actors)
                addTrailer(trailer)
            }
        }
    }

    private fun getTrailer(
        document: org.jsoup.nodes.Document
    ): String? {

        return document
            .selectFirst("div.post-info-trailer button")
            ?.attr("data-modal")
            ?.substringAfter("trailer/")
            ?.takeIf { it.isNotBlank() }
            ?.let {
                "https://www.youtube.com/embed/$it"
            }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        Log.d(
            "HDFilmCehennemi",
            "=========================================="
        )

        Log.d(
            "HDFilmCehennemi",
            "LOAD LINKS"
        )

        Log.d(
            "HDFilmCehennemi",
            "DATA: $data"
        )

        Log.d(
            "HDFilmCehennemi",
            "=========================================="
        )

        var found = false

        try {

            val document = app.get(
                data,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to mainUrl
                )
            ).document

            val alternativeLinks = document.select(
                "div.alternative-links"
            )

            Log.d(
                "HDFilmCehennemi",
                "Alternative link sayısı: ${alternativeLinks.size}"
            )

            for (element in alternativeLinks) {

                val langCode = element
                    .attr("data-lang")
                    .uppercase()
                    .ifBlank {
                        "TR"
                    }

                val buttons = element.select(
                    "button.alternative-link"
                )

                Log.d(
                    "HDFilmCehennemi",
                    "Dil: $langCode"
                )

                Log.d(
                    "HDFilmCehennemi",
                    "Buton sayısı: ${buttons.size}"
                )

                for (button in buttons) {

                    val sourceName = button
                        .text()
                        .replace(
                            Regex("\\(.*?\\)"),
                            ""
                        )
                        .trim() +
                            " [$langCode]"

                    val videoID = button
                        .attr("data-video")
                        .trim()

                    if (videoID.isBlank()) {
                        Log.e(
                            "HDFilmCehennemi",
                            "Video ID boş: $sourceName"
                        )
                        continue
                    }

                    try {

                        Log.d(
                            "HDFilmCehennemi",
                            "------------------------------------------"
                        )

                        Log.d(
                            "HDFilmCehennemi",
                            "SOURCE: $sourceName"
                        )

                        Log.d(
                            "HDFilmCehennemi",
                            "VIDEO ID: $videoID"
                        )

                        /*
                         * 1. Video endpoint
                         */

                        val videoUrl =
                            "$mainUrl/video/$videoID/"

                        Log.d(
                            "HDFilmCehennemi",
                            "VIDEO URL: $videoUrl"
                        )

                        val videoResponse = app.get(
                            videoUrl,
                            headers = mapOf(
                                "X-Requested-With" to "fetch",
                                "Referer" to data,
                                "User-Agent" to USER_AGENT,
                                "Accept" to "*/*"
                            )
                        )

                        val responseText = videoResponse.text

                        Log.d(
                            "HDFilmCehennemi",
                            "VIDEO RESPONSE CODE: ${videoResponse.code}"
                        )

                        Log.d(
                            "HDFilmCehennemi",
                            "VIDEO RESPONSE LENGTH: ${responseText.length}"
                        )

                        /*
                         * 2. JSON/HTML escape temizleme
                         */

                        val cleanJson = responseText
                            .replace("\\\"", "\"")
                            .replace("\\/", "/")
                            .replace("\\u0026", "&")
                            .replace("&amp;", "&")

                        /*
                         * 3. iframe src bul
                         */

                        var iframeSrc =
                            Regex(
                                """src\s*=\s*["'](https?://[^"']+)["']"""
                            )
                                .find(cleanJson)
                                ?.groupValues
                                ?.get(1)

                        /*
                         * data-src
                         */

                        if (iframeSrc.isNullOrBlank()) {

                            iframeSrc =
                                Regex(
                                    """data-src\s*=\s*["'](https?://[^"']+)["']"""
                                )
                                    .find(cleanJson)
                                    ?.groupValues
                                    ?.get(1)
                        }

                        /*
                         * iframe URL JSON içerisinde farklı şekilde
                         * bulunabiliyorsa URL olarak ara.
                         */

                        if (iframeSrc.isNullOrBlank()) {

                            iframeSrc =
                                Regex(
                                    """https?://[^"'\\\s<>]+"""
                                )
                                    .find(cleanJson)
                                    ?.value
                        }

                        if (iframeSrc.isNullOrBlank()) {

                            Log.e(
                                "HDFilmCehennemi",
                                "Iframe bulunamadı: $sourceName"
                            )

                            Log.e(
                                "HDFilmCehennemi",
                                "Response: ${cleanJson.take(1000)}"
                            )

                            continue
                        }

                        /*
                         * URL temizle
                         */

                        iframeSrc = iframeSrc
                            .replace("\\/", "/")
                            .replace("&amp;", "&")
                            .trim()

                        var finalIframe = fixUrl(
                            iframeSrc
                        )

                        /*
                         * rapidrame_id
                         */

                        if (
                            finalIframe.contains(
                                "rapidrame_id=",
                                ignoreCase = true
                            )
                        ) {

                            val rapidId = finalIframe
                                .substringAfter(
                                    "rapidrame_id=",
                                    ""
                                )
                                .substringBefore("&")
                                .trim()

                            if (rapidId.isNotBlank()) {

                                finalIframe =
                                    "$mainUrl/playerr/$rapidId"

                                Log.d(
                                    "HDFilmCehennemi",
                                    "Rapidrame dönüştürüldü: $finalIframe"
                                )
                            }
                        }

                        Log.d(
                            "HDFilmCehennemi",
                            "PLAYER URL: $finalIframe"
                        )

                        /*
                         * 4. Player sayfasını al
                         */

                        val playerResponse = app.get(
                            finalIframe,
                            headers = mapOf(
                                "Referer" to data,
                                "User-Agent" to USER_AGENT,
                                "Accept" to "text/html,application/xhtml+xml"
                            )
                        )

                        val playerPage =
                            playerResponse.text

                        Log.d(
                            "HDFilmCehennemi",
                            "PLAYER HTTP CODE: ${playerResponse.code}"
                        )

                        Log.d(
                            "HDFilmCehennemi",
                            "PLAYER LENGTH: ${playerPage.length}"
                        )

                        /*
                         * 5. contentUrl
                         */

                        var contentUrl =
                            Regex(
                                """"contentUrl"\s*:\s*"([^"]+)""""
                            )
                                .find(playerPage)
                                ?.groupValues
                                ?.get(1)

                        /*
                         * Escape karakterlerini temizle
                         */

                        contentUrl = contentUrl
                            ?.replace("\\/", "/")
                            ?.replace("\\u0026", "&")
                            ?.replace("&amp;", "&")
                            ?.trim()

                        /*
                         * 6. master.txt alternatif arama
                         */

                        if (contentUrl.isNullOrBlank()) {

                            contentUrl =
                                Regex(
                                    """https?://[^"'\\\s<>]+/master\.txt(?:\?[^"'\\\s<>]*)?"""
                                )
                                    .find(playerPage)
                                    ?.value
                        }

                        /*
                         * 7. m3u8 alternatif arama
                         */

                        if (contentUrl.isNullOrBlank()) {

                            contentUrl =
                                Regex(
                                    """https?://[^"'\\\s<>]+\.m3u8(?:\?[^"'\\\s<>]*)?"""
                                )
                                    .find(playerPage)
                                    ?.value
                        }

                        if (contentUrl.isNullOrBlank()) {

                            Log.e(
                                "HDFilmCehennemi",
                                "HLS URL bulunamadı!"
                            )

                            Log.e(
                                "HDFilmCehennemi",
                                "PLAYER: $finalIframe"
                            )

                            Log.e(
                                "HDFilmCehennemi",
                                "PLAYER RESPONSE:"
                            )

                            Log.e(
                                "HDFilmCehennemi",
                                playerPage.take(3000)
                            )

                            continue
                        }

                        /*
                         * 8. URL temizliği
                         */

                        val masterUrl = fixUrl(
                            contentUrl
                                .replace("\\/", "/")
                                .replace("\\u0026", "&")
                                .replace("&amp;", "&")
                                .trim()
                        )

                        Log.d(
                            "HDFilmCehennemi",
                            "HLS URL: $masterUrl"
                        )

                        /*
                         * 9. HLS URL gerçekten playlist mi?
                         *
                         * Bu test çok önemli.
                         */

                        try {

                            val hlsResponse = app.get(
                                masterUrl,
                                headers = mapOf(
                                    "User-Agent" to USER_AGENT,
                                    "Referer" to finalIframe,
                                    "Accept" to "*/*"
                                )
                            )

                            val hlsBody = hlsResponse.text

                            Log.d(
                                "HDFilmCehennemi",
                                "HLS HTTP CODE: ${hlsResponse.code}"
                            )

                            Log.d(
                                "HDFilmCehennemi",
                                "HLS CONTENT TYPE: ${
                                    hlsResponse.headers["Content-Type"]
                                }"
                            )

                            Log.d(
                                "HDFilmCehennemi",
                                "HLS LENGTH: ${hlsBody.length}"
                            )

                            Log.d(
                                "HDFilmCehennemi",
                                "HLS BODY:"
                            )

                            Log.d(
                                "HDFilmCehennemi",
                                hlsBody.take(1000)
                            )

                            /*
                             * HTTP hata
                             */

                            if (hlsResponse.code !in 200..299) {

                                Log.e(
                                    "HDFilmCehennemi",
                                    "HLS sunucusu HTTP ${hlsResponse.code} döndürdü."
                                )

                                continue
                            }

                            /*
                             * Gerçek HLS playlist kontrolü
                             */

                            val isHls =
                                hlsBody.trimStart()
                                    .startsWith("#EXTM3U")

                            if (!isHls) {

                                Log.e(
                                    "HDFilmCehennemi",
                                    "URL #EXTM3U ile başlamıyor."
                                )

                                continue
                            }

                        } catch (hlsException: Exception) {

                            Log.e(
                                "HDFilmCehennemi",
                                "HLS test hatası: ${hlsException.message}",
                                hlsException
                            )

                            /*
                             * HLS test başarısız olsa bile
                             * callback ile linki deneyebiliriz.
                             */
                        }

                        /*
                         * 10. CloudStream ExtractorLink
                         */

                        val streamHeaders = mapOf(
                            "User-Agent" to USER_AGENT,
                            "Referer" to finalIframe,
                            "Origin" to try {
                                java.net.URI(finalIframe).scheme +
                                    "://" +
                                    java.net.URI(finalIframe).host
                            } catch (_: Exception) {
                                mainUrl
                            }
                        )

                        Log.d(
                            "HDFilmCehennemi",
                            "------------------------------------------"
                        )

                        Log.d(
                            "HDFilmCehennemi",
                            "CALLBACK"
                        )

                        Log.d(
                            "HDFilmCehennemi",
                            "NAME: $sourceName"
                        )

                        Log.d(
                            "HDFilmCehennemi",
                            "URL: $masterUrl"
                        )

                        Log.d(
                            "HDFilmCehennemi",
                            "REFERER: ${streamHeaders["Referer"]}"
                        )

                        Log.d(
                            "HDFilmCehennemi",
                            "ORIGIN: ${streamHeaders["Origin"]}"
                        )

                        callback.invoke(
                            ExtractorLink(
                                source = name,
                                name = sourceName,
                                url = masterUrl,
                                referer = finalIframe,
                                quality = Qualities.Unknown.value,
                                type = ExtractorLinkType.M3U8,
                                headers = streamHeaders
                            )
                        )

                        found = true

                        Log.d(
                            "HDFilmCehennemi",
                            "LINK BAŞARIYLA EKLENDİ"
                        )

                    } catch (e: Exception) {

                        Log.e(
                            "HDFilmCehennemi",
                            "Kaynak işleme hatası: $sourceName",
                            e
                        )
                    }
                }
            }

        } catch (e: Exception) {

            Log.e(
                "HDFilmCehennemi",
                "loadLinks genel hata: ${e.message}",
                e
            )
        }

        Log.d(
            "HDFilmCehennemi",
            "=========================================="
        )

        Log.d(
            "HDFilmCehennemi",
            "SONUÇ - Link bulundu: $found"
        )

        Log.d(
            "HDFilmCehennemi",
            "=========================================="
        )

        return found
    }
}

data class Results(
    @JsonProperty("results")
    val results: List<String> = arrayListOf()
)
