package io.github.queryparams.overtakelink.car

import android.view.MotionEvent
import io.github.queryparams.overtakelink.car.TouchPointers.Frame
import io.github.queryparams.overtakelink.car.TouchPointers.Pointer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchPointersTest {
    private val down = MotionEvent.ACTION_DOWN
    private val up = MotionEvent.ACTION_UP
    private val move = MotionEvent.ACTION_MOVE

    private fun TouchPointers.one(id: Int, action: Int, x: Float, y: Float) = apply(TouchPointers.finger(id, action, x, y))

    @Test
    fun oneFingerIsPlainGesture() {
        val t = TouchPointers()
        assertEquals(down, t.one(0, down, 1f, 2f).single().action)
        assertEquals(move, t.one(0, move, 3f, 4f).single().action)
        val e = t.one(0, up, 5f, 6f).single()
        assertEquals(up, e.action)
        assertEquals(listOf(Pointer(0, 5f, 6f)), e.pointers)
    }

    @Test
    fun fingerByFingerPinch() {
        // QDLink по кабелю: каждый палец своим кадром.
        val t = TouchPointers()
        t.one(0, down, 10f, 10f)
        val second = t.one(1, down, 50f, 50f).single()
        assertEquals(MotionEvent.ACTION_POINTER_DOWN, second.action)
        assertEquals(1, second.actionIndex)
        val m = t.one(1, move, 60f, 60f).single()
        assertEquals(listOf(Pointer(0, 10f, 10f), Pointer(1, 60f, 60f)), m.pointers)
        val u = t.one(0, up, 11f, 11f).single()
        assertEquals(MotionEvent.ACTION_POINTER_UP, u.action)
        assertEquals(0, u.actionIndex)
        assertEquals(listOf(Pointer(0, 11f, 11f), Pointer(1, 60f, 60f)), u.pointers)
        assertEquals(listOf(Pointer(1, 61f, 61f)), t.one(1, up, 61f, 61f).single().pointers)
    }

    @Test
    fun wholeFrameMovesAllFingersAtOnce() {
        // 5A5A и GAP: все пальцы одним кадром — одно движение, без промежуточного «пальцы сошлись».
        val t = TouchPointers()
        t.one(0, down, 10f, 0f)
        t.apply(Frame(down, 1, listOf(Pointer(0, 10f, 0f), Pointer(1, 20f, 0f))))
        val m = t.apply(Frame(move, 0, listOf(Pointer(0, 20f, 0f), Pointer(1, 30f, 0f)))).single()
        assertEquals(listOf(Pointer(0, 20f, 0f), Pointer(1, 30f, 0f)), m.pointers)
        // Нажатие второго пальца несёт и свежее место первого.
        val t2 = TouchPointers()
        t2.one(0, down, 10f, 0f)
        val pd = t2.apply(Frame(down, 1, listOf(Pointer(0, 40f, 0f), Pointer(1, 60f, 0f)))).single()
        assertEquals(listOf(Pointer(0, 40f, 0f), Pointer(1, 60f, 0f)), pd.pointers)
        // Отпускание полным кадром несёт свежие места оставшихся пальцев.
        val pu = t2.apply(Frame(up, 0, listOf(Pointer(0, 45f, 0f), Pointer(1, 70f, 0f)))).single()
        assertEquals(MotionEvent.ACTION_POINTER_UP, pu.action)
        assertEquals(listOf(Pointer(0, 45f, 0f), Pointer(1, 70f, 0f)), pu.pointers)
    }

    @Test
    fun repeatedPressStartsNewGesture() {
        // Отпускание потерялось (QDLink не пересылает отмену): старый жест отменяется, новое нажатие — новый жест.
        val t = TouchPointers()
        t.one(0, down, 1f, 1f)
        t.one(1, down, 5f, 5f)
        val e = t.one(0, down, 90f, 90f)
        assertEquals(listOf(MotionEvent.ACTION_CANCEL, down), e.map { it.action })
        // Отмена — всем пальцам старого жеста.
        assertEquals(listOf(0, 1), e[0].pointers.map { it.id })
        assertEquals(listOf(Pointer(0, 90f, 90f)), e[1].pointers)
    }

    @Test
    fun strayCancelAndLimit() {
        val t = TouchPointers(max = 2)
        assertTrue(t.one(3, move, 1f, 1f).isEmpty())
        assertTrue(t.one(3, up, 1f, 1f).isEmpty())
        assertTrue(t.apply(Frame(MotionEvent.ACTION_CANCEL, 0, emptyList())).isEmpty())
        t.one(0, down, 0f, 0f)
        t.one(1, down, 0f, 0f)
        assertTrue(t.one(2, down, 0f, 0f).isEmpty())
        val c = t.apply(Frame(MotionEvent.ACTION_CANCEL, 0, emptyList())).single()
        assertEquals(2, c.pointers.size)
        assertEquals(down, t.one(5, down, 0f, 0f).single().action)
    }

    @Test
    fun resetForgetsWhatAaNeverSaw() {
        // Палец нажат, пока AA не готов: учёт сброшен — второй палец для AA первый, а не POINTER_DOWN.
        val t = TouchPointers()
        t.one(0, down, 1f, 1f)
        t.reset()
        assertEquals(down, t.one(1, down, 2f, 2f).single().action)
        assertTrue(t.one(0, up, 1f, 1f).isEmpty())
    }
}
