package org.intergalactictv.client.streaming

import android.app.Activity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.IOException

internal class NoWebVideoException : IOException("На этом сайте не удалось получить видео для ТВ-плеера. Выберите другой источник.")

internal interface SiteVideoSearch {
    suspend fun search(query: String, found: (VideoItem) -> Unit, progress: (Int, Int) -> Unit)
}

/** Only verified media leaves this layer. Search-page/iframe URLs never become result cards. */
internal class NativeWebVideoSearch(
    private val activity: Activity,
    private val pages: WebVideoSearch = WebVideoSearch(),
    private val probe: BrowserMediaProbe = BrowserMediaProbe()
) : SiteVideoSearch {
    override suspend fun search(query: String, found: (VideoItem) -> Unit, progress: (Int, Int) -> Unit) {
        val sites = pages.findPages(query)
        extract(sites, found, progress)
    }

    suspend fun extract(sites: List<VideoItem>, found: (VideoItem) -> Unit, progress: (Int, Int) -> Unit) = coroutineScope {
        val browser = BackgroundVideoBrowser(activity)
        val limit = Semaphore(2)
        var completed = 0
        try {
            sites.take(WebVideoSearch.MAX_PAGES).map { site -> async(Dispatchers.Main.immediate) {
                limit.withPermit {
                    try {
                        extractPage(browser, site)?.let(found)
                    } catch (e: kotlinx.coroutines.CancellationException) { throw e
                    } catch (_: Exception) { /* One failed site does not stop other video sources. */ }
                    progress(++completed, minOf(sites.size, WebVideoSearch.MAX_PAGES))
                }
            } }.awaitAll()
        } finally { withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main.immediate) { browser.close() } }
    }

    private suspend fun extractPage(browser: BackgroundVideoBrowser, site: VideoItem): VideoItem? {
        val discovered = browser.scan(site)
        // Prefer adaptive manifests, then direct files. Do not list multiple quality variants as separate movies.
        val candidates = discovered.media.sortedByDescending { media ->
            when {
                media.url.contains(".m3u8") || media.mime?.contains("mpegurl") == true -> 3
                media.url.contains(".mpd") || media.mime?.contains("dash") == true -> 2
                else -> 1
            }
        }
        return kotlinx.coroutines.withTimeoutOrNull(12_000L) {
            for (candidate in candidates.take(4)) {
                probe.verify(candidate, discovered.page)?.let { return@withTimeoutOrNull it }
            }
            null
        }
    }

    suspend fun resolve(video: VideoItem): PlaybackInfo {
        // Reuse a still-valid link; revisit the original site if it has expired or came from legacy history.
        video.streamUrl?.let { url ->
            val cached = probe.verify(BrowserMedia(url, video.requestHeaders["Referer"] ?: video.pageUrl,
                video.title, video.thumbnail, video.durationSec, video.streamMimeType), video)
            if (cached != null) return PlaybackInfo(cached.streamUrl!!, cached.isLive, cached.title, cached.streamMimeType, cached.requestHeaders)
        }
        val browser = BackgroundVideoBrowser(activity)
        try {
            val fresh = extractPage(browser, video.copy(streamUrl = null))
                ?: throw NoWebVideoException()
            return PlaybackInfo(fresh.streamUrl!!, fresh.isLive, fresh.title, fresh.streamMimeType, fresh.requestHeaders)
        } finally { withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main.immediate) { browser.close() } }
    }
}
