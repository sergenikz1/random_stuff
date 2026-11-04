package org.intergalactictv.client.streaming

import android.app.Activity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Catalogs appear immediately; verified streams replace their entries as the crawl progresses. */
internal class WebEpisodeSearch(private val activity: Activity, private val probe: BrowserMediaProbe = BrowserMediaProbe()) {
    suspend fun search(show: VideoItem, cached: List<VideoItem>, found: (VideoItem) -> Unit, progress: (String) -> Unit) {
        val browser = BackgroundVideoBrowser(activity)
        val known = linkedMapOf<String, VideoItem>()
        cached.filter { it.webEpisodeTarget != null }.forEach { known[it.id] = it }
        val pending = linkedMapOf<String, WebEpisodeTarget>()
        val visited = mutableSetOf<String>()
        fun catalog(items: List<WebEpisodeTarget>) {
            items.forEach { target ->
                if (target.id !in visited && pending.size < 200) pending[target.id] = target
                if (!target.isSeason && target.id !in known) {
                    target.video(show).also { known[it.id] = it; found(it) }
                }
            }
        }
        try {
            val complete = withTimeoutOrNull(8 * 60_000L) {
                progress("Ищу переключатели сезонов и серий…")
                browser.scan(show.copy(streamUrl = null, webEpisodeTarget = null), true, ::catalog)
                var checked = 0
                while (pending.isNotEmpty() && checked < 160) {
                    val target = pending.values.firstOrNull { it.isSeason } ?: pending.values.first()
                    pending.remove(target.id)
                    if (!visited.add(target.id)) continue
                    if (known[target.id]?.streamUrl != null) continue
                    progress("Проверяю ${target.label.lowercase()} · найдено серий: ${known.size}")
                    try {
                        val discovery = browser.scan(target.video(show), true, ::catalog)
                        if (!target.isSeason) {
                            for (media in discovery.media.take(4)) {
                                val verified = probe.verify(media, target.video(show)) ?: continue
                                known[verified.id] = verified; found(verified)
                                break
                            }
                        }
                    } catch (e: CancellationException) { throw e
                    } catch (_: Exception) { /* Keep the catalog entry: the user can retry it on demand. */ }
                    checked++
                }
                pending.isEmpty()
            } == true
            progress(if (known.isEmpty()) "Переключатели серий на этом сайте не найдены. Попробуйте другой источник."
                else if (!complete) "Поиск приостановлен · серий: ${known.size}. Можно смотреть или нажать «Искать ещё»."
                else "Найдено серий: ${known.size}. Выберите серию для просмотра.")
        } finally {
            withContext(NonCancellable + Dispatchers.Main.immediate) { browser.close() }
        }
    }
}
