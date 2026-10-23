package io.github.queryparams.overtakelink.car

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.aap.AapService
import com.andrerinas.openheadunit.aap.protocol.messages.TouchEvent
import com.andrerinas.openheadunit.aap.protocol.messages.VideoFocusEvent
import com.andrerinas.openheadunit.connection.CommManager
import com.andrerinas.openheadunit.decoder.video.VideoDecoder
import com.andrerinas.openheadunit.decoder.video.WarmRelaunchKeyframePolicy
import com.andrerinas.openheadunit.utils.HeadUnitScreenConfig
import com.andrerinas.openheadunit.utils.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import io.github.queryparams.overtakelink.link.EventLog
import java.io.ByteArrayOutputStream

/**
 * Прямой режим: H.264 от Android Auto уходит на ГУ как есть — без декодирования, композиции и
 * повторного кодирования. AA рисует стандартный кадр (1080p или 720p) с интерфейсом посередине
 * и полями поровну с краёв; в SPS дописывается обрезка кадра до этой области (см. [SpsCrop]).
 * Касания пересчитываются в координаты AA прямо в потоке чтения USB.
 */
object DirectProjection {

    interface Sink {
        fun frame(data: ByteArray, off: Int, len: Int, keyFrame: Boolean)
    }

    private val main = Handler(Looper.getMainLooper())
    private var scope: CoroutineScope? = null
    @Volatile private var sink: Sink? = null
    @Volatile private var context: Context? = null
    @Volatile private var carWidth = 0
    @Volatile private var carHeight = 0
    @Volatile private var sps: ByteArray? = null
    @Volatile private var pps: ByteArray? = null
    /** Нужен ключевой кадр с SPS/PPS: начало показа, запрос ГУ или отброшенные кадры. */
    @Volatile private var needKey = true
    @Volatile private var framesFromAa = 0L
    @Volatile private var closed = false
    private var wasProjecting = false
    private var downTime = 0L
    private val splashes = HashMap<String, Frame>()


    /**
     * Размер кадра AA в прямом режиме. 1080p и 720p — интерфейс в полосе экрана машины, в SPS
     * дописывается обрезка до неё. 480p — кадр целиком меньше экрана машины: без обрезки, ГУ
     * растягивает его на экран, AA рисует с поправкой на вытянутые пиксели (картинка мягче).
     */
    enum class Size(val width: Int, val height: Int, val resolution: Settings.Resolution) {
        FULL(1920, 1080, Settings.Resolution._1920x1080),
        LIGHT(1280, 720, Settings.Resolution._1280x720),
        SMALL(800, 480, Settings.Resolution._800x480),
    }

    /** Настройки приёмника AA под прямой режим: H.264, 30 к/с — 1080p при 60 к/с (уровень 4.2) декодер ГУ на MT6735 не принимает. */
    @JvmStatic
    fun prepareSettings(settings: Settings, size: Size) {
        settings.resolutionId = size.resolution.id
        settings.videoFitMode = if (size == Size.SMALL) Settings.VideoFitMode.FILL else Settings.VideoFitMode.CONTAIN
        settings.videoCodec = VideoDecoder.CodecType.H264.settingsValue
        settings.fpsLimit = 30
    }

    /**
     * Размер экрана, под который AA раскладывает интерфейс. Для 720p — уменьшенная до ширины кадра
     * копия экрана машины с теми же пропорциями: ГУ растянет её без искажений.
     */
    @JvmStatic
    fun layoutSize(carWidth: Int, carHeight: Int, size: Size): IntArray =
        if (size == Size.LIGHT) intArrayOf(size.width, Math.round(size.width.toFloat() * carHeight / carWidth)) else intArrayOf(carWidth, carHeight)

