package com.example.media

import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Surface
import com.example.model.StreamQualityProfile
import com.example.transport.PacketCodec
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class VideoEncoderPipeline(
    private val mediaProjection: MediaProjection,
    private val densityDpi: Int,
    private val initialProfile: StreamQualityProfile,
    private val onVideoConfig: (width: Int, height: Int, csd: ByteArray) -> Unit,
    private val onEncodedFrame: (data: ByteArray, isKeyFrame: Boolean, ptsUs: Long) -> Unit,
    private val onTelemetrySample: (fps: Int, kbps: Int, encodeLatencyMs: Int, width: Int, height: Int) -> Unit,
    private val onProjectionStopped: () -> Unit,
    private val onLog: (String, String, Boolean) -> Unit
) {
    private var codec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var drainThread: Thread? = null
    private val running = AtomicBoolean(false)

    private var currentWidth = align16(initialProfile.resolution.height) // Portrait default width (720 or 1080)
    private var currentHeight = align16(initialProfile.resolution.width) // Portrait default height (1280 or 1920)
    private var currentProfile = initialProfile
    private var lastCsd: ByteArray = ByteArray(0)

    private val framesInWindow = AtomicInteger(0)
    private val bytesInWindow = AtomicInteger(0)

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            onLog("VIDEO", "MediaProjection stopped by system or user", false)
            stop()
            onProjectionStopped()
        }

        override fun onCapturedContentResize(width: Int, height: Int) {
            if (width <= 0 || height <= 0) return
            val targetShort = currentProfile.resolution.height
            val targetLong = currentProfile.resolution.width
            val isLandscape = width > height
            val newW = align16(if (isLandscape) targetLong else targetShort)
            val newH = align16(if (isLandscape) targetShort else targetLong)
            if (newW != currentWidth || newH != currentHeight) {
                onLog("VIDEO", "Captured content resized: ${newW}x${newH}", false)
                reconfigure(newW, newH)
            }
        }
    }

    fun start(screenWidth: Int, screenHeight: Int) {
        val targetShort = currentProfile.resolution.height
        val targetLong = currentProfile.resolution.width
        val isLandscape = screenWidth > screenHeight
        currentWidth = align16(if (isLandscape) targetLong else targetShort)
        currentHeight = align16(if (isLandscape) targetShort else targetLong)

        mediaProjection.registerCallback(projectionCallback, Handler(Looper.getMainLooper()))
        startEncoderAndDisplay()
    }

    private fun startEncoderAndDisplay() {
        running.set(true)
        try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, currentWidth, currentHeight).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, currentProfile.bitrateMbps * 1_000_000)
                setInteger(MediaFormat.KEY_FRAME_RATE, currentProfile.targetFps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                setInteger(MediaFormat.KEY_PRIORITY, 0)
                if (currentProfile.lowLatencyEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                }
                if (currentProfile.lowLatencyEnabled) {
                    setInteger(MediaFormat.KEY_LATENCY, 0)
                }
            }

            val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val surface = encoder.createInputSurface()
            encoder.start()

            codec = encoder
            inputSurface = surface

            virtualDisplay = mediaProjection.createVirtualDisplay(
                "TallyLinkDisplay",
                currentWidth,
                currentHeight,
                densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                surface,
                null,
                null
            )

            drainThread = Thread({ drainLoop(encoder) }, "TallyLink-VideoEnc").apply {
                priority = Thread.MAX_PRIORITY
                start()
            }
            onLog("VIDEO", "Encoder active: ${currentWidth}x${currentHeight} @ ${currentProfile.targetFps}fps (${currentProfile.bitrateMbps} Mbps)", false)
        } catch (e: Exception) {
            onLog("VIDEO", "Encoder start failed: ${e.message}", true)
            stopEncoderOnly()
        }
    }

    private fun drainLoop(encoder: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        var windowStartMs = System.currentTimeMillis()
        while (running.get()) {
            try {
                val idx = encoder.dequeueOutputBuffer(info, 12_000)
                if (idx >= 0) {
                    val outBuf = encoder.getOutputBuffer(idx)
                    if (outBuf != null && info.size > 0) {
                        val bytes = ByteArray(info.size)
                        outBuf.position(info.offset)
                        outBuf.limit(info.offset + info.size)
                        outBuf.get(bytes)

                        val isConfig = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                        val isKey = (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0

                        if (isConfig) {
                            lastCsd = bytes
                            onVideoConfig(currentWidth, currentHeight, bytes)
                        } else {
                            if (isKey && lastCsd.isNotEmpty()) {
                                // Ensure late-joining or reconnecting Director always receives CSD before IDR
                                onVideoConfig(currentWidth, currentHeight, lastCsd)
                            }
                            onEncodedFrame(bytes, isKey, info.presentationTimeUs)
                            framesInWindow.incrementAndGet()
                            bytesInWindow.addAndGet(bytes.size)
                        }
                    }
                    encoder.releaseOutputBuffer(idx, false)
                }

                val now = System.currentTimeMillis()
                val elapsed = now - windowStartMs
                if (elapsed >= 1000L) {
                    val fps = (framesInWindow.getAndSet(0) * 1000L / elapsed).toInt()
                    val kbps = ((bytesInWindow.getAndSet(0) * 8L) / elapsed).toInt()
                    val estLat = if (currentProfile.lowLatencyEnabled) 8 else 24
                    onTelemetrySample(fps, kbps, estLat, currentWidth, currentHeight)
                    windowStartMs = now
                }
            } catch (_: IllegalStateException) {
                break
            } catch (_: Exception) {
                if (!running.get()) break
            }
        }
    }

    fun updateBitrate(bitrateMbps: Int) {
        currentProfile = currentProfile.copy(bitrateMbps = bitrateMbps)
        try {
            val bundle = Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, bitrateMbps * 1_000_000)
            }
            codec?.setParameters(bundle)
        } catch (_: Exception) {
        }
    }

    fun requestKeyFrame() {
        try {
            val bundle = Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            }
            codec?.setParameters(bundle)
            if (lastCsd.isNotEmpty()) {
                onVideoConfig(currentWidth, currentHeight, lastCsd)
            }
        } catch (_: Exception) {
        }
    }

    fun updateProfile(profile: StreamQualityProfile, screenWidth: Int, screenHeight: Int) {
        val needsRestart = profile.resolution != currentProfile.resolution ||
            profile.targetFps != currentProfile.targetFps ||
            profile.lowLatencyEnabled != currentProfile.lowLatencyEnabled
        currentProfile = profile
        if (needsRestart && running.get()) {
            val isLandscape = screenWidth > screenHeight
            val w = align16(if (isLandscape) profile.resolution.width else profile.resolution.height)
            val h = align16(if (isLandscape) profile.resolution.height else profile.resolution.width)
            reconfigure(w, h)
        } else {
            updateBitrate(profile.bitrateMbps)
        }
    }

    private fun reconfigure(newWidth: Int, newHeight: Int) {
        stopEncoderOnly()
        currentWidth = newWidth
        currentHeight = newHeight
        startEncoderAndDisplay()
    }

    private fun stopEncoderOnly() {
        running.set(false)
        try { drainThread?.join(400) } catch (_: Exception) {}
        drainThread = null
        try { virtualDisplay?.release() } catch (_: Exception) {}
        virtualDisplay = null
        try { codec?.stop() } catch (_: Exception) {}
        try { codec?.release() } catch (_: Exception) {}
        codec = null
        try { inputSurface?.release() } catch (_: Exception) {}
        inputSurface = null
    }

    fun stop() {
        stopEncoderOnly()
        try { mediaProjection.unregisterCallback(projectionCallback) } catch (_: Exception) {}
    }

    private fun align16(value: Int): Int = (value / 16) * 16
}
