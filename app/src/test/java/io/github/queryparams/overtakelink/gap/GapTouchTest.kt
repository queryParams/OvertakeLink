package io.github.queryparams.overtakelink.gap

import io.github.queryparams.overtakelink.car.TouchPointers.Pointer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/** Касания GAP Smart Tool: старый пакет одного пальца и кадр протокола 3 со всеми пальцами. */
class GapTouchTest {
    private fun packet(vararg bytes: Int): GapProtocol.Packet {
        val b = ByteArray(bytes.size) { bytes[it].toByte() }
        val o = ByteArrayOutputStream()
        GapProtocol.write(DataOutputStream(o), GapProtocol.TOUCH, b, 0, b.size)
        return GapProtocol.read(DataInputStream(ByteArrayInputStream(o.toByteArray())))
    }

    @Test
    fun oldPacketIsFingerZero() {
        val f = GapProtocol.touch(packet(2, 1, 0, 0, 200))!!
        assertEquals(2, f.action)
        assertEquals(listOf(Pointer(0, 256f, 200f)), f.pointers)
    }

    @Test
    fun frameCarriesAllFingers() {
        // Нажат палец 7, на экране пальцы 3 и 7.
        val f = GapProtocol.touch(packet(0, 7, 2, 3, 0, 10, 0, 20, 7, 0, 30, 0, 40))!!
        assertEquals(0, f.action)
        assertEquals(7, f.id)
        assertEquals(listOf(Pointer(3, 10f, 20f), Pointer(7, 30f, 40f)), f.pointers)
    }

    @Test
    fun brokenFrameIsRejected() {
        assertNull(GapProtocol.touch(packet(0, 1, 2, 1, 0, 1, 0, 1)))
        assertNull(GapProtocol.touch(packet(9, 1, 1, 1, 0, 1, 0, 1)))
        assertNull(GapProtocol.touch(packet(0, 1, 0, 0, 0, 0)))
    }
}