    @JvmStatic
    fun start(context: Context, width: Int, height: Int, size: Size, sink: Sink) {
        val app = App.provide(context)
        this.context = context.applicationContext
        this.sink = sink
        carWidth = width
        carHeight = height
        sps = null
        pps = null
        needKey = true
        framesFromAa = 0
        closed = false
        wasProjecting = false
        noticeText = null
        prepareSettings(app.settings, size)
        val car = CarScreen.windowContext(context) ?: context
        HeadUnitScreenConfig.init(car, car.resources.displayMetrics, app.settings)
        val layout = layoutSize(width, height, size)
        HeadUnitScreenConfig.updateSurfaceDimensions(layout[0], layout[1])
        app.videoDecoder.passthrough = ::onAccessUnit
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        scope = s
        s.launch {
            app.commManager.connectionState.collect { state -> onState(app.commManager, state) }
        }
        EventLog.add("Экран машины: прямой режим ${width}x$height — видео Android Auto без перекодирования")
    }

    @JvmStatic
    fun stop() {
        val ctx = context ?: return
        App.provide(ctx).videoDecoder.passthrough = null
        scope?.cancel()
        scope = null
        main.removeCallbacks(firstFrameWatchdog)
        sink = null
        context = null
    }

    private fun onState(comm: CommManager, state: CommManager.ConnectionState) {
        EventLog.add("Android Auto: ${state.javaClass.simpleName}")
        when (state) {
            is CommManager.ConnectionState.HandshakeComplete -> {
                HeadUnitScreenConfig.lockResolution()
                scope?.launch { comm.startReading() }
            }
            is CommManager.ConnectionState.TransportStarted -> {
                wasProjecting = true
                noticeText = null
                closed = false
                EventLog.add("Экран машины: AA рисует ${HeadUnitScreenConfig.getNegotiatedWidth()}x" +
                    "${HeadUnitScreenConfig.getNegotiatedHeight()}, поля ${HeadUnitScreenConfig.getWidthMargin()}x" +
                    "${HeadUnitScreenConfig.getHeightMargin()}")
                main.removeCallbacks(firstFrameWatchdog)
                main.post(firstFrameWatchdog)
            }
            is CommManager.ConnectionState.Disconnected -> if (wasProjecting) {
                wasProjecting = false
                closed = true
                showSplash(text(R.string.ol_car_closed))
            }
            else -> {}
        }
    }

    /** Пока от AA нет кадров, просим видеофокус: без него AA не шлёт видео. */
    private val firstFrameWatchdog = object : Runnable {
        override fun run() {
            val ctx = context ?: return
            val comm = App.provide(ctx).commManager
            if (framesFromAa > 0 || comm.connectionState.value !is CommManager.ConnectionState.TransportStarted) return
            comm.send(VideoFocusEvent(gain = true, unsolicited = true))
            main.postDelayed(this, 1500)
        }
    }

    /** ГУ начало показ или просит ключевой кадр: SPS/PPS + IDR от AA (или заставка, если AA нет). */
    @JvmStatic
    fun requestKeyFrame() {
        needKey = true
        val ctx = context ?: return
        val comm = App.provide(ctx).commManager
        if (comm.connectionState.value is CommManager.ConnectionState.TransportStarted) {
            // Повторный запрос уже выданного фокуса AA игнорирует: как OHU, отпускаем и забираем фокус.
            val cycle = comm.releaseVideoFocusForKeyframe()
            if (cycle != null) {
                main.postDelayed({ comm.retakeVideoFocusForKeyframe(cycle) }, WarmRelaunchKeyframePolicy.FOCUS_CYCLE_GAP_MS)
            }
            val asked = framesFromAa
            // AA не ответил кадром за секунду (ещё грузится) — пусть на экране будет заставка.
            main.postDelayed({ if (framesFromAa == asked && sink != null) showSplash(text(R.string.ol_car_starting)) }, 1500)
        } else {
            showSplash(noticeText ?: text(if (closed) R.string.ol_car_closed else R.string.ol_car_starting))
        }
    }

