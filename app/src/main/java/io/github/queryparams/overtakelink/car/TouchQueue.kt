package io.github.queryparams.overtakelink.car

import android.view.MotionEvent

/**
 * Кадры касаний по порядку для главного потока. Порядок нажатий и отпусканий сохраняется; движение, пришедшее за
 * движением, сливается с ним в один кадр с последним местом каждого пальца — очередь не растёт, если главный поток не
 * успевает, и AA не видит промежуточных положений вперемешку. Переполнение — сначала выпадают движения; одни нажатия и
 * отпускания — всё накопленное отменяется: AA получает отмену, пальцы не «зависнут». Потокобезопасна.
 */
class TouchQueue(private val max: Int = MAX) {
    private val frames = ArrayDeque<TouchPointers.Frame>()
    private var draining = false

    /** Поставить кадр; true — очередь ждала, нужно запланировать разбор ([poll] до null). */
    @Synchronized
    fun offer(f: TouchPointers.Frame): Boolean {
        val last = frames.lastOrNull()
        if (f.action == MotionEvent.ACTION_MOVE && last != null && last.action == MotionEvent.ACTION_MOVE) {
            val merged = LinkedHashMap<Int, TouchPointers.Pointer>()
            for (p in last.pointers) merged[p.id] = p
            for (p in f.pointers) merged[p.id] = p
            frames[frames.size - 1] = TouchPointers.Frame(MotionEvent.ACTION_MOVE, f.id, merged.values.toList())
            return false
        }
        if (frames.size >= max) {
            val move = frames.indexOfFirst { it.action == MotionEvent.ACTION_MOVE }
            if (move >= 0) {
                frames.removeAt(move)
            } else {
                frames.clear()
                frames.addLast(TouchPointers.Frame(MotionEvent.ACTION_CANCEL, 0, emptyList()))
            }
        }
        frames.addLast(f)
        if (draining) return false
        draining = true
        return true
    }

    /** Следующий кадр; null — очередь разобрана, следующий [offer] снова попросит разбор. */
    @Synchronized
    fun poll(): TouchPointers.Frame? = frames.removeFirstOrNull() ?: run { draining = false; null }

    companion object {
        const val MAX = 64
    }
}
