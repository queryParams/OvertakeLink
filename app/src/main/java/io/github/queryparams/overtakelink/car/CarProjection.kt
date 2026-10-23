package io.github.queryparams.overtakelink.car

import android.app.Presentation
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.MotionEvent
import android.view.Surface
import android.content.Intent
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView
import com.andrerinas.openheadunit.aap.AapService
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.aap.ProjectionWatchdogPolicy
import com.andrerinas.openheadunit.aap.protocol.messages.TouchEvent
import com.andrerinas.openheadunit.aap.protocol.messages.VideoFocusEvent
import com.andrerinas.openheadunit.connection.CommManager
import com.andrerinas.openheadunit.decoder.video.DecoderStopPolicy
import com.andrerinas.openheadunit.decoder.video.VideoDimensionsListener
import com.andrerinas.openheadunit.input.TouchCoordinateMapper
import com.andrerinas.openheadunit.utils.HeadUnitScreenConfig
import com.andrerinas.openheadunit.view.IProjectionView
import com.andrerinas.openheadunit.view.ProjectionView
import com.andrerinas.openheadunit.view.ProjectionViewScaler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import io.github.queryparams.overtakelink.link.EventLog

/**
 * Android Auto на экране машины. Окно-презентация на собственном виртуальном дисплее: стороннее
 * приложение не может запускать activity на недоверенный виртуальный дисплей (нужно системное
 * ACTIVITY_EMBEDDING), а окно на своём приватном дисплее — может, и без оглядки на блокировку телефона.
 * Делает то же, что экран проекции Open Headunit: отдаёт свою поверхность декодеру видео AA,
 * запускает чтение после рукопожатия, просит видеофокус и пересчитывает касания в координаты AA.
 */