    /** Сообщение на экране машины вместо картинки AA (например, «сервер AA выключен»). */
    @JvmStatic
    fun notice(text: String) {
        noticeText = text
        showSplash(text)
    }

    @Volatile private var noticeText: String? = null

    private fun text(id: Int) = context?.getString(id) ?: ""

    private fun showSplash(text: String) {
        Thread({
            try {
                val frame = synchronized(splashes) {
                    splashes.getOrPut(text) { splash(text) }
                }
                send(frame)
            } catch (e: Exception) {
                EventLog.error("заставка экрана машины", e)
            }
        }, "ol-splash").start()
    }

    /**
     * Заставка того же размера и с той же обрезкой, что и кадры AA: декодер ГУ настраивается на
     * первый кадр и смену размера посреди потока может не пережить.
     */
    private fun splash(text: String): Frame {
        val raw = SplashFrame.encode(HeadUnitScreenConfig.getNegotiatedWidth(), HeadUnitScreenConfig.getNegotiatedHeight(),
            visibleHeight(), text)
        return frame(raw, 0, raw.size, false) ?: throw IllegalStateException("в заставке нет кадра")
    }

    private fun cropSps(b: ByteArray, off: Int, len: Int) = SpsCrop.crop(b, off, len, HeadUnitScreenConfig.getWidthMargin() / 2,
        HeadUnitScreenConfig.getHeightMargin() / 2, visibleWidth(), visibleHeight())

    /** SPS для ГУ: с обрезкой до полосы, а если полей нет (480p) — исходный, без перезаписи. */
    private fun headUnitSps(b: ByteArray, off: Int, len: Int, cropped: SpsCrop.Result): ByteArray =
        if (HeadUnitScreenConfig.getWidthMargin() == 0 && HeadUnitScreenConfig.getHeightMargin() == 0) b.copyOfRange(off, off + len)
        else cropped.nal

    /**
     * Кадр для ГУ: SPS+PPS и сам кадр (срезы) — отдельными пакетами, как у кодировщика телефона.
     * Приёмник QDLink на ГУ (DrawMirrorUtil) узнаёт пакет по первому NAL: ключевым считает только
     * пакет, который начинается с IDR, и до первого такого выбрасывает всё; SPS+PPS+IDR одним
     * пакетом для него — просто SPS.
     */
    private class Frame(val config: ByteArray?, val slices: ByteArray, val key: Boolean)

    private fun send(f: Frame) {
        val s = sink ?: return
        if (f.config != null) s.frame(f.config, 0, f.config.size, true)
        s.frame(f.slices, 0, f.slices.size, f.key)
    }

    /** Единица доступа Annex-B → [Frame]; null, если в ней нет кадра (только SPS/PPS). */
    private fun frame(buf: ByteArray, off: Int, len: Int, fromAa: Boolean): Frame? {
        var sp: ByteArray? = null
        var pp: ByteArray? = null
        val slices = ByteArrayOutputStream(len)
        var key = false
        forEachNal(buf, off, len) { s, e ->
            when (val type = buf[s].toInt() and 31) {
                7 -> {
                    val r = cropSps(buf, s, e - s)
                    val nal = headUnitSps(buf, s, e - s, r)
                    if (fromAa && sps == null) {
                        EventLog.add("Экран машины: поток AA H.264 профиль ${r.profile} уровень ${r.level / 10}.${r.level % 10}, " +
                            "${r.codedWidth}x${r.codedHeight}, " +
                            "на ГУ — ${visibleWidth()}x${visibleHeight()} из середины кадра\n" +
                            "    SPS AA: ${EventLog.hex(buf, s, e - s, 64)}\n    SPS на ГУ: ${EventLog.hex(nal, 0, nal.size, 64)}")
                    }
                    sp = nal
                }
                8 -> pp = buf.copyOfRange(s, e)
                in 1..5 -> {
                    if (type == 5) key = true
                    writeNal(slices, buf, s, e - s)
                }
                // AUD, SEI и прочее декодеру не нужно, а в начале пакета помешало бы ГУ узнать кадр.
                else -> {}
            }
        }
        if (fromAa) {
            sp?.let { sps = it }
            pp?.let { pps = it }
        }
        if (slices.size() == 0) return null
        var config: ByteArray? = null
        if (key) {
            val keySps = sp ?: if (fromAa) sps else null
            val keyPps = pp ?: if (fromAa) pps else null
            if (keySps != null && keyPps != null) {
                val out = ByteArrayOutputStream(keySps.size + keyPps.size + 8)
                writeNal(out, keySps, 0, keySps.size)
                writeNal(out, keyPps, 0, keyPps.size)
                config = out.toByteArray()
            }
        }
        return Frame(config, slices.toByteArray(), key)
    }

