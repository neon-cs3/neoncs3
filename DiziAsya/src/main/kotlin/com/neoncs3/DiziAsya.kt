package com.neoncs3

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Element
import java.net.URLEncoder

class DiziAsya : MainAPI() {
    override var mainUrl = "https://diziasya.com/"
    override var name = "DiziAsya"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override var sequentialMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie, TvType.Anime)

    private val requestHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8",
        "Referer" to mainUrl,
    )

    override val mainPage = mainPageOf(
        "${mainUrl}diziler" to "Diziler",
        "${mainUrl}filmler" to "Filmler",
        "${mainUrl}animeler" to "Animeler",
        mainUrl to "Son Eklenenler",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else addPage(request.data, page)
        val document = app.get(url, headers = requestHeaders).document
        val results = document.select("a[href]")
            .mapNotNull { it.toSearchResponse() }
            .distinctBy { it.url }

        return newHomePageResponse(request.name, results, hasNext = results.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = URLEncoder.encode(query.trim(), Charsets.UTF_8.name())
        val candidates = listOf(
            "${mainUrl}arama?q=$q",
            "${mainUrl}arama?query=$q",
            "${mainUrl}ara?q=$q",
            "${mainUrl}search?q=$q",
            "${mainUrl}?s=$q",
        )

        for (url in candidates) {
            val document = runCatching { app.get(url, headers = requestHeaders).document }.getOrNull() ?: continue
            val results = document.select("a[href]")
                .mapNotNull { it.toSearchResponse() }
                .distinctBy { it.url }
            if (results.isNotEmpty()) return results
        }

        return emptyList()
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, headers = requestHeaders).document
        val path = url.lowercase()

        val title = document.selectFirst("h1")?.text()?.trim()
            ?: document.selectFirst("meta[property='og:title']")?.attr("content")?.trim()
            ?: return null

        val poster = document.selectFirst("meta[property='og:image']")?.attr("content")
            ?.takeIf { it.isNotBlank() }?.let(::fixUrl)
            ?: document.selectFirst("img")?.let { posterOf(it) }

        val plot = document.selectFirst("meta[name='description']")?.attr("content")?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("article p, .description, .plot, main p")?.text()?.trim()

        val body = document.text()
        val year = Regex("\\b(19|20)\\d{2}\\b").find(body)?.value?.toIntOrNull()
        val rating = Regex("(?i)(?:IMDb|IMDB|Puan)\\s*[: ]\\s*([0-9]+(?:[.,][0-9]+)?)")
            .find(body)?.groupValues?.getOrNull(1)?.replace(',', '.')?.toDoubleOrNull()

        return when {
            "/film/" in path -> newMovieLoadResponse(title, url, TvType.Movie, url) {
                posterUrl = poster
                this.plot = plot
                this.year = year
                rating?.let { score = Score.from10(it) }
            }
            "/anime/" in path -> {
                val episodes = parseEpisodes(document)
                newAnimeLoadResponse(title, url, TvType.Anime) {
                    posterUrl = poster
                    this.plot = plot
                    this.year = year
                    rating?.let { score = Score.from10(it) }
                    if (episodes.isNotEmpty()) addEpisodes(DubStatus.Subbed, episodes)
                }
            }
            else -> {
                val episodes = parseEpisodes(document)
                newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                    posterUrl = poster
                    this.plot = plot
                    this.year = year
                    rating?.let { score = Score.from10(it) }
                }
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val page = runCatching { app.get(data, headers = requestHeaders) }.getOrNull() ?: return false
        val document = page.document
        val sourceUrls = LinkedHashSet<String>()

        // Do not try to reconstruct hidden media tokens. Use public player links and
        // let CloudStream's registered extractors handle supported hosts.
        document.select("iframe[src], iframe[data-src], [data-iframe]")
            .forEach { element ->
                val raw = element.attr("src")
                    .ifBlank { element.attr("data-src") }
                    .ifBlank { element.attr("data-iframe") }
                if (raw.isNotBlank()) sourceUrls.add(fixUrl(raw))
            }

        val html = document.html().replace("\\/", "/")
        Regex("(?i)https?://[^\\s\"'<>]+")
            .findAll(html)
            .map { it.value.trimEnd(')', ';', ',') }
            .filter { it.startsWith("https://dzyedge.com/") || it.startsWith("http://dzyedge.com/") }
            .forEach { sourceUrls.add(it) }

        // Openly exposed media URLs are accepted as a fallback.
        document.select("video[src], source[src], [data-src], [data-video]").forEach { element ->
            val raw = element.attr("src")
                .ifBlank { element.attr("data-src") }
                .ifBlank { element.attr("data-video") }
            if (raw.isNotBlank() && isMedia(raw)) sourceUrls.add(fixUrl(raw))
        }

        var loaded = false
        for (source in sourceUrls) {
            if (source.contains("dzyedge.com/", ignoreCase = true)) {
                loaded = loadExtractor(
                    source,
                    data,
                    subtitleCallback,
                    callback,
                ) || loaded
            } else if (isMedia(source)) {
                val type = when {
                    ".m3u8" in source.lowercase() -> ExtractorLinkType.M3U8
                    ".mpd" in source.lowercase() -> ExtractorLinkType.DASH
                    else -> ExtractorLinkType.VIDEO
                }
                callback(
                    newExtractorLink(
                        source = name,
                        name = "DiziAsya",
                        url = source,
                        type = type,
                    ) {
                        referer = data
                        quality = Qualities.Unknown.value
                        headers = mapOf(
                            "User-Agent" to USER_AGENT,
                            "Referer" to data,
                            "Accept" to "*/*",
                        )
                    }
                )
                loaded = true
            }
        }

        document.select("track[src], source[subtitles][src]").forEach { track ->
            val subtitle = fixUrl(track.attr("src"))
            if (subtitle.isNotBlank()) {
                subtitleCallback(newSubtitleFile("Türkçe", subtitle))
            }
        }

        return loaded
    }

    private fun parseEpisodes(document: org.jsoup.nodes.Document): List<Episode> {
        return document.select("a[href]")
            .mapNotNull { element ->
                val href = element.attr("href").trim()
                if (!href.contains("/sezon-", ignoreCase = true) || !href.contains("-bolum-", ignoreCase = true)) return@mapNotNull null
                val match = Regex("(?i)/sezon-(\\d+)-bolum-(\\d+)").find(href) ?: return@mapNotNull null
                val season = match.groupValues[1].toIntOrNull() ?: return@mapNotNull null
                val episode = match.groupValues[2].toIntOrNull() ?: return@mapNotNull null
                val label = element.text().trim().ifBlank { "Bölüm $episode" }
                newEpisode(fixUrl(href)) {
                    name = label
                    this.season = season
                    this.episode = episode
                    posterUrl = element.selectFirst("img")?.let { posterOf(it) }
                }
            }
            .distinctBy { it.data }
            .sortedWith(compareBy<Episode> { it.season ?: 0 }.thenBy { it.episode ?: 0 })
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val href = attr("href").trim()
        if (href.isBlank()) return null
        val absolute = fixUrlNull(href) ?: return null
        if (absolute.contains("/sezon-", ignoreCase = true) || absolute.contains("-bolum-", ignoreCase = true)) return null

        val path = absolute.lowercase()
        val title = text().trim().ifBlank {
            selectFirst("img")?.attr("alt")?.trim().orEmpty()
        }
        if (title.isBlank()) return null

        val poster = selectFirst("img")?.let { posterOf(it) }
        val type = when {
            "/film/" in path -> TvType.Movie
            "/anime/" in path -> TvType.Anime
            "/dizi/" in path -> TvType.TvSeries
            else -> return null
        }

        return when (type) {
            TvType.Movie -> newMovieSearchResponse(title, absolute, TvType.Movie) { posterUrl = poster }
            TvType.Anime -> newAnimeSearchResponse(title, absolute, TvType.Anime) { posterUrl = poster }
            else -> newTvSeriesSearchResponse(title, absolute, TvType.TvSeries) { posterUrl = poster }
        }
    }

    private fun posterOf(element: Element): String? {
        val raw = element.attr("data-src").ifBlank { element.attr("src") }
        return raw.takeIf { it.isNotBlank() }?.let(::fixUrl)
    }

    private fun isMedia(url: String): Boolean = Regex("(?i)\\.(m3u8|mpd|mp4)(?:$|\\?)").containsMatchIn(url)

    private fun addPage(url: String, page: Int): String {
        return if (url.contains("?")) "$url&page=$page" else "$url?page=$page"
    }
}
