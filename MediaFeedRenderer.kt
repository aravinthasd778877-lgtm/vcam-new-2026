package com.vstream.vcam.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.Surface
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.vstream.vcam.model.FeedSourceType
import com.vstream.vcam.model.VCamConfig
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Renders an uploaded Image, uploaded Video, or OBS Network Stream (RTSP/RTMP/HLS/HTTP)
 * onto any Android Surface (Preview SurfaceView or hooked Camera2 Surface).
 */
class MediaFeedRenderer(private val context: Context) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val imageExecutor = Executors.newSingleThreadScheduledExecutor()
    private var imageLoopTask: ScheduledFuture<*>? = null
    private var exoPlayer: ExoPlayer? = null
    private var activeSurface: Surface? = null

    fun start(surface: Surface, config: VCamConfig) {
        stop()
        activeSurface = surface

        when (config.sourceType) {
            FeedSourceType.IMAGE -> startImageLoop(surface, config)
            FeedSourceType.VIDEO -> startVideoOrStream(surface, config.videoUri, config)
            FeedSourceType.OBS_STREAM -> startVideoOrStream(surface, config.obsStreamUrl, config)
        }
    }

    private fun startImageLoop(surface: Surface, config: VCamConfig) {
        val bitmap = loadBitmapFromUri(config.imageUri)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        imageLoopTask = imageExecutor.scheduleAtFixedRate({
            if (!surface.isValid) return@scheduleAtFixedRate
            var canvas: Canvas? = null
            try {
                canvas = surface.lockCanvas(null)
                canvas.drawColor(Color.BLACK)
                if (bitmap != null) {
                    val matrix = computeCenterCropMatrix(
                        srcW = bitmap.width.toFloat(),
                        srcH = bitmap.height.toFloat(),
                        dstW = canvas.width.toFloat(),
                        dstH = canvas.height.toFloat(),
                        flipH = config.flipHorizontal
                    )
                    canvas.drawBitmap(bitmap, matrix, paint)
                }
            } catch (_: Throwable) {
                // Ignore transient surface lock errors during rotation/teardown
            } finally {
                if (canvas != null) {
                    try {
                        surface.unlockCanvasAndPost(canvas)
                    } catch (_: Throwable) {
                    }
                }
            }
        }, 0, 33, TimeUnit.MILLISECONDS)
    }

    private fun startVideoOrStream(surface: Surface, uriString: String, config: VCamConfig) {
        if (uriString.isBlank()) return
        mainHandler.post {
            val player = ExoPlayer.Builder(context).build().apply {
                setVideoSurface(surface)
                repeatMode = if (config.loopVideo) Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_OFF
                volume = if (config.muteAudio) 0f else 1f
                setMediaItem(MediaItem.fromUri(Uri.parse(uriString)))
                prepare()
                playWhenReady = true
            }
            exoPlayer = player
        }
    }

    private fun loadBitmapFromUri(uriString: String): Bitmap? {
        if (uriString.isBlank()) return null
        return try {
            context.contentResolver.openInputStream(Uri.parse(uriString))?.use { stream ->
                BitmapFactory.decodeStream(stream)
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun computeCenterCropMatrix(
        srcW: Float,
        srcH: Float,
        dstW: Float,
        dstH: Float,
        flipH: Boolean
    ): Matrix {
        val matrix = Matrix()
        val scale = maxOf(dstW / srcW, dstH / srcH)
        val scaledW = srcW * scale
        val scaledH = srcH * scale
        val dx = (dstW - scaledW) / 2f
        val dy = (dstH - scaledH) / 2f
        matrix.postScale(if (flipH) -scale else scale, scale)
        matrix.postTranslate(if (flipH) dx + scaledW else dx, dy)
        return matrix
    }

    fun stop() {
        imageLoopTask?.cancel(true)
        imageLoopTask = null
        mainHandler.post {
            exoPlayer?.setVideoSurface(null)
            exoPlayer?.release()
            exoPlayer = null
        }
        activeSurface = null
    }
}
