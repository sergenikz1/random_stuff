package org.scryertv.client.streaming

/** Counts actual playing time once the end card appears, independently of HLS end events. */
internal class AutoNextController {
    data class Decision(val show: Boolean, val seconds: Long, val advance: Boolean)
    private var remaining: Long? = null
    private var lastTick: Long? = null
    private var wasPlaying = false
    private var fired = false
    var cancelled = false

    fun seek() { remaining = null; lastTick = null; wasPlaying = false; fired = false }

    fun tick(now: Long, position: Long, duration: Long, fallbackDuration: Long,
             playing: Boolean, ended: Boolean, live: Boolean, hasNext: Boolean, autoplay: Boolean): Decision {
        val length = duration.takeIf { it > 0 } ?: fallbackDuration.takeIf { it > 0 }
        val mediaRemaining = length?.let { (it - position.coerceAtLeast(0)).coerceAtLeast(0) }
        if (!live && hasNext && !cancelled) {
            if (remaining == null && mediaRemaining != null && mediaRemaining <= WINDOW_MS) remaining = mediaRemaining
            else if (remaining != null) {
                val elapsed = if (playing && wasPlaying) (now - (lastTick ?: now)).coerceAtLeast(0) else 0
                remaining = (remaining!! - elapsed).coerceAtLeast(0)
                // A seek is reset explicitly. A growing/unfinished HLS timeline must not extend this countdown.
                if (mediaRemaining != null) remaining = minOf(remaining!!, mediaRemaining)
            }
        }
        lastTick = now; wasPlaying = playing
        val show = !live && hasNext && !cancelled && (ended || remaining != null)
        val due = show && autoplay && !fired && (ended || remaining == 0L)
        if (due) fired = true
        return Decision(show, ((remaining ?: 0L) + 999) / 1000, due)
    }

    companion object { const val WINDOW_MS = 20_000L }
}
