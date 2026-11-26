package com.example.media

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.view.Surface
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class VideoDecoderPipeline(
    private val onResolutionChanged: (width: Int, height: Int) -> Unit,
    private val onTelemetrySample: (fps: Int, kbps: Int, width: Int, height: Int) -> Unit,
    private val onLog: (String, String, Boolean) -> Unit
) {
    private data class FrameItem(
        val data: ByteArray,
        val ptsUs: Long,
        val isConfig: Boolean
    )

    private var codec: MediaCodec? = null
    @Volatile private var outputSurface: Surface? = null
    private var width = 1080
    private var height = 1920
    private var lastCsd: ByteArray? = null

    private val queue = LinkedBlockingQueue<FrameItem>(12)
    private val running = AtomicBoolean(false)
    private var workerThread: Thread? = null
    private val lock = Any()

    private val frameCounter = AtomicInteger(0)
    private val byteCounter = AtomicInteger(0)

    fun attachSurface(surface: Surface?) {
        synchronized(lock) {
            outputSurface = surface
            if (surface != null && lastCsd != null) {
                restartDecoderLocked(width, height, lastCsd!!)
            } else if (surface == null) {
                stopDecoderLocked()
            }
        }
    }

    fun onVideoConfig(newWidth: Int, newHeight: Int, csd: ByteArray) {
        synchronized(lock) {
            val sizeChanged = newWidth != width || newHeight != height
            val csdChanged = lastCsd == null || !lastCsd!!.contentEquals(csd)
            width = newWidth
            height = newHeight
            lastCsd = csd
            if (sizeChanged) {
                onResolutionChanged(newWidth, newHeight)
            }
            if (outputSurface != null && (codec == null || sizeChanged || csdChanged)) {
                restartDecoderLocked(newWidth, newHeight, csd)
            }
        }
    }

    fun submitFrame(data: ByteArray, ptsUs: Long, isKeyFrame: Boolean) {
        if (!running.get()) return
        if (queue.remainingCapacity() <= 2) {
            // Drop oldest non-config frames to maintain low latency
            queue.poll()
        }
        queue.offer(FrameItem(data, ptsUs, false))
        byteCounter.addAndGet(data.size)
    }

    private fun restartDecoderLocked(w: Int, h: Int, csd: ByteArray) {
        stopDecoderLocked()
        val surface = outputSurface ?: return
        try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
                setByteBuffer("csd-0", ByteBuffer.wrap(csd))
                setInteger(MediaFormat.KEY_PRIORITY, 0)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                }
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, w * h)
            }
            val decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            decoder.configure(format, surface, null, 0)
            decoder.start()
            codec = decoder
            running.set(true)
            workerThread = Thread({ decodeLoop(decoder) }, "TallyLink-VideoDec").apply {
                priority = Thread.MAX_PRIORITY
                start()
            }
            onLog("VIDEO", "Decoder ready (${w}x${h})", false)
        } catch (e: Exception) {
            onLog("VIDEO", "Decoder init failed: ${e.message}", true)
            stopDecoderLocked()
        }
    }

    private fun decodeLoop(decoder: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        var windowStartMs = System.currentTimeMillis()
        while (running.get()) {
            try {
                val frame = queue.poll(8, TimeUnit.MILLISECONDS)
                if (frame != null) {
                    val inIdx = decoder.dequeueInputBuffer(8_000)
                    if (inIdx >= 0) {
                        val inBuf = decoder.getInputBuffer(inIdx)
                        if (inBuf != null) {
                            inBuf.clear()
                            inBuf.put(frame.data)
                            decoder.queueInputBuffer(inIdx, 0, frame.data.size, frame.ptsUs, 0)
                        }
                    }
                }
                var outIdx = decoder.dequeueOutputBuffer(info, 2_000)
                while (outIdx >= 0) {
                    decoder.releaseOutputBuffer(outIdx, true)
                    frameCounter.incrementAndGet()
                    outIdx = decoder.dequeueOutputBuffer(info, 0)
                }

                val now = System.currentTimeMillis()
                val elapsed = now - windowStartMs
                if (elapsed >= 1000L) {
                    val fps = (frameCounter.getAndSet(0) * 1000L / elapsed).toInt()
                    val kbps = ((byteCounter.getAndSet(0) * 8L) / elapsed).toInt()
                    onTelemetrySample(fps, kbps, width, height)
                    windowStartMs = now
                }
            } catch (_: IllegalStateException) {
                break
            } catch (_: Exception) {
                if (!running.get()) break
            }
        }
    }

    private fun stopDecoderLocked() {
        running.set(false)
        queue.clear()
        try { workerThread?.join(350) } catch (_: Exception) {}
        workerThread = null
        try { codec?.stop() } catch (_: Exception) {}
        try { codec?.release() } catch (_: Exception) {}
        codec = null
    }

    fun release() {
        synchronized(lock) {
            outputSurface = null
            stopDecoderLocked()
        }
    }
}
