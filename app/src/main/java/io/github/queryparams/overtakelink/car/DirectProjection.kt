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
    private val splashes = HashMap<String, ByteArray>()


    /**
     * Настройки приёмника AA под прямой режим: H.264, интерфейс в полосе экрана машины, 30 к/с —
     * 1080p при 60 к/с (уровень 4.2) декодер ГУ на MT6735 не принимает. Облегчённый — 720p:
     * ГУ само растягивает картинку на экран.
     */
    @JvmStatic
    fun prepareSettings(settings: Settings, light: Boolean) {
        settings.resolutionId = (if (light) Settings.Resolution._1280x720 else Settings.Resolution._1920x1080).id
        settings.videoFitMode = Settings.VideoFitMode.CONTAIN
        settings.videoCodec = VideoDecoder.CodecType.H264.settingsValue
        settings.fpsLimit = 30
    }

    /**
     * Размер экрана, под который AA раскладывает интерфейс. В облегчённом режиме — уменьшенная до
     * ширины 720p копия экрана машины с теми же пропорциями: ГУ растянет её без искажений.
     */
    @JvmStatic
    fun layoutSize(carWidth: Int, carHeight: Int, light: Boolean): IntArray =
        if (light) intArrayOf(LIGHT_WIDTH, Math.round(LIGHT_WIDTH.toFloat() * carHeight / carWidth)) else intArrayOf(carWidth, carHeight)

    private const val LIGHT_WIDTH = 1280

    @JvmStatic
    fun start(context: Context, width: Int, height: Int, light: Boolean, sink: Sink) {
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
        prepareSettings(app.settings, light)
        val car = CarScreen.windowContext(context) ?: context
        HeadUnitScreenConfig.init(car, car.resources.displayMetrics, app.settings)
        val layout = layoutSize(width, height, light)
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
                val data = synchronized(splashes) {
                    splashes.getOrPut(text) { SplashFrame.encode(carWidth, carHeight, text) }
                }
                sink?.frame(data, 0, data.size, true)
            } catch (e: Exception) {
                EventLog.error("заставка экрана машины", e)
            }
        }, "ol-splash").start()
    }

    /** Кадр от AA (поток видео OHU): Annex-B, одна единица доступа. */
    private fun onAccessUnit(buf: ByteArray, off: Int, len: Int) {
        val out = ByteArrayOutputStream(len + 64)
        var key = false
        var hasConfig = false
        forEachNal(buf, off, len) { s, e ->
            when (buf[s].toInt() and 31) {
                7 -> {
                    val r = SpsCrop.crop(buf, s, e - s, HeadUnitScreenConfig.getWidthMargin() / 2,
                        HeadUnitScreenConfig.getHeightMargin() / 2, visibleWidth(), visibleHeight())
                    if (sps == null) {
                        EventLog.add("Экран машины: поток AA H.264 профиль ${r.profile} уровень ${r.level / 10}.${r.level % 10}, " +
                            "${r.codedWidth}x${r.codedHeight}, " +
                            "на ГУ — ${visibleWidth()}x${visibleHeight()} из середины кадра")
                    }
                    sps = r.nal
                    hasConfig = true
                    writeNal(out, r.nal, 0, r.nal.size)
                }
                8 -> {
                    pps = buf.copyOfRange(s, e)
                    writeNal(out, buf, s, e - s)
                }
                5 -> {
                    if (!hasConfig) {
                        val sp = sps
                        val pp = pps
                        if (sp != null && pp != null) {
                            writeNal(out, sp, 0, sp.size)
                            writeNal(out, pp, 0, pp.size)
                        }
                    }
                    key = true
                    writeNal(out, buf, s, e - s)
                }
                else -> writeNal(out, buf, s, e - s)
            }
        }
        if (out.size() == 0) return
        framesFromAa++
        if (needKey && !key) return
        if (key) needKey = false
        val data = out.toByteArray()
        sink?.frame(data, 0, data.size, key)
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
