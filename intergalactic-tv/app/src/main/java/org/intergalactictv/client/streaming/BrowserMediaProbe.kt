package org.intergalactictv.client.streaming

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

internal data class BrowserMedia(
    val url: String,
    val referer: String,
    val title: String = "",
    val poster: String? = null,
    val durationSec: Int = 0,
    val mime: String? = null
)

/** A URL is a video only after checking the actual response, never just its filename. */
internal class BrowserMediaProbe(private val http: OkHttpClient = RutubeApi.http.newBuilder()
    .cookieJar(WebPlaybackCookies()).callTimeout(8, TimeUnit.SECONDS).build()) {

    suspend fun verify(media: BrowserMedia, page: VideoItem): VideoItem? {
        if (WebVideoSearch.webUrl(media.url) == null || isAdvertisement(media.url)) return null
        val referer = WebVideoSearch.webUrl(media.referer) ?: page.pageUrl
        val origin = referer.toHttpUrlOrNull()?.let { "${it.scheme}://${it.host}" +
            if (it.port != okhttp3.HttpUrl.defaultPort(it.scheme)) ":${it.port}" else "" }.orEmpty()
        val request = Request.Builder().url(media.url)
            .header("User-Agent", WebVideoSearch.USER_AGENT).header("Referer", referer)
            .header("Origin", origin)
            .header("Range", "bytes=0-65535").build()
        val call = http.newCall(request)
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resume(null)
                }
                override fun onResponse(call: Call, response: Response) {
                    val result = runCatching { response.use {
                        if (!it.isSuccessful) return@use null
                        val body = it.body ?: return@use null
                        val type = body.contentType()?.let { t -> "${t.type}/${t.subtype}" }.orEmpty()
                        val bytes = body.byteStream().use { input ->
                            val out = java.io.ByteArrayOutputStream()
                            val buffer = ByteArray(2048)
                            val limit = if (type.startsWith("video/")) 2048 else 65536
                            while (out.size() < limit) {
                                val n = input.read(buffer, 0, minOf(buffer.size, limit - out.size()))
                                if (n < 0) break
                                out.write(buffer, 0, n)
                            }
                            out.toByteArray()
                        }
                        val mime = identify(type, bytes) ?: return@use null
                        val title = media.title.ifBlank { page.title }.take(240)
                        page.copy(title = title, thumbnail = media.poster?.let(WebVideoSearch::webUrl) ?: page.thumbnail,
                            durationSec = media.durationSec.coerceIn(0, 7 * 24 * 3600), streamUrl = media.url,
                            streamMimeType = mime,
                            requestHeaders = mapOf("Referer" to referer, "Origin" to origin, "User-Agent" to WebVideoSearch.USER_AGENT))
                    } }.getOrNull()
                    if (continuation.isActive) continuation.resume(result)
                }
            })
        }
    }

    companion object {
        internal fun isAdvertisement(value: String): Boolean {
            val url = value.toHttpUrlOrNull() ?: return true
            return url.host.endsWith("doubleclick.net") || url.host.endsWith("googlesyndication.com") ||
                url.host.startsWith("ads.") || Regex("/(?:ads|advert|preroll)/", RegexOption.IGNORE_CASE).containsMatchIn(url.encodedPath)
        }

        internal fun identify(type: String, bytes: ByteArray): String? {
            val text = bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF").trimStart()
            if (text.startsWith("#EXTM3U") && text.contains("#EXT-X-")) {
                if (text.contains("METHOD=SAMPLE-AES") || text.contains("KEYFORMAT=\"com.apple.streamingkeydelivery")) return null
                return "application/x-mpegURL"
            }
            if (Regex("<(?:\\w+:)?MPD(?:\\s|>)").containsMatchIn(text)) {
                if (text.contains("ContentProtection", ignoreCase = true)) return null
                return "application/dash+xml"
            }
            // HTML error/consent pages must not become video cards, even with a video MIME header.
            if (text.startsWith("<!doctype", true) || text.startsWith("<html", true) || text.startsWith('{')) return null
            if (bytes.size >= 8 && bytes.copyOfRange(4, 8).toString(Charsets.US_ASCII) == "ftyp") return "video/mp4"
            if (bytes.size >= 4 && bytes.take(4).map { it.toInt() and 255 } == listOf(0x1a, 0x45, 0xdf, 0xa3)) return "video/webm"
            return type.takeIf { it in setOf("video/mp4", "video/webm", "video/x-matroska", "video/quicktime") && bytes.isNotEmpty() }
        }
    }
}
