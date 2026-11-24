package io.github.queryparams.overtakelink.car

import android.view.MotionEvent

/**
 * Пальцы на экране машины → касания Android Auto. Вход — кадры: что случилось в один момент (нажатие или отпускание
 * пальца, движение, отмена) и где сейчас пальцы этого кадра. QDLink по кабелю шлёт кадр на палец, 5A5A и GAP Smart
 * Tool — все пальцы сразу. Android Auto ждёт в каждом событии всех пальцев, второе нажатие — POINTER_DOWN с номером в
 * списке. Учёт — ровно то, что знает текущий сеанс AA: всё, что AA не отправлено, сбрасывается через [reset].
 * Координаты — как пришли, без пересчёта. Не потокобезопасен: зовётся из одного потока.
 */
class TouchPointers(private val max: Int = MAX) {
    /** Событие для Android Auto: действие как в MotionEvent, номер пальца в списке (для POINTER_*), все пальцы. */
    class Event(val action: Int, val actionIndex: Int, val pointers: List<Pointer>)

    data class Pointer(val id: Int, val x: Float, val y: Float)

    /**
     * Кадр касаний: action — ACTION_DOWN / ACTION_UP (палец id нажат или отпущен), ACTION_MOVE или ACTION_CANCEL;
     * pointers — где сейчас пальцы, о которых знает источник (у QDLink по кабелю — только палец id).
     */
    class Frame(val action: Int, val id: Int, val pointers: List<Pointer>)

    private val down = ArrayList<Pointer>()

    /** События для Android Auto по порядку; пустой список — сообщать нечего. */
    fun apply(f: Frame): List<Event> {
        if (f.action == MotionEvent.ACTION_CANCEL) return listOfNotNull(cancel())
        val moved = f.pointers.count { p -> index(p.id).also { if (it >= 0) down[it] = p } >= 0 }
        return when (f.action) {
            MotionEvent.ACTION_MOVE -> if (moved > 0) listOf(Event(MotionEvent.ACTION_MOVE, 0, down.toList())) else emptyList()
            MotionEvent.ACTION_DOWN -> press(f)
            MotionEvent.ACTION_UP -> release(f.id)
            else -> emptyList()
        }
    }

    private fun press(f: Frame): List<Event> {
        val p = f.pointers.firstOrNull { it.id == f.id } ?: return emptyList()
        val out = ArrayList<Event>(2)
        // Палец уже нажат: его отпускание потерялось (QDLink не пересылает отмену жеста) — старый жест отменить,
        // это нажатие — начало нового, а не продолжение старого.
        if (index(f.id) >= 0) cancel()?.let { out.add(it) }
        if (down.size >= max) return out
        down.add(p)
        out.add(if (down.size == 1) Event(MotionEvent.ACTION_DOWN, 0, down.toList())
        else Event(MotionEvent.ACTION_POINTER_DOWN, down.size - 1, down.toList()))
        return out
    }

    private fun release(id: Int): List<Event> {
        val at = index(id)
        if (at < 0) return emptyList()
        val e = if (down.size == 1) Event(MotionEvent.ACTION_UP, 0, down.toList())
        else Event(MotionEvent.ACTION_POINTER_UP, at, down.toList())
        down.removeAt(at)
        return listOf(e)
    }

    private fun cancel(): Event? {
        if (down.isEmpty()) return null
        val e = Event(MotionEvent.ACTION_CANCEL, 0, down.toList())
        down.clear()
        return e
    }

    /** AA этих пальцев не знает (сеанс не готов или новый): забыть без событий. */
    fun reset() = down.clear()

    private fun index(id: Int): Int = down.indexOfFirst { it.id == id }

    companion object {
        /** Больше пальцев Android Auto не ждёт, лишние от испорченного потока не копятся. */
        const val MAX = 10

        /** Кадр одного пальца — как шлёт QDLink по кабелю (!BIN): action 0 нажатие, 1 отпускание, 2 движение. */
        @JvmStatic
        fun finger(id: Int, action: Int, x: Float, y: Float) = Frame(action, id, listOf(Pointer(id, x, y)))
    }
}