class CarProjection private constructor(context: Context, display: Display) :
    Presentation(context, display), IProjectionView.Callbacks, VideoDimensionsListener {

    private val app = App.provide(context)
    private val commManager get() = app.commManager
    private val videoDecoder get() = app.videoDecoder
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var view: ProjectionView
    private lateinit var splash: TextView
    /** Пиксель для принудительной перерисовки: кодировщик выдаёт кадр, только когда картинка меняется. */
    private lateinit var nudgePixel: android.view.View
    /** AA был на экране и закрылся (Exit в его меню): касание экрана машины открывает его снова. */
    private var closed = false
    private var wasProjecting = false
    private var surfaceSet = false
    private var downTime = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prepareSettings(app.settings)
        HeadUnitScreenConfig.init(context, context.resources.displayMetrics, app.settings)
        val root = FrameLayout(context)
        root.setBackgroundColor(Color.BLACK)
        view = ProjectionView(context)
        view.addCallback(this)
        root.addView(view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        splash = TextView(context).apply {
            setBackgroundColor(Color.BLACK)
            setTextColor(Color.WHITE)
            textSize = 40f
            gravity = Gravity.CENTER
        }
        root.addView(splash, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        showSplash("OvertakeLink\nЗапускаем Android Auto…")
        nudgePixel = android.view.View(context).apply { setBackgroundColor(Color.BLACK) }
        root.addView(nudgePixel, FrameLayout.LayoutParams(1, 1))
        setContentView(root)
        videoDecoder.dimensionsListener = this
        scope.launch {
            commManager.connectionState.collect { state ->
                EventLog.add("Android Auto: ${state.javaClass.simpleName}")
                if (state is CommManager.ConnectionState.TransportStarted) wasProjecting = true
                if (state is CommManager.ConnectionState.Disconnected && wasProjecting) {
                    wasProjecting = false
                    closed = true
                    showSplash("Android Auto закрыт\nКоснитесь экрана, чтобы открыть снова")
                }
                if (state is CommManager.ConnectionState.HandshakeComplete) {
                    HeadUnitScreenConfig.lockResolution()
                    if (surfaceSet) commManager.startReading()
                    main.removeCallbacks(firstFrameWatchdog)
                    main.postDelayed(firstFrameWatchdog, 1000)
                }
            }
        }
    }

    /** Как сторож экрана проекции OHU: пока на поверхности нет картинки, просим у AA видео. */
    private val firstFrameWatchdog = object : Runnable {
        override fun run() {
            if (!ProjectionWatchdogPolicy.shouldNudgeForFirstFrame(
                    sessionLive = ProjectionWatchdogPolicy.isSessionLive(commManager.connectionState.value),
                    surfaceSet = surfaceSet,
                    crediblePictureOnSurface = videoDecoder.hasCrediblePicture,
                    warmRelaunchCycleSpent = false,
                )
            ) return
            commManager.send(VideoFocusEvent(gain = true, unsolicited = true))
            main.postDelayed(this, 1500)
        }
    }

    override fun onSurfaceCreated(surface: Surface) {}

    override fun onSurfaceChanged(surface: Surface, width: Int, height: Int) {
        val changed = !videoDecoder.isCurrentSurface(surface)
        surfaceSet = true
        videoDecoder.setSurface(surface)
        HeadUnitScreenConfig.updateSurfaceDimensions(width, height)
        EventLog.add("Экран машины: поверхность AA ${width}x$height, AA рисует " +
            "${HeadUnitScreenConfig.getNegotiatedWidth()}x${HeadUnitScreenConfig.getNegotiatedHeight()}, " +
            "поля ${HeadUnitScreenConfig.getWidthMargin()}x${HeadUnitScreenConfig.getHeightMargin()}")
        when (commManager.connectionState.value) {
            is CommManager.ConnectionState.Connected -> scope.launch { commManager.startHandshake() }
            is CommManager.ConnectionState.StartingTransport -> {}
            is CommManager.ConnectionState.HandshakeComplete -> scope.launch { commManager.startReading() }
            is CommManager.ConnectionState.TransportStarted ->
                if (changed) commManager.send(VideoFocusEvent(gain = true, unsolicited = true))
            else -> commManager.send(VideoFocusEvent(gain = true, unsolicited = false))
        }
        if (videoDecoder.videoWidth > 0 && videoDecoder.videoHeight > 0) {
            onVideoDimensionsChanged(videoDecoder.videoWidth, videoDecoder.videoHeight)
        }
        main.removeCallbacks(firstFrameWatchdog)
        main.postDelayed(firstFrameWatchdog, 1000)
    }

    override fun onSurfaceDestroyed(surface: Surface) {
        if (!videoDecoder.isCurrentSurface(surface)) return
        surfaceSet = false
        commManager.send(VideoFocusEvent(gain = false, unsolicited = false))
        videoDecoder.detachSurfaceIfCurrent(surface, DecoderStopPolicy.REASON_SURFACE_DESTROYED)
    }

    override fun onVideoDimensionsChanged(width: Int, height: Int) {
        main.post {
            EventLog.add("Android Auto: видео ${width}x$height")
            view.setVideoSize(width, height)
            ProjectionViewScaler.updateScale(view, width, height)
        }
    }

    private fun showSplash(text: String) {
        splash.text = text
        splash.visibility = android.view.View.VISIBLE
        videoDecoder.onFirstFrameListener = { main.post { splash.visibility = android.view.View.GONE } }
    }

    /** Касание с экрана ГУ: action как в MotionEvent, координаты — пиксели экрана машины. */
    private fun touch(action: Int, x: Float, y: Float) {
        if (closed) {
            if (action == MotionEvent.ACTION_UP) {
                closed = false
                showSplash("OvertakeLink\nЗапускаем Android Auto…")
                EventLog.add("Экран машины: касание — открываем Android Auto снова")
                context.startService(Intent(context, AapService::class.java).setAction(AapService.ACTION_START_SELF_MODE))
            }
            return
        }
        val w = HeadUnitScreenConfig.getNegotiatedWidth()
        val h = HeadUnitScreenConfig.getNegotiatedHeight()
        if (w <= 0 || h <= 0 || view.width <= 0 || view.height <= 0) return
        val now = SystemClock.uptimeMillis()
        if (action == MotionEvent.ACTION_DOWN) downTime = now
        val ev = MotionEvent.obtain(downTime, now, action, x, y, 0)
        try {
            val aaAction = TouchEvent.motionEventToAction(ev) ?: return
            val p = TouchCoordinateMapper.map(
                rawX = x,
                rawY = y,
                inputSurfaceWidth = view.width.toFloat(),
                inputSurfaceHeight = view.height.toFloat(),
                negotiatedWidth = w,
                negotiatedHeight = h,
                marginWidth = HeadUnitScreenConfig.getWidthMargin().toFloat(),
                marginHeight = HeadUnitScreenConfig.getHeightMargin().toFloat(),
                fitMode = app.settings.videoFitMode,
                hudMirroring = false
            )
            commManager.send(TouchEvent(SystemClock.elapsedRealtime(), aaAction, 0, listOf(Triple(0, p.x, p.y))))
        } finally {
            ev.recycle()
        }
    }

    private fun release() {
        scope.cancel()
        main.removeCallbacks(firstFrameWatchdog)
        if (videoDecoder.dimensionsListener === this) videoDecoder.dimensionsListener = null
        videoDecoder.onFirstFrameListener = null
        view.removeCallback(this)
        dismiss()
    }

    companion object {
        private val main = Handler(Looper.getMainLooper())

        /** Настройки приёмника AA под перекодирование: AA сам выбирает размер, картинка растягивается на окно. */
        @JvmStatic
        fun prepareSettings(settings: com.andrerinas.openheadunit.utils.Settings) {
            settings.resolutionId = com.andrerinas.openheadunit.utils.Settings.Resolution.AUTO.id
            settings.videoFitMode = com.andrerinas.openheadunit.utils.Settings.VideoFitMode.FILL
            settings.videoCodec = "Auto"
            // 30 к/с: ГУ всё равно показывает ~24, а распаковывать вдвое меньше кадров — меньше расход батареи.
            settings.fpsLimit = 30
        }
        private var current: CarProjection? = null

        /** Показать Android Auto на экране машины (с любого потока). */
        @JvmStatic
        fun show(context: Context) = main.post {
            val display = CarScreen.current()?.display() ?: return@post
            if (current != null) return@post
            try {
                current = CarProjection(context.applicationContext, display).also { it.show() }
                EventLog.add("Экран машины: окно Android Auto открыто на дисплее #${display.displayId}")
            } catch (e: Exception) {
                EventLog.error("окно Android Auto на экране машины", e)
            }
        }

        /** Сообщение на экране машины вместо картинки AA (например, «сервер AA выключен»). */
        @JvmStatic
        fun notice(text: String) = main.post { current?.showSplash(text) }

        @JvmStatic
        fun close() = main.post {
            current?.release()
            current = null
        }

        @JvmStatic
        fun touch(action: Int, x: Float, y: Float) = main.post { current?.touch(action, x, y) }

        /**
         * Новый кадр без изменений на экране AA: ГУ начало показ или просит ключевой кадр, а
         * неподвижная картинка иначе не дала бы кодировщику ни одного кадра — на ГУ был бы чёрный экран.
         */
        @JvmStatic
        fun redraw() = main.post {
            val p = current?.nudgePixel ?: return@post
            p.setBackgroundColor(if (p.tag == true) Color.BLACK else 0xFF010101.toInt())
            p.tag = p.tag != true
        }
    }
}
