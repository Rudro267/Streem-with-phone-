package com.example.media

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.os.Build
import com.example.model.AudioLevels
import com.example.model.AudioMixerConfig
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.sqrt
import kotlin.math.tanh

class AudioMixerPipeline(
    private val mediaProjection: MediaProjection?,
    private val hasMicPermission: Boolean,
    initialConfig: AudioMixerConfig,
    private val onMixedPcmFrame: (sampleRate: Int, channels: Int, pcmBytes: ByteArray, length: Int) -> Unit,
    private val onAudioLevels: (AudioLevels) -> Unit,
    private val shouldEmitUiMeters: () -> Boolean,
    private val onLog: (String, String, Boolean) -> Unit
) {
    private val configRef = AtomicReference(initialConfig)
    private val running = AtomicBoolean(false)
    private var mixThread: Thread? = null

    private val outputSampleRate = 48000
    private val outputFrameSamples = 480 // 10ms mono frame

    fun updateConfig(config: AudioMixerConfig) {
        configRef.set(config)
    }

    @SuppressLint("MissingPermission")
    fun start() {
        if (running.getAndSet(true)) return

        if (!hasMicPermission) {
            onLog("AUDIO", "RECORD_AUDIO not granted; audio capture skipped", false)
            running.set(false)
            return
        }

        mixThread = Thread({
            var internalRecord: AudioRecord? = null
            var internalRate = outputSampleRate
            var internalChannels = 1

            var micRecord: AudioRecord? = null
            var micRate = outputSampleRate

            try {
                // 1. Internal Playback Capture (requires RECORD_AUDIO + MediaProjection on Android 10+)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && mediaProjection != null) {
                    val internalResult = openPlaybackCaptureRecord(mediaProjection)
                    internalRecord = internalResult?.first
                    internalRate = internalResult?.second ?: outputSampleRate
                    internalChannels = internalResult?.third ?: 1
                }

                // 2. Microphone Capture (MIC source with validated buffer & sample rate fallback)
                val micResult = openMicRecord()
                micRecord = micResult?.first
                micRate = micResult?.second ?: outputSampleRate

                if (internalRecord == null && micRecord == null) {
                    onLog("AUDIO", "No audio input track available on this device/emulator", false)
                    return@Thread
                }

                val internalReadSamples = (internalRate / 100) * internalChannels // 10ms
                val micReadSamples = micRate / 100 // 10ms mono
                val internalShortBuf = ShortArray(internalReadSamples.coerceAtLeast(960))
                val micShortBuf = ShortArray(micReadSamples.coerceAtLeast(480))

                val monoInternalBuf = FloatArray(outputFrameSamples)
                val monoMicBuf = FloatArray(outputFrameSamples)

                val outBytes = ByteArray(outputFrameSamples * 2)
                val outShortBuf = ByteBuffer.wrap(outBytes).order(ByteOrder.LITTLE_ENDIAN)

                var meterTicks = 0
                while (running.get()) {
                    val cfg = configRef.get()

                    val internalRead = if (internalRecord != null) {
                        internalRecord.read(internalShortBuf, 0, internalReadSamples, AudioRecord.READ_BLOCKING)
                    } else 0

                    val micRead = if (micRecord != null) {
                        if (internalRecord == null) {
                            micRecord.read(micShortBuf, 0, micReadSamples, AudioRecord.READ_BLOCKING)
                        } else {
                            micRecord.read(micShortBuf, 0, micReadSamples, AudioRecord.READ_NON_BLOCKING)
                        }
                    } else 0

                    if (internalRead <= 0 && micRead <= 0) {
                        Thread.sleep(5)
                        continue
                    }

                    // Convert internal buffer to mono float[outputFrameSamples]
                    monoInternalBuf.fill(0f)
                    if (internalRead > 0) {
                        val frames = internalRead / internalChannels
                        for (i in 0 until outputFrameSamples) {
                            val srcIdx = (i * frames) / outputFrameSamples
                            if (srcIdx < frames) {
                                val sample = if (internalChannels == 2) {
                                    (internalShortBuf[srcIdx * 2].toFloat() + internalShortBuf[srcIdx * 2 + 1].toFloat()) * 0.5f
                                } else {
                                    internalShortBuf[srcIdx].toFloat()
                                }
                                monoInternalBuf[i] = sample
                            }
                        }
                    }

                    // Convert mic buffer to mono float[outputFrameSamples]
                    monoMicBuf.fill(0f)
                    if (micRead > 0) {
                        for (i in 0 until outputFrameSamples) {
                            val srcIdx = (i * micRead) / outputFrameSamples
                            if (srcIdx < micRead) {
                                monoMicBuf[i] = micShortBuf[srcIdx].toFloat()
                            }
                        }
                    }

                    val intGain = if (cfg.isInternalMuted) 0f else cfg.internalGain
                    val mGain = if (cfg.isMicMuted) 0f else cfg.micGain

                    val computeMeters = shouldEmitUiMeters()
                    var intSumSq = 0.0
                    var micSumSq = 0.0
                    var masterSumSq = 0.0
                    var clipped = false

                    outShortBuf.clear()
                    for (i in 0 until outputFrameSamples) {
                        val intScaled = monoInternalBuf[i] * intGain
                        val micScaled = monoMicBuf[i] * mGain
                        val rawSum = intScaled + micScaled

                        val mixed = if (kotlin.math.abs(rawSum) > 29490f) {
                            (tanh((rawSum / 32767f).toDouble()) * 32767.0).toInt().coerceIn(-32768, 32767)
                        } else {
                            rawSum.toInt().coerceIn(-32768, 32767)
                        }

                        outShortBuf.putShort(mixed.toShort())

                        if (computeMeters) {
                            if (kotlin.math.abs(rawSum) > 32760f) clipped = true
                            intSumSq += (intScaled / 32767.0) * (intScaled / 32767.0)
                            micSumSq += (micScaled / 32767.0) * (micScaled / 32767.0)
                            masterSumSq += (mixed / 32767.0) * (mixed / 32767.0)
                        }
                    }

                    onMixedPcmFrame(outputSampleRate, 1, outBytes, outputFrameSamples * 2)

                    if (computeMeters) {
                        meterTicks++
                        if (meterTicks >= 6) {
                            meterTicks = 0
                            onAudioLevels(
                                AudioLevels(
                                    internalRms = (sqrt(intSumSq / outputFrameSamples) * 2.2).toFloat().coerceIn(0f, 1f),
                                    micRms = (sqrt(micSumSq / outputFrameSamples) * 2.2).toFloat().coerceIn(0f, 1f),
                                    masterRms = (sqrt(masterSumSq / outputFrameSamples) * 2.2).toFloat().coerceIn(0f, 1f),
                                    isClipping = clipped
                                )
                            )
                        }
                    } else {
                        meterTicks = 0
                    }
                }
            } catch (e: Exception) {
                if (running.get()) {
                    onLog("AUDIO", "Mixer loop error: ${e.message}", true)
                }
            } finally {
                try { internalRecord?.stop(); internalRecord?.release() } catch (_: Exception) {}
                try { micRecord?.stop(); micRecord?.release() } catch (_: Exception) {}
                onAudioLevels(AudioLevels())
            }
        }, "TallyLink-AudioMixer").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    @SuppressLint("MissingPermission")
    private fun openPlaybackCaptureRecord(projection: MediaProjection): Triple<AudioRecord, Int, Int>? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val candidates = listOf(
            48000 to AudioFormat.CHANNEL_IN_STEREO,
            44100 to AudioFormat.CHANNEL_IN_STEREO,
            48000 to AudioFormat.CHANNEL_IN_MONO,
            44100 to AudioFormat.CHANNEL_IN_MONO
        )
        val captureConfig = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .build()

        for ((sr, mask) in candidates) {
            val minBuf = AudioRecord.getMinBufferSize(sr, mask, AudioFormat.ENCODING_PCM_16BIT)
            if (minBuf <= 0) continue
            try {
                val format = AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sr)
                    .setChannelMask(mask)
                    .build()
                val rec = AudioRecord.Builder()
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(minBuf * 2)
                    .setAudioPlaybackCaptureConfig(captureConfig)
                    .build()
                if (rec.state == AudioRecord.STATE_INITIALIZED) {
                    rec.startRecording()
                    val ch = if (mask == AudioFormat.CHANNEL_IN_STEREO) 2 else 1
                    onLog("AUDIO", "Internal audio capture active (${sr}Hz, ${ch}ch)", false)
                    return Triple(rec, sr, ch)
                }
                rec.release()
            } catch (_: Exception) {
            }
        }
        onLog("AUDIO", "Internal playback capture unsupported on this hardware", false)
        return null
    }

    @SuppressLint("MissingPermission")
    private fun openMicRecord(): Pair<AudioRecord, Int>? {
        val sources = listOf(MediaRecorder.AudioSource.MIC, MediaRecorder.AudioSource.DEFAULT)
        val rates = listOf(48000, 44100, 16000)

        for (src in sources) {
            for (sr in rates) {
                val minBuf = AudioRecord.getMinBufferSize(sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                if (minBuf <= 0) continue
                try {
                    val rec = AudioRecord(
                        src,
                        sr,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        minBuf * 2
                    )
                    if (rec.state == AudioRecord.STATE_INITIALIZED) {
                        rec.startRecording()
                        onLog("AUDIO", "Mic commentary capture active (${sr}Hz)", false)
                        return rec to sr
                    }
                    rec.release()
                } catch (_: Exception) {
                }
            }
        }
        onLog("AUDIO", "Microphone capture unavailable on this hardware", false)
        return null
    }

    fun stop() {
        running.set(false)
        try { mixThread?.join(350) } catch (_: Exception) {}
        mixThread = null
    }
}
