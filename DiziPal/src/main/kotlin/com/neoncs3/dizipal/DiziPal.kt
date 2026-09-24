package com.neoncs3.dizipal

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Element
import java.net.URLEncoder

class DiziPal : MainAPI() {
    override var mainUrl = "https://dizipal1583.com/"
    override var name = "DiziPal"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override var sequentialMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries)

    override val mainPage = mainPageOf(
        "${mainUrl}yabanci-dizi-izle" to "Yabancı Diziler",
        mainUrl to "Güncel",
    )

    private val requestHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8",
        "Referer" to mainUrl,
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val pageUrl = request.data
        val document = app.get(pageUrl, headers = requestHeaders).document
        val results = LinkedHashMap<String, SearchResponse>()

        document.select("a[href*='/series/']").forEach { element ->
            val response = element.toSeriesResponse() ?: return@forEach
            results.putIfAbsent(response.url, response)
        }

        // Ana sayfadaki son bölüm bağlantıları da katalogda görünsün.
        document.select("a[href*='/bolum/']").forEach { element ->
            val href = fixUrlNull(element.attr("href")) ?: return@forEach
            val text = element.text().trim()
            val parsed = parseEpisodeLabel(text)
            val title = parsed?.first ?: text
            if (title.isNotBlank() && href.isNotBlank()) {
                results.putIfAbsent(
                    href,
                    newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                        posterUrl = element.selectFirst("img")?.posterUrl()
                    }
                )
            }
        }

        return newHomePageResponse(request.name, results.values.toList(), hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query.trim(), Charsets.UTF_8.name())
        val url = "${mainUrl}yabanci-dizi-izle?kelime=$encoded"
        val document = app.get(url, headers = requestHeaders).document

        return document.select("a[href*='/series/']")
            .mapNotNull { it.toSeriesResponse() }
            .distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse? {
        val initialDocument = app.get(url, headers = requestHeaders).document

        val seriesUrl = if (url.contains("/bolum/")) {
            initialDocument.selectFirst("a[href*='/series/']")?.attr("href")?.let { fixUrl(it) }
                ?: return null
        } else {
            url
        }

        val seriesDocument = if (seriesUrl == url) initialDocument else app.get(seriesUrl, headers = requestHeaders).document
        val title = seriesDocument.selectFirst("h1")?.text()?.trim()
            ?: seriesDocument.selectFirst("meta[property='og:title']")?.attr("content")?.trim()
            ?: return null

        val poster = seriesDocument.selectFirst("meta[property='og:image']")?.attr("content")?.takeIf { it.isNotBlank() }?.let { fixUrl(it) }
            ?: seriesDocument.selectFirst("img")?.posterUrl()
        val plot = seriesDocument.selectFirst("meta[name='description']")?.attr("content")?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: seriesDocument.selectFirst("p")?.text()?.trim()
        val bodyText = seriesDocument.text()

        val year = Regex("(?i)Gösterim Yılı\\s*(?:\\n|:)?\\s*(\\d{4})").find(bodyText)
            ?.groupValues?.getOrNull(1)?.toIntOrNull()

        val rating = Regex("(?i)IMDB Puanı\\s*(?:\\n|:)?\\s*([0-9]+(?:[.,][0-9]+)?)")
            .find(bodyText)?.groupValues?.getOrNull(1)?.replace(',', '.')?.toDoubleOrNull()

        val episodes = seriesDocument.select("a[href*='/bolum/']")
            .mapNotNull { anchor ->
                val href = fixUrlNull(anchor.attr("href")) ?: return@mapNotNull null
                val label = anchor.text().trim()
                val parsed = parseEpisodeLabel(label) ?: return@mapNotNull null
                val season = parsed.second
                val episode = parsed.third
                newEpisode(href) {
                    name = parsed.first
                    this.season = season
                    this.episode = episode
                    posterUrl = anchor.selectFirst("img")?.posterUrl()
                }
            }
            .distinctBy { it.data }
            .sortedWith(compareBy<Episode> { it.season ?: 0 }.thenBy { it.episode ?: 0 })

        if (episodes.isEmpty()) return null

        return newTvSeriesLoadResponse(title, seriesUrl, TvType.TvSeries, episodes) {
            posterUrl = poster
            this.plot = plot
            this.year = year
            rating?.let { this.score = Score.from10(it) }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val directUrls = LinkedHashSet<String>()
        val subtitleUrls = LinkedHashSet<String>()
        val mediaReferers = LinkedHashMap<String, String>()

        // Önce bölüm sayfasını okuyup player iframe'ini bul.
        // chrome://media-internals kaydında gerçek oynatma çerçevesi iframe.php?v=...
        // olarak göründüğü için doğrudan bölüm URL'sini medya kaynağı olarak kullanmıyoruz.
        val episodeDocument = try {
            app.get(
                data,
                headers = requestHeaders,
            ).document
        } catch (_: Exception) {
            return false
        }

        fun collectMedia(document: org.jsoup.nodes.Document, pageUrl: String) {
            document.select("video[src], source[src], [data-video], [data-src], meta[property='og:video'], meta[itemprop='contentUrl']")
                .forEach { element ->
                    val raw = when {
                        element.attr("src").isNotBlank() -> element.attr("src")
                        element.attr("data-video").isNotBlank() -> element.attr("data-video")
                        element.attr("data-src").isNotBlank() -> element.attr("data-src")
                        else -> element.attr("content")
                    }
                    val fixed = raw.takeIf { it.isNotBlank() }?.let { fixUrl(it) }
                    if (fixed != null && isDirectMediaUrl(fixed)) {
                        directUrls.add(fixed)
                        mediaReferers.putIfAbsent(fixed, pageUrl)
                    }
                }

            // Player sayfasında JS içine gömülmüş m3u8/mpd/mp4 adreslerini yakala.
            val html = document.html().replace("\\/", "/")
            Regex("""(?i)https?://[^\s\"'<>]+\.(?:m3u8|mpd|mp4)(?:\?[^\s\"'<>]*)?""")
                .findAll(html)
                .map { it.groupValues[0] }
                .forEach {
                    directUrls.add(it)
                    mediaReferers.putIfAbsent(it, pageUrl)
                }

            document.select("track[kind='subtitles'], track[kind='captions'], track[src]")
                .forEach { track ->
                    val src = track.attr("src").takeIf { it.isNotBlank() } ?: return@forEach
                    subtitleUrls.add(fixUrl(src))
                }
        }

        // Bölüm sayfasının kendisi doğrudan medya veriyorsa bunu da kabul et.
        collectMedia(episodeDocument, data)

        if (directUrls.isEmpty()) {
            val iframeUrls = LinkedHashSet<String>()

            episodeDocument.select("iframe[src], iframe[data-src], [data-iframe], [data-player-url]")
                .forEach { element ->
                    val raw = element.attr("src")
                        .ifBlank { element.attr("data-src") }
                        .ifBlank { element.attr("data-iframe") }
                        .ifBlank { element.attr("data-player-url") }
                    val fixed = raw.takeIf { it.isNotBlank() }?.let { fixUrl(it) }
                    if (fixed != null) iframeUrls.add(fixed)
                }

            // Kayıtlarda iframe adresi script içinde tutuluyorsa onu da yakala.
            val pageHtml = episodeDocument.html().replace("\\/", "/")
            Regex("""(?i)https?://[^\s\"'<>]+/iframe\.php\?[^\s\"'<>]+""")
                .findAll(pageHtml)
                .map { it.groupValues[0] }
                .forEach { iframeUrls.add(it) }

            for (iframeUrl in iframeUrls) {
                val iframeOrigin = Regex("(?i)^https?://[^/]+")
                    .find(iframeUrl)?.value
                    ?: mainUrl.trimEnd('/')

                val iframeDocument = try {
                    app.get(
                        iframeUrl,
                        headers = mapOf(
                            "User-Agent" to USER_AGENT,
                            "Referer" to data,
                            "Origin" to iframeOrigin,
                            "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8",
                        ),
                    ).document
                } catch (_: Exception) {
                    continue
                }

                collectMedia(iframeDocument, iframeUrl)

                if (directUrls.isNotEmpty()) {
                    // Hangi player iframe'ının medya verdiğini bulduğumuz anda dur.
                    break
                }
            }
        }

        subtitleUrls.forEach { subtitleUrl ->
            val lang = "Türkçe"
            subtitleCallback(newSubtitleFile(lang, subtitleUrl))
        }

        directUrls.forEach { mediaUrl ->
            val type = when {
                mediaUrl.contains(".m3u8", ignoreCase = true) -> ExtractorLinkType.M3U8
                mediaUrl.contains(".mpd", ignoreCase = true) -> ExtractorLinkType.DASH
                else -> ExtractorLinkType.VIDEO
            }

            val mediaOrigin = Regex("(?i)^https?://[^/]+")
                .find(mediaUrl)?.value

            callback(
                newExtractorLink(
                    source = name,
                    name = "DiziPal",
                    url = mediaUrl,
                    type = type,
                ) {
                    val mediaReferer = mediaReferers[mediaUrl] ?: data
                    referer = mediaReferer
                    quality = Qualities.Unknown.value
                    headers = buildMap {
                        put("User-Agent", USER_AGENT)
                        put("Referer", mediaReferer)
                        put("Accept", "*/*")
                        put("Accept-Language", "tr-TR,tr;q=0.9,en;q=0.8")
                        if (mediaOrigin != null) put("Origin", mediaOrigin)
                    }
                }
            )
        }

        return directUrls.isNotEmpty()
    }

    private fun Element.toSeriesResponse(): SearchResponse? {
        val href = fixUrlNull(attr("href")) ?: return null
        if (!href.contains("/series/")) return null
        val title = text().trim().ifBlank {
            selectFirst("img")?.attr("alt")?.trim().orEmpty()
        }
        if (title.isBlank()) return null

        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            posterUrl = selectFirst("img")?.posterUrl()
        }
    }

    private fun Element?.posterUrl(): String? {
        if (this == null) return null
        val raw = attr("data-src").ifBlank { attr("src") }.trim()
        return raw.takeIf { it.isNotBlank() }?.let { fixUrl(it) }
    }

    private fun parseEpisodeLabel(text: String): Triple<String, Int, Int>? {
        val regex = Regex("(?i)^(.*?)\\s+(\\d+)\\.\\s*Sezon\\s+(\\d+)\\.\\s*Bölüm")
        val match = regex.find(text.trim()) ?: return null
        val title = match.groupValues[1].trim()
        val season = match.groupValues[2].toIntOrNull() ?: return null
        val episode = match.groupValues[3].toIntOrNull() ?: return null
        return Triple(title, season, episode)
    }

    private fun isDirectMediaUrl(url: String): Boolean {
        return Regex("(?i)\\.(m3u8|mpd|mp4)(?:$|\\?)").containsMatchIn(url)
    }
}
