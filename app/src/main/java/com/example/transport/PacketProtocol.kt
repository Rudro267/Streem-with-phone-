package com.example.transport

import com.example.model.CueUrgency
import com.example.model.RemoteTouchAction
import com.example.model.RemoteTouchEvent
import com.example.model.StreamerCue
import com.example.model.TelestratorPoint
import com.example.model.TelestratorStroke
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

enum class PacketType(val code: Byte) {
    VIDEO_CONFIG(1),
    VIDEO_FRAME(2),
    AUDIO_PCM(3),
    REMOTE_TOUCH(4),
    TELESTRATOR_STROKE(5),
    STREAMER_CUE(6),
    CUE_ACK(7),
    CONTROL_PING(8),
    CONTROL_PONG(9),
    KEYFRAME_REQUEST(10);

    companion object {
        fun fromCode(code: Byte): PacketType? = entries.firstOrNull { it.code == code }
    }
}

data class StudioPacket(
    val type: PacketType,
    val flags: Byte = 0,
    val sequence: Int = 0,
    val timestampUs: Long = System.nanoTime() / 1000L,
    val payload: ByteArray = ByteArray(0)
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is StudioPacket) return false
        return type == other.type &&
            flags == other.flags &&
            sequence == other.sequence &&
            timestampUs == other.timestampUs &&
            payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var result = type.hashCode()
        result = 31 * result + flags
        result = 31 * result + sequence
        result = 31 * result + timestampUs.hashCode()
        result = 31 * result + payload.contentHashCode()
        return result
    }
}

object PacketCodec {
    private const val MAGIC_0: Byte = 0x54 // 'T'
    private const val MAGIC_1: Byte = 0x4C // 'L'
    private const val MAX_PAYLOAD_BYTES = 4 * 1024 * 1024 // 4 MB safety ceiling

    const val FLAG_KEYFRAME: Byte = 0x01

    @Throws(IOException::class)
    fun writePacket(out: DataOutputStream, packet: StudioPacket) {
        writePacketDirect(out, packet.type, packet.flags, packet.sequence, packet.timestampUs, packet.payload, packet.payload.size)
    }

    @Throws(IOException::class)
    fun writePacketDirect(
        out: DataOutputStream,
        type: PacketType,
        flags: Byte,
        sequence: Int,
        timestampUs: Long,
        payload: ByteArray,
        payloadLength: Int
    ) {
        out.writeByte(MAGIC_0.toInt())
        out.writeByte(MAGIC_1.toInt())
        out.writeByte(type.code.toInt())
        out.writeByte(flags.toInt())
        out.writeInt(sequence)
        out.writeLong(timestampUs)
        out.writeInt(payloadLength)
        if (payloadLength > 0) {
            out.write(payload, 0, payloadLength)
        }
        out.flush()
    }

    fun encodeAudioPcmInto(
        sampleRate: Int,
        channels: Int,
        pcmData: ByteArray,
        pcmLength: Int,
        dest: ByteArray
    ): Int {
        dest[0] = (sampleRate ushr 24).toByte()
        dest[1] = (sampleRate ushr 16).toByte()
        dest[2] = (sampleRate ushr 8).toByte()
        dest[3] = sampleRate.toByte()
        dest[4] = (channels ushr 24).toByte()
        dest[5] = (channels ushr 16).toByte()
        dest[6] = (channels ushr 8).toByte()
        dest[7] = channels.toByte()
        System.arraycopy(pcmData, 0, dest, 8, pcmLength)
        return 8 + pcmLength
    }

    @Throws(IOException::class)
    fun readPacket(input: DataInputStream): StudioPacket {
        // Resynchronize on magic header if needed
        while (true) {
            val b0 = input.readByte()
            if (b0 != MAGIC_0) continue
            val b1 = input.readByte()
            if (b1 == MAGIC_1) break
        }
        val typeCode = input.readByte()
        val flags = input.readByte()
        val sequence = input.readInt()
        val timestampUs = input.readLong()
        val length = input.readInt()
        if (length < 0 || length > MAX_PAYLOAD_BYTES) {
            throw IOException("Invalid packet payload length: $length")
        }
        val payload = ByteArray(length)
        if (length > 0) {
            input.readFully(payload)
        }
        val type = PacketType.fromCode(typeCode)
            ?: throw IOException("Unknown packet type: $typeCode")
        return StudioPacket(
            type = type,
            flags = flags,
            sequence = sequence,
            timestampUs = timestampUs,
            payload = payload
        )
    }

    fun encodeVideoConfig(width: Int, height: Int, rotationDeg: Int, csd: ByteArray): ByteArray {
        val buf = ByteBuffer.allocate(16 + csd.size).order(ByteOrder.BIG_ENDIAN)
        buf.putInt(width)
        buf.putInt(height)
        buf.putInt(rotationDeg)
        buf.putInt(csd.size)
        buf.put(csd)
        return buf.array()
    }

    data class VideoConfigPayload(
        val width: Int,
        val height: Int,
        val rotationDeg: Int,
        val csd: ByteArray
    )

    fun decodeVideoConfig(payload: ByteArray): VideoConfigPayload? {
        if (payload.size < 16) return null
        val buf = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
        val width = buf.int
        val height = buf.int
        val rotation = buf.int
        val csdLen = buf.int
        if (csdLen < 0 || csdLen > buf.remaining()) return null
        val csd = ByteArray(csdLen)
        buf.get(csd)
        return VideoConfigPayload(width, height, rotation, csd)
    }

    fun encodeAudioPcm(sampleRate: Int, channels: Int, pcmData: ByteArray, length: Int): ByteArray {
        val buf = ByteBuffer.allocate(8 + length).order(ByteOrder.BIG_ENDIAN)
        buf.putInt(sampleRate)
        buf.putInt(channels)
        buf.put(pcmData, 0, length)
        return buf.array()
    }

