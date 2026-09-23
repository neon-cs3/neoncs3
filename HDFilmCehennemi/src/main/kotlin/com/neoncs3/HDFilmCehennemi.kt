package com.neoncs3

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.newMovieSearchResponse
import com.lagradost.cloudstream3.utils.newMovieLoadResponse
import com.lagradost.cloudstream3.utils.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class HDFilmCehennemi : MainAPI() {

    override var mainUrl = "https://www.hdfilmcehennemi.nl"
    override var name = "HDFilmCehennemi"

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    override var lang = "tr"

    override val mainPage = mainPageOf(
        mainUrl to "Yeni Eklenen Filmler",
        "$mainUrl/yabancidiziizle-5" to "Yabancı Diziler",
        "$mainUrl/category/tavsiye-filmler-izle3" to "Tavsiye Filmler",
        "$mainUrl/imdb-7-puan-uzeri-filmle-2" to "IMDB 7+ Filmler",
        "$mainUrl/en-cok-yorumlananlar-2" to "En Çok Yorumlananlar",
        "$mainUrl/en-cok-begenilen-filmleri-izle-4" to "En Çok Beğenilenler",

        "$mainUrl/genre/aile" to "Aile",
        "$mainUrl/genre/aksiyon" to "Aksiyon",
        "$mainUrl/genre/animasyon" to "Animasyon",
        "$mainUrl/genre/belgesel" to "Belgesel",
        "$mainUrl/genre/bilim-kurgu" to "Bilim Kurgu",
        "$mainUrl/genre/komedi" to "Komedi",
        "$mainUrl/genre/korku" to "Korku",
        "$mainUrl/genre/romantik" to "Romantik"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val document = app.get(
            request.data,
            headers = mapOf(
                "User-Agent" to USER_AGENT
            )
        ).document

        val items = document.select("div.section-content a.poster").mapNotNull {
            toSearchResult(it)
        }

        return newHomePageResponse(
            request.name,
            items,
            hasNext = false
        )
    }

    private fun toSearchResult(element: Element): SearchResponse? {
        val title = element.selectFirst("strong.poster-title")?.text()?.trim()
            ?: return null

        val href = element.attr("href").takeIf { it.isNotBlank() }
            ?: return null

        val poster = element.selectFirst("img")?.let {
            it.attr("data-src").ifBlank {
                it.attr("src")
            }
        }

        return newMovieSearchResponse(
            title,
            fixUrl(href),
            TvType.Movie
        ) {
            this.posterUrl = poster
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> {
        return search(query)
    }

    override suspend fun search(query: String): List<SearchResponse> {

        val url = "$mainUrl/search?q=${query}"

        val response = app.get(
            url,
            headers = mapOf(
                "X-Requested-With" to "fetch",
                "User-Agent" to USER_AGENT
            )
        )

        val results = try {
            parseJson<Results>(response.text).results
        } catch (e: Exception) {
            Log.e("HDFilmCehennemi", "Search JSON parse hatası: ${e.message}")
            return emptyList()
        }

        return results.mapNotNull { html ->

            try {
                val document = Jsoup.parse(html)

                val title = document
                    .selectFirst("h4.title")
                    ?.text()
                    ?.trim()
                    ?: return@mapNotNull null

                val href = document
                    .selectFirst("a")
                    ?.attr("href")
                    ?.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null

                val img = document.selectFirst("img")

                val poster = img?.attr("data-src").ifNullOrBlank {
                    img?.attr("src")
                }

                newMovieSearchResponse(
                    title,
                    fixUrl(href),
                    TvType.Movie
                ) {
                    posterUrl = poster
                }

            } catch (e: Exception) {
                Log.e(
                    "HDFilmCehennemi",
                    "Search result parse hatası: ${e.message}"
                )
                null
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {

        val document = app.get(
            url,
            headers = mapOf(
                "User-Agent" to USER_AGENT
            )
        ).document

        val title = document
            .selectFirst("h1.section-title")
            ?.text()
            ?.substringBefore(" izle")
            ?.trim()
            ?: document.title()

        val poster = document
            .select("aside.post-info-poster img.lazyload")
            .lastOrNull()
            ?.attr("data-src")

        val tags = document
            .select("div.post-info-genres a")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }

        val year = document
            .selectFirst("div.post-info-year-country a")
            ?.text()
            ?.trim()
            ?.toIntOrNull()

        val description = document
            .selectFirst("article.post-info-content > p")
            ?.text()
            ?.trim()

        val actors = document
            .select("div.post-info-cast a")
            .mapNotNull { actor ->

                val name = actor
                    .selectFirst("strong")
                    ?.text()
                    ?.trim()
                    ?: actor.text().trim()

                if (name.isBlank()) {
                    return@mapNotNull null
                }

                val image = actor
                    .selectFirst("img")
                    ?.let {
                        it.attr("data-src").ifBlank {
                            it.attr("src")
                        }
                    }

                Actor(
                    name = name,
                    image = image
                )
            }

        val recommendations = document
            .select("div.section-slider-container div.slider-slide")
            .mapNotNull { item ->

                val link = item
                    .selectFirst("a")
                    ?.attr("href")
                    ?.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null

                val recTitle = item
                    .attr("title")
                    .ifBlank {
                        item.selectFirst("strong.poster-title")?.text()
                    }
                    ?.trim()
                    ?: return@mapNotNull null

                val image = item
                    .selectFirst("img")
                    ?.let {
                        it.attr("data-src").ifBlank {
                            it.attr("src")
                        }
                    }

                newMovieSearchResponse(
                    recTitle,
                    fixUrl(link),
                    TvType.Movie
                ) {
                    posterUrl = image
                }
            }

        val trailer = document
            .selectFirst("div.post-info-trailer button[data-modal]")
            ?.attr("data-modal")
            ?.let { trailerUrl ->

                val youtubeId = when {
                    trailerUrl.contains("youtube.com/watch") ->
                        trailerUrl.substringAfter("v=").substringBefore("&")

                    trailerUrl.contains("youtu.be/") ->
                        trailerUrl.substringAfter("youtu.be/").substringBefore("?")

                    trailerUrl.contains("embed/") ->
                        trailerUrl.substringAfter("embed/").substringBefore("?")

                    else -> null
                }

                youtubeId?.let {
                    "https://www.youtube.com/embed/$it"
                }
            }

        val seasons = document.select("div.seasons")

        if (seasons.isNotEmpty()) {

            val episodes = document
                .select("div.seasons-tab-content a")
                .mapNotNull { episode ->

                    val episodeHref = episode
                        .attr("href")
                        .takeIf { it.isNotBlank() }
                        ?: return@mapNotNull null

                    val episodeText = episode.text().trim()

                    val episodeNumber = Regex(
                        """(\d+)\.\s*Bölüm"""
                    )
                        .find(episodeText)
                        ?.groupValues
                        ?.getOrNull(1)
                        ?.toIntOrNull()
                        ?: return@mapNotNull null

                    val seasonNumber = Regex(
                        """(\d+)\.\s*Sezon"""
                    )
                        .find(episodeText)
                        ?.groupValues
                        ?.getOrNull(1)
                        ?.toIntOrNull()
                        ?: 1

                    Episode(
                        name = episodeText,
                        season = seasonNumber,
                        episode = episodeNumber,
                        data = fixUrl(episodeHref)
                    )
                }

            return newTvSeriesLoadResponse(
                title,
                url,
                TvType.TvSeries,
                episodes
            ) {

                posterUrl = poster
                this.year = year
                plot = description
                this.tags = tags

                addActors(actors)

                if (!trailer.isNullOrBlank()) {
                    addTrailer(trailer)
                }

                this.recommendations = recommendations
            }

        } else {

            return newMovieLoadResponse(
                title,
                url,
                TvType.Movie,
                url
            ) {

                posterUrl = poster
                this.year = year
                plot = description
                this.tags = tags

                addActors(actors)

                if (!trailer.isNullOrBlank()) {
                    addTrailer(trailer)
                }

                this.recommendations = recommendations
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        Log.d("HDFilmCehennemi", "==========================================")
        Log.d("HDFilmCehennemi", "LOAD LINKS BAŞLADI")
        Log.d("HDFilmCehennemi", "DATA: $data")
        Log.d("HDFilmCehennemi", "==========================================")

        val document = try {
            app.get(
                data,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to mainUrl
                )
            ).document
        } catch (e: Exception) {

            Log.e(
                "HDFilmCehennemi",
                "Ana sayfa alınamadı: ${e.message}"
            )

            return false
        }

        val alternativeLinks = document.select(
            "div.alternative-links"
        )

        Log.d(
            "HDFilmCehennemi",
            "Alternative link sayısı: ${alternativeLinks.size}"
        )

        if (alternativeLinks.isEmpty()) {
            Log.e(
                "HDFilmCehennemi",
                "Alternative links bulunamadı!"
            )
            return false
        }

        var found = false

        val processedVideoIds = mutableSetOf<String>()
        val processedStreams = mutableSetOf<String>()

        for (alternative in alternativeLinks) {

            val language = alternative
                .attr("data-lang")
                .uppercase()

            val buttons = alternative.select(
                "button.alternative-link"
            )

            Log.d(
                "HDFilmCehennemi",
                "Dil: $language"
            )

            Log.d(
                "HDFilmCehennemi",
                "Buton sayısı: ${buttons.size}"
            )

            for (button in buttons) {

                val sourceName = button
                    .text()
                    .replace(Regex("""\([^)]*\)"""), "")
                    .replace(Regex("""\[[^]]*]"""), "")
                    .trim()

                val videoId = button
                    .attr("data-video")
                    .trim()

                if (videoId.isBlank()) {
                    continue
                }

                Log.d(
                    "HDFilmCehennemi",
                    "SOURCE: $sourceName [$language]"
                )

                Log.d(
                    "HDFilmCehennemi",
                    "VIDEO ID: $videoId"
                )

                if (!processedVideoIds.add(videoId)) {
                    Log.d(
                        "HDFilmCehennemi",
                        "Video ID zaten işlendi: $videoId"
                    )
                }

                val videoUrl =
                    "$mainUrl/video/$videoId/"

                Log.d(
                    "HDFilmCehennemi",
                    "VIDEO URL: $videoUrl"
                )

                val videoResponse = try {

                    app.get(
                        videoUrl,
                        headers = mapOf(
                            "X-Requested-With" to "fetch",
                            "Referer" to data,
                            "User-Agent" to USER_AGENT
                        )
                    )

                } catch (e: Exception) {

                    Log.e(
                        "HDFilmCehennemi",
                        "Video isteği hata: ${e.message}"
                    )

                    continue
                }

                Log.d(
                    "HDFilmCehennemi",
                    "VIDEO RESPONSE CODE: ${videoResponse.code}"
                )

                Log.d(
                    "HDFilmCehennemi",
                    "VIDEO RESPONSE LENGTH: ${videoResponse.text.length}"
                )

                if (videoResponse.code !in 200..299) {
                    continue
                }

                /*
                 * /video/ID/ cevabı bazen doğrudan iframe,
                 * bazen rapidrame_id içeriyor.
                 */

                var iframeUrl: String? = null

                val videoDocument = Jsoup.parse(
                    videoResponse.text
                )

                iframeUrl = videoDocument
                    .selectFirst("iframe")
                    ?.let {
                        it.attr("src").ifBlank {
                            it.attr("data-src")
                        }
                    }

                /*
                 * Rapidrame ID.
                 *
                 * Örnek:
                 * rapidrame_id=dc6xkw7mtjf0
                 */

                if (iframeUrl.isNullOrBlank()) {

                    val rapidId = Regex(
                        """rapidrame_id\s*=\s*([A-Za-z0-9_-]+)"""
                    )
                        .find(videoResponse.text)
                        ?.groupValues
                        ?.getOrNull(1)

                    if (!rapidId.isNullOrBlank()) {

                        iframeUrl =
                            "$mainUrl/playerr/$rapidId"

                        Log.d(
                            "HDFilmCehennemi",
                            "Rapidrame dönüştürüldü: $iframeUrl"
                        )
                    }
                }

                /*
                 * HTML içinde /rplayer/ şeklinde geçiyorsa
                 * doğrudan onu da kullan.
                 */

                if (iframeUrl.isNullOrBlank()) {

                    val rplayer = Regex(
                        """(?:https?:)?//[^"' ]+/(?:rplayer|playerr)/[A-Za-z0-9_-]+/?"""
                    )
                        .find(videoResponse.text)
                        ?.value

                    if (!rplayer.isNullOrBlank()) {
                        iframeUrl = fixUrl(rplayer)
                    }
                }

                if (iframeUrl.isNullOrBlank()) {

                    Log.e(
                        "HDFilmCehennemi",
                        "Iframe / player URL bulunamadı!"
                    )

                    continue
                }

                iframeUrl = fixUrl(iframeUrl)

                Log.d(
                    "HDFilmCehennemi",
                    "PLAYER URL: $iframeUrl"
                )

                val playerResponse = try {

                    app.get(
                        iframeUrl,
                        headers = mapOf(
                            "Referer" to data,
                            "User-Agent" to USER_AGENT
                        )
                    )

                } catch (e: Exception) {

                    Log.e(
                        "HDFilmCehennemi",
                        "Player isteği hata: ${e.message}"
                    )

                    continue
                }

                Log.d(
                    "HDFilmCehennemi",
                    "PLAYER HTTP CODE: ${playerResponse.code}"
                )

                Log.d(
                    "HDFilmCehennemi",
                    "PLAYER LENGTH: ${playerResponse.text.length}"
                )

                if (playerResponse.code !in 200..299) {
                    continue
                }

                val playerHtml = playerResponse.text

                /*
                 * =====================================================
                 *  HDFILMCEHENNEMI GQSR4 ÇÖZÜCÜ
                 * =====================================================
                 *
                 * Player içinde kaynak şu şekilde saklanıyor:
                 *
                 * var gqsr4 = n5pg1("....".split("!"));
                 *
                 * JWPlayer ise:
                 *
                 * sources: [{file: gqsr4, type: "hls"}]
                 *
                 * Dolayısıyla JSON-LD contentUrl kullanmak yerine
                 * gqsr4 değerini gerçek JS algoritmasıyla çözmek
                 * gerekiyor.
                 */

                val encryptedSource = Regex(
                    """(?:var\s+)?gqsr4\s*=\s*n5pg1\(\s*["']([\s\S]*?)["']\s*\.split\(\s*["']!["']\s*\)\s*\)"""
                )
                    .find(playerHtml)
                    ?.groupValues
                    ?.getOrNull(1)

                if (encryptedSource.isNullOrBlank()) {

                    Log.e(
                        "HDFilmCehennemi",
                        "gqsr4 şifreli veri bulunamadı!"
                    )

                    /*
                     * Eski JSON-LD yöntemi fallback olarak bırakıldı.
                     */

                    val jsonLdUrl = Regex(
                        """"contentUrl"\s*:\s*"([^"]+)""""
                    )
                        .find(playerHtml)
                        ?.groupValues
                        ?.getOrNull(1)

                    if (!jsonLdUrl.isNullOrBlank() &&
                        (
                            jsonLdUrl.contains(".m3u8") ||
                            jsonLdUrl.contains("master.txt")
                        )
                    ) {

                        val streamUrl = jsonLdUrl
                            .replace("\\/", "/")

                        if (processedStreams.add(streamUrl)) {

                            Log.d(
                                "HDFilmCehennemi",
                                "JSON-LD HLS bulundu: $streamUrl"
                            )

                            callback(
                                newExtractorLink(
                                    name,
                                    "$sourceName [$language]",
                                    streamUrl,
                                    ExtractorLinkType.M3U8
                                ) {
                                    referer = iframeUrl
                                    quality = Qualities.Unknown.value
                                }
                            )

                            found = true
                        }
                    }

                    continue
                }

                Log.d(
                    "HDFilmCehennemi",
                    "gqsr4 encrypted length: ${encryptedSource.length}"
                )

                val decodedSource = try {

                    decodeGqsr4(
                        encryptedSource
                    )

                } catch (e: Exception) {

                    Log.e(
                        "HDFilmCehennemi",
                        "gqsr4 decode hatası: ${e.message}"
                    )

                    null
                }

                if (decodedSource.isNullOrBlank()) {

                    Log.e(
                        "HDFilmCehennemi",
                        "gqsr4 decode sonucu boş!"
                    )

                    continue
                }

                val streamUrl = decodedSource
                    .trim()
                    .replace("\\/", "/")
                    .replace("\\u0026", "&")

                Log.d(
                    "HDFilmCehennemi",
                    "DECODED HLS URL: $streamUrl"
                )

                if (
                    !streamUrl.startsWith("http://") &&
                    !streamUrl.startsWith("https://")
                ) {

                    Log.e(
                        "HDFilmCehennemi",
                        "Decode edilen URL geçersiz: $streamUrl"
                    )

                    continue
                }

                if (
                    !streamUrl.contains(".m3u8") &&
                    !streamUrl.contains("master.txt")
                ) {

                    Log.e(
                        "HDFilmCehennemi",
                        "Decode edilen URL HLS değil: $streamUrl"
                    )

                    continue
                }

                /*
                 * Aynı kaynak Close + Rapidrame altında
                 * iki kez gelirse CloudStream'de duplicate oluşmasın.
                 */

                if (!processedStreams.add(streamUrl)) {

                    Log.d(
                        "HDFilmCehennemi",
                        "Stream zaten eklendi: $streamUrl"
                    )

                    continue
                }

                /*
                 * Player'ın gerçek origin'i:
                 *
                 * https://hdfilmcehennemi.mobi/
                 *
                 * Decode edilen CDN bazı durumlarda Referer kontrolü
                 * yapabiliyor.
                 */

                val streamReferer = when {
                    iframeUrl.contains("hdfilmcehennemi.mobi") ->
                        "https://hdfilmcehennemi.mobi/"

                    else ->
                        iframeUrl
                }

                callback(
                    newExtractorLink(
                        name,
                        "$sourceName [$language]",
                        streamUrl,
                        ExtractorLinkType.M3U8
                    ) {
                        referer = streamReferer
                        quality = Qualities.Unknown.value
                    }
                )

                found = true

                Log.d(
                    "HDFilmCehennemi",
                    "HLS LINK CALLBACK YAPILDI"
                )
            }
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

    /**
     * HDFilmCehennemi player'ındaki n5pg1() JavaScript
     * fonksiyonunun Kotlin karşılığı.
     *
     * Önemli:
     * Bu fonksiyon tahmini değildir.
     * Player kaynak kodundaki algoritmanın birebir Kotlin
     * karşılığıdır.
     */
    private fun decodeGqsr4(input: String): String {

        val ae3 = input
            .split("!")
            .toMutableList()

        if (ae3.size < 3) {
            throw IllegalArgumentException(
                "n5pg1 input çok kısa"
            )
        }

        /*
         * JavaScript:
         *
         * pp9v1 = ae3.length - 2
         * g1g5b = pp9v1 % 7
         * m636 = 8 + (pp9v1 % 5)
         */

        val pp9v1 = ae3.size - 2

        val g1g5b = pp9v1 % 7

        val m636 = 8 + (pp9v1 % 5)

        if (m636 >= ae3.size) {
            throw IllegalArgumentException(
                "n5pg1 m636 sınır dışında"
            )
        }

        /*
         * JavaScript:
         *
         * sk37 = ae3.splice(m636,1)[0]
         * pp7 = ae3.splice(g1g5b,1)[0]
         */

        val sk37 = ae3.removeAt(m636)

        if (g1g5b >= ae3.size) {
            throw IllegalArgumentException(
                "n5pg1 g1g5b sınır dışında"
            )
        }

        val pp7 = ae3.removeAt(g1g5b)

        /*
         * JavaScript:
         *
         * ab768 = ae3.join('')
         */

        var ab768 = ae3.joinToString("")

        /*
         * JavaScript:
         *
         * if (pp7.length > 4096)
         *     ab768 = atob(ab768)
         *
         * JS atob binary string döndürür.
         * Bu yüzden ISO-8859-1 kullanıyoruz.
         */

        if (pp7.length > 4096) {

            val decodedBytes = Base64.decode(
                ab768,
                Base64.DEFAULT
            )

            ab768 = String(
                decodedBytes,
                Charsets.ISO_8859_1
            )
        }

        /*
         * =====================================================
         * re5 / nf0qv / hj8mz / sj3 / u0v
         * =====================================================
         */

        var re5 = 0
        var nf0qv = 0

        for (i in ab768.indices) {

            val charCode = ab768[i].code

            re5 = (
                re5 * 37 +
                    charCode
                ) % 241

            nf0qv = (
                nf0qv +
                    ((charCode shl 1) xor i)
                ) and 255
        }

        val hj8mz =
            (re5 * 3 + nf0qv) % 256

        val sj3 =
            (nf0qv % 11) + 5

        var u0v =
            ((nf0qv * 251 + re5) % 65519) + 1

        /*
         * =====================================================
         * sk37 dönüşümleri
         * =====================================================
         */

        var transformed = ab768

        for (index in sk37.length - 1 downTo 0) {

            when (sk37[index]) {

                '7' -> {

                    val bytes = try {
                        Base64.decode(
                            transformed,
                            Base64.DEFAULT
                        )
                    } catch (e: Exception) {
                        throw IllegalArgumentException(
                            "n5pg1 Base64 decode başarısız",
                            e
                        )
                    }

                    transformed = String(
                        bytes,
                        Charsets.ISO_8859_1
                    )
                }

                '3' -> {

                    transformed =
                        transformed.reversed()
                }

                else -> {

                    val shiftSource =
                        sk37[index].code

                    val shift =
                        (26 - ((shiftSource - 96) % 26)) % 26

                    val chars =
                        transformed.toCharArray()

                    for (i in chars.indices) {

                        val c = chars[i]

                        when {

                            c in 'a'..'z' -> {

                                chars[i] =
                                    (
                                        'a'.code +
                                            (
                                                (c.code - 'a'.code + shift)
                                                    .mod(26)
                                            )
                                        ).toChar()
                            }

                            c in 'A'..'Z' -> {

                                chars[i] =
                                    (
                                        'A'.code +
                                            (
                                                (c.code - 'A'.code + shift)
                                                    .mod(26)
                                            )
                                        ).toChar()
                            }
                        }
                    }

                    transformed =
                        String(chars)
                }
            }
        }

        /*
         * JavaScript:
         *
         * if (sk37.length > 2048)
         *     transformed = reverse(transformed)
         */

        if (sk37.length > 2048) {
            transformed =
                transformed.reversed()
        }

        /*
         * =====================================================
         * Fisher-Yates benzeri permutation
         * =====================================================
         *
         * for (i = len - 1; i > 0; i--) {
         *     u0v = (u0v * 97 + 41) % 65519
         *     l3g[i] = u0v % (i + 1)
         *     swap(...)
         * }
         */

        val chars =
            transformed.toCharArray()

        for (i in chars.size - 1 downTo 1) {

            u0v =
                (u0v * 97 + 41) % 65519

            val swapIndex =
                u0v % (i + 1)

            val temp =
                chars[i]

            chars[i] =
                chars[swapIndex]

            chars[swapIndex] =
                temp
        }

        /*
         * =====================================================
         * XOR pass
         * =====================================================
         *
         * fo6 = hj8mz
         *
         * for each char:
         *
         * fo6 = (fo6 * 5 + sj3) % 256
         * char = char ^ fo6
         * fo6 = (fo6 + char) % 256
         */

        var fo6 =
            hj8mz

        val output =
            CharArray(chars.size)

        for (i in chars.indices) {

            fo6 =
                (fo6 * 5 + sj3) % 256

            val original =
                chars[i].code

            val decoded =
                original xor fo6

            output[i] =
                decoded.toChar()

            fo6 =
                (fo6 + original) % 256
        }

        return String(output)
    }

    private fun String?.ifNullOrBlank(
        fallback: () -> String?
    ): String? {
        return if (this.isNullOrBlank()) {
            fallback()
        } else {
            this
        }
    }

    data class Results(
        @com.google.gson.annotations.SerializedName("results")
        val results: List<String> = arrayListOf()
    )
}