    /** Кадр от AA (поток видео OHU): Annex-B, одна единица доступа. */
    private fun onAccessUnit(buf: ByteArray, off: Int, len: Int) {
        val f = frame(buf, off, len, true) ?: return
        framesFromAa++
        if (needKey && !f.key) return
        if (f.key) needKey = false
        send(f)
    }

    private fun visibleWidth(): Int =
        (HeadUnitScreenConfig.getNegotiatedWidth() - HeadUnitScreenConfig.getWidthMargin()).coerceAtLeast(16)

    private fun visibleHeight(): Int =
        (HeadUnitScreenConfig.getNegotiatedHeight() - HeadUnitScreenConfig.getHeightMargin()).coerceAtLeast(16)

    /** Касание с экрана ГУ (поток чтения USB): пиксели машины → координаты AA. */
    @JvmStatic
    fun touch(action: Int, x: Float, y: Float) {
        val ctx = context ?: return
        if (closed) {
            if (action == MotionEvent.ACTION_UP) {
                closed = false
                EventLog.add("Экран машины: касание — открываем Android Auto снова")
                showSplash(text(R.string.ol_car_starting))
                ctx.startService(Intent(ctx, AapService::class.java).setAction(AapService.ACTION_START_SELF_MODE))
            }
            return
        }
        val comm = App.provide(ctx).commManager
        if (comm.connectionState.value !is CommManager.ConnectionState.TransportStarted || carWidth <= 0) return
        val now = SystemClock.uptimeMillis()
        if (action == MotionEvent.ACTION_DOWN) downTime = now
        val ev = MotionEvent.obtain(downTime, now, action, x, y, 0)
        try {
            val aaAction = TouchEvent.motionEventToAction(ev) ?: return
            val ax = (x * visibleWidth() / carWidth).toInt()
            val ay = (y * visibleHeight() / carHeight).toInt()
            comm.send(TouchEvent(SystemClock.elapsedRealtime(), aaAction, 0, listOf(Triple(0, ax, ay))))
        } finally {
            ev.recycle()
        }
    }

    private inline fun forEachNal(b: ByteArray, off: Int, len: Int, block: (Int, Int) -> Unit) {
        val end = off + len
        var start = -1
        var i = off
        while (i + 2 < end) {
            if (b[i].toInt() == 0 && b[i + 1].toInt() == 0 && b[i + 2].toInt() == 1) {
                if (start >= 0) block(start, trimZeros(b, start, i))
                start = i + 3
                i += 3
            } else {
                i++
            }
        }
        if (start >= 0 && start < end) block(start, end)
    }

    private fun trimZeros(b: ByteArray, start: Int, endExclusive: Int): Int {
        var e = endExclusive
        while (e > start && b[e - 1].toInt() == 0) e--
        return e
    }

    private fun writeNal(out: ByteArrayOutputStream, b: ByteArray, off: Int, len: Int) {
        out.write(START, 0, 4)
        out.write(b, off, len)
    }

    private val START = byteArrayOf(0, 0, 0, 1)
}
