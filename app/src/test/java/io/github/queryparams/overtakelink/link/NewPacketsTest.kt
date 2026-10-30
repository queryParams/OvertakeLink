package io.github.queryparams.overtakelink.link

import org.junit.Assert.assertEquals
import org.junit.Test

class NewPacketsTest {
    @Test
    fun `json command is padded to 512 and keeps the unpadded size`() {
        val json = """{"CMD":"HEARTBEAT"}"""
        val p = NewPackets.json(json)
        assertEquals("5A5A", String(p, 0, 4))
        assertEquals(512, p.size)
        assertEquals(16 + json.length, OldPackets.i32(p, 4))
        assertEquals(NewPackets.TYPE_CMD, p[10].toInt())
        assertEquals(NewPackets.FORMAT_JSON, p[13].toInt())
        assertEquals(json, String(p, 16, json.length))
    }

    @Test
    fun `video carries the stream frame size and the data at 48`() {
        val v = Packets.Video().apply {
            width = 1920; height = 720; frameWidth = 1920; frameHeight = 1080
            frameRate = 30; bitRate = 4_000_000; frameInterval = 2
        }
        val data = ByteArray(600) { 7 }
        val p = NewPackets.video(v, data, 0, data.size)
        assertEquals(1024, p.size)
        assertEquals(48 + 600, OldPackets.i32(p, 4))
        assertEquals(32, OldPackets.i16(p, 8).toInt())
        assertEquals(NewPackets.TYPE_VIDEO, p[10].toInt())
        assertEquals(NewPackets.FORMAT_VIDEO, p[13].toInt())
        assertEquals(1920, OldPackets.i32(p, 20))
        assertEquals(1080, OldPackets.i32(p, 24))
        assertEquals(90, OldPackets.i16(p, 28).toInt())
        assertEquals(30, OldPackets.i32(p, 32))
        assertEquals(4_000_000, OldPackets.i32(p, 36))
        assertEquals(2, OldPackets.i32(p, 40))
        assertEquals(7, p[48].toInt())
        assertEquals(0, p[48 + 600].toInt())
    }
}
