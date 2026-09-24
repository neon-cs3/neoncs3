package com.neoncs3

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder

/**
 * DiziKorea provider for https://dizikorea3.com/
 *
 * Supported:
 * - /dizi/{slug}
 * - /dizi/{slug}/sezon-{n}/bolum-{m}
 * - /film/{slug}
 * - provider/player pages
 * - Vidmoly
 * - Filemoon
 * - VIP
 * - Vmbox HLS / master.m3u8
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
        "Accept" to "*/*",
        "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8",
    )

    /**
     * URL + bu URL'nin bulunduğu sayfanın referer'ı.
     *
     * Örnek:
     * Vidmoly player
     *      ↓
     * Vmbox master.m3u8
     *
     * Böylece Vmbox'a:
     * Referer = Vidmoly player URL
     * gönderilebilir.
     */
    private data class Candidate(
        val url: String,
        val referer: String,
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

        val document = app.get(
            url,
            headers = requestHeaders,
        ).document

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
                app.get(
                    url,
                    headers = requestHeaders,
                ).document
            }.getOrNull() ?: continue

            val results = response.select("a[href]")
                .mapNotNull { it.toSearchResponse() }
                .distinctBy { it.url }

            if (results.isNotEmpty()) {
                return results
            }
        }

        return emptyList()
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(
            url,
            headers = requestHeaders,
        ).document

        val normalized = url.lowercase()

        val title = document.selectFirst("h1")
            ?.text()
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("meta[property='og:title']")
                ?.attr("content")
                ?.trim()
            ?: return null

        val poster = document.selectFirst("meta[property='og:image']")
            ?.attr("content")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let(::fixUrl)
            ?: document.selectFirst("img")?.let(::posterOf)

        val plot = document.selectFirst("meta[name='description']")
            ?.attr("content")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: document.selectFirst(
                ".description, .plot, article p, main p"
            )?.text()?.trim()

        val text = document.text()

        val year = Regex("\\b(?:19|20)\\d{2}\\b")
            .find(text)
            ?.value
            ?.toIntOrNull()

        val score = Regex(
            "(?i)(?:IMDb|IMDB|puan)\\s*[★:]?\\s*([0-9]+(?:[.,][0-9]+)?)"
        )
            .find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.replace(',', '.')
            ?.toDoubleOrNull()

        return when {
            "/film/" in normalized -> {
                newMovieLoadResponse(
                    title,
                    url,
                    TvType.Movie,
                    url,
                ) {
                    posterUrl = poster
                    this.plot = plot
                    this.year = year
                    score?.let {
                        this.score = Score.from10(it)
                    }
                }
            }

            "/dizi/" in normalized -> {
                val episodes = parseEpisodes(document)

                newTvSeriesLoadResponse(
                    title,
                    url,
                    TvType.TvSeries,
                    episodes,
                ) {
                    posterUrl = poster
                    this.plot = plot
                    this.year = year
                    score?.let {
                        this.score = Score.from10(it)
                    }
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

        val candidates = LinkedHashMap<String, Candidate>()
        val scannedPages = HashSet<String>()

        /**
         * Aday URL ekler.
         */
        fun addCandidate(
            rawUrl: String?,
            referer: String,
        ) {
            if (rawUrl.isNullOrBlank()) {
                return
            }

            var url = decodeEmbeddedText(
                rawUrl.trim()
            )

            // JS URL'leri
            url = url
                .replace("\\/", "/")
                .replace("\\u002F", "/", ignoreCase = true)
                .replace("\\u003A", ":", ignoreCase = true)

            if (url.startsWith("//")) {
                url = "https:$url"
            } else if (url.startsWith("/")) {
                url = fixUrl(url)
            }

            if (
                !url.startsWith("http://") &&
                !url.startsWith("https://")
            ) {
                return
            }

            url = url.trimEnd(
                ')',
                ']',
                ';',
                ',',
                '"',
                '\''
            )

            if (url.isBlank()) {
                return
            }

            // Aynı URL daha önce bulunduysa ilk referer'ı koru.
            candidates.putIfAbsent(
                url,
                Candidate(
                    url = url,
                    referer = referer,
                )
            )
        }

        /**
         * HTML / JS içindeki bütün muhtemel URL'leri tara.
         */
        fun scanHtml(
            html: String,
            referer: String,
        ) {
            var decoded = decodeEmbeddedText(html)

            // \uXXXX
            decoded = decoded.replace(
                Regex("""\\u([0-9a-fA-F]{4})""")
            ) { match ->
                runCatching {
                    match.groupValues[1]
                        .toInt(16)
                        .toChar()
                        .toString()
                }.getOrDefault(
                    match.value
                )
            }

            // \xXX
            decoded = decoded.replace(
                Regex("""\\x([0-9a-fA-F]{2})""")
            ) { match ->
                runCatching {
                    match.groupValues[1]
                        .toInt(16)
                        .toChar()
                        .toString()
                }.getOrDefault(
                    match.value
                )
            }

            /**
             * Genel HTTP URL'leri.
             */
            Regex(
                """https?://[^\s"'<>\\]+"""
            )
                .findAll(decoded)
                .forEach {
                    addCandidate(
                        it.value,
                        referer
                    )
                }

            /**
             * Vmbox.
             *
             * Örnek:
             * https://box-1103-o.vmbox.space/hls/...,
             * .urlset/master.m3u8
             */
            Regex(
                """(?i)https?://[a-z0-9.-]+\.vmbox\.space[^\s"'<>\\]*"""
            )
                .findAll(decoded)
                .forEach {
                    addCandidate(
                        it.value,
                        referer
                    )
                }

            /**
             * Dplayer.
             */
            Regex(
                """(?i)https?://[a-z0-9.-]*dplayer82\.site[^\s"'<>\\]*"""
            )
                .findAll(decoded)
                .forEach {
                    addCandidate(
                        it.value,
                        referer
                    )
                }

            /**
             * Doğrudan m3u8.
             */
            Regex(
                """(?i)https?://[^\s"'<>\\]+\.m3u8(?:\?[^\s"'<>\\]*)?"""
            )
                .findAll(decoded)
                .forEach {
                    addCandidate(
                        it.value,
                        referer
                    )
                }

            /**
             * master.m3u8.
             */
            Regex(
                """(?i)https?://[^\s"'<>\\]+/master\.m3u8(?:\?[^\s"'<>\\]*)?"""
            )
                .findAll(decoded)
                .forEach {
                    addCandidate(
                        it.value,
                        referer
                    )
                }
        }

        /**
         * DOM elementindeki URL'leri tara.
         */
        fun scanElement(
            element: Element,
            referer: String,
        ) {
            val attributes = listOf(
                "href",
                "src",
                "data-src",
                "data-url",
                "data-href",
                "data-link",
                "data-video",
                "data-iframe",
                "data-embed",
                "data-player",
                "data-file",
                "data-stream",
                "data-source",
                "onclick",
            )

            for (attribute in attributes) {
                val value = element
                    .attr(attribute)
                    .trim()

                if (value.isBlank()) {
                    continue
                }

                addCandidate(
                    value,
                    referer
                )

                Regex(
                    """https?://[^\s"'<>\\]+"""
                )
                    .findAll(
                        decodeEmbeddedText(value)
                    )
                    .forEach {
                        addCandidate(
                            it.value,
                            referer
                        )
                    }
            }
        }

        // =====================================================
        // 1. DiziKorea bölüm sayfası
        // =====================================================

        val response = runCatching {
            app.get(
                data,
                headers = requestHeaders + mapOf(
                    "Referer" to mainUrl
                )
            )
        }.getOrNull() ?: return false

        val document = response.document

        // HTML tara.
        scanHtml(
            response.text,
            data
        )

        // Elementleri tara.
        document.select(
            "iframe, a, button, video, source, script, " +
                "template, noscript, " +
                "[data-url], [data-href], [data-src], " +
                "[data-video], [data-player], [data-iframe], " +
                "[data-embed], [data-link], [onclick]"
        ).forEach {
            scanElement(
                it,
                data
            )
        }

        // Script içerikleri.
        document.select(
            "script, template, noscript"
        ).forEach {
            scanHtml(
                it.data(),
                data
            )

            scanHtml(
                it.html(),
                data
            )
        }

        // =====================================================
        // 2. Bulunan player sayfalarını aç
        // =====================================================

        val initialCandidates = candidates.values.toList()

        for (candidate in initialCandidates) {

            val url = candidate.url
            val lower = url.lowercase()

            // Doğrudan medya URL'siyse tekrar açma.
            if (isMediaUrl(url)) {
                continue
            }

            /**
             * Sadece muhtemel video/player sağlayıcılarını tara.
             */
            val isPlayer =
                lower.contains("vidmoly") ||
                    lower.contains("filemoon") ||
                    lower.contains("dplayer82.site") ||
                    lower.contains("vmbox.space")

            if (!isPlayer) {
                continue
            }

            if (!scannedPages.add(url)) {
                continue
            }

            /**
             * Player URL'sini kendi referer'ı ile aç.
             */
            val playerResponse = runCatching {
                app.get(
                    url,
                    headers = requestHeaders + mapOf(
                        "Referer" to candidate.referer
                    )
                )
            }.getOrNull() ?: continue

            /**
             * Bu player sayfasındaki bulunan medya URL'lerinin
             * referer'ı artık PLAYER URL'si olacak.
             */
            scanHtml(
                playerResponse.text,
                url
            )

            val playerDocument = playerResponse.document

            playerDocument.select(
                "iframe, video, source, script, " +
                    "[src], [href], " +
                    "[data-src], [data-url], " +
                    "[data-video], " +
                    "[data-player], " +
                    "[data-iframe], " +
                    "[data-embed]"
            ).forEach {
                scanElement(
                    it,
                    url
                )
            }

            playerDocument.select(
                "script, template, noscript"
            ).forEach {
                scanHtml(
                    it.data(),
                    url
                )

                scanHtml(
                    it.html(),
                    url
                )
            }
        }

        // =====================================================
        // 3. Doğrudan medya bağlantılarını çıkar
        // =====================================================

        var found = false

        for (candidate in candidates.values) {

            val url = candidate.url

            if (!isMediaUrl(url)) {
                continue
            }

            val lower = url.lowercase()

            val type = when {
                lower.contains(".m3u8") ||
                    lower.contains("/master.m3u8") ||
                    lower.contains("/hls/") -> {
                    ExtractorLinkType.M3U8
                }

                lower.contains(".mpd") -> {
                    ExtractorLinkType.DASH
                }

                else -> {
                    ExtractorLinkType.VIDEO
                }
            }

            /**
             * Gerçek önemli nokta:
             *
             * Eğer m3u8 Vidmoly player sayfasından bulunduysa:
             * referer = Vidmoly URL
             *
             * Eğer doğrudan DiziKorea'dan bulunduysa:
             * referer = bölüm URL
             */
            val mediaReferer = candidate.referer.ifBlank {
                data
            }

            callback(
                newExtractorLink(
                    source = name,
                    name = hostLabel(url),
                    url = url,
                    type = type,
                ) {
                    referer = mediaReferer

                    quality = when {
                        lower.contains("1080") ->
                            Qualities.P1080.value

                        lower.contains("720") ->
                            Qualities.P720.value

                        lower.contains("480") ->
                            Qualities.P480.value

                        lower.contains("360") ->
                            Qualities.P360.value

                        else ->
                            Qualities.Unknown.value
                    }

                    /**
                     * Origin özellikle gönderilmiyor.
                     *
                     * Vmbox tarafında:
                     * Referer = player URL
                     * User-Agent = tarayıcı benzeri
                     */
                    headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Referer" to mediaReferer,
                        "Accept" to "*/*",
                        "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8",
                    )
                }
            )

            found = true
        }

        // =====================================================
        // 4. Vidmoly / Filemoon extractor
        // =====================================================

        for (candidate in candidates.values) {

            val url = candidate.url
            val lower = url.lowercase()

            if (isMediaUrl(url)) {
                continue
            }

            val supportedExtractor =
                lower.contains("vidmoly") ||
                    lower.contains("vidmoly.net") ||
                    lower.contains("vidmoly.to") ||
                    lower.contains("vidmoly.me") ||
                    lower.contains("filemoon") ||
                    lower.contains("filemoon.sx")

            if (!supportedExtractor) {
                continue
            }

            val ok = runCatching {
                loadExtractor(
                    url,
                    candidate.referer,
                    subtitleCallback,
                    callback,
                )
            }.getOrDefault(false)

            if (ok) {
                found = true
            }
        }

        // =====================================================
        // 5. Altyazılar
        // =====================================================

        document.select(
            "track[src], track[data-src]"
        ).forEach { track ->

            val subtitleUrl = track
                .attr("src")
                .ifBlank {
                    track.attr("data-src")
                }
                .trim()

            if (subtitleUrl.isNotBlank()) {
                subtitleCallback(
                    newSubtitleFile(
                        track.attr("label")
                            .ifBlank { "Türkçe" },
                        fixUrl(subtitleUrl),
                    )
                )
            }
        }

        return found
    }

    private fun parseEpisodes(
        document: org.jsoup.nodes.Document
    ): List<Episode> {

        return document
            .select("a[href]")
            .mapNotNull { element ->

                val href = element
                    .attr("href")
                    .trim()

                val absolute =
                    fixUrlNull(href)
                        ?: return@mapNotNull null

                val match = Regex(
                    "(?i)/sezon-(\\d+)/bolum-(\\d+)"
                ).find(absolute)
                    ?: return@mapNotNull null

                val season =
                    match.groupValues[1]
                        .toIntOrNull()
                        ?: return@mapNotNull null

                val episode =
                    match.groupValues[2]
                        .toIntOrNull()
                        ?: return@mapNotNull null

                val label =
                    element.text()
                        .trim()
                        .ifBlank {
                            "$episode. Bölüm"
                        }

                newEpisode(absolute) {
                    name = label
                    this.season = season
                    this.episode = episode
                    posterUrl =
                        element
                            .selectFirst("img")
                            ?.let(::posterOf)
                }
            }
            .distinctBy {
                it.data
            }
            .sortedWith(
                compareBy<Episode> {
                    it.season ?: 0
                }.thenBy {
                    it.episode ?: 0
                }
            )
    }

    private fun Element.toSearchResponse(): SearchResponse? {

        val href = attr("href")
            .trim()

        val absolute =
            fixUrlNull(href)
                ?: return null

        val path = absolute.lowercase()

        if (
            path == mainUrl ||
            absolute.contains(
                "/sezon-",
                ignoreCase = true
            ) ||
            absolute.contains(
                "/bolum-",
                ignoreCase = true
            )
        ) {
            return null
        }

        val type = when {
            "/film/" in path ->
                TvType.Movie

            "/dizi/" in path ->
                TvType.TvSeries

            else ->
                return null
        }

        val title = text()
            .trim()
            .ifBlank {
                selectFirst("img")
                    ?.attr("alt")
                    ?.trim()
                    .orEmpty()
            }

        if (title.isBlank()) {
            return null
        }

        val poster =
            selectFirst("img")
                ?.let(::posterOf)

        return when (type) {

            TvType.Movie -> {
                newMovieSearchResponse(
                    title,
                    absolute,
                    TvType.Movie,
                ) {
                    posterUrl = poster
                }
            }

            else -> {
                newTvSeriesSearchResponse(
                    title,
                    absolute,
                    TvType.TvSeries,
                ) {
                    posterUrl = poster
                }
            }
        }
    }

    private fun posterOf(
        element: Element
    ): String? {

        val raw = element
            .attr("data-src")
            .ifBlank {
                element.attr("src")
            }
            .ifBlank {
                element.attr("data-lazy-src")
            }

        return raw
            .takeIf {
                it.isNotBlank()
            }
            ?.let(::fixUrl)
    }

    private fun isMediaUrl(
        url: String
    ): Boolean {

        val value = url.lowercase()

        return Regex(
            """(?i)\.(m3u8|mpd|mp4)(?:$|[?#])"""
        ).containsMatchIn(value) ||
            value.contains(
                ".urlset/master.m3u8"
            ) ||
            value.contains(
                "/master.m3u8"
            ) ||
            (
                value.contains("/hls/") &&
                    (
                        value.contains(".m3u8") ||
                            value.contains("master")
                        )
                )
    }

    private fun isExternalPlayer(
        url: String
    ): Boolean {

        val value = url.lowercase()

        return value.contains("vidmoly") ||
            value.contains("filemoon") ||
            value.contains("vidmolyme") ||
            value.contains("vidmolyto") ||
            value.contains("vidmolybiz") ||
            value.contains("vidmoly.net") ||
            value.contains("dplayer82.site") ||
            value.contains("vmbox.space")
    }

    private fun decodeEmbeddedText(
        value: String
    ): String {

        return value
            .replace("\\/", "/")
            .replace("\\\"", "\"")
            .replace("\\'", "'")
            .replace(
                "\\u002F",
                "/",
                ignoreCase = true
            )
            .replace(
                "\\u003A",
                ":",
                ignoreCase = true
            )
            .replace(
                "\\u0026",
                "&",
                ignoreCase = true
            )
            .replace(
                "&amp;",
                "&",
                ignoreCase = true
            )
            .replace(
                "&quot;",
                "\"",
                ignoreCase = true
            )
            .replace(
                "&#x2F;",
                "/",
                ignoreCase = true
            )
            .replace(
                "&#47;",
                "/",
                ignoreCase = true
            )
            .replace("\n", " ")
            .replace("\r", " ")
    }

    private fun hostLabel(
        url: String
    ): String {

        return runCatching {
            URI(url).host
        }
            .getOrNull()
            ?.ifBlank {
                "DiziKorea"
            }
            ?: "DiziKorea"
    }

    private fun withPage(
        url: String,
        page: Int
    ): String {

        if (page <= 1) {
            return url
        }

        return if (url.contains("?")) {
            "$url&page=$page"
        } else {
            "$url?page=$page"
        }
    }
}
```
