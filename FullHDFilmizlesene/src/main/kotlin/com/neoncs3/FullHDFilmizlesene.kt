package com.neoncs3

import android.util.Base64
import android.util.Log
import android.net.Uri
import java.net.URI
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class FullHDFilmizlesene : MainAPI() {
    override var mainUrl = "https://www.fullhdfilmizlesene.now"
    override var name = "FullDFilmizlesene"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override var lang = "tr"

    override val supportedTypes = setOf(TvType.Movie)

    private val browserHeaders = mapOf(
        "User-Agent" to (
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/130.0.0.0 Safari/537.36"
        ),
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7",
        "Referer" to "$mainUrl/"
    )

    override val mainPage = mainPageOf(
        mainUrl to "Ana Sayfa",
        "$mainUrl/yeni-filmler" to "Yeni Filmler",
        "$mainUrl/yil/2026-filmleri-izle" to "2026 Filmleri",
        "$mainUrl/filmizle/hd-720p-filmler-izle" to "1080p / 720p",
        "$mainUrl/filmizle/turkce-dublaj-filmler-1" to "Türkçe Dublaj",
        "$mainUrl/filmizle/turkce-altyazili-filmler-1" to "Türkçe Altyazılı",
        "$mainUrl/filmizle/yerli-filmler" to "Yerli Filmler"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val targetUrl = if (page <= 1) request.data else pageUrl(request.data, page)

        return try {
            val document = app.get(targetUrl, headers = browserHeaders).document

            // Afişin bulunduğu kartın tamamını seçiyoruz.
            // Önce sitenin klasik li.film yapısı, sonra olası alternatif kart sınıfları.
            val cards = document
                .select("li.film, article.film, .film-card, .film-item")
                .filter { it.selectFirst("a[href*='/film/']") != null }
                .distinctBy {
                    fixUrlNull(it.selectFirst("a[href*='/film/']")?.attr("href")) ?: it.html()
                }

            val results = cards.mapNotNull { it.toSearchResult() }

            Log.d(name, "getMainPage url=$targetUrl cards=${cards.size} results=${results.size}")

            newHomePageResponse(
                request.name,
                results,
                hasNext = results.isNotEmpty()
            )
        } catch (e: Exception) {
            Log.e(name, "getMainPage hata: $targetUrl -> ${e.message}", e)
            newHomePageResponse(request.name, emptyList(), hasNext = false)
        }
    }

    private fun pageUrl(base: String, page: Int): String {
        if (page <= 1) return base
        return if (base.endsWith("/")) {
            "${base}sayfa/$page/"
        } else {
            "$base/sayfa/$page/"
        }
    }

    private fun isFilmUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("fullhdfilmizlesene.now") && lower.contains("/film/")
    }

    private fun firstNonBlank(vararg values: String?): String? =
        values.firstOrNull { !it.isNullOrBlank() }?.trim()?.takeIf { it.isNotEmpty() }

    private fun extractPoster(element: Element): String? {
        val img = element.selectFirst("img") ?: return null

        val direct = firstNonBlank(
            img.attr("data-src"),
            img.attr("data-lazy-src"),
            img.attr("data-original"),
            img.attr("data-url"),
            img.attr("src")
        )

        if (!direct.isNullOrBlank() && !direct.startsWith("data:image", ignoreCase = true)) {
            return fixUrlNull(direct)
        }

        val srcSet = firstNonBlank(img.attr("data-srcset"), img.attr("srcset"))
        if (!srcSet.isNullOrBlank()) {
            val first = srcSet.split(",")
                .map { it.trim().substringBefore(" ") }
                .firstOrNull { it.isNotBlank() }
            if (!first.isNullOrBlank()) return fixUrlNull(first)
        }

        val style = firstNonBlank(img.attr("style"), element.attr("style"))
        if (!style.isNullOrBlank()) {
            val match = Regex("""url\(['\"]?([^)'\"]+)['\"]?\)""")
                .find(style)
                ?.groupValues
                ?.getOrNull(1)

            if (!match.isNullOrBlank()) return fixUrlNull(match)
        }

        return null
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = fixUrlNull(
            selectFirst("a[href*='/film/']")?.attr("href")
                ?: selectFirst("a[href]")?.attr("href")
        ) ?: return null

        if (!isFilmUrl(href)) return null

        val title = firstNonBlank(
            selectFirst("span.film-title")?.text(),
            selectFirst(".film-title")?.text(),
            selectFirst("h2")?.text(),
            selectFirst("h3")?.text(),
            selectFirst("h4")?.text(),
            selectFirst("a[title]")?.attr("title"),
            selectFirst("img[alt]")?.attr("alt"),
            selectFirst("a")?.attr("title"),
            selectFirst("a")?.text()
        )
            ?.replace(Regex("\\s+"), " ")
            ?.removeSuffix(" izle")
            ?.removeSuffix(" İzle")
            ?.trim()
            ?: return null

        val posterUrl = extractPoster(this)
        val cardText = text()

        val quality = when {
            cardText.contains("4K", ignoreCase = true) -> SearchQuality.HD
            cardText.contains("1080", ignoreCase = true) -> SearchQuality.HD
            cardText.contains("720", ignoreCase = true) -> SearchQuality.HD
            cardText.contains("HD", ignoreCase = true) -> SearchQuality.HD
            else -> null
        }

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
            this.quality = quality
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val searchUrls = listOf(
            "$mainUrl/arama/$encoded",
            "$mainUrl/search/?q=$encoded",
            "$mainUrl/search?q=$encoded",
            "$mainUrl/?s=$encoded"
        )

        for (searchUrl in searchUrls) {
            try {
                val document = app.get(searchUrl, headers = browserHeaders).document
                val results = document
                    .select("li.film, article.film, .film-card, .film-item")
                    .filter { it.selectFirst("a[href*='/film/']") != null }
                    .mapNotNull { it.toSearchResult() }
                    .distinctBy { it.url }

                Log.d(name, "search url=$searchUrl results=${results.size}")
                if (results.isNotEmpty()) return results
            } catch (e: Exception) {
                Log.d(name, "Arama başarısız: $searchUrl -> ${e.message}")
            }
        }

        return emptyList()
    }

    override suspend fun load(url: String): LoadResponse? {
        return try {
            val document = app.get(url, headers = browserHeaders).document

            val title = firstNonBlank(
                document.selectFirst("div.izle-titles")?.text(),
                document.selectFirst("h1")?.text(),
                document.selectFirst("meta[property='og:title']")?.attr("content")
            )
                ?.removeSuffix(" izle")
                ?.removeSuffix(" İzle")
                ?.trim()
                ?: return null

            val posterUrl = firstNonBlank(
                document.selectFirst("meta[property='og:image']")?.attr("content"),
                document.selectFirst("meta[name='twitter:image']")?.attr("content"),
                extractPoster(document)
            )?.let { fixUrlNull(it) }

            val plot = firstNonBlank(
                document.selectFirst("div.ozet-ic > p")?.text(),
                document.selectFirst("meta[name='description']")?.attr("content"),
                document.selectFirst("article p")?.text()
            )

            val year = firstNonBlank(
                document.selectFirst("div.dd a.category")?.text()?.split(" ")?.firstOrNull(),
                Regex("""(?:Yapım|Yıl)\s*[:\-]?\s*(\d{4})""")
                    .find(document.text())
                    ?.groupValues
                    ?.getOrNull(1),
                Regex("""\b(19\d{2}|20\d{2})\b""")
                    .find(document.text())
                    ?.groupValues
                    ?.getOrNull(1)
            )?.toIntOrNull()

            val tags = document
                .select("a[rel='category tag'], a[href*='/tur/'], a[href*='/genre/']")
                .map { it.text().trim() }
                .filter { it.isNotBlank() }
                .distinct()

            Log.d(name, "load title=$title poster=$posterUrl")

            newMovieLoadResponse(
                name = title,
                url = url,
                type = TvType.Movie,
                dataUrl = url
            ) {
                this.posterUrl = posterUrl
                this.plot = plot
                this.year = year
                this.tags = tags
            }
        } catch (e: Exception) {
            Log.e(name, "Film sayfası yüklenemedi: ${e.message}", e)
            null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            Log.d(name, "loadLinks -> $data")

            val document = app.get(data, headers = browserHeaders).document
            val pageHtml = document.html()
            var emitted = false
            val emittedSubtitleUrls = mutableSetOf<String>()

            // Önce film sayfasındaki olası VTT/SRT bağlantılarını tara.
            val pageSubtitleCount = emitSubtitleCandidates(
                html = pageHtml,
                baseUrl = data,
                subtitleCallback = subtitleCallback,
                emittedUrls = emittedSubtitleUrls
            )

            // Film sayfasındaki scx nesnesinde ROT13 + Base64 ile saklanan embed
            // URL'lerini çözüyoruz.
            val scxUrls = extractScxUrls(pageHtml)
            Log.d(name, "scx decoded urls=${scxUrls.size}")

            for (embedUrl in scxUrls) {
                // Çözülen değer doğrudan bir HLS URL'si ise kullan.
                if (embedUrl.contains(".m3u8", ignoreCase = true)) {
                    if (emitM3u8(embedUrl, data, callback)) emitted = true
                    continue
                }

                // CloudStream'deki yüklü extractor'ları dene.
                try {
                    var extractorEmitted = false
                    loadExtractor(
                        embedUrl,
                        data,
                        subtitleCallback,
                    ) { link ->
                        extractorEmitted = true
                        callback(link)
                    }
                    if (extractorEmitted) emitted = true
                    Log.d(name, "loadExtractor=$extractorEmitted -> $embedUrl")
                } catch (e: Exception) {
                    Log.d(name, "loadExtractor hata -> $embedUrl : ${e.message}")
                }

                // RapidVid/Atom kaynakları için yerleşik çözümleyici.
                if (embedUrl.contains("rapidvid", ignoreCase = true)) {
                    try {
                        val rapidHtml = app.get(
                            embedUrl,
                            headers = browserHeaders + ("Referer" to data)
                        ).text

                        val rapidSubtitleCount = emitSubtitleCandidates(
                            html = rapidHtml,
                            baseUrl = embedUrl,
                            subtitleCallback = subtitleCallback,
                            emittedUrls = emittedSubtitleUrls
                        )

                        val rapidConfigSubtitles = extractRapidVidSubtitleCandidates(rapidHtml)
                        for ((subtitleUrl, subtitleLang) in rapidConfigSubtitles) {
                            emitSubtitle(
                                url = subtitleUrl,
                                lang = subtitleLang,
                                baseUrl = embedUrl,
                                subtitleCallback = subtitleCallback,
                                emittedUrls = emittedSubtitleUrls
                            )
                        }

                        val resolved = resolveRapidVidFromHtml(rapidHtml)
                        Log.d(
                            name,
                            "RapidVid cm=${resolved != null} subs=${rapidSubtitleCount + rapidConfigSubtitles.size}"
                        )
                        if (!resolved.isNullOrBlank() && emitRapidVidMaster(
                                resolved,
                                refererForMedia(embedUrl),
                                callback
                            )
                        ) {
                            emitted = true
                        }
                    } catch (e: Exception) {
                        Log.d(name, "RapidVid çözümleme hata -> ${e.message}")
                    }
                }
            }

            // Eski sürümlerde video iframe üzerinden geliyorsa bunu da destekle.
            val iframeUrls = document
                .select("iframe[src], iframe[data-src]")
                .mapNotNull { frame ->
                    fixUrlNull(
                        firstNonBlank(frame.attr("data-src"), frame.attr("src"))
                    )
                }
                .filter { it.isNotBlank() }
                .distinct()

            for (iframeUrl in iframeUrls) {
                try {
                    var extractorEmitted = false
                    loadExtractor(
                        iframeUrl,
                        data,
                        subtitleCallback,
                    ) { link ->
                        extractorEmitted = true
                        callback(link)
                    }
                    if (extractorEmitted) emitted = true
                } catch (e: Exception) {
                    Log.d(name, "iframe extractor hata -> $iframeUrl : ${e.message}")
                }

                // Iframe'in kendi HTML'inde medya ve altyazı bağlantıları olabilir.
                try {
                    val iframeHtml = app.get(
                        iframeUrl,
                        headers = browserHeaders + ("Referer" to data)
                    ).text

                    emitSubtitleCandidates(
                        html = iframeHtml,
                        baseUrl = iframeUrl,
                        subtitleCallback = subtitleCallback,
                        emittedUrls = emittedSubtitleUrls
                    )

                    val candidates = extractMediaCandidates(iframeHtml)
                    for (candidate in candidates) {
                        if (emitM3u8(candidate, iframeUrl, callback)) emitted = true
                    }

                    if (iframeUrl.contains("rapidvid", ignoreCase = true)) {
                        val resolved = resolveRapidVidFromHtml(iframeHtml)
                        if (!resolved.isNullOrBlank() && emitM3u8(
                                resolved,
                                refererForMedia(iframeUrl),
                                callback
                            )
                        ) {
                            emitted = true
                        }
                    }
                } catch (e: Exception) {
                    Log.d(name, "iframe okunamadı -> $iframeUrl : ${e.message}")
                }
            }

            // Son çare: film sayfasının kendisinde açık bir HLS bağlantısı varsa.
            for (candidate in extractMediaCandidates(pageHtml)) {
                if (emitM3u8(candidate, data, callback)) emitted = true
            }

            Log.d(
                name,
                "loadLinks emitted=$emitted subtitles=${emittedSubtitleUrls.size} pageSubs=$pageSubtitleCount"
            )
            emitted
        } catch (e: Exception) {
            Log.e(name, "loadLinks hata: ${e.message}", e)
            false
        }
    }


    private fun emitSubtitleCandidates(
        html: String,
        baseUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        emittedUrls: MutableSet<String>
    ): Int {
        var count = 0

        // <track src="..." srclang="tr" label="Türkçe"> biçimi.
        val trackRegex = Regex("""(?is)<track\b([^>]+)>""")
        for (match in trackRegex.findAll(html)) {
            val attrs = match.groupValues.getOrNull(1) ?: continue
            val src = Regex("""(?i)\bsrc\s*=\s*[\"']([^\"']+)[\"']""")
                .find(attrs)?.groupValues?.getOrNull(1) ?: continue
            val lang = Regex("""(?i)\bsrclang\s*=\s*[\"']([^\"']+)[\"']""")
                .find(attrs)?.groupValues?.getOrNull(1)
                ?: Regex("""(?i)\blabel\s*=\s*[\"']([^\"']+)[\"']""")
                    .find(attrs)?.groupValues?.getOrNull(1)
                ?: guessSubtitleLanguage(src)

            if (emitSubtitle(src, lang, baseUrl, subtitleCallback, emittedUrls)) count++
        }

        // JS/JSON içinde subtitle/subtitles/caption/captions/track anahtarlarından
        // sonra gelen VTT/SRT URL'lerini tara.
        val keyedRegex = Regex(
            """(?is)(?:subtitle(?:s)?|caption(?:s)?|textTrack(?:s)?)\s*[:=]\s*[^\n]{0,700}?(https?://[^\"'\s<>]+\.(?:vtt|srt)(?:\?[^\"'\s<>]+)?)"""
        )
        for (match in keyedRegex.findAll(html)) {
            val url = match.groupValues.getOrNull(1) ?: continue
            val context = match.value
            val lang = Regex("""(?i)(?:lang|language|label|srclang|name)\s*[:=]\s*[\"']([^\"']+)[\"']""")
                .find(context)?.groupValues?.getOrNull(1)
                ?: guessSubtitleLanguage(url)
            if (emitSubtitle(url, lang, baseUrl, subtitleCallback, emittedUrls)) count++
        }

        // Son çare: sayfadaki tüm mutlak VTT/SRT URL'lerini tara.
        val directRegex = Regex(
            """https?://[^\"'\s<>]+\.(?:vtt|srt)(?:\?[^\"'\s<>]+)?""",
            RegexOption.IGNORE_CASE
        )
        for (match in directRegex.findAll(html)) {
            val url = match.value
            if (emitSubtitle(
                    url,
                    guessSubtitleLanguage(url),
                    baseUrl,
                    subtitleCallback,
                    emittedUrls
                )
            ) count++
        }

        return count
    }

    private fun emitSubtitle(
        url: String,
        lang: String?,
        baseUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        emittedUrls: MutableSet<String>
    ): Boolean {
        val resolved = resolveSubtitleUrl(url, baseUrl) ?: return false
        if (!resolved.startsWith("http://") && !resolved.startsWith("https://")) return false
        if (!resolved.contains(".vtt", ignoreCase = true) && !resolved.contains(".srt", ignoreCase = true)) {
            return false
        }
        if (!emittedUrls.add(resolved)) return false

        val displayLang = normalizeSubtitleLanguage(lang ?: guessSubtitleLanguage(resolved))
        subtitleCallback(SubtitleFile(displayLang, resolved))
        Log.d(name, "subtitle emit -> $displayLang $resolved")
        return true
    }

    private fun resolveSubtitleUrl(raw: String, baseUrl: String): String? {
        val value = raw
            .replace("\\/", "/")
            .replace("\\u0026", "&")
            .trim(' ', '\'', '"', '`')

        if (value.startsWith("http://") || value.startsWith("https://")) return value
        if (value.startsWith("//")) return "https:$value"

        return try {
            URI(baseUrl).resolve(value).toString()
        } catch (_: Exception) {
            null
        }
    }

    private fun guessSubtitleLanguage(value: String): String {
        val lower = value.lowercase()
        return when {
            Regex("(?:^|[^a-z])(tr|tur|turkish)(?:[^a-z]|$)").containsMatchIn(lower) -> "Türkçe"
            Regex("(?:^|[^a-z])(en|eng|english)(?:[^a-z]|$)").containsMatchIn(lower) -> "English"
            Regex("(?:^|[^a-z])(fr|fra|fre|french|francais)(?:[^a-z]|$)").containsMatchIn(lower) -> "Français"
            Regex("(?:^|[^a-z])(de|ger|deu|german)(?:[^a-z]|$)").containsMatchIn(lower) -> "Deutsch"
            Regex("(?:^|[^a-z])(es|spa|spanish)(?:[^a-z]|$)").containsMatchIn(lower) -> "Español"
            else -> "Altyazı"
        }
    }

    private fun normalizeSubtitleLanguage(language: String): String {
        return when (language.lowercase().trim()) {
            "tr", "tur", "turkish", "türkçe", "turkce" -> "Türkçe"
            "en", "eng", "english" -> "English"
            "fr", "fra", "fre", "french", "français", "francais" -> "Français"
            "de", "ger", "deu", "german", "deutsch" -> "Deutsch"
            "es", "spa", "spanish", "español", "espanol" -> "Español"
            else -> language.trim().ifBlank { "Altyazı" }
        }
    }

    private fun extractRapidVidSubtitleCandidates(html: String): List<Pair<String, String?>> {
        val config = decodeRapidVidConfig(html) ?: return emptyList()
        val found = linkedMapOf<String, String?>()

        Regex(
            """https?://[^\"'\s<>]+\.(?:vtt|srt)(?:\?[^\"'\s<>]+)?""",
            RegexOption.IGNORE_CASE
        ).findAll(config).forEach { match ->
            val url = match.value
            val start = maxOf(0, match.range.first - 250)
            val end = minOf(config.length, match.range.last + 250)
            val context = config.substring(start, end)
            val lang = Regex("""(?i)(?:lang|language|label|name|srclang)\s*[:=]\s*[\"']([^\"']+)[\"']""")
                .find(context)?.groupValues?.getOrNull(1)
                ?: guessSubtitleLanguage(url)
            found[url] = lang
        }

        return found.entries.map { it.key to it.value }
    }

    private fun decodeRapidVidConfig(html: String): String? {
        val encoded = Regex(
            """(?:window\.)?_p8\s*=\s*['"]([^'"]+)['"]"""
        ).find(html)?.groupValues?.getOrNull(1) ?: return null

        return try {
            val stage1 = Base64.decode(
                encoded.reversed(),
                Base64.DEFAULT
            ).toString(Charsets.UTF_8)

            val unshifted = buildString(stage1.length) {
                val key = "K9L"
                for (i in stage1.indices) {
                    val shift = key[i % key.length].code % 5 + 1
                    append((stage1[i].code - shift).toChar())
                }
            }

            Base64.decode(unshifted, Base64.DEFAULT)
                .toString(Charsets.UTF_8)
        } catch (e: Exception) {
            Log.d(name, "RapidVid config decode başarısız: ${e.message}")
            null
        }
    }

    private fun extractScxUrls(html: String): List<String> {
        val block = extractBalancedObject(html, "scx") ?: return emptyList()
        val found = linkedSetOf<String>()

        // scx içindeki tüm string değerlerini deniyoruz. Etiket/başlık gibi
        // kısa değerler doğal olarak URL filtresinden geçmeyecek.
        val quoted = Regex("""(['\"])(.*?)\1""").findAll(block)
        for (match in quoted) {
            val raw = match.groupValues.getOrNull(2) ?: continue
            if (raw.length < 8) continue

            for (decoded in decodePossibleScxValues(raw)) {
                val url = decoded
                    .replace("\\/", "/")
                    .replace("\\u0026", "&")
                    .trim()
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    found += url
                }
            }
        }

        return found.toList()
    }

    private fun decodePossibleScxValues(raw: String): List<String> {
        val values = linkedSetOf<String>()

        fun addBase64(value: String) {
            try {
                val clean = value.replace("-", "+").replace("_", "/")
                val decoded = Base64.decode(clean, Base64.DEFAULT)
                    .toString(Charsets.UTF_8)
                    .trim()
                if (decoded.startsWith("http://") || decoded.startsWith("https://")) {
                    values += decoded
                }
            } catch (_: Exception) {
            }
        }

        // Güncel sitede: atob(rot13(kayıt))
        addBase64(rot13(raw))

        // Eski/alternatif biçim için ters sırayı da dene.
        addBase64(raw)
        addBase64(rot13(raw).reversed())

        if (raw.startsWith("http://") || raw.startsWith("https://")) {
            values += raw
        }

        return values.toList()
    }

    private fun rot13(value: String): String = buildString(value.length) {
        for (c in value) {
            append(
                when (c) {
                    in 'a'..'z' -> ((c.code - 'a'.code + 13) % 26 + 'a'.code).toChar()
                    in 'A'..'Z' -> ((c.code - 'A'.code + 13) % 26 + 'A'.code).toChar()
                    else -> c
                }
            )
        }
    }

    private fun extractBalancedObject(html: String, variable: String): String? {
        val marker = Regex("""\b(?:var|let|const)\s+$variable\s*=\s*""").find(html)
            ?: return null
        val start = html.indexOf('{', marker.range.last + 1)
        if (start < 0) return null

        var depth = 0
        var quote: Char? = null
        var escaped = false

        for (i in start until html.length) {
            val c = html[i]

            if (quote != null) {
                if (escaped) {
                    escaped = false
                } else if (c == '\\') {
                    escaped = true
                } else if (c == quote) {
                    quote = null
                }
                continue
            }

            if (c == '\"' || c == '\'') {
                quote = c
                continue
            }

            when (c) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return html.substring(start, i + 1)
                }
            }
        }

        return null
    }

    private suspend fun emitRapidVidMaster(
        url: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val cleanUrl = url.trim()
        if (!cleanUrl.startsWith("http://") && !cleanUrl.startsWith("https://")) {
            return false
        }

        // RapidVid'in güncel cm master URL'leri her zaman .m3u8 ile bitmiyor
        // (ör. /mp/... veya /mm/... biçimleri). Bu nedenle burada uzantı
        // kontrolü yapmadan HLS olarak bildiriyoruz.
        callback(
            newExtractorLink(
                source = name,
                name = "FullDFilmizlesene • RapidVid",
                url = cleanUrl,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = referer
                this.headers = browserHeaders
                quality = Qualities.P1080.value
            }
        )

        Log.d(name, "RapidVid cm emit -> $cleanUrl referer=$referer")
        return true
    }

    private suspend fun resolveRapidVid(url: String): String? {
        val html = app.get(
            url,
            headers = browserHeaders + ("Referer" to mainUrl + "/")
        ).text
        return resolveRapidVidFromHtml(html)
    }

    private fun resolveRapidVidFromHtml(html: String): String? {
        val json = decodeRapidVidConfig(html) ?: return null
        return Regex(
            """[\"']cm[\"']\s*:\s*[\"']([^\"']+)[\"']"""
        ).find(json)?.groupValues?.getOrNull(1)
            ?.replace("\\/", "/")
    }

    private fun refererForMedia(embedUrl: String): String {
        return try {
            val uri = Uri.parse(embedUrl)
            "${uri.scheme ?: "https"}://${uri.host ?: "rapidvid.net"}/"
        } catch (_: Exception) {
            "https://rapidvid.net/"
        }
    }

    private fun extractMediaCandidates(html: String): List<String> {
        val candidates = linkedSetOf<String>()

        fun add(raw: String?) {
            if (raw.isNullOrBlank()) return
            var value = raw
                .replace("\\/", "/")
                .replace("\\u0026", "&")
                .trim(' ', '\'', '"', '`')

            if (value.startsWith("//")) value = "https:$value"

            if (value.startsWith("http://") || value.startsWith("https://")) {
                candidates += value
            }
        }

        Regex(
            """(?i)(?:selectedMasterUrl|masterUrl)\s*[:=]\s*[\"']([^\"']+)[\"']"""
        ).findAll(html).forEach { add(it.groupValues[1]) }

        Regex(
            """(?i)[\"'](https?://[^\"']*(?:master\.txt|master\.m3u8|\.m3u8(?:\?[^\"']*)?|\.mp4(?:\?[^\"']*)?))[\"']"""
        ).findAll(html).forEach { add(it.groupValues[1]) }

        Regex(
            """(?i)(https?://[^\s\"'<>]+(?:master\.txt|master\.m3u8|\.m3u8(?:\?[^\s\"'<>]*)?|\.mp4(?:\?[^\s\"'<>]*)?))"""
        ).findAll(html).forEach { add(it.groupValues[1]) }

        return candidates.toList()
    }

    private suspend fun emitM3u8(
        candidate: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var url = candidate

        if (url.contains("master.txt", ignoreCase = true)) {
            try {
                val text = app.get(
                    url,
                    headers = browserHeaders + ("Referer" to referer)
                ).text

                val m3u8 = Regex(
                    """(?i)(https?://[^\s\"'<>]+(?:master\.m3u8|\.m3u8)(?:\?[^\s\"'<>]*)?)"""
                ).find(text)?.groupValues?.getOrNull(1)

                if (!m3u8.isNullOrBlank()) url = m3u8
            } catch (e: Exception) {
                Log.d(name, "master.txt okunamadı -> $url : ${e.message}")
            }
        }

        if (!url.contains(".m3u8", ignoreCase = true)) return false

        callback(
            newExtractorLink(
                source = name,
                name = "FullDFilmizlesene",
                url = url,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = referer
                quality = Qualities.P1080.value
            }
        )

        Log.d(name, "M3U8 emit -> $url referer=$referer")
        return true
    }
}
