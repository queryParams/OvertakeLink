package io.github.queryparams.overtakelink.link

import org.junit.Assert.assertEquals
import org.junit.Test

class OldTouchTest {
    /** Пакет касания !BIN из журнала машины (QDLink, 2026-11-26): с +64 — 00 01 | действие | x | y | флаг | палец. */
    private fun packet(finger: Int, flag: Int = 1, total: Int = 96): ByteArray {
        val b = ByteArray(total)
        val tail = intArrayOf(0x00, 0x01, 0x80, 0x00, 0x05, 0x6a, 0x01, 0x5e, flag shr 8, flag and 0xff, finger shr 8, finger and 0xff)
        for (i in tail.indices) if (64 + i < total) b[64 + i] = tail[i].toByte()
        return b
    }

    @Test
    fun secondFingerFromCar() {
        assertEquals(1, OldPackets.touchFinger(packet(1), 0, 96))
        assertEquals(0, OldPackets.touchFinger(packet(0), 0, 96))
    }

    @Test
    fun noFlagIsOneFinger() {
        assertEquals(0, OldPackets.touchFinger(packet(1, flag = 0), 0, 96))
        // Короткий пакет старых ГУ — без номера пальца.
        assertEquals(0, OldPackets.touchFinger(packet(1, total = 74), 0, 74))
    }

    @Test
    fun coordinatesStillWhereTheyWere() {
        val b = packet(1)
        assertEquals(1386, OldPackets.i16(b, 68).toInt())
        assertEquals(350, OldPackets.i16(b, 70).toInt())
    }
}
