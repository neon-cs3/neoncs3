package com.neoncs3

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.net.URI


class DiziBoxizle : MainAPI() {

    override var mainUrl = "https://diziboxizle.com"
    override var name = "DiziBoxizle"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override var sequentialMainPage = true

    override val supportedTypes = setOf(
        TvType.TvSeries,
        TvType.Movie,
    )

    private val requestHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8",
        "Referer" to "$mainUrl/",
    )

    override val mainPage = mainPageOf(
        "$mainUrl/dizi-turu/" to "Tüm Diziler",
        "$mainUrl/tur/filmler/" to "Filmler",
        "$mainUrl/tum-bolumler/" to "Son Bölümler",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest,
    ): HomePageResponse {
        val url = withPage(request.data, page)
        val document = runCatching {
            app.get(url, headers = requestHeaders).document
        }.getOrNull() ?: return newHomePageResponse(request.name, emptyList(), false)

        val results = document.select("a[href]")
            .mapNotNull { it.toSearchResponse() }
            .distinctBy { it.url }

        val hasNext = results.isNotEmpty() && page < 50 && hasNextPage(document, page)

        return newHomePageResponse(
            request.name,
            results,
            hasNext = hasNext,
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim()
        if (q.length < 2) return emptyList()

        val encoded = URLEncoder.encode(q, "UTF-8")
        val urls = listOf(
            "$mainUrl/?s=$encoded",
            "$mainUrl/?search=$encoded",
            "$mainUrl/ara/$encoded/",
        )

        for (searchUrl in urls) {
            val document = runCatching {
                app.get(searchUrl, headers = requestHeaders).document
            }.getOrNull() ?: continue

            val results = document.select("a[href]")
                .mapNotNull { it.toSearchResponse() }
                .distinctBy { it.url }
                .filterNot { isEpisodeUrl(it.url) }

            if (results.isNotEmpty()) return results
        }

        return emptyList()
    }

    override suspend fun load(url: String): LoadResponse? {
        val normalizedUrl = fixUrl(url)
        val document = runCatching {
            app.get(
                normalizedUrl,
                headers = requestHeaders + ("Referer" to "$mainUrl/"),
            ).document
        }.getOrNull() ?: return null

        val path = normalizedUrl.lowercase()

        // Movies use /film/{slug}/ on the current site.
        if ("/film/" in path) {
            val title = pageTitle(document) ?: return null
            return newMovieLoadResponse(title, normalizedUrl, TvType.Movie, normalizedUrl) {
                posterUrl = posterOf(document)
                plot = pagePlot(document)
                year = pageYear(document)
                pageRating(document)?.let { score = Score.from10(it) }
            }
        }

        // Episode URLs are data passed to loadLinks(), not standalone search results.
        if (isEpisodeUrl(normalizedUrl)) {
            return null
        }

        // DiziBOX series pages are root-level slugs, e.g. /the-lowdown/.
        val title = pageTitle(document) ?: return null
        val episodes = parseEpisodes(document)

        if (episodes.isEmpty()) return null

        return newTvSeriesLoadResponse(title, normalizedUrl, TvType.TvSeries, episodes) {
            posterUrl = posterOf(document)
            plot = pagePlot(document)
            year = pageYear(document)
            pageRating(document)?.let { score = Score.from10(it) }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val episodeUrl = fixUrl(data)
        val response = runCatching {
            app.get(
                episodeUrl,
                headers = requestHeaders + ("Referer" to "$mainUrl/"),
            )
        }.getOrNull() ?: return false

        val document = response.document
        val rawHtml = buildString {
            append(document.html())
            document.select("script, noscript, template").forEach {
                append('\n')
                append(it.data())
                append('\n')
                append(it.html())
            }
        }.decodeEmbeddedText()

        val candidates = LinkedHashSet<String>()

        // 1) iframe/embed/provider links shown by DiziBOX.
        document.select(
            "iframe[src], iframe[data-src], [data-iframe], [data-embed], [data-video], [data-player], " +
                "[data-embed-url], [data-player-url], [data-video-url], [data-stream]"
        ).forEach { element ->
            extractUrlFromElement(element)?.let(candidates::add)
        }

        // 2) Common provider buttons/anchors (for example Vidmoly/Ok.ru).
        document.select(
            "a[href], button, [role='button'], [data-url], [data-href], [data-src], [data-link]"
        ).forEach { element ->
            val label = element.text().trim().lowercase()
            val providerLike = label.contains("vidmoly") ||
                label.contains("okru") ||
                label.contains("ok.ru") ||
                label.contains("moly") ||
                label.contains("player") ||
                label.contains("1080p") ||
                label.contains("720p")

            if (providerLike) {
                extractUrlFromElement(element)?.let(candidates::add)
            }
        }

        // 3) URLs embedded in attributes, scripts or JSON.
        document.select(
            "[href], [src], [data-url], [data-href], [data-src], [data-link], [data-video], [data-iframe], " +
                "[data-embed], [data-player], [data-embed-url], [data-player-url], [data-video-url], [data-stream], [onclick]"
        ).forEach { element ->
            element.attributes().forEach { attr ->
                val value = attr.value.trim().decodeEmbeddedText()
                Regex("https?://[^\\s\\\"'<>]+", RegexOption.IGNORE_CASE)
                    .findAll(value)
                    .map { it.value.trimEnd(')', ']', '}', ';', ',') }
                    .forEach { url ->
                        if (isMediaUrl(url) || isExternalPlayer(url)) candidates.add(url)
                    }
            }
        }

        // 4) Direct HLS/media URLs in page source.
        Regex("https?://[^\\s\\\"'<>]+", RegexOption.IGNORE_CASE)
            .findAll(rawHtml)
            .map { it.value.trimEnd(')', ']', '}', ';', ',') }
            .filter { isMediaUrl(it) || isExternalPlayer(it) }
            .forEach(candidates::add)

        // 5) Explicitly cover the vmeas.cloud format from the supplied player URL:
        //    https://box-1579-p.vmeas.cloud/hls2/.../index-v1-a1.m3u8?...token...
        Regex(
            "https?://[a-z0-9.-]+\\.vmeas\\.cloud/[^\\s\\\"'<>]+(?:\\.m3u8)(?:\\?[^\\s\\\"'<>]+)?",
            RegexOption.IGNORE_CASE,
        ).findAll(rawHtml)
            .map { it.value.trimEnd(')', ']', '}', ';', ',') }
            .forEach(candidates::add)

        var found = false

        for (candidate in candidates) {
            val clean = candidate.decodeEmbeddedText()

            when {
                isMediaUrl(clean) -> {
                    val type = when {
                        Regex("(?i)\\.m3u8(?:$|\\?)").containsMatchIn(clean) -> ExtractorLinkType.M3U8
                        Regex("(?i)\\.mpd(?:$|\\?)").containsMatchIn(clean) -> ExtractorLinkType.DASH
                        else -> ExtractorLinkType.VIDEO
                    }

                    callback(
                        newExtractorLink(
                            source = name,
                            name = hostLabel(clean),
                            url = clean,
                            type = type,
                        ) {
                            referer = episodeUrl
                            quality = qualityFromUrl(clean)
                            headers = mapOf(
                                "User-Agent" to USER_AGENT,
                                "Referer" to episodeUrl,
                                "Accept" to "*/*",
                                "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8",
                            )
                        }
                    )
                    found = true
                }

                isExternalPlayer(clean) -> {
                    val ok = runCatching {
                        loadExtractor(
                            clean,
                            episodeUrl,
                            subtitleCallback,
                            callback,
                        )
                    }.getOrDefault(false)
                    found = ok || found
                }
            }
        }

        // Public subtitle tracks only.
        document.select("track[src], track[data-src]").forEach { track ->
            val raw = track.attr("src").ifBlank { track.attr("data-src") }
            if (raw.isNotBlank()) {
                subtitleCallback(
                    newSubtitleFile(
                        track.attr("label").ifBlank { "Türkçe" },
                        fixUrl(raw),
                    )
                )
            }
        }

        return found
    }

    private fun parseEpisodes(document: Document): List<Episode> {
        return document.select("a[href]")
            .mapNotNull { element ->
                val href = fixUrlNull(element.attr("href")) ?: return@mapNotNull null
                val match = EPISODE_PATTERN.find(href)
                    ?: ALT_EPISODE_PATTERN.find(href)
                    ?: return@mapNotNull null

                val season = match.groupValues.getOrNull(1)?.toIntOrNull() ?: return@mapNotNull null
                val episode = match.groupValues.getOrNull(2)?.toIntOrNull() ?: return@mapNotNull null

                val label = element.text().trim().ifBlank { "$season. Sezon $episode. Bölüm" }

                newEpisode(href) {
                    name = label
                    this.season = season
                    this.episode = episode
                    posterUrl = element.selectFirst("img")?.let(::posterOfElement)
                }
            }
            .distinctBy { it.data }
            .sortedWith(
                compareBy<Episode> { it.season ?: 0 }
                    .thenBy { it.episode ?: 0 }
            )
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val href = attr("href").trim()
        val absolute = fixUrlNull(href) ?: return null
        val path = absolute.lowercase()

        if (absolute == "$mainUrl/" ||
            path.contains("/dizi-turu/") ||
            path.contains("/tum-bolumler/") ||
            path.contains("/tur/") ||
            path.contains("/kategori/") ||
            path.contains("/tag/") ||
            path.contains("/author/") ||
            path.contains("/page/") ||
            isEpisodeUrl(absolute)
        ) return null

        val title = text().trim().ifBlank {
            selectFirst("img")?.attr("alt")?.trim().orEmpty()
        }
        if (title.isBlank()) return null

        val poster = selectFirst("img")?.let(::posterOfElement)

        return if (path.contains("/film/")) {
            newMovieSearchResponse(title, absolute, TvType.Movie) {
                posterUrl = poster
            }
        } else {
            // DiziBOX series pages are root-level slugs.
            newTvSeriesSearchResponse(title, absolute, TvType.TvSeries) {
                posterUrl = poster
            }
        }
    }

    private fun extractUrlFromElement(element: Element): String? {
        val attrs = listOf(
            "href",
            "src",
            "data-url",
            "data-href",
            "data-src",
            "data-link",
            "data-video",
            "data-iframe",
            "data-embed",
            "data-player",
            "data-embed-url",
            "data-player-url",
            "data-video-url",
            "data-stream",
        )

        for (attribute in attrs) {
            val raw = element.attr(attribute).trim()
            if (raw.isBlank()) continue

            val decoded = raw.decodeEmbeddedText()
            val direct = Regex("https?://[^\\s\\\"'<>]+", RegexOption.IGNORE_CASE)
                .find(decoded)
                ?.value
                ?.trimEnd(')', ']', '}', ';', ',')

            if (!direct.isNullOrBlank()) return direct

            if (decoded.startsWith("//") || decoded.startsWith("/")) {
                return fixUrl(decoded)
            }
        }

        return null
    }

    private fun isEpisodeUrl(url: String): Boolean {
        return EPISODE_PATTERN.containsMatchIn(url) || ALT_EPISODE_PATTERN.containsMatchIn(url)
    }

    private fun isExternalPlayer(url: String): Boolean {
        val value = url.lowercase()
        return value.contains("vidmoly") ||
            value.contains("ok.ru") ||
            value.contains("odnoklassniki") ||
            value.contains("doodstream") ||
            value.contains("streamtape") ||
            value.contains("filemoon")
    }

    private fun isMediaUrl(url: String): Boolean {
        val value = url.lowercase()
        return Regex("(?i)\\.(m3u8|mpd|mp4|webm)(?:$|[?#])").containsMatchIn(value) ||
            value.contains("/hls2/") && value.contains(".m3u8") ||
            value.contains(".vmeas.cloud/") && value.contains(".m3u8")
    }

    private fun String.decodeEmbeddedText(): String {
        return this
            .replace("\\/", "/")
            .replace("\\\"", "\"")
            .replace("\\u002F", "/", ignoreCase = true)
            .replace("\\u003A", ":", ignoreCase = true)
            .replace("&amp;", "&", ignoreCase = true)
            .replace("&quot;", "\"", ignoreCase = true)
            .replace("&#x2F;", "/", ignoreCase = true)
            .replace("&#47;", "/", ignoreCase = true)
            .replace("\\u0026", "&", ignoreCase = true)
    }

    private fun pageTitle(document: Document): String? {
        return document.selectFirst("h1")?.text()?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("meta[property='og:title']")?.attr("content")?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    private fun pagePlot(document: Document): String? {
        return document.selectFirst("meta[name='description']")?.attr("content")?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: document.selectFirst(".description, .plot, article p, main p")?.text()?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    private fun pageYear(document: Document): Int? {
        val text = document.text()
        return Regex("(?<!\\d)(?:19|20)\\d{2}(?!\\d)")
            .find(text)?.value?.toIntOrNull()
    }

    private fun pageRating(document: Document): Double? {
        val text = document.text()
        return Regex("(?i)(?:IMDb|IMDB)\\s*[:/]?\\s*([0-9]+(?:[.,][0-9]+)?)")
            .find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.replace(',', '.')
            ?.toDoubleOrNull()
    }

    private fun posterOf(document: Document): String? {
        return document.selectFirst("meta[property='og:image']")?.attr("content")
            ?.takeIf { it.isNotBlank() }
            ?.let(::fixUrl)
            ?: document.selectFirst("img")?.let(::posterOfElement)
    }

    private fun posterOfElement(element: Element): String? {
        val raw = element.attr("data-src")
            .ifBlank { element.attr("data-lazy-src") }
            .ifBlank { element.attr("src") }
        return raw.takeIf { it.isNotBlank() }?.let(::fixUrl)
    }

    private fun hostLabel(url: String): String {
        return runCatching { URI(url).host }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: "DiziBoxizle"
    }

    private fun qualityFromUrl(url: String): Int {
        val value = url.lowercase()
        return when {
            "2160" in value || "4k" in value -> Qualities.P2160.value
            "1440" in value -> Qualities.P1440.value
            "1080" in value -> Qualities.P1080.value
            "720" in value -> Qualities.P720.value
            "480" in value -> Qualities.P480.value
            "360" in value -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }

    private fun withPage(url: String, page: Int): String {
        if (page <= 1) return url
        return "${url.trimEnd('/')}/page/$page/"
    }

    private fun hasNextPage(document: Document, page: Int): Boolean {
        val nextPage = page + 1
        return document.select("a[href]").any { element ->
            val href = fixUrlNull(element.attr("href")).orEmpty()
            val label = element.text().trim().lowercase()

            label.contains("sonraki") ||
                label.contains("next") ||
                label == "»" ||
                href.endsWith("/page/$nextPage/") ||
                href.contains("/page/$nextPage/?")
        }
    }

    companion object {
        // /the-lowdown-1-sezon-1-bolum/
        private val EPISODE_PATTERN = Regex(
            "(?i)-(\\d+)-sezon-(\\d+)-bolum(?:/|$)"
        )

        // Fallback for /.../sezon-1-bolum-1/ style URLs.
        private val ALT_EPISODE_PATTERN = Regex(
            "(?i)/sezon-(\\d+)-bolum-(\\d+)(?:/|$)"
        )
    }
}
