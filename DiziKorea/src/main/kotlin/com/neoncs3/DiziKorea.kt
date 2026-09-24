package com.neoncs3

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Element
import java.net.URLEncoder

/**
 * DiziKorea provider for https://dizikorea3.com/
 *
 * Scanned against the current site structure:
 * - /dizi/{slug}
 * - /dizi/{slug}/sezon-{n}/bolum-{m}
 * - /film/{slug}
 * - category pages such as /kore-dizileri-izle-dq, /cin-dizileri and /filmler
 * - episode/movie providers exposed as VIP, Vidmoly and Filemoon buttons
 */
class DiziKorea : MainAPI() {

    override var mainUrl = "https://dizikorea3.com"
    override var name = "DiziKorea"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override var sequentialMainPage = true

    override val supportedTypes = setOf(
        TvType.TvSeries,
        TvType.Movie,
        TvType.Anime,
    )

    private val requestHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8",
    )

    override val mainPage = mainPageOf(
        "$mainUrl/kore-dizileri-izle-dq" to "Kore Dizileri",
        "$mainUrl/cin-dizileri" to "Çin Dizileri",
        "$mainUrl/japon-dizileri" to "Japon Dizileri",
        "$mainUrl/tayland-dizileri" to "Tayland Dizileri",
        "$mainUrl/tayvan-dizileri" to "Tayvan Dizileri",
        "$mainUrl/filipin-dizileri" to "Filipin Dizileri",
        "$mainUrl/filmler" to "Filmler",
        "$mainUrl/dizi-arsivi" to "Dizi Arşivi",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest,
    ): HomePageResponse {
        val url = withPage(request.data, page)
        val document = app.get(url, headers = requestHeaders).document

        val results = document.select("a[href]")
            .mapNotNull { it.toSearchResponse() }
            .distinctBy { it.url }

        return newHomePageResponse(
            request.name,
            results,
            hasNext = results.isNotEmpty(),
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val candidates = listOf(
            "$mainUrl/arama?q=$encoded",
            "$mainUrl/arama?query=$encoded",
            "$mainUrl/ara?q=$encoded",
            "$mainUrl/search?q=$encoded",
            "$mainUrl/?s=$encoded",
        )

        for (url in candidates) {
            val response = runCatching {
                app.get(url, headers = requestHeaders).document
            }.getOrNull() ?: continue

            val results = response.select("a[href]")
                .mapNotNull { it.toSearchResponse() }
                .distinctBy { it.url }

            if (results.isNotEmpty()) return results
        }

        return emptyList()
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, headers = requestHeaders).document
        val normalized = url.lowercase()

        val title = document.selectFirst("h1")?.text()?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("meta[property='og:title']")?.attr("content")?.trim()
            ?: return null

        val poster = document.selectFirst("meta[property='og:image']")?.attr("content")
            ?.takeIf { it.isNotBlank() }
            ?.let(::fixUrl)
            ?: document.selectFirst("img")?.let(::posterOf)

        val plot = document.selectFirst("meta[name='description']")?.attr("content")?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: document.selectFirst(".description, .plot, article p, main p")?.text()?.trim()

        val text = document.text()
        val year = Regex("\\b(?:19|20)\\d{2}\\b")
            .find(text)?.value?.toIntOrNull()

        val score = Regex("(?i)(?:IMDb|IMDB|puan)\\s*[★:]?\\s*([0-9]+(?:[.,][0-9]+)?)")
            .find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.replace(',', '.')
            ?.toDoubleOrNull()

        return when {
            "/film/" in normalized -> {
                newMovieLoadResponse(title, url, TvType.Movie, url) {
                    posterUrl = poster
                    this.plot = plot
                    this.year = year
                    score?.let { this.score = Score.from10(it) }
                }
            }

            "/dizi/" in normalized -> {
                val episodes = parseEpisodes(document)
                newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                    posterUrl = poster
                    this.plot = plot
                    this.year = year
                    score?.let { this.score = Score.from10(it) }
                }
            }

            else -> null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val response = runCatching {
            app.get(
                data,
                headers = requestHeaders + ("Referer" to mainUrl),
            )
        }.getOrNull() ?: return false

        val document = response.document
        val candidates = LinkedHashSet<String>()

        // 1) Normal iframe/embed links.
        document.select(
            "iframe[src], iframe[data-src], [data-iframe], [data-embed], [data-video], [data-player]"
        ).forEach { element ->
            extractUrlFromElement(element)?.let(candidates::add)
        }

        // 2) Provider buttons/anchors. The site currently exposes VIP, Vidmoly and Filemoon.
        document.select("a[href], button, [role='button'], [data-url], [data-href], [data-src]")
            .filter { element ->
                val label = element.text().trim().lowercase()
                label.contains("vidmoly") ||
                    label.contains("filemoon") ||
                    label == "vip" ||
                    label.contains(" vip")
            }
            .forEach { element ->
                extractUrlFromElement(element)?.let(candidates::add)
            }

        // 3) Any obvious external player URLs embedded in attributes/script.
        document.select("[href], [src], [data-url], [data-href], [data-src], [data-link], [data-video], [data-iframe], [data-embed], [data-player], [onclick]")
            .forEach { element ->
                element.attributes().forEach { attr ->
                    val value = decodeEmbeddedText(attr.value.trim())
                    if (value.startsWith("http://") || value.startsWith("https://")) {
                        if (isExternalPlayer(value) || isMediaUrl(value)) {
                            candidates.add(value)
                        }
                    }
                }
            }

        val rawHtml = decodeEmbeddedText(
            buildString {
                append(document.html())
                document.select("script, noscript, template").forEach {
                    append('\n')
                    append(it.data())
                    append('\n')
                    append(it.html())
                }
            }
        )

        // DiziKorea'nın güncel HLS sağlayıcısı vmbox.space biçiminde yayın adresleri
        // üretiyor. Bu adresler HTML/JS içinde doğrudan veya escaped olarak bulunabiliyor.
        Regex(
            """(?i)https?://[a-z0-9.-]+\.vmbox\.space/hls/[^\s"'<>\\]+/master\.m3u8(?:\?[^\s"'<>]+)?"""
        )
            .findAll(rawHtml)
            .map { it.value.trimEnd(')', ']', ';', ',', "\"") }
            .forEach(candidates::add)

        // Genel medya/player URL taraması.
        Regex("https?://[^\\s\"'<>]+")
            .findAll(rawHtml)
            .map { it.value.trimEnd(')', ']', ';', ',') }
            .filter { isExternalPlayer(it) || isMediaUrl(it) }
            .forEach(candidates::add)

        var found = false

        for (candidate in candidates) {
            when {
                isMediaUrl(candidate) -> {
                    val type = when {
                        Regex("(?i)\\.m3u8(?:$|\\?)").containsMatchIn(candidate) -> ExtractorLinkType.M3U8
                        Regex("(?i)\\.mpd(?:$|\\?)").containsMatchIn(candidate) -> ExtractorLinkType.DASH
                        else -> ExtractorLinkType.VIDEO
                    }

                    callback(
                        newExtractorLink(
                            source = name,
                            name = hostLabel(candidate),
                            url = candidate,
                            type = type,
                        ) {
                            referer = data
                            quality = Qualities.Unknown.value
                            headers = mapOf(
                                "User-Agent" to USER_AGENT,
                                "Referer" to data,
                                "Origin" to mainUrl,
                                "Accept" to "*/*",
                                "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8",
                            )
                        }
                    )
                    found = true
                }

                else -> {
                    // Let CloudStream's built-in extractors handle supported hosts.
                    val ok = runCatching {
                        loadExtractor(
                            candidate,
                            data,
                            subtitleCallback,
                            callback,
                        )
                    }.getOrDefault(false)
                    found = ok || found
                }
            }
        }

        // 4) Public subtitle tracks only.
        document.select("track[src], track[data-src]").forEach { track ->
            val url = track.attr("src").ifBlank { track.attr("data-src") }
            if (url.isNotBlank()) {
                subtitleCallback(
                    newSubtitleFile(
                        track.attr("label").ifBlank { "Türkçe" },
                        fixUrl(url),
                    )
                )
            }
        }

        return found
    }

    private fun parseEpisodes(document: org.jsoup.nodes.Document): List<Episode> {
        val episodes = document.select("a[href]")
            .mapNotNull { element ->
                val href = element.attr("href").trim()
                val absolute = fixUrlNull(href) ?: return@mapNotNull null

                val match = Regex(
                    "(?i)/sezon-(\\d+)/bolum-(\\d+)"
                ).find(absolute) ?: return@mapNotNull null

                val season = match.groupValues[1].toIntOrNull() ?: return@mapNotNull null
                val episode = match.groupValues[2].toIntOrNull() ?: return@mapNotNull null

                val label = element.text().trim().ifBlank { "$episode. Bölüm" }

                newEpisode(absolute) {
                    name = label
                    this.season = season
                    this.episode = episode
                    posterUrl = element.selectFirst("img")?.let(::posterOf)
                }
            }
            .distinctBy { it.data }
            .sortedWith(
                compareBy<Episode> { it.season ?: 0 }
                    .thenBy { it.episode ?: 0 }
            )

        return episodes
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val href = attr("href").trim()
        val absolute = fixUrlNull(href) ?: return null
        val path = absolute.lowercase()

        if (path == mainUrl ||
            absolute.contains("/sezon-", ignoreCase = true) ||
            absolute.contains("/bolum-", ignoreCase = true)
        ) return null

        val type = when {
            "/film/" in path -> TvType.Movie
            "/dizi/" in path -> TvType.TvSeries
            else -> return null
        }

        val title = text().trim().ifBlank {
            selectFirst("img")?.attr("alt")?.trim().orEmpty()
        }
        if (title.isBlank()) return null

        val poster = selectFirst("img")?.let(::posterOf)

        return when (type) {
            TvType.Movie -> newMovieSearchResponse(title, absolute, TvType.Movie) {
                posterUrl = poster
            }

            else -> newTvSeriesSearchResponse(title, absolute, TvType.TvSeries) {
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
        )

        for (attribute in attrs) {
            val value = element.attr(attribute).trim()
            if (value.isNotBlank() &&
                (value.startsWith("http://") || value.startsWith("https://") || value.startsWith("//") || value.startsWith("/"))
            ) {
                return fixUrl(value)
            }
        }

        return null
    }

    private fun isExternalPlayer(url: String): Boolean {
        val value = url.lowercase()
        return value.contains("vidmoly") ||
            value.contains("filemoon") ||
            value.contains("vidmolyme") ||
            value.contains("vidmolyto") ||
            value.contains("vidmolybiz") ||
            value.contains("vidmoly.net")
    }

    private fun isVmboxHls(url: String): Boolean {
        return Regex(
            """(?i)https?://[a-z0-9.-]+\.vmbox\.space/hls/[^\s"'<>]+/master\.m3u8(?:\?[^\s"'<>]+)?"""
        ).matches(url)
    }

    private fun isMediaUrl(url: String): Boolean {
        val value = url.lowercase()
        return Regex("(?i)\\.(m3u8|mpd|mp4)(?:$|[?#])").containsMatchIn(value) ||
            value.contains(".urlset/master.m3u8") ||
            value.contains("/hls/") && value.contains("/master.m3u8")
    }

    private fun decodeEmbeddedText(value: String): String {
        return value
            .replace("\\/", "/")
            .replace("\\\"", "\"")
            .replace("\\u002F", "/", ignoreCase = true)
            .replace("\\u003A", ":", ignoreCase = true)
            .replace("&amp;", "&", ignoreCase = true)
            .replace("&quot;", "\"", ignoreCase = true)
            .replace("&#x2F;", "/", ignoreCase = true)
            .replace("&#47;", "/", ignoreCase = true)
            .replace("\n", " ")
            .replace("\r", " ")
    }

    private fun hostLabel(url: String): String {
        return runCatching {
            java.net.URI(url).host
        }.getOrNull()?.ifBlank { "DiziKorea" } ?: "DiziKorea"
    }

    private fun posterOf(element: Element): String? {
        val raw = element.attr("data-src")
            .ifBlank { element.attr("src") }
            .ifBlank { element.attr("data-lazy-src") }

        return raw.takeIf { it.isNotBlank() }?.let(::fixUrl)
    }

    private fun withPage(url: String, page: Int): String {
        if (page <= 1) return url
        return if (url.contains("?")) "$url&page=$page" else "$url?page=$page"
    }
}
