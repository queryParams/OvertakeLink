package io.github.queryparams.overtakelink.link

import org.junit.Assert.assertEquals
import org.junit.Test

class OldPacketsVideoTest {
    private fun video(frameWidth: Int = 0, frameHeight: Int = 0) = Packets.Video().apply {
        width = 1920
        height = 590
        this.frameWidth = frameWidth
        this.frameHeight = frameHeight
    }

    @Test
    fun `header carries the screen size by default`() {
        val p = OldPackets.video(ByteArray(512), video(), ByteArray(10), 0, 10)
        assertEquals(1920, OldPackets.i32(p, 116))
        assertEquals(590, OldPackets.i32(p, 120))
    }

    @Test
    fun `header carries the stream frame size when it differs from the screen`() {
        val p = OldPackets.video(ByteArray(512), video(1920, 1080), ByteArray(10), 0, 10)
        assertEquals(1920, OldPackets.i32(p, 116))
        assertEquals(1080, OldPackets.i32(p, 120))
        assertEquals(1024, p.size)
        assertEquals(10, OldPackets.i32(p, 92))
    }
}
