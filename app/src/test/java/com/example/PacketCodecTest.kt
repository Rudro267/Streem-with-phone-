package com.example

import com.example.model.CueUrgency
import com.example.model.RemoteTouchAction
import com.example.model.RemoteTouchEvent
import com.example.model.StreamerCue
import com.example.model.TelestratorPoint
import com.example.model.TelestratorStroke
import com.example.transport.PacketCodec
import com.example.transport.PacketType
import com.example.transport.StudioPacket
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

class PacketCodecTest {

    @Test
    fun `packet framing roundtrip preserves header and payload`() {
        val baos = ByteArrayOutputStream()
        val out = DataOutputStream(baos)
        val payload = byteArrayOf(1, 2, 3, 4, 5)
        PacketCodec.writePacket(
            out,
            StudioPacket(
                type = PacketType.VIDEO_FRAME,
                flags = PacketCodec.FLAG_KEYFRAME,
                sequence = 42,
                timestampUs = 987654321L,
                payload = payload
            )
        )

        val decoded = PacketCodec.readPacket(DataInputStream(ByteArrayInputStream(baos.toByteArray())))
        assertEquals(PacketType.VIDEO_FRAME, decoded.type)
        assertEquals(PacketCodec.FLAG_KEYFRAME, decoded.flags)
        assertEquals(42, decoded.sequence)
        assertEquals(987654321L, decoded.timestampUs)
        assertArrayEquals(payload, decoded.payload)
    }

    @Test
    fun `remote touch encode and decode clamps normalized coords`() {
        val encoded = PacketCodec.encodeRemoteTouch(
            RemoteTouchEvent(RemoteTouchAction.MOVE, 0.42f, 0.78f, pointerId = 1)
        )
        val decoded = PacketCodec.decodeRemoteTouch(encoded)
        assertNotNull(decoded)
        assertEquals(RemoteTouchAction.MOVE, decoded!!.action)
        assertEquals(0.42f, decoded.normX, 0.0001f)
        assertEquals(0.78f, decoded.normY, 0.0001f)
        assertEquals(1, decoded.pointerId)
    }

    @Test
    fun `telestrator stroke and clear roundtrip`() {
        val stroke = TelestratorStroke(
            id = 555L,
            colorArgb = 0xFFFF2D46L,
            widthNorm = 0.01f,
            points = listOf(TelestratorPoint(0.1f, 0.2f), TelestratorPoint(0.9f, 0.8f))
        )
        val encoded = PacketCodec.encodeTelestratorStroke(stroke)
        val cmd = PacketCodec.decodeTelestrator(encoded)
        assertTrue(cmd is PacketCodec.TelestratorCommand.UpsertStroke)
        val decoded = (cmd as PacketCodec.TelestratorCommand.UpsertStroke).stroke
        assertEquals(555L, decoded.id)
        assertEquals(2, decoded.points.size)
        assertEquals(0.9f, decoded.points[1].normX, 0.0001f)

        val clearCmd = PacketCodec.decodeTelestrator(PacketCodec.encodeTelestratorClear())
        assertTrue(clearCmd is PacketCodec.TelestratorCommand.ClearAll)
    }

    @Test
    fun `streamer cue encode and decode preserves utf8 text and urgency`() {
        val cue = StreamerCue(
            id = 99L,
            text = "Read Superchat #4",
            urgency = CueUrgency.ALERT,
            senderRole = "Director"
        )
        val decoded = PacketCodec.decodeStreamerCue(PacketCodec.encodeStreamerCue(cue))
        assertNotNull(decoded)
        assertEquals(99L, decoded!!.id)
        assertEquals("Read Superchat #4", decoded.text)
        assertEquals(CueUrgency.ALERT, decoded.urgency)
        assertEquals("Director", decoded.senderRole)
    }
}
