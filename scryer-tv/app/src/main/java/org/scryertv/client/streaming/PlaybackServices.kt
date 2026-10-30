package org.scryertv.client.streaming

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy

/** The activity uses the same playback path for search, history, playlists and automatic next. */
internal interface PlaybackServices {
    suspend fun resolve(video: VideoItem): PlaybackInfo
    suspend fun episodes(video: VideoItem): List<VideoItem>
    fun player(context: Context, video: VideoItem, maxHeight: Int, decoderMode: Int): Player
}

@OptIn(markerClass = [UnstableApi::class])
internal object DefaultPlaybackServices : PlaybackServices {
    override suspend fun resolve(video: VideoItem) = StreamRepository.resolve(video)
    override suspend fun episodes(video: VideoItem) = StreamRepository.episodes(video)

    override fun player(context: Context, video: VideoItem, maxHeight: Int, decoderMode: Int): Player {
        val headers = if (video.streamUrl == null) mapOf("Referer" to RutubeApi.REFERER) else emptyMap()
        val source = OkHttpDataSource.Factory(RutubeApi.http)
            .setUserAgent(RutubeApi.USER_AGENT).setDefaultRequestProperties(headers)
        val tracks = DefaultTrackSelector(context).apply {
            val parameters = buildUponParameters().setPreferredVideoMimeTypes(MimeTypes.VIDEO_H264)
            val compatibilityLimit = when { decoderMode >= 2 -> 480; decoderMode == 1 -> 720; else -> 0 }
            val height = if (compatibilityLimit > 0) minOf(maxHeight.takeIf { it > 0 } ?: compatibilityLimit, compatibilityLimit) else maxHeight
            if (height > 0) parameters.setMaxVideoSize(Int.MAX_VALUE, height)
            if (decoderMode > 0) parameters.setMaxVideoFrameRate(30)
            setParameters(parameters)
        }
        val renderers = DefaultRenderersFactory(context).setEnableDecoderFallback(true)
        if (decoderMode > 0) renderers.setMediaCodecSelector { mimeType, secure, tunneling ->
            MediaCodecSelector.DEFAULT.getDecoderInfos(mimeType, secure, tunneling).sortedByDescending { it.softwareOnly }
        }
        return ExoPlayer.Builder(context, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(source).setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(2)))
            .setTrackSelector(tracks).build().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
                setHandleAudioBecomingNoisy(true)
            }
    }
}
