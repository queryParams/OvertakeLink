package io.github.queryparams.overtakelink.car

import android.view.MotionEvent
import io.github.queryparams.overtakelink.car.TouchPointers.Frame
import io.github.queryparams.overtakelink.car.TouchPointers.Pointer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchQueueTest {
    private fun drain(q: TouchQueue) = generateSequence { q.poll() }.toList()

    @Test
    fun movesMergeIntoOneFrameWithLatestOfEachFinger() {
        val q = TouchQueue()
        assertTrue(q.offer(TouchPointers.finger(0, MotionEvent.ACTION_MOVE, 10f, 0f)))
        assertFalse(q.offer(TouchPointers.finger(1, MotionEvent.ACTION_MOVE, 30f, 0f)))
        assertFalse(q.offer(TouchPointers.finger(0, MotionEvent.ACTION_MOVE, 20f, 0f)))
        val f = drain(q).single()
        assertEquals(listOf(Pointer(0, 20f, 0f), Pointer(1, 30f, 0f)), f.pointers)
    }

    @Test
    fun movesDoNotJumpOverPressAndRelease() {
        val q = TouchQueue()
        q.offer(TouchPointers.finger(0, MotionEvent.ACTION_MOVE, 1f, 0f))
        q.offer(TouchPointers.finger(1, MotionEvent.ACTION_DOWN, 2f, 0f))
        q.offer(TouchPointers.finger(0, MotionEvent.ACTION_MOVE, 3f, 0f))
        assertEquals(listOf(MotionEvent.ACTION_MOVE, MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE), drain(q).map { it.action })
    }

    @Test
    fun overflowDropsMovesFirstThenCancels() {
        val q = TouchQueue(max = 3)
        q.offer(TouchPointers.finger(0, MotionEvent.ACTION_DOWN, 0f, 0f))
        q.offer(TouchPointers.finger(0, MotionEvent.ACTION_MOVE, 1f, 0f))
        q.offer(TouchPointers.finger(1, MotionEvent.ACTION_DOWN, 0f, 0f))
        q.offer(TouchPointers.finger(1, MotionEvent.ACTION_UP, 0f, 0f))
        // Движение выпало, отпускание дошло.
        assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP), drain(q).map { it.action })
        val q2 = TouchQueue(max = 2)
        q2.offer(TouchPointers.finger(0, MotionEvent.ACTION_DOWN, 0f, 0f))
        q2.offer(TouchPointers.finger(1, MotionEvent.ACTION_DOWN, 0f, 0f))
        q2.offer(TouchPointers.finger(1, MotionEvent.ACTION_UP, 0f, 0f))
        // Одни нажатия и отпускания — отмена накопленного, затем новое.
        assertEquals(listOf(MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_UP), drain(q2).map { it.action })
    }

    @Test
    fun drainedQueueAsksForNewDrain() {
        val q = TouchQueue()
        assertTrue(q.offer(Frame(MotionEvent.ACTION_DOWN, 0, listOf(Pointer(0, 0f, 0f)))))
        drain(q)
        assertNull(q.poll())
        assertTrue(q.offer(Frame(MotionEvent.ACTION_UP, 0, listOf(Pointer(0, 0f, 0f)))))
    }
}