    data class AudioPcmPayload(
        val sampleRate: Int,
        val channels: Int,
        val pcm: ByteArray
    )

    fun decodeAudioPcm(payload: ByteArray): AudioPcmPayload? {
        if (payload.size < 8) return null
        val buf = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
        val sampleRate = buf.int
        val channels = buf.int
        val pcm = ByteArray(buf.remaining())
        buf.get(pcm)
        return AudioPcmPayload(sampleRate, channels, pcm)
    }

    fun encodeRemoteTouch(event: RemoteTouchEvent): ByteArray {
        val buf = ByteBuffer.allocate(13).order(ByteOrder.BIG_ENDIAN)
        buf.put(event.action.code)
        buf.putInt(event.pointerId)
        buf.putFloat(event.normX)
        buf.putFloat(event.normY)
        return buf.array()
    }

    fun decodeRemoteTouch(payload: ByteArray): RemoteTouchEvent? {
        if (payload.size < 13) return null
        val buf = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
        val action = RemoteTouchAction.fromCode(buf.get())
        val pointerId = buf.int
        val x = buf.float.coerceIn(0f, 1f)
        val y = buf.float.coerceIn(0f, 1f)
        return RemoteTouchEvent(action, x, y, pointerId)
    }

    fun encodeTelestratorClear(): ByteArray = byteArrayOf(1)

    fun encodeTelestratorStroke(stroke: TelestratorStroke): ByteArray {
        val points = stroke.points
        val buf = ByteBuffer.allocate(1 + 8 + 8 + 4 + 4 + points.size * 8)
            .order(ByteOrder.BIG_ENDIAN)
        buf.put(0) // ADD_OR_UPDATE
        buf.putLong(stroke.id)
        buf.putLong(stroke.colorArgb)
        buf.putFloat(stroke.widthNorm)
        buf.putInt(points.size)
        for (p in points) {
            buf.putFloat(p.normX)
            buf.putFloat(p.normY)
        }
        return buf.array()
    }

    sealed interface TelestratorCommand {
        data object ClearAll : TelestratorCommand
        data class UpsertStroke(val stroke: TelestratorStroke) : TelestratorCommand
    }

    fun decodeTelestrator(payload: ByteArray): TelestratorCommand? {
        if (payload.isEmpty()) return null
        val buf = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
        val cmd = buf.get()
        if (cmd.toInt() == 1) return TelestratorCommand.ClearAll
        if (payload.size < 25) return null
        val id = buf.long
        val colorArgb = buf.long
        val widthNorm = buf.float
        val count = buf.int
        if (count < 0 || count > 2000 || buf.remaining() < count * 8) return null
        val points = ArrayList<TelestratorPoint>(count)
        repeat(count) {
            points.add(TelestratorPoint(buf.float.coerceIn(0f, 1f), buf.float.coerceIn(0f, 1f)))
        }
        return TelestratorCommand.UpsertStroke(
            TelestratorStroke(
                id = id,
                colorArgb = colorArgb,
                widthNorm = widthNorm,
                points = points
            )
        )
    }

    fun encodeStreamerCue(cue: StreamerCue): ByteArray {
        val roleBytes = cue.senderRole.toByteArray(Charsets.UTF_8)
        val textBytes = cue.text.toByteArray(Charsets.UTF_8)
        val buf = ByteBuffer.allocate(8 + 1 + 4 + roleBytes.size + 4 + textBytes.size)
            .order(ByteOrder.BIG_ENDIAN)
        buf.putLong(cue.id)
        buf.put(cue.urgency.ordinal.toByte())
        buf.putInt(roleBytes.size)
        buf.put(roleBytes)
        buf.putInt(textBytes.size)
        buf.put(textBytes)
        return buf.array()
    }

    fun decodeStreamerCue(payload: ByteArray): StreamerCue? {
        if (payload.size < 17) return null
        val buf = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
        val id = buf.long
        val urgencyIdx = buf.get().toInt().coerceIn(0, CueUrgency.entries.lastIndex)
        val roleLen = buf.int
        if (roleLen < 0 || roleLen > 256 || buf.remaining() < roleLen + 4) return null
        val roleBytes = ByteArray(roleLen)
        buf.get(roleBytes)
        val textLen = buf.int
        if (textLen < 0 || textLen > 2048 || buf.remaining() < textLen) return null
        val textBytes = ByteArray(textLen)
        buf.get(textBytes)
        return StreamerCue(
            id = id,
            text = String(textBytes, Charsets.UTF_8),
            urgency = CueUrgency.entries[urgencyIdx],
            senderRole = String(roleBytes, Charsets.UTF_8)
        )
    }

    fun encodeCueAck(cueId: Long): ByteArray =
        ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(cueId).array()

    fun decodeCueAck(payload: ByteArray): Long? =
        if (payload.size >= 8) ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN).long else null

    fun encodePing(sentAtMs: Long, remoteControlEnabled: Boolean): ByteArray =
        ByteBuffer.allocate(9).order(ByteOrder.BIG_ENDIAN)
            .putLong(sentAtMs)
            .put(if (remoteControlEnabled) 1 else 0)
            .array()

    data class PingPayload(val sentAtMs: Long, val remoteControlEnabled: Boolean)

    fun decodePing(payload: ByteArray): PingPayload? {
        if (payload.size < 9) return null
        val buf = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
        return PingPayload(buf.long, buf.get().toInt() != 0)
    }

    fun encodePong(echoSentAtMs: Long): ByteArray =
        ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(echoSentAtMs).array()

    fun decodePong(payload: ByteArray): Long? =
        if (payload.size >= 8) ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN).long else null
}
