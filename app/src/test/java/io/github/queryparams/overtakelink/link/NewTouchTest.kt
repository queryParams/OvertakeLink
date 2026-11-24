package io.github.queryparams.overtakelink.link

import io.github.queryparams.overtakelink.car.TouchPointers.Pointer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.ByteBuffer

/** Касания 5A5A: все пальцы пакета — один момент; испорченный пакет не применяется. */
class NewTouchTest {
    private fun packet(vararg fingers: Any): ByteArray {
        val n = fingers.size / 4
        val bb = ByteBuffer.allocate(5 + 10 * n)
        bb.putInt(0).put(n.toByte())
        for (i in 0 until n) {
            bb.put((fingers[i * 4] as Int).toByte()).put((fingers[i * 4 + 1] as Int).toByte())
                .putFloat(fingers[i * 4 + 2] as Float).putFloat(fingers[i * 4 + 3] as Float)
        }
        return bb.array()
    }

    @Test
    fun moveFirstThenPressWithAllFingers() {
        // Палец 0 движется, палец 1 нажат — одно движение и нажатие, оба с местами обоих пальцев.
        val b = packet(0, 3, 10f, 20f, 1, 1, 30f, 40f)
        val f = NewPackets.touchFrames(b, 0, b.size)!!
        assertEquals(listOf(2, 0), f.map { it.action })
        assertEquals(1, f[1].id)
        val both = listOf(Pointer(0, 10f, 20f), Pointer(1, 30f, 40f))
        assertEquals(both, f[0].pointers)
        assertEquals(both, f[1].pointers)
    }

    @Test
    fun brokenPacketIsRejectedWhole() {
        val good = packet(0, 2, 1f, 1f, 1, 2, 2f, 2f)
        assertNull(NewPackets.touchFrames(good, 0, good.size - 3))
        val nan = packet(0, 2, 1f, 1f, 1, 2, Float.NaN, 2f)
        assertNull(NewPackets.touchFrames(nan, 0, nan.size))
        val action = packet(0, 7, 1f, 1f)
        assertNull(NewPackets.touchFrames(action, 0, action.size))
        assertNull(NewPackets.touchFrames(ByteArray(3), 0, 3))
    }
}
