package jp.muya.xsaver

import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Tiny generated fixtures only: no user links, media, credentials or external requests. */
@RunWith(AndroidJUnit4::class)
@androidx.media3.common.util.UnstableApi
class PreviewPlaybackTest {
    @Test fun splitTracksPlayTogetherAndRelease() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val ended = CountDownLatch(1)
        val failure = AtomicReference<String?>()
        var player: ExoPlayer? = null
        var sawAudio = false
        var sawVideo = false
        var elapsed = 0L
        val fixtureDir = java.io.File(instrumentation.targetContext.cacheDir, "preview-test-${System.nanoTime()}").apply { mkdirs() }
        fun fixture(name: String): String {
            val file = java.io.File(fixtureDir, name)
            instrumentation.context.assets.open(name).use { input -> file.outputStream().use { input.copyTo(it) } }
            return file.toURI().toString()
        }
        val video = fixture("preview-test-video.mp4")
        val audio = fixture("preview-test-audio.m4a")
        try {
            instrumentation.runOnMainSync {
                val p = PreviewPlayback.create(instrumentation.targetContext,
                    video, audio, null, null, emptyMap(), emptyMap())
                player = p; p.volume = 0f
                // Instrumentation has no foreground Activity: test decoding without audio focus.
                p.setAudioAttributes(p.audioAttributes, false)
                p.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == Player.STATE_READY) { sawVideo = p.videoFormat != null; sawAudio = p.audioFormat != null }
                        if (state == Player.STATE_ENDED) { elapsed = p.currentPosition; ended.countDown() }
                    }
                    override fun onPlayerError(error: PlaybackException) { failure.set(error.errorCodeName); ended.countDown() }
                })
                p.prepare(); p.playWhenReady = true
            }
            assertTrue("Playback did not finish", ended.await(30, TimeUnit.SECONDS))
            assertNull(failure.get()); assertTrue("Video track missing", sawVideo); assertTrue("Audio track missing", sawAudio)
            assertTrue("Playback did not advance", elapsed >= 1000)
        } finally { instrumentation.runOnMainSync { player?.release() }; fixtureDir.deleteRecursively() }
    }
}
