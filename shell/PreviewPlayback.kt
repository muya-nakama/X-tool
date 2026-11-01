package jp.muya.xsaver

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MergingMediaSource

@androidx.media3.common.util.UnstableApi
object PreviewPlayback {
    fun create(context: Context, video: String?, audio: String?, videoMime: String?, audioMime: String?, videoHeaders: Map<String, String>, audioHeaders: Map<String, String>): ExoPlayer {
        fun source(url: String, mime: String?, headers: Map<String, String>): androidx.media3.exoplayer.source.MediaSource {
            val http = DefaultHttpDataSource.Factory().setConnectTimeoutMs(15000).setReadTimeoutMs(30000)
                .setAllowCrossProtocolRedirects(false).setDefaultRequestProperties(headers)
            val factory = DefaultMediaSourceFactory(DefaultDataSource.Factory(context, http))
            val item = MediaItem.Builder().setUri(url).apply { if (mime != null) setMimeType(mime) }.build()
            return factory.createMediaSource(item)
        }
        val player = ExoPlayer.Builder(context).setLoadControl(DefaultLoadControl.Builder()
            .setBufferDurationsMs(2000, 12000, 500, 1000).build()).build()
        try {
            player.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
            player.setHandleAudioBecomingNoisy(true)
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setMaxVideoSize(1280, 720).build()
            val v = video?.let { source(it, videoMime, videoHeaders) }
            val a = audio?.let { source(it, audioMime, audioHeaders) }
            player.setMediaSource(if (v != null && a != null) MergingMediaSource(v, a) else v ?: a ?: error("No preview source"))
            return player
        } catch (error: Exception) { player.release(); throw error }
    }
}
