package org.intergalactictv.client.streaming

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.jsoup.Jsoup
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import okio.ByteString.Companion.decodeBase64
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Independent browser-like client: never shares the Scryer API key or cookies. */
class WebVideoSearch(
    private val http: OkHttpClient = client,
    private val searchEndpoint: String = SEARCH_URL,
    private val fallbackEndpoint: String? = "https://www.bing.com/search"
) {
    data class Result(val items: List<VideoItem>, val inspected: Int, val unavailable: Int)
    private data class Html(val text: String, val url: String)

    suspend fun search(query: String): Result = coroutineScope {
        var pages = emptyList<VideoItem>()
        for (endpoint in listOfNotNull(searchEndpoint, fallbackEndpoint).distinct()) {
            try {
                val url = endpoint.toHttpUrlOrNull()!!.newBuilder()
                    .addQueryParameter("q", "$query смотреть онлайн").build().toString()
                val html = fetch(url)
                pages = withContext(Dispatchers.IO) { parseResults(html.text, html.url).take(MAX_PAGES) }
                if (pages.isNotEmpty()) break
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (_: Exception) { /* Try the next search provider. */ }
        }
        if (pages.isEmpty()) throw IOException("Поисковик не вернул страницы. Откройте поиск в браузере.")
        val checked = pages.map { page -> async(Dispatchers.IO) {
            try {
                val body = fetch(page.pageUrl)
                parsePage(body.text, body.url, page.title) to true
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (_: Exception) { page to false }
        } }.awaitAll()
        Result(checked.map { it.first }, pages.size, checked.count { !it.second })
    }

    suspend fun inspect(url: String, title: String = "Видео с сайта"): VideoItem {
        val html = fetch(url)
        return withContext(Dispatchers.IO) { parsePage(html.text, html.url, title) }
    }

    private suspend fun fetch(url: String): Html {
        require(webUrl(url) != null) { "Введите HTTP/HTTPS-ссылку" }
        val call = http.newCall(Request.Builder().url(url).header("User-Agent", USER_AGENT).build())
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        val html = response.use {
                            if (!it.isSuccessful) throw IOException("Сайт: HTTP ${it.code}")
                            val body = it.body ?: throw IOException("Пустая страница")
                            val type = body.contentType()
                            if (type != null && type.subtype != "html" && type.subtype != "xhtml+xml") {
                                throw IOException("Сайт не вернул HTML-страницу")
                            }
                            val bytes = body.byteStream().use { stream ->
                                val out = java.io.ByteArrayOutputStream()
                                val buffer = ByteArray(8192)
                                while (true) {
                                    val n = stream.read(buffer)
                                    if (n < 0) break
                                    if (out.size() + n > MAX_HTML_BYTES) throw IOException("Слишком большая страница")
                                    out.write(buffer, 0, n)
                                }
                                out.toByteArray()
                            }
                            Html(bytes.toString(type?.charset(Charsets.UTF_8) ?: Charsets.UTF_8), it.request.url.toString())
                        }
                        if (continuation.isActive) continuation.resume(html)
                    } catch (e: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(e)
                    }
                }
            })
        }
    }

    companion object {
        const val MAX_PAGES = 6
        const val SEARCH_URL = "https://html.duckduckgo.com/html/"
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
        private const val MAX_HTML_BYTES = 2 * 1024 * 1024
        private val client = OkHttpClient.Builder().callTimeout(12, TimeUnit.SECONDS)
            .connectTimeout(6, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).build()

        fun webUrl(value: String): String? = value.toHttpUrlOrNull()?.takeIf {
            it.username.isEmpty() && it.password.isEmpty()
        }?.toString()

        fun isMediaUrl(value: String): Boolean {
            val path = value.toHttpUrlOrNull()?.encodedPath?.lowercase() ?: return false
            return listOf(".m3u8", ".mpd", ".mp4", ".webm", ".m4v").any(path::endsWith)
        }

        fun browserSearch(query: String): VideoItem = page(
            "https://duckduckgo.com/".toHttpUrlOrNull()!!.newBuilder()
                .addQueryParameter("q", "$query смотреть онлайн").build().toString(),
            "Поиск в браузере · $query"
        )

        fun page(url: String, title: String, description: String = ""): VideoItem {
            val normalized = webUrl(url) ?: throw IllegalArgumentException("Введите HTTP/HTTPS-ссылку")
            val id = MessageDigest.getInstance("SHA-256").digest(normalized.toByteArray())
                .joinToString("") { "%02x".format(it) }
            return VideoItem("web:$id", title, description = description,
                group = normalized.toHttpUrlOrNull()!!.host, webPageUrl = normalized)
        }

        internal fun parseResults(html: String, base: String): List<VideoItem> =
            Jsoup.parse(html, base).select("a.result__a, li.b_algo h2 a").mapNotNull { link ->
                val href = base.toHttpUrlOrNull()?.resolve(link.attr("href")) ?: return@mapNotNull null
                val target = if (href.host == "duckduckgo.com" || href.host.endsWith(".duckduckgo.com")) {
                    href.queryParameter("uddg") ?: return@mapNotNull null
                } else if ((href.host == "bing.com" || href.host.endsWith(".bing.com")) && href.encodedPath == "/ck/a") {
                    val encoded = href.queryParameter("u")?.removePrefix("a1") ?: return@mapNotNull null
                    encoded.decodeBase64()?.utf8() ?: return@mapNotNull null
                } else href.toString()
                val url = webUrl(target) ?: return@mapNotNull null
                page(url, link.text().ifBlank { url }, "Страница из интернет-поиска")
            }.distinctBy { it.pageUrl }

        /** Recognise actual media, never mistake an iframe/embed URL for a stream. */
        internal fun parsePage(html: String, base: String, fallback: String): VideoItem {
            val doc = Jsoup.parse(html, base)
            fun meta(key: String) = doc.selectFirst("meta[property='$key'], meta[name='$key']")?.attr("content").orEmpty()
            fun resolve(raw: String): String? = raw.takeIf { it.isNotBlank() }
                ?.let { base.toHttpUrlOrNull()?.resolve(it)?.toString() }?.let(::webUrl)
            val title = meta("og:title").ifBlank { doc.title() }.ifBlank { fallback }.take(240)
            val media = linkedSetOf<String>()
            doc.select("video[src], video source[src], audio source[src]").forEach { element ->
                resolve(element.attr("src"))?.takeIf(::isMediaUrl)?.let(media::add)
            }
            listOf("og:video", "og:video:url", "og:video:secure_url", "twitter:player:stream").forEach { key ->
                resolve(meta(key))?.takeIf(::isMediaUrl)?.let(media::add)
            }
            fun jsonVideo(value: Any?) {
                when (value) {
                    is JSONObject -> {
                        if (value.optString("@type").contains("VideoObject")) {
                            resolve(value.optString("contentUrl"))?.let(media::add)
                        }
                        value.keys().forEach { jsonVideo(value.opt(it)) }
                    }
                    is JSONArray -> for (i in 0 until value.length()) jsonVideo(value.opt(i))
                }
            }
            doc.select("script[type=application/ld+json]").forEach {
                runCatching { jsonVideo(JSONTokener(it.data()).nextValue()) }
            }
            val image = resolve(meta("og:image"))
            return page(base, title, meta("og:description").ifBlank { meta("description") }.take(2000)).copy(
                thumbnail = image,
                streamUrl = media.firstOrNull(),
                requestHeaders = if (media.isEmpty()) emptyMap() else mapOf("Referer" to base, "User-Agent" to USER_AGENT)
            )
        }
    }
}
