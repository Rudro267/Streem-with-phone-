package com.example.media

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import com.example.model.AudioLevels
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

class AudioPlayerPipeline(
    private val onAudioLevels: (AudioLevels) -> Unit
) {
    private var track: AudioTrack? = null
    private var currentSampleRate = 0
    private var currentChannels = 0
    @Volatile var isMonitorMuted: Boolean = false
    @Volatile var monitorVolume: Float = 1.0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            try {
                track?.setVolume(if (isMonitorMuted) 0f else field)
            } catch (_: Exception) {}
        }

    fun setMuted(muted: Boolean) {
        isMonitorMuted = muted
        try {
            track?.setVolume(if (muted) 0f else monitorVolume)
        } catch (_: Exception) {}
    }

    @Synchronized
    fun writePcm(sampleRate: Int, channels: Int, pcmBytes: ByteArray) {
        if (pcmBytes.isEmpty()) return
        if (track == null || sampleRate != currentSampleRate || channels != currentChannels) {
            release()
            initTrack(sampleRate, channels)
        }
        val activeTrack = track ?: return
        try {
            activeTrack.write(pcmBytes, 0, pcmBytes.size, AudioTrack.WRITE_NON_BLOCKING)
            computeLevel(pcmBytes)
        } catch (_: Exception) {
        }
    }

    private fun initTrack(sampleRate: Int, channels: Int) {
        currentSampleRate = sampleRate
        currentChannels = channels
        val channelMask = if (channels >= 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
        val minBuf = AudioTrack.getMinBufferSize(sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
            .coerceAtLeast(1920)
        val builder = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelMask)
                    .build()
            )
            .setBufferSizeInBytes(minBuf)
            .setTransferMode(AudioTrack.MODE_STREAM)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
        }
        track = builder.build().apply {
            setVolume(if (isMonitorMuted) 0f else monitorVolume)
            play()
        }
    }

    private var meterCounter = 0
    private fun computeLevel(pcmBytes: ByteArray) {
        meterCounter++
        if (meterCounter < 4) return
        meterCounter = 0
        val shortCount = pcmBytes.size / 2
        if (shortCount <= 0) return
        val buf = ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN)
        var sumSq = 0.0
        var clipped = false
        for (i in 0 until shortCount) {
            val s = buf.short.toInt()
            if (kotlin.math.abs(s) > 32700) clipped = true
            val norm = s / 32767.0
            sumSq += norm * norm
        }
        val rms = (sqrt(sumSq / shortCount) * 2.2).toFloat().coerceIn(0f, 1f)
        onAudioLevels(AudioLevels(masterRms = rms, isClipping = clipped))
    }

    @Synchronized
    fun release() {
        try {
            track?.stop()
            track?.release()
        } catch (_: Exception) {}
        track = null
        currentSampleRate = 0
        currentChannels = 0
        onAudioLevels(AudioLevels())
    }
}
