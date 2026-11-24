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
import kotlin.math.roundToInt

/**
 * Прямой режим: H.264 от Android Auto уходит на ГУ как есть — без декодирования, композиции и
 * повторного кодирования. AA рисует стандартный кадр (1080p, 720p или 480p) целиком, с поправкой
 * на вытянутые пиксели; ГУ растягивает его на свой экран. Касания ГУ присылает уже в координатах
 * кадра — они уходят в AA прямо из потока чтения USB.
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
    /** Сколько кадров AA было к началу текущего подключения AA: «первый кадр» — свой у каждого подключения. */
    @Volatile private var framesAtTransport = 0L
    @Volatile private var closed = false
    private var wasProjecting = false
    /** Пальцы, о которых знает сеанс AA: касания приходят из одного потока чтения (USB или GAP), блокировка — на смену потока. */
    private val pointers = TouchPointers()
    /** Заставки по тексту и размеру кадра: смена прямого режима (1080p → 480p) — другая заставка. */
    private val splashes = HashMap<String, Frame>()
    /** Отправка кадров AA и заставки — по очереди: заставка не вклинивается в уже идущий поток AA. */
    private val sendLock = Any()
    /** Номер показа (start): заставка от прошлого показа в новый не уходит. */
    @Volatile private var generation = 0


    /**
     * Размер кадра AA в прямом режиме. Кадр идёт на ГУ целиком и как есть: SPS с обрезкой до полосы
     * экрана декодер ГУ не показывает (в машине 720p и 1080p с обрезкой — чёрный экран, 480p без неё — картинка).
     */
    enum class Size(val width: Int, val height: Int, val resolution: Settings.Resolution) {
        FULL(1920, 1080, Settings.Resolution._1920x1080),
        LIGHT(1280, 720, Settings.Resolution._1280x720),
        SMALL(800, 480, Settings.Resolution._800x480),
    }

    /** Настройки приёмника AA под прямой режим: H.264, 30 к/с — 1080p при 60 к/с (уровень 4.2) декодер ГУ на MT6735 не принимает. */
    @JvmStatic
    fun prepareSettings(settings: Settings, size: Size) = prepareSettings(settings, size, false)

    /**
     * fitScreen — экран показывает кадр без растяжения (GAP Smart Tool): AA рисует интерфейс в полосе пропорций экрана,
     * остальное — поля (CONTAIN). Без него — весь кадр, ГУ растягивает (QDLink). fit — во сколько раз полоса меньше
     * места в GAP ({@link #fit}).
     */
    @JvmStatic
    fun prepareSettings(settings: Settings, size: Size, fitScreen: Boolean, fit: Float = 1f) {
        settings.resolutionId = size.resolution.id
        settings.videoFitMode = if (fitScreen) Settings.VideoFitMode.CONTAIN else Settings.VideoFitMode.FILL
        // Один холст интерфейса (~853 dp в ширину) для всех размеров: автоматика OHU даёт 150 dpi на
        // 800 px ширины, но не выше 240 — в 1080p холст вырос бы до 1280 dp и интерфейс измельчал.
        settings.dpiPixelDensity = size.width * 150 / 800
        // Полоса интерфейса в GAP Smart Tool низкая (1724×640 у 1080p): при 360 dpi в ней ~284 dp высоты — мелко и тесно
        // для AA; 240 dpi — ~1150×427 dp, как у обычной широкой магнитолы. Полоса меньше (720p, 480p) — плотность меньше
        // во столько же раз: интерфейс AA того же размера, только чётче или мягче.
        if (fitScreen) settings.dpiPixelDensity = (240 * fit).roundToInt()
        settings.videoCodec = VideoDecoder.CodecType.H264.settingsValue
        settings.fpsLimit = 30
    }

    @JvmStatic
    fun start(context: Context, width: Int, height: Int, size: Size, sink: Sink) = start(context, width, height, size, sink, false, 1f)

    /**
     * GAP: во сколько раз экран AA меньше места в GAP. Полоса экрана лежит в кадре 1:1 — у 1080p она целиком, а кадр
     * уже места (720p, 480p) — экран той же формы, но меньше, иначе полей нет и GAP растянул бы кадр по ширине.
     */
    @JvmStatic
    fun fit(width: Int, height: Int, size: Size): Float = minOf(1f, size.width.toFloat() / width, size.height.toFloat() / height)

    /** fit — из {@link #fit}: экран width×height уже уменьшен, плотность — во столько же раз (интерфейс AA того же размера). */
    @JvmStatic
    fun start(context: Context, width: Int, height: Int, size: Size, sink: Sink, fitScreen: Boolean, fit: Float) {
        // Новый показ — новый учёт пальцев: прежнего показа AA не знает.
        synchronized(pointers) { pointers.reset() }
        val app = App.provide(context)
        this.context = context.applicationContext
        val gen: Int
        // Получатель и поколение меняются под той же блокировкой, что и отправка: дочитанный кадр прошлого показа
        // не уйдёт новому получателю.
        synchronized(sendLock) {
            this.sink = sink
            sps = null
            pps = null
            synchronized(waitLock) {
                needKey = true
                waitChanges++
            }
            framesFromAa = 0
            framesAtTransport = 0
            generation++
            gen = generation
        }
        // Новый показ: первый запрос ключевого кадра — сразу, пауза прошлого показа не в счёт. Под keyLock:
        // запоздавший запрос прошлого показа не перезапишет сброс.
        synchronized(keyLock) {
            lastCycleAt = 0L
            // Пауза между циклами — только для GAP Smart Tool; кабель (QDLink) — как раньше, сразу.
            limitCycles = fitScreen
        }
        carWidth = width
        carHeight = height
        synchronized(splashes) { splashes.clear() }
        closed = false
        wasProjecting = false
        noticeText = null
        prepareSettings(app.settings, size, fitScreen, fit)
        val car = CarScreen.windowContext(context) ?: context
        HeadUnitScreenConfig.init(car, car.resources.displayMetrics, app.settings)
        HeadUnitScreenConfig.updateSurfaceDimensions(width, height)
        app.videoDecoder.passthrough = { b, o, l -> onAccessUnit(b, o, l, gen) }
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        scope = s
        s.launch {
            app.commManager.connectionState.collect { state -> onState(app.commManager, state) }
        }
        EventLog.add("Экран машины: прямой режим ${width}x$height — видео Android Auto без перекодирования")
    }

    @JvmStatic
    fun stop() {
        synchronized(pointers) { pointers.reset() }
        val ctx = context ?: return
        App.provide(ctx).videoDecoder.passthrough = null
        scope?.cancel()
        scope = null
        main.removeCallbacks(firstFrameWatchdog)
        main.removeCallbacks(splashCheck)
        // Отложенный запрос ключевого кадра — прошлого показа: новому он не нужен.
        main.removeCallbacks(keyLater)
        pendingSplash.set(null)
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
                framesAtTransport = framesFromAa
                EventLog.add("Экран машины: AA рисует ${frameWidth()}x${frameHeight()}, ГУ растягивает на ${carWidth}x$carHeight")
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
            if (framesFromAa > framesAtTransport || comm.connectionState.value !is CommManager.ConnectionState.TransportStarted) return
            comm.send(VideoFocusEvent(gain = true, unsolicited = true))
            main.postDelayed(this, 1500)
        }
    }

    /** ГУ начало показ или просит ключевой кадр: SPS/PPS + IDR от AA (или заставка, если AA нет). */
    @JvmStatic
    fun requestKeyFrame() = requestKey(false)

    /**
     * retry — отложенный повтор: ключевой кадр за паузу уже пришёл — видео не перезапускаем. Зовут поток чтения GAP, его
     * тик и главный: проверка паузы и её отметка — под keyLock, а сам цикл (он пишет журнал) — уже без замка.
     */
    private fun requestKey(retry: Boolean) {
        // Пересылку кадров (needKey) останавливаем только с самим циклом: отложенный запрос не должен замораживать
        // исправную картинку на всю паузу.
        if (!retry) keyWanted = true
        val ctx = context ?: return
        val comm = App.provide(ctx).commManager
        if (comm.connectionState.value is CommManager.ConnectionState.TransportStarted) {
            // Каждый цикл фокуса AA видит как пропажу экрана и пересобирает его (карточки исчезают и проявляются):
            // не чаще раза в KEY_CYCLE_MS. Запросы в паузе — один отложенный, если ключевой кадр так и не пришёл.
            val mine: Long
            synchronized(keyLock) {
                // Отложенный повтор — только в показе GAP: сменили вывод на кабель — ему он не нужен.
                if (retry && (!keyWanted || !limitCycles)) return
                val now = android.os.SystemClock.elapsedRealtime()
                val wait = if (limitCycles) lastCycleAt + KEY_CYCLE_MS - now else 0L
                if (wait > 0) {
                    main.removeCallbacks(keyLater)
                    main.postDelayed(keyLater, wait)
                    return
                }
                lastCycleAt = now
                mine = now
            }
            // Ждём ключевой кадр — до сброса фокуса: запоздавший поток не включит ожидание уже после первого IDR нового
            // потока. Цикл не начался — ожидание снимаем, иначе картинка встала бы без нового IDR.
            val wasWaiting: Boolean
            val changesBefore: Long
            synchronized(waitLock) {
                wasWaiting = needKey
                changesBefore = waitChanges
                needKey = true
            }
            // Повторный запрос уже выданного фокуса AA игнорирует: как OHU, отпускаем и забираем фокус.
            val cycle = comm.releaseVideoFocusForKeyframe()
            if (cycle != null) {
                main.postDelayed({ comm.retakeVideoFocusForKeyframe(cycle) }, WarmRelaunchKeyframePolicy.FOCUS_CYCLE_GAP_MS)
            } else {
                // Цикл не состоялся — паузу не держим (следующий запрос пусть проходит сразу), ожидание — как было до запроса.
                // …если только за это время ожидание не трогали: прошёл IDR, заставка ждёт IDR после себя или отброшен кадр.
                synchronized(waitLock) { if (waitChanges == changesBefore) needKey = wasWaiting }
                synchronized(keyLock) { if (lastCycleAt == mine) lastCycleAt = 0L }
            }
            // AA не ответил кадром за секунду (ещё грузится) — пусть на экране будет заставка. Проверка одна на все
            // запросы: повторные лишь перезаводят её, очередь главного потока не растёт.
            splashCheckAsked = framesFromAa
            main.removeCallbacks(splashCheck)
            main.postDelayed(splashCheck, 1500)
        } else if (!retry) {
            synchronized(waitLock) {
                needKey = true
                waitChanges++
            }
            showSplash(noticeText ?: text(if (closed) R.string.ol_car_closed else R.string.ol_car_starting))
        }
    }

    /** Цикл фокуса ради ключевого кадра — не чаще (мс). */
    private const val KEY_CYCLE_MS = 4000L
    private val keyLock = Any()
    private var lastCycleAt = 0L
    /** Показ в GAP Smart Tool (fitScreen): там пауза между циклами; по кабелю QDLink поведение прежнее. */
    private var limitCycles = false
    private val keyLater = Runnable { requestKey(true) }
    /**
     * Ожидание IDR (needKey) и счётчик его изменений — IDR, заставка, новый показ, отброшенный кадр — под waitLock (берут и
     * внутри sendLock, не наоборот): откат в requestKey их не перезапишет и не ждёт отправки кадра.
     */
    private val waitLock = Any()
    private var waitChanges = 0L

    /** ГУ просило ключевой кадр, а он ещё не пришёл (отложенный повтор смотрит на это, а не на needKey). */
    @Volatile private var keyWanted = false

    @Volatile private var splashCheckAsked = 0L
    private val splashCheck = Runnable {
        if (framesFromAa == splashCheckAsked && sink != null) showSplash(text(R.string.ol_car_starting))
    }

    /** Сообщение на экране машины вместо картинки AA (например, «сервер AA выключен»). */
    @JvmStatic
    fun notice(text: String) {
        noticeText = text
        showSplash(text)
    }

    @Volatile private var noticeText: String? = null

    private fun text(id: Int) = context?.getString(id) ?: ""

    /** Ожидающая заставка — одна, последняя; кодирует её один рабочий поток. */
    private class SplashRequest(val text: String, val aaAtRequest: Long, val gen: Int)
    private val pendingSplash = java.util.concurrent.atomic.AtomicReference<SplashRequest?>(null)
    private val splashWorker = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "ol-splash") }
    private val splashScheduled = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun showSplash(text: String) {
        // Повторные запросы ГУ сливаются: потоков и ожидающих заставок не копится.
        pendingSplash.set(SplashRequest(text, framesFromAa, generation))
        if (!splashScheduled.compareAndSet(false, true)) return
        splashWorker.execute {
            splashScheduled.set(false)
            val req = pendingSplash.getAndSet(null) ?: return@execute
            try {
                // Устаревший показ — не кодируем зря.
                if (req.gen != generation) return@execute
                val frame = synchronized(splashes) {
                    splashes.getOrPut("${req.text}|${frameWidth()}x${frameHeight()}") { splash(req.text) }
                }
                synchronized(sendLock) {
                    // Пока заставка кодировалась, пошли кадры AA (или начался новый показ) — она уже не нужна:
                    // её SPS/PPS и IDR поверх потока AA испортили бы картинку до следующего ключевого кадра.
                    if (framesFromAa != req.aaAtRequest || generation != req.gen) return@execute
                    send(frame)
                    // Следующие кадры AA ссылались бы на свой опорный кадр, а на экране — заставка: ждём IDR от AA.
                    synchronized(waitLock) {
                        needKey = true
                        waitChanges++
                    }
                }
            } catch (e: Exception) {
                EventLog.error("заставка экрана машины", e)
            }
        }
    }

    /**
     * Заставка того же размера, что и кадры AA: декодер ГУ настраивается на первый кадр и смену
     * размера посреди потока может не пережить. Текст сжат по ширине так же, как ГУ его растянет.
     */
    private fun splash(text: String): Frame {
        val xScale = frameWidth().toFloat() / carWidth / (frameHeight().toFloat() / carHeight)
        val raw = SplashFrame.encode(frameWidth(), frameHeight(), xScale, text)
        return frame(raw, 0, raw.size, false) ?: throw IllegalStateException("в заставке нет кадра")
    }

    /**
     * Кадр для ГУ: SPS+PPS и сам кадр (срезы) — отдельными пакетами, как у кодировщика телефона.
     * Приёмник QDLink на ГУ (DrawMirrorUtil) узнаёт пакет по первому NAL: ключевым считает только
     * пакет, который начинается с IDR, и до первого такого выбрасывает всё; SPS+PPS+IDR одним
     * пакетом для него — просто SPS.
     */
    private class Frame(val config: ByteArray?, val slices: ByteArray, val slicesLen: Int, val key: Boolean)

    /** Срезы кадра без лишней копии: отдаём внутренний буфер и его заполненную длину. */
    private class Slices(size: Int) : ByteArrayOutputStream(size) {
        fun raw(): ByteArray = buf
        fun length(): Int = count
    }

    private fun send(f: Frame) {
        val s = sink ?: return
        if (f.config != null) s.frame(f.config, 0, f.config.size, true)
        s.frame(f.slices, 0, f.slicesLen, f.key)
    }

    /** Единица доступа Annex-B → [Frame]; null, если в ней нет кадра (только SPS/PPS). */
    private fun frame(buf: ByteArray, off: Int, len: Int, fromAa: Boolean): Frame? {
        var sp: ByteArray? = null
        var pp: ByteArray? = null
        val slices = Slices(len)
        var key = false
        forEachNal(buf, off, len) { s, e ->
            when (val type = buf[s].toInt() and 31) {
                7 -> {
                    if (fromAa && sps == null && e - s > 3) {
                        val level = buf[s + 3].toInt() and 0xff
                        EventLog.add("Экран машины: поток AA H.264 профиль ${buf[s + 1].toInt() and 0xff} уровень ${level / 10}.${level % 10}, " +
                            "кадр ${frameWidth()}x${frameHeight()} целиком\n    SPS: ${EventLog.hex(buf, s, e - s, 64)}")
                    }
                    sp = buf.copyOfRange(s, e)
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
        return Frame(config, slices.raw(), slices.length(), key)
    }

    /** Кадр от AA (поток видео OHU): Annex-B, одна единица доступа. */
    private fun onAccessUnit(buf: ByteArray, off: Int, len: Int, gen: Int) {
        synchronized(sendLock) {
            // Кадр прошлого показа (быстрое переподключение) — не наш: ни разбор SPS, ни отправка.
            if (gen != generation) return
            val f = frame(buf, off, len, true) ?: return
            framesFromAa++
            synchronized(waitLock) {
                // Отброшенный кадр тоже считается: без него цепочка P-кадров уже не полна, откат ожидания недопустим.
                if (needKey && !f.key) {
                    waitChanges++
                    return
                }
                if (f.key) {
                    needKey = false
                    keyWanted = false
                    waitChanges++
                }
            }
            send(f)
        }
    }

    /** Кадр AA и поля вокруг интерфейса: [ширина, высота, поле по ширине, поле по высоте]. */
    @JvmStatic
    fun geometry(): IntArray = intArrayOf(frameWidth(), frameHeight(), HeadUnitScreenConfig.getWidthMargin(), HeadUnitScreenConfig.getHeightMargin())

    private fun frameWidth(): Int = HeadUnitScreenConfig.getNegotiatedWidth()

    private fun frameHeight(): Int = HeadUnitScreenConfig.getNegotiatedHeight()

    /**
     * Кадр касаний с экрана ГУ (поток чтения USB или GAP). ГУ присылает его уже в координатах кадра: пересчитывает
     * пиксели экрана в размер из заголовка видеопакета (libhmi_for_hclink, coordinate_transformation). Учёт пальцев —
     * то, что знает идущий сеанс AA: учёт живёт в пределах показа (start/stop его сбрасывают), а пока AA не
     * готов, пуст — палец, нажатый до начала показа, AA не достанется отпусканием без нажатия.
     */
    @JvmStatic
    fun touch(f: TouchPointers.Frame) {
        val ctx = context ?: return
        if (closed) {
            synchronized(pointers) { pointers.reset() }
            if (f.action == MotionEvent.ACTION_UP) {
                closed = false
                EventLog.add("Экран машины: касание — открываем Android Auto снова")
                showSplash(text(R.string.ol_car_starting))
                ctx.startService(Intent(ctx, AapService::class.java).setAction(AapService.ACTION_START_SELF_MODE))
            }
            return
        }
        val comm = App.provide(ctx).commManager
        val w = frameWidth() - 1
        val h = frameHeight() - 1
        val events = synchronized(pointers) {
            if (comm.connectionState.value !is CommManager.ConnectionState.TransportStarted || carWidth <= 0 || w < 0 || h < 0) {
                pointers.reset()
                return
            }
            pointers.apply(f)
        }
        for (e in events) {
            comm.send(TouchEvent(SystemClock.elapsedRealtime(), e.action, e.actionIndex,
                e.pointers.map { Triple(it.id, it.x.toInt().coerceIn(0, w), it.y.toInt().coerceIn(0, h)) }))
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
