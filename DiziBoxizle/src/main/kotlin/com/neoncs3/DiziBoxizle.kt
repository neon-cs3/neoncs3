package com.neoncs3

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.getAndUnpack
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

        val allowEpisodeItems = request.name.contains("bölüm", ignoreCase = true)

        val results = document.select("a[href]")
            .asSequence()
            .filterNot { it.isSiteChromeLink() }
            .mapNotNull { it.toSearchResponse(allowEpisodeItems) }
            .distinctBy { it.url }
            .toList()

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
                .asSequence()
                .filterNot { it.isSiteChromeLink() }
                .mapNotNull { it.toSearchResponse(false) }
                .distinctBy { it.url }
                .filterNot { isEpisodeUrl(it.url) }
                .toList()

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

        // The site's "Son Bölümler" list links directly to episode pages.
        // Expose such a page as a one-episode series so CloudStream can reach loadLinks().
        if (isEpisodeUrl(normalizedUrl)) {
            val episodeTitle = pageTitle(document) ?: return null
            val match = EPISODE_PATTERN.find(normalizedUrl)
                ?: ALT_EPISODE_PATTERN.find(normalizedUrl)
            val season = match?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 1
            val episode = match?.groupValues?.getOrNull(2)?.toIntOrNull() ?: 1
            val seriesTitle = episodeTitle
                .replace(Regex("(?i)\\s*\\d+\\.\\s*Sezon\\s*\\d+\\.\\s*Bölüm\\s*$"), "")
                .trim()
                .ifBlank { episodeTitle }

            val singleEpisode = newEpisode(normalizedUrl) {
                name = episodeTitle
                this.season = season
                this.episode = episode
                posterUrl = posterOf(document)
            }

            return newTvSeriesLoadResponse(
                seriesTitle,
                normalizedUrl,
                TvType.TvSeries,
                listOf(singleEpisode),
            ) {
                posterUrl = posterOf(document)
                plot = pagePlot(document)
                year = pageYear(document)
                pageRating(document)?.let { score = Score.from10(it) }
            }
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
                append("\n")
                append(it.data())
                append("\n")
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

        // 5) VMEAS HLS URLs.
        //    Handles both /index-v1-a1.m3u8?... and master.m3u8?... URLs,
        //    including paths such as
        //    /y8f7mscf5o6e_,n,l,.urlset/master.m3u8?...
        VMEAS_M3U8_PATTERN.findAll(rawHtml)
            .map { it.value.trimEnd(')', ']', '}', ';') }
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

                    emitMediaLink(
                        clean,
                        episodeUrl,
                        callback,
                    )
                    found = true
                }

                isExternalPlayer(clean) -> {
                    // First inspect the provider page itself. VidMoly/Moly may hide the
                    // real VMEAS master.m3u8 URL inside JavaScript instead of exposing it
                    // as a normal HTML video element.
                    val providerFound = extractProviderMedia(
                        clean,
                        episodeUrl,
                        subtitleCallback,
                        callback,
                    )

                    val extracted = runCatching {
                        loadExtractor(
                            clean,
                            episodeUrl,
                            subtitleCallback,
                            callback,
                        )
                    }.getOrDefault(false)

                    found = providerFound || extracted || found
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

    private suspend fun emitMediaLink(
        mediaUrl: String,
        sourcePage: String,
        callback: (ExtractorLink) -> Unit,
    ) {
        val type = when {
            Regex("(?i)\\.(?:m3u8)(?:$|\\?)").containsMatchIn(mediaUrl) -> ExtractorLinkType.M3U8
            Regex("(?i)\\.(?:mpd)(?:$|\\?)").containsMatchIn(mediaUrl) -> ExtractorLinkType.DASH
            else -> ExtractorLinkType.VIDEO
        }

        val providerOrigin = originOf(sourcePage)
        val isProviderPage = sourcePage.contains("vidmoly", ignoreCase = true) ||
            sourcePage.contains("moly", ignoreCase = true) ||
            sourcePage.contains("oynatloload.top", ignoreCase = true)

        // media-internals shows playback inside the VidMoly frame.
        // Use the provider origin as the HLS Referer/Origin instead of DiziBox.
        val mediaReferer = if (isProviderPage) {
            providerOrigin?.plus("/") ?: sourcePage
        } else {
            sourcePage
        }

        val mediaHeaders = linkedMapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to mediaReferer,
            "Accept" to "*/*",
            "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8",
        )

        if (isProviderPage && providerOrigin != null) {
            mediaHeaders["Origin"] = providerOrigin
        }

        callback(
            newExtractorLink(
                source = name,
                name = hostLabel(mediaUrl),
                url = mediaUrl,
                type = type,
            ) {
                referer = mediaReferer
                quality = qualityFromUrl(mediaUrl)
                headers = mediaHeaders
            }
        )
    }

    private suspend fun extractProviderMedia(
        providerUrl: String,
        episodeUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val providerCandidates = LinkedHashSet<String>()
        providerCandidates.add(providerUrl)
        vidMolyClassicUrl(providerUrl)?.let(providerCandidates::add)

        var found = false

        for (pageUrl in providerCandidates) {
            val providerResponse = runCatching {
                app.get(
                    pageUrl,
                    headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Referer" to episodeUrl,
                        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                        "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8",
                        "Sec-Fetch-Dest" to "iframe",
                        "Sec-Fetch-Mode" to "navigate",
                        "Sec-Fetch-Site" to "cross-site",
                        "Sec-Fetch-User" to "?1",
                    ),
                )
            }.getOrNull() ?: continue

            val providerDocument = providerResponse.document
            val providerHtml = buildString {
                append(providerDocument.html())
                providerDocument.select("script, noscript, template").forEach {
                    append("\n")
                    append(it.data())
                    append("\n")
                    append(it.html())
                }
            }.decodeEmbeddedText()

            // Search both raw and unpacked JavaScript.
            val unpackedHtml = runCatching { getAndUnpack(providerHtml) }
                .getOrDefault(providerHtml)
            val searchableHtml = if (unpackedHtml == providerHtml) {
                providerHtml
            } else {
                providerHtml + "\n" + unpackedHtml
            }

            val sourceUrls = LinkedHashSet<String>()

            PROVIDER_SOURCE_PATTERN.findAll(searchableHtml)
                .mapNotNull { it.groupValues.getOrNull(1)?.trim() }
                .map { it.decodeEmbeddedText() }
                .forEach(sourceUrls::add)

            PROVIDER_ANY_SOURCE_PATTERN.findAll(searchableHtml)
                .mapNotNull { it.groupValues.getOrNull(1)?.trim() }
                .map { it.decodeEmbeddedText() }
                .forEach(sourceUrls::add)

            VMEAS_M3U8_PATTERN.findAll(searchableHtml)
                .map { it.value.trimEnd(')', ']', '}', ';', ',') }
                .forEach(sourceUrls::add)

            GENERIC_M3U8_PATTERN.findAll(searchableHtml)
                .map { it.value.trimEnd(')', ']', '}', ';', ',') }
                .forEach(sourceUrls::add)

            for (rawSource in sourceUrls) {
                val mediaUrl = normalizeProviderMediaUrl(rawSource, pageUrl)
                if (!isMediaUrl(mediaUrl)) continue

                emitMediaLink(mediaUrl, pageUrl, callback)
                found = true
            }

            providerDocument.select("track[src], track[data-src]").forEach { track ->
                val subtitle = track.attr("src").ifBlank { track.attr("data-src") }
                if (subtitle.isNotBlank()) {
                    subtitleCallback(
                        newSubtitleFile(
                            track.attr("label").ifBlank { "Türkçe" },
                            fixUrl(subtitle),
                        )
                    )
                }
            }

            if (found) return true
        }

        return found
    }

    private fun normalizeProviderMediaUrl(raw: String, pageUrl: String): String {
        val source = raw.trim()
        return when {
            source.startsWith("//") -> "https:$source"
            source.startsWith("/") -> (originOf(pageUrl) ?: mainUrl) + source
            source.startsWith("http://", ignoreCase = true) ||
                source.startsWith("https://", ignoreCase = true) -> source
            else -> source
        }.replace("\\/", "/")
    }

    private fun vidMolyClassicUrl(url: String): String? {
        val path = runCatching { URI(url).path }.getOrNull() ?: return null
        val id = Regex("(?i)/v/([a-z0-9]+)$").find(path)?.groupValues?.getOrNull(1)
            ?: Regex("(?i)/embed-([a-z0-9]+)\\.html$").find(path)?.groupValues?.getOrNull(1)
            ?: return null

        val origin = originOf(url) ?: "https://vidmoly.biz"
        return "$origin/embed-$id.html"
    }

    private fun originOf(url: String): String? {
        return runCatching {
            val uri = URI(url)
            if (uri.scheme.isNullOrBlank() || uri.host.isNullOrBlank()) return@runCatching null
            "${uri.scheme}://${uri.host}"
        }.getOrNull()
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
                    posterUrl = posterFromElement(element, href) ?: guessedPoster(href)
                }
            }
            .distinctBy { it.data }
            .sortedWith(
                compareBy<Episode> { it.season ?: 0 }
                    .thenBy { it.episode ?: 0 }
            )
    }

    private fun Element.toSearchResponse(allowEpisode: Boolean): SearchResponse? {
        if (isSiteChromeLink()) return null

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
            path.contains("/yil/") ||
            path.contains("/sort/")
        ) return null

        val rawTitle = text().trim().ifBlank {
            selectFirst("img")?.attr("alt")?.trim().orEmpty()
        }
        if (rawTitle.isBlank()) return null

        val posterFromDom = posterFromElement(this, absolute)
        // Real DiziBOX content cards have a poster image in the same card/container.
        // Header alphabet entries and login/navigation links do not, so this prevents
        // those links from appearing as fake content cards.
        if (posterFromDom == null && !path.contains("/film/")) return null

        val poster = posterFromDom ?: guessedPoster(absolute)
        val title = cleanCardTitle(rawTitle)
        if (title.isBlank()) return null

        if (isEpisodeUrl(absolute)) {
            if (!allowEpisode) return null

            val match = EPISODE_PATTERN.find(absolute)
                ?: ALT_EPISODE_PATTERN.find(absolute)
            val season = match?.groupValues?.getOrNull(1)?.toIntOrNull()
            val episode = match?.groupValues?.getOrNull(2)?.toIntOrNull()
            val episodeName = buildString {
                append(title)
                if (season != null && episode != null) {
                    append(" - ")
                    append(season)
                    append(". Sezon ")
                    append(episode)
                    append(". Bölüm")
                }
            }

            return newTvSeriesSearchResponse(episodeName, absolute, TvType.TvSeries) {
                posterUrl = poster
            }
        }

        if (path.contains("/film/")) {
            return newMovieSearchResponse(title, absolute, TvType.Movie) {
                posterUrl = poster
            }
        }

        // DiziBOX series pages are root-level slugs.
        return newTvSeriesSearchResponse(title, absolute, TvType.TvSeries) {
            posterUrl = poster
        }
    }

    private fun Element.isSiteChromeLink(): Boolean {
        val value = text().trim().replace(Regex("\\s+"), " ").lowercase()

        if (value == "dizibox" ||
            value == "dizibox izle" ||
            value == "üye ol" ||
            value == "üye girişi" ||
            value == "üye girişi yap" ||
            value == "anasayfa" ||
            value == "diziler" ||
            value == "bölümler" ||
            value == "filmler" ||
            value == "sonraki »" ||
            value == "son »" ||
            value == "sonraki" ||
            value == "en yeniler" ||
            value == "en çok yorumlananlar" ||
            value == "imdb puanı" ||
            value == "tüm diziler" ||
            value == "tüm filmler" ||
            value == "dizi arşivi" ||
            value == "mobil uygulam indir" ||
            value == "mobil uygulama indir" ||
            value == "iletişim" ||
            value == "iletişim / reklam" ||
            Regex("^[a-z]$").containsMatchIn(value) ||
            value == "#" ||
            Regex("^(?:19|20)\\d{2}$").containsMatchIn(value)
        ) return true

        var current: Element? = this
        var depth = 0
        while (current != null && depth < 8) {
            val tag = current.tagName().lowercase()
            if (tag == "header" || tag == "nav" || tag == "footer" || tag == "aside") {
                return true
            }

            val marker = buildString {
                append(current.id())
                append(' ')
                append(current.classNames().joinToString(" "))
            }.lowercase()

            if (Regex("\\b(site[-_]?header|site[-_]?footer|navbar|navigation|main[-_]?menu|mobile[-_]?menu|side[-_]?bar|sidebar|widget|login|register|alphabet|social|user[-_]?menu)\\b")
                    .containsMatchIn(marker)
            ) return true

            current = current.parent()
            depth++
        }

        return false
    }

    private fun posterFromElement(element: Element, targetUrl: String? = null): String? {
        // 1) Most DiziBOX cards put the poster directly inside the <a>.
        element.selectFirst("img, picture img")?.let { image ->
            posterOfElement(image)?.let { return it }
        }

        val targetSlug = targetUrl
            ?.let { runCatching { URI(it).path.trimEnd('/').substringAfterLast('/') }.getOrNull() }
            ?.lowercase()
            ?.replace(Regex("-(?:\\d+x\\d+|\\d+)$"), "")
            .orEmpty()

        // 2) Some layouts keep the title link and image in the same card container.
        //    Only inspect a few nearby ancestors and never climb into header/nav/sidebar.
        var current: Element? = element.parent()
        var depth = 0
        while (current != null && depth < 4) {
            val tag = current.tagName().lowercase()
            if (tag == "header" || tag == "nav" || tag == "footer" || tag == "aside") break

            val marker = (current.id() + " " + current.classNames().joinToString(" ")).lowercase()
            val looksLikeCard = Regex("\\b(card|item|post|entry|movie|film|series|dizi|content|thumbnail|list|archive)\\b")
                .containsMatchIn(marker)

            val images = current.select("img")
            if (images.size == 1 || looksLikeCard) {
                val preferred = images.firstOrNull { image ->
                    val imageUrl = posterOfElement(image).orEmpty().lowercase()
                    targetSlug.isNotBlank() && imageUrl.contains(targetSlug)
                } ?: images.firstOrNull()

                preferred?.let { image ->
                    posterOfElement(image)?.let { return it }
                }
            }

            current = current.parent()
            depth++
        }

        return null
    }

    private fun cleanCardTitle(raw: String): String {
        var title = raw.trim().replace(Regex("\\s+"), " ")
        title = title.replace(
            Regex("(?i)^IMDb\\s*[0-9]+(?:[.,][0-9]+)?(?:\\s*/\\s*10)?\\s*"),
            "",
        )
        title = title.replace(Regex("\\s+(?:19|20)\\d{2}\\s*$"), "")
        return title.trim()
    }

    private fun guessedPoster(url: String): String? {
        val slug = runCatching {
            URI(url).path.trimEnd('/').substringAfterLast('/')
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: return null

        if (slug.contains("-sezon-") || slug.contains("-bolum")) {
            return null
        }

        return "$mainUrl/wp-content/uploads/afisler/$slug-220x140.jpg"
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
            value.contains("moly") ||
            value.contains("ok.ru") ||
            value.contains("odnoklassniki") ||
            value.contains("doodstream") ||
            value.contains("streamtape") ||
            value.contains("filemoon") ||
            value.contains("oynatloload.top") ||
            value.contains("vidmoly.biz")
    }

    private fun isMediaUrl(url: String): Boolean {
        val value = url.lowercase()
        return Regex("(?i)\\.(m3u8|mpd|mp4|webm)(?:$|[?#])").containsMatchIn(value) ||
            (value.contains("/hls2/") && value.contains(".m3u8")) ||
            (value.contains(".vmeas.cloud/") && value.contains(".m3u8"))
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
        val yearRegex = Regex("(?<!\\d)(?:19|20)\\d{2}(?!\\d)")

        // First use fields that are normally the actual release/year label on the title card.
        val visibleYearSelectors = listOf(
            ".year", ".release-year", ".release_date", ".release-date",
            ".meta .year", ".post-meta .year", ".movie-year", ".dizi-year",
            "[itemprop='copyrightYear']", "[itemprop='releaseDate']"
        )

        for (selector in visibleYearSelectors) {
            val value = document.select(selector).joinToString(" ") { element ->
                element.text() + " " + element.attr("content")
            }
            yearRegex.find(value)?.value?.toIntOrNull()?.let { return it }
        }

        // Some cards put the year in the title/description rather than a dedicated field.
        val focusedText = listOfNotNull(
            document.selectFirst("h1")?.text(),
            document.selectFirst("meta[property='og:title']")?.attr("content"),
            document.selectFirst("meta[name='description']")?.attr("content"),
            document.selectFirst("meta[property='og:description']")?.attr("content"),
        ).joinToString(" ")

        yearRegex.find(focusedText)?.value?.toIntOrNull()?.let { return it }

        // Prefer releaseDate in JSON-LD. datePublished is deliberately not used because it
        // is often the site's upload date rather than the series/movie release year.
        for (script in document.select("script[type='application/ld+json']")) {
            val json = script.data().ifBlank { script.html() }
            val release = Regex(
                """(?is)[\\\"']?releaseDate[\\\"']?\\s*:\\s*[\\\"']([^\\\"']+)"""
            ).find(json)?.groupValues?.getOrNull(1)
            yearRegex.find(release.orEmpty())?.value?.toIntOrNull()?.let { return it }
        }

        // Do not scan the entire document as a final fallback: that often returns the
        // website footer/copyright year (for example 2026) instead of the title's year.
        return null
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
            .ifBlank { element.attr("data-original") }
            .ifBlank { element.attr("data-image") }
            .ifBlank { element.attr("data-lazy-srcset") }
            .ifBlank { element.attr("data-srcset") }
            .ifBlank { element.attr("srcset") }
            .ifBlank { element.attr("src") }

        val firstUrl = Regex("https?://[^\\s,]+", RegexOption.IGNORE_CASE)
            .find(raw)
            ?.value
            ?.trimEnd(',')

        val firstRelative = raw
            .split(',')
            .firstOrNull()
            ?.trim()
            ?.substringBefore(' ')
            ?.takeIf { it.isNotBlank() }

        return firstUrl
            ?.takeIf { it.isNotBlank() }
            ?.let(::fixUrl)
            ?: firstRelative?.let(::fixUrl)
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
        // VidMoly classic embeds commonly expose:
        // sources: [{ file: "https://.../master.m3u8?..." }]
        private val PROVIDER_SOURCE_PATTERN = Regex(
            "(?is)\\bsources\\s*:\\s*\\[\\s*\\{\\s*[\"']?file[\"']?[\\s:\=]*[\"']([^\"']+)[\"']"
        )

        // Fallback for variants using src/url/source/hls directly.
        private val PROVIDER_ANY_SOURCE_PATTERN = Regex(
            "(?is)\\b(?:file|src|url|source|hls)\\s*[:=]\\s*[\"'](https?://[^\"']+(?:m3u8|mpd)(?:\\?[^\"']+)?)['\"]"
        )

        private val VMEAS_M3U8_PATTERN = Regex(
            "https?://[a-z0-9.-]+\\.vmeas\\.cloud/[^\\s\"'<>]+?\\.m3u8(?:\\?[^\\s\"'<>]+)?",
            RegexOption.IGNORE_CASE,
        )

        private val GENERIC_M3U8_PATTERN = Regex(
            "https?://[^\\s\"'<>]+(?:master|index|playlist)[^\\s\"'<>]*\\.m3u8(?:\\?[^\\s\"'<>]+)?",
            RegexOption.IGNORE_CASE,
        )

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
