package io.github.queryparams.overtakelink;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.hardware.usb.UsbAccessory;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.PowerManager;
import android.provider.Settings;

import com.andrerinas.openheadunit.App;
import com.andrerinas.openheadunit.BuildConfig;
import com.andrerinas.openheadunit.R;
import com.andrerinas.openheadunit.aap.AapService;
import com.andrerinas.openheadunit.connection.CommManager;

import java.io.Closeable;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.Locale;
import java.util.Timer;
import java.util.TimerTask;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import io.github.queryparams.overtakelink.car.CarScreen;
import io.github.queryparams.overtakelink.car.CarProjection;
import io.github.queryparams.overtakelink.car.DirectProjection;
import io.github.queryparams.overtakelink.gap.GapService;
import io.github.queryparams.overtakelink.link.EventLog;
import io.github.queryparams.overtakelink.link.LinkSession;
import io.github.queryparams.overtakelink.link.OldPackets;
import io.github.queryparams.overtakelink.link.Packets;
import io.github.queryparams.overtakelink.link.ScreenGeometry;

/**
 * Сердце OvertakeLink: держит связь с ГУ по USB Accessory (протокол QDLink), поднимает экран машины
 * (виртуальный дисплей → H.264 → ГУ) и запускает на нём Android Auto через Open Headunit.
 */
public final class OvertakeLinkService extends Service implements LinkSession.Host {
    public static final String ACTION_CONNECT_USB = "io.github.queryparams.overtakelink.CONNECT_USB";
    public static final String ACTION_STOP = "io.github.queryparams.overtakelink.STOP";
    /** Отладка без машины: канал QDLink по TCP к имитатору ГУ на ПК (extra {@link #EXTRA_SIM} = «хост:порт»). */
    public static final String ACTION_CONNECT_SIM = "io.github.queryparams.overtakelink.CONNECT_SIM";
    public static final String EXTRA_SIM = "sim";

    public enum State { WAITING, HANDSHAKE, RUNNING }

    private static final String CHANNEL = "overtakelink";
    /** Общий номер уведомления: его же показывает встроенный приёмник Android Auto (AapService). */
    public static final int NOTIFICATION_ID = 7;
    /** Отдельный канал с высокой важностью: подсказка должна всплыть, пока человек сидит в машине. */
    private static final String ALERT_CHANNEL = "overtakelink-alert";
    private static final int AA_SERVER_OFF_ID = 8;
    /** Подсказка «ГУ не отвечает — переподключите кабель». */
    private static final int HU_SILENT_ID = 9;
    /**
     * Сколько ждать первого ответа ГУ. В норме ГУ отвечает за ~10 мс; молчит — значит, его программа подключения
     * телефона не запущена (телефон вернулся на шину слишком быстро, ещё в режиме аксессуара) и ждёт, пока телефон
     * пропадёт с USB. Помогает только переподключить кабель с паузой.
     */
    private static final long HU_SILENT_MS = 5000;
    /** Больше кадров в очереди к ГУ — уже заметное отставание: пропускаем всё до свежего ключевого кадра. */
    private static final int VIDEO_QUEUE_LIMIT = 3;
    /** Предел очереди и для ключевых кадров (SPS/PPS + IDR): дальше — только память без пользы. */
    private static final int VIDEO_QUEUE_HARD_LIMIT = 30;
    /** Предел всей очереди записи (служебные пакеты тоже): за ним ГУ явно не читает. */
    private static final int OUT_QUEUE_MAX = 500;
    public static final String PREF_VIDEO_MODE = "videoMode";
    /** Сколько секунд ждать, пока прежнее соединение отпустит аксессуар. */
    private static final int OPEN_WAIT_S = 20;
    public static final String VIDEO_DIRECT = "direct";
    public static final String VIDEO_DIRECT_LIGHT = "direct720";
    public static final String VIDEO_DIRECT_SMALL = "direct480";
    public static final String VIDEO_TRANSCODE = "transcode";
    /** По умолчанию — совместимый: проверен в машине; прямой быстрее, но зависит от декодера ГУ. */
    public static final String VIDEO_DEFAULT = VIDEO_TRANSCODE;
    private static final int CAR_DPI = 160;
    /**
     * Битрейт кодировщика. ГУ просит 2,27 Мбит/с, как у родного QDLink, но для текста и карт на 1920x590
     * этого мало; декодер ГУ (уровень 4) принимает до 20 Мбит/с. Ключевой кадр — раз в 2 с, как у родного.
     */
    private static final int BITRATE = 6_000_000;
    /** Нижний предел битрейта «Совместимого» режима, если ГУ просит меньше: ниже картинка заметно портится. */
    private static final int MIN_BITRATE = 2_000_000;
    /** Битрейт кодировщика сейчас: с запуска — BITRATE, после play — как просит ГУ (не ниже MIN_BITRATE). */
    private volatile int encoderBitrate = BITRATE;
    private static final int DEFAULT_KEY_INTERVAL = 2;

    private static volatile OvertakeLinkService instance;
    private static volatile State state = State.WAITING;
    /** null — сервис ещё не запускался: показываем «вставьте кабель». */
    private static volatile String detail;

    /** Очередь записи текущего сеанса: у каждого сеанса своя — поток записи старого не заберёт пакет нового. */
    /** Очередь записи и отбрасывание кадров текущего сеанса: у каждого сеанса свой Outbox. */
    private volatile Outbox outbox = new Outbox();
    /** «Стоп» или остановка сервиса: начатое подключение кабеля не доводить. */
    private volatile boolean stopping;
    /**
     * Команды ГУ (видео, касания, экран, таймеры) — в своём потоке, а не внутри разбора пакета: разбор держит монитор
     * LinkSession, и брать под ним монитор сервиса — путь к взаимной блокировке с отправкой кадров. Порядок сохраняется.
     */
    /**
     * Обязательные команды ГУ (экран, play/stop) — свой поток; повторы сливаются в последнее состояние, так что
     * очередь не растёт, а итог не теряется. Звук и ключевые кадры — в ограниченный {@link #commands}.
     */
    private final java.util.concurrent.ExecutorService critical =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> new Thread(r, "ol-critical"));

    /** Host текущего сеанса: поток ol-critical разбирает обязательные команды только у него. */
    private volatile SessionHost currentHost;
    private final java.util.concurrent.atomic.AtomicBoolean criticalScheduled = new java.util.concurrent.atomic.AtomicBoolean();

    /**
     * В потоке ol-critical не больше одной задачи разбора: экран, затем видео — по последнему состоянию текущего
     * сеанса. Без монитора сервиса: разбор пакета держит монитор LinkSession, и ждать здесь сервис — путь к
     * взаимной блокировке с запуском экрана.
     */
    private void scheduleCritical() {
        if (!criticalScheduled.compareAndSet(false, true)) {
            return;
        }
        try {
            critical.execute(() -> {
                criticalScheduled.set(false);
                SessionHost h = currentHost;
                if (h == null) {
                    return;
                }
                Runnable screen = h.pendingScreen.getAndSet(null);
                if (screen != null) {
                    safely("команда ГУ: экран", screen);
                }
                Runnable video = h.pendingVideo.getAndSet(null);
                if (video != null) {
                    safely("команда ГУ: видео", video);
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // Сервис останавливается.
        }
    }

    /** Предел очереди команд ГУ. */
    private static final int COMMANDS_MAX = 200;
    private long commandsDropped;
    private final java.util.concurrent.ThreadPoolExecutor commands = new java.util.concurrent.ThreadPoolExecutor(1, 1,
            0, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(COMMANDS_MAX), r -> new Thread(r, "ol-commands"),
            (r, pool) -> {
                // Команды копятся быстрее, чем выполняются (перегрузка): новые отбрасываем — память не растёт.
                if (commandsDropped++ % 100 == 0) {
                    EventLog.add("Команды ГУ: очередь полна, команды отбрасываются. Отброшено: " + commandsDropped);
                }
            });


    /** Номер попытки подключения кабеля: устаревшая (кабель переподключили) не открывает и не гасит сервис. */
    private volatile int connectAttempt;
    private PowerManager.WakeLock wakeLock;
    private Closeable link;
    private InputStream in;
    private OutputStream out;
    private Timer timer;
    private volatile LinkSession session;
    private volatile boolean connected;
    /** Сигнал Android «машина отсоединена» — пока есть USB-соединение. */
    private BroadcastReceiver detachReceiver;
    private volatile long connectedAt;
    /** Первая запись текущего сеанса в USB завершилась. */
    private volatile boolean firstWriteDone;
    /** После отброшенных кадров ждём ключевой: P-кадры без предшественников ломают картинку. */
    private volatile boolean direct;
    /** Кадр AA в прямом режиме — для строки состояния. */
    private volatile DirectProjection.Size directSize;
    /** Размер видео из команды play ГУ — для строки статуса. */
    private volatile int videoWidth, videoHeight;

    public static State state() {
        return state;
    }

    public static String detail(Context c) {
        String d = detail;
        return d != null ? d : c.getString(R.string.ol_plug_cable);
    }

    public static String title(Context c) {
        return c.getString(state == State.RUNNING ? R.string.ol_state_running
                : state == State.HANDSHAKE ? R.string.ol_state_handshake : R.string.ol_state_waiting);
    }

    public static long connectedAt() {
        OvertakeLinkService s = instance;
        return s == null ? 0 : s.connectedAt;
    }

    private void setState(State s, String d) {
        state = s;
        detail = d;
        updateNotification();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        EventLog.init(this);
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "OvertakeLink", NotificationManager.IMPORTANCE_LOW));
        nm.createNotificationChannel(new NotificationChannel(ALERT_CHANNEL, getString(R.string.ol_alerts), NotificationManager.IMPORTANCE_HIGH));
        startForegroundWithMicrophone();
        wakeLock = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "overtakelink:link");
        wakeLock.setReferenceCounted(false);
        EventLog.add("OvertakeLink " + BuildConfig.VERSION_NAME + " (" + BuildConfig.GIT_SHA + ") запущен. Телефон: " + Build.MANUFACTURER + " " + Build.MODEL + ", Android " + Build.VERSION.RELEASE);
    }

    /**
     * Микрофон для Ассистента: «разрешено во время использования» в фоне работает, только если
     * foreground-сервис взял тип microphone, пока приложение было на экране. Сервис стартует из
     * UsbAttachActivity, то есть именно тогда; доступ получает весь процесс, включая приёмник AA.
     */
    private void startForegroundWithMicrophone() {
        int types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE;
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            types |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
        }
        try {
            startForeground(NOTIFICATION_ID, buildNotification(), types);
        } catch (RuntimeException e) {
            EventLog.add("Микрофон для Ассистента недоступен: " + e.getMessage());
            startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        }
    }

    private Notification buildNotification() {
        return notification(this);
    }

    public static Notification notification(Context c) {
        // Уведомление общее с приёмником AA: пока работает вывод в GAP Smart Tool — показывать его, а не кабельное.
        if (GapService.state() != State.WAITING) {
            return GapService.notification(c);
        }
        PendingIntent open = PendingIntent.getActivity(c, 0, new Intent(c, OvertakeLinkActivity.class),
                PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(c, 1, new Intent(c, OvertakeLinkService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(c, CHANNEL)
                .setContentTitle("OvertakeLink · " + title(c))
                .setContentText(detail(c))
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(null, c.getString(R.string.ol_stop), stop).build())
                .setOngoing(true)
                .build();
    }

    private void updateNotification() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) {
            nm.notify(NOTIFICATION_ID, buildNotification());
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_CONNECT_USB.equals(action)) {
            // В своём потоке: открытие занятого аксессуара повторяется несколько секунд.
            // Номер попытки и снятие «Стопа» — здесь, в главном потоке: «Стоп», пришедший после, их не потеряет.
            int attempt;
            synchronized (this) {
                attempt = ++connectAttempt;
                stopping = false;
            }
            new Thread(() -> safely("подключение USB", () -> connectUsb(attempt)), "ol-usb").start();
        } else if (ACTION_CONNECT_SIM.equals(action) && BuildConfig.SIM_LINK) {
            String target = intent.getStringExtra(EXTRA_SIM);
            new Thread(() -> safely("подключение к имитатору ГУ", () -> connectSim(target)), "ol-sim").start();
        } else if (ACTION_STOP.equals(action)) {
            stopping = true;
            disconnect("остановлено пользователем");
            stopSelf();
        } else if (!connected) {
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        stopping = true;
        disconnect("сервис остановлен");
        // Ожидающие команды закрытого сеанса не нужны.
        commands.shutdownNow();
        critical.shutdownNow();
        if (wakeLock.isHeld()) {
            wakeLock.release();
        }
        instance = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private static void safely(String what, Runnable r) {
        try {
            r.run();
        } catch (Throwable t) {
            EventLog.error(what, t);
        }
    }

    // ---------- USB ----------

    /**
     * Подключение кабеля. Если старое соединение ещё числится живым — кабель переподключили, а чтение старого
     * не заметило обрыва (на части телефонов оно висит до минуты): старое закрываем и подключаемся заново.
     * Пока висящее чтение держит аксессуар, Android не даёт открыть его снова — пробуем до {@link #OPEN_WAIT_S} с.
     */
    private void connectUsb(int attempt) {
        EventLog.newConnection();
        EventLog.add("USB: Android сообщил о подключении машины");
        synchronized (this) {
            if (attempt != connectAttempt || stopping) {
                return;
            }
            if (connected) {
                EventLog.add("USB: прежнее соединение ещё числится живым — машина подключилась заново, закрываем прежнее");
                close("переподключение кабеля", session, false);
            }
        }
        UsbManager usb = getSystemService(UsbManager.class);
        UsbAccessory[] list = usb.getAccessoryList();
        if (list == null || list.length == 0) {
            EventLog.add("USB: аксессуара машины нет в списке Android");
            connectFailed(attempt, getString(R.string.ol_car_not_found));
            return;
        }
        UsbAccessory acc = list[0];
        EventLog.add("USB: аксессуар «" + acc.getManufacturer() + " / " + acc.getModel() + "», версия " + acc.getVersion());
        if (!usb.hasPermission(acc)) {
            EventLog.add("USB: нет разрешения на аксессуар");
            connectFailed(attempt, getString(R.string.ol_no_usb_permission));
            return;
        }
        ParcelFileDescriptor pfd = null;
        int tries = 0;
        // Ждём без блокировки сервиса: «Стоп» в это время не должен подвешивать главный поток.
        while (pfd == null && tries < OPEN_WAIT_S && !stopping && attempt == connectAttempt) {
            tries++;
            pfd = usb.openAccessory(acc);
            if (pfd == null) {
                if (tries == 1) {
                    EventLog.add("USB: аксессуар занят — ждём, пока его отпустит прежнее соединение (до " + OPEN_WAIT_S + " с)");
                }
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }
        if (pfd == null) {
            if (attempt != connectAttempt) {
                return;
            }
            if (stopping) {
                stopAaIfOwner();
                return;
            }
            EventLog.add("USB: аксессуар так и не открылся за " + OPEN_WAIT_S + " с");
            connectFailed(attempt, getString(R.string.ol_usb_busy));
            return;
        }
        if (tries > 1) {
            EventLog.add("USB: аксессуар открыт с попытки " + tries);
        }
        synchronized (this) {
            if (attempt != connectAttempt) {
                // Кабель переподключили — подключением занимается новая попытка.
                try {
                    pfd.close();
                } catch (IOException ignored) {
                }
                return;
            }
            if (stopping) {
                // Пока ждали аксессуар, нажали «Стоп» или сервис останавливается — не подключаемся.
                try {
                    pfd.close();
                } catch (IOException ignored) {
                }
                stopAaIfOwner();
                return;
            }
            open(pfd, new FileInputStream(pfd.getFileDescriptor()), new FileOutputStream(pfd.getFileDescriptor()));
            watchDetach();
        }
    }

    /**
     * Подключиться не вышло: AapService уже поднят UsbAttachActivity — его тоже остановить, иначе он
     * останется работать без машины.
     */
    /**
     * Остановить Android Auto, только если этот экземпляр сервиса — действующий (или никакого нет): после «Стопа»
     * Android мог уже создать новый экземпляр для нового подключения, и AA теперь его.
     */
    private void stopAaIfOwner() {
        OvertakeLinkService live = instance;
        if (live == null || live == this) {
            stopService(new Intent(this, AapService.class));
        }
    }

    private void connectFailed(int attempt, String why) {
        synchronized (this) {
            // Устаревшая попытка или уже идёт сеанс — его не трогаем.
            if (attempt != connectAttempt || connected) {
                return;
            }
            stopAaIfOwner();
            idle(why);
        }
    }

    /** Отсоединение кабеля — сразу закрываем соединение, не дожидаясь ошибки чтения. */
    private void watchDetach() {
        LinkSession s = session;
        detachReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                if (Intent.ACTION_POWER_DISCONNECTED.equals(i.getAction())) {
                    EventLog.add("Android: пропало питание USB");
                    return;
                }
                new Thread(() -> close("Android: машина отсоединена", s, true), "ol-detach").start();
            }
        };
        // Пропажа питания — только в журнал: на части телефонов она бывает без обрыва связи (защита батареи), а
        // настоящее отсоединение приходит отдельно.
        IntentFilter f = new IntentFilter(UsbManager.ACTION_USB_ACCESSORY_DETACHED);
        f.addAction(Intent.ACTION_POWER_DISCONNECTED);
        registerReceiver(detachReceiver, f, Context.RECEIVER_NOT_EXPORTED);
    }

    private synchronized void connectSim(String target) {
        if (connected) {
            EventLog.add("Имитатор ГУ: прежнее соединение ещё числится живым — закрываем прежнее");
            close("переподключение", session, false);
        }
        int colon = target.lastIndexOf(':');
        Socket socket;
        try {
            socket = new Socket(target.substring(0, colon), Integer.parseInt(target.substring(colon + 1)));
            socket.setTcpNoDelay(true);
            EventLog.add("Имитатор ГУ: подключились к " + target);
            open(socket, socket.getInputStream(), socket.getOutputStream());
        } catch (IOException e) {
            idle(getString(R.string.ol_sim_unavailable, target, e.getMessage()));
        }
    }

    private void open(Closeable l, InputStream i, OutputStream o) {
        wakeLock.acquire();
        link = l;
        in = i;
        out = o;
        SessionHost h = new SessionHost();
        LinkSession s = new LinkSession(h);
        h.owner = s;
        Outbox box = new Outbox();
        h.box = box;
        outbox = box;
        session = s;
        currentHost = h;
        connected = true;
        connectedAt = System.currentTimeMillis();
        firstWriteDone = false;
        setState(State.HANDSHAKE, getString(R.string.ol_waiting_hu));
        timer = new Timer("ol-timer");
        new Thread(() -> readLoop(s, i), "ol-reader").start();
        new Thread(() -> writeLoop(s, o, box.q), "ol-writer").start();
        timer.schedule(new TimerTask() {
            private boolean silentShown;
            private boolean answered;

            @Override
            public void run() {
                safely("таймер", s::tick);
                if (answered || s != session) {
                    return;
                }
                if (s.proto() != LinkSession.Proto.UNKNOWN) {
                    // ГУ ответило: подсказка прошлого молчания (этого или прежнего подключения) больше не нужна.
                    answered = true;
                    getSystemService(NotificationManager.class).cancel(HU_SILENT_ID);
                } else if (!silentShown && System.currentTimeMillis() - connectedAt >= HU_SILENT_MS) {
                    silentShown = true;
                    huSilent(s);
                }
            }
        }, 0, 3000);
    }

    private void disconnect(String reason) {
        close(reason, session, true);
    }

    /**
     * Закрыть соединение s, если оно ещё текущее: потоки прежнего соединения, очнувшись после
     * переподключения, не должны закрыть новое. idle — машины больше нет, сервис можно остановить.
     */
    private void close(String reason, LinkSession s, boolean idle) {
        // Ресурсы именно этого сеанса — забираем под блокировкой: переподключение, пришедшее во время очистки,
        // откроет новые, и закрывать их тут нельзя.
        Closeable l;
        synchronized (this) {
            if (!connected || s != session) {
                return;
            }
            connected = false;
            l = link;
            link = null;
            in = null;
            out = null;
            // Под той же блокировкой, что и запуск проекции в carScreen(): экран не запустится после очистки.
            tearDownCar();
            EventLog.add("USB: соединение закрыто — " + reason);
            // Подсказка «ГУ не видит телефон» — про этот сеанс: переподключение или «Стоп» её снимают.
            getSystemService(NotificationManager.class).cancel(HU_SILENT_ID);
            if (timer != null) {
                timer.cancel();
                timer = null;
            }
            if (detachReceiver != null) {
                unregisterReceiver(detachReceiver);
                detachReceiver = null;
            }
        }
        try {
            if (l != null) {
                l.close();
            }
        } catch (Exception ignored) {
        }
        // Выгрузка — чтение и запись всего журнала: не на главном потоке («Стоп» из уведомления).
        new Thread(() -> {
            try {
                EventLog.add("Журнал выгружен: " + EventLog.export(this));
            } catch (Exception e) {
                EventLog.error("выгрузка журнала", e);
            }
        }, "ol-log-export").start();
        if (idle) {
            synchronized (this) {
                // Пока чистили, кабель вставили снова — новый сеанс уже идёт, сервис не останавливаем.
                if (!connected) {
                    idle(getString(R.string.ol_plug_cable));
                }
            }
        }
    }

    /**
     * Машины нет — сервису незачем жить: без него телефон спокойно спит. Следующее подключение
     * кабеля снова запустит его через UsbAttachActivity.
     */
    private void idle(String why) {
        if (wakeLock.isHeld()) {
            wakeLock.release();
        }
        setState(State.WAITING, why);
        stopSelf();
    }

    /**
     * Читаем строго по 512 байт, как родной QDLink: ГУ шлёт пакеты ровно по 512 байт без признака конца
     * передачи, и чтение большим буфером не завершается, пока буфер не наполнится (ответы — через ~30 с).
     */
    private void readLoop(LinkSession s, InputStream i) {
        byte[] buf = new byte[OldPackets.BLOCK];
        try {
            while (connected && s == session) {
                int n = i.read(buf, 0, OldPackets.BLOCK);
                if (n < 0) {
                    close("ГУ закрыло канал", s, true);
                    return;
                }
                s.onBytes(buf, n);
            }
        } catch (IOException e) {
            close("кабель отключён (" + e.getMessage() + ")", s, true);
        } catch (Throwable t) {
            EventLog.error("разбор данных от ГУ", t);
            close("сбой разбора", s, true);
        }
    }

    /** Пишет отдельный поток: поток чтения никогда не ждёт записи (иначе обе стороны зависают). */
    private void writeLoop(LinkSession s, OutputStream o, LinkedBlockingQueue<byte[]> q) {
        try {
            boolean first = true;
            while (connected && s == session) {
                byte[] p = q.poll(500, TimeUnit.MILLISECONDS);
                if (p != null) {
                    long t = System.currentTimeMillis();
                    o.write(p);
                    if (first) {
                        // Запись в аксессуар, который ГУ не читает, может не завершиться — это видно по отсутствию строки.
                        first = false;
                        long ms = System.currentTimeMillis() - t;
                        synchronized (this) {
                            // Прежний сеанс, дописавший после переподключения, не отмечает новый.
                            if (connected && s == session) {
                                firstWriteDone = true;
                                EventLog.add("USB: первая запись в машину прошла за " + ms + " мс");
                            }
                        }
                    }
                }
            }
        } catch (IOException e) {
            close("ошибка записи: " + e.getMessage(), s, true);
        } catch (InterruptedException ignored) {
        }
    }

    // ---------- LinkSession.Host ----------

    @Override
    public void send(byte[] packet) {
        if (connected) {
            outbox.offer(packet);
        }
    }

    private String videoMode() {
        return getSharedPreferences("overtakelink", MODE_PRIVATE).getString(PREF_VIDEO_MODE, VIDEO_DEFAULT);
    }

    /** Размер кадра прямого режима по выбору видео (прямой 1080p / 720p / 480p). */
    public static DirectProjection.Size directSize(String mode) {
        return VIDEO_DIRECT_LIGHT.equals(mode) ? DirectProjection.Size.LIGHT
                : VIDEO_DIRECT_SMALL.equals(mode) ? DirectProjection.Size.SMALL : DirectProjection.Size.FULL;
    }

    /**
     * ГУ сообщило размер экрана (через ~12 мс после подключения): сразу поднимаем экран машины
     * и Android Auto, не дожидаясь команды play: AA успевает загрузиться, пока ГУ договаривается.
     */
    @Override
    public void carScreen(int carWidth, int carHeight) {
        carScreenFor(session, carWidth, carHeight);
    }

    /** Экран машины для сеанса owner: запуск, начатый устаревшим сеансом, не подменит показ нового. */
    void carScreenFor(LinkSession owner, int carWidth, int carHeight) {
        if (owner != session || CarScreen.current() != null) {
            return;
        }
        getSharedPreferences("overtakelink", MODE_PRIVATE).edit().putString("carScreen", carWidth + "×" + carHeight).apply();
        Packets.Video v = new Packets.Video();
        v.width = carWidth;
        v.height = carHeight;
        v.frameRate = CarScreen.FRAME_RATE;
        v.bitRate = BITRATE;
        encoderBitrate = BITRATE;
        v.frameInterval = DEFAULT_KEY_INTERVAL;
        LinkSession s = owner;
        // Колбэки видео пишут в очередь своего сеанса: кадр старого кодировщика в новый сеанс не попадёт.
        Outbox box;
        synchronized (this) {
            if (owner != session) {
                return;
            }
            box = outbox;
        }
        String mode = videoMode();
        // Режим — локально: в поля direct/directSize — только вместе с публикацией экрана, после проверки сеанса.
        boolean isDirect = !VIDEO_TRANSCODE.equals(mode);
        DirectProjection.Size size = directSize(mode);
        CarScreen screen = new CarScreen(carWidth, carHeight, CAR_DPI, new CarScreen.Sink() {
            @Override
            public void onEncoderFailed(String why) {
                // Картинка на ГУ застыла бы навсегда, а статус врал бы «работает»: закрываем показ с причиной.
                new Thread(() -> {
                    synchronized (OvertakeLinkService.this) {
                        // Отказ старого кодировщика не должен закрыть новый сеанс или переписать его статус.
                        if (!connected || s != session) {
                            return;
                        }
                        close("сбой кодировщика экрана машины: " + why, s, true);
                        setState(State.WAITING, getString(R.string.ol_video_failed, why));
                    }
                }, "ol-encoder-failed").start();
            }

            @Override
            public void onCodecConfig(byte[] spsPps) {
                if (s.videoPlaying()) {
                    if (box.acceptVideo(true)) {
                        box.offer(s.videoPacket(spsPps, 0, spsPps.length));
                    }
                }
            }

            @Override
            public void onFrame(byte[] data, int off, int len, boolean keyFrame) {
                if (s.videoPlaying()) {
                    if (box.acceptVideo(keyFrame)) {
                        box.offer(s.videoPacket(data, off, len));
                    }
                }
            }
        });
        try {
            if (isDirect) {
                screen.startDirect(this);
            } else {
                screen.start(this, v);
            }
        } catch (Exception e) {
            EventLog.error("запуск экрана машины", e);
            screen.stop();
            setState(State.HANDSHAKE, getString(isDirect ? R.string.ol_video_failed_direct : R.string.ol_video_failed, e.getMessage()));
            return;
        }
        synchronized (this) {
            if (!connected || s != session || CarScreen.current() != null) {
                // Соединение закрылось, пока экран запускался (или экран уже есть у нового сеанса) — гасим свой.
                EventLog.add("Экран машины: соединение закрылось во время запуска — экран гасим");
                screen.stop();
                // Android Auto общий: если уже идёт новый сеанс (в этом или новом экземпляре сервиса) — не трогаем.
                if (!connected) {
                    stopAaIfOwner();
                }
                return;
            }
            screen.publish();
            direct = isDirect;
            directSize = size;
            if (isDirect) {
                s.setFrameSize(size.getWidth(), size.getHeight());
                DirectProjection.start(this, carWidth, carHeight, size, (data, off, len, keyFrame) -> {
                    if (s.videoPlaying()) {
                        if (box.acceptVideo(keyFrame)) {
                        box.offer(s.videoPacket(data, off, len));
                    }
                    }
                });
            } else {
                CarProjection.show(this, screen);
            }
            sendAap(AapService.ACTION_START_SELF_MODE);
        }
        setState(State.HANDSHAKE, getString(R.string.ol_aa_starting));
        NotificationManager nm = getSystemService(NotificationManager.class);
        retryAaUntilStarted(nm, s);
        if (AaServer.isListening()) {
            nm.cancel(AA_SERVER_OFF_ID);
        } else {
            // Обычно после перезагрузки телефона: AA не запускает свой сервер сам.
            EventLog.add("Сервер Android Auto выключен — показываем подсказку на экране машины и телефона");
            String howTo = getString(R.string.ol_how_to_start);
            if (isDirect) {
                DirectProjection.notice(howTo);
            } else {
                CarProjection.notice(howTo);
            }
            setState(State.HANDSHAKE, howTo.replace('\n', ' '));
            notifyAaServerOff(nm);
        }
    }

    /**
     * Сервер AA бывает выключен или ещё не поднят (HyperOS выгружает AA в фоне), и тогда первая попытка
     * подключить Android Auto заканчивается ошибкой. Пока кабель подключён и AA ни разу не показался,
     * раз в 3 с повторяем попытку, как только сервер доступен, — без переподключения кабеля. После первого
     * успешного подключения не вмешиваемся: выход из AA — решение пользователя.
     */
    private void retryAaUntilStarted(NotificationManager nm, LinkSession mine) {
        Timer t = timer;
        if (t == null) {
            return;
        }
        t.schedule(new TimerTask() {
            @Override
            public void run() {
                safely("повтор подключения Android Auto", () -> {
                    CommManager.ConnectionState aa = App.Companion.provide(OvertakeLinkService.this)
                            .getCommManager().getConnectionState().getValue();
                    if (!connected) {
                        cancel();
                        return;
                    }
                    if (aa instanceof CommManager.ConnectionState.TransportStarted) {
                        cancel();
                        // Пока AA запускался, статус перезаписывался «запускаем Android Auto»; видео уже идёт — вернуть.
                        LinkSession s = session;
                        if (s != null && s.videoPlaying()) {
                            setState(State.RUNNING, runningText());
                        }
                        return;
                    }
                    boolean failed = aa instanceof CommManager.ConnectionState.Error
                            || aa instanceof CommManager.ConnectionState.Disconnected;
                    if (failed && AaServer.isListening()) {
                        synchronized (OvertakeLinkService.this) {
                            // «Стоп» или отключение между проверкой выше и командой — AA без машины не поднимаем.
                            if (!connected || session != mine) {
                                cancel();
                                return;
                            }
                            EventLog.add("Сервер Android Auto доступен — подключаем Android Auto снова");
                            nm.cancel(AA_SERVER_OFF_ID);
                            sendAap(AapService.ACTION_START_SELF_MODE);
                            setState(State.HANDSHAKE, getString(R.string.ol_aa_starting));
                        }
                    }
                });
            }
        }, 3000, 3000);
    }

    /** ГУ не отвечает на подключение: подсказка на телефоне (в машине показать нечем — ГУ телефон не слушает). */
    private synchronized void huSilent(LinkSession s) {
        // Таймер мог сработать, пока сеанс закрывали или открывали новый: подсказка — только живому текущему.
        if (!connected || s != session || s.proto() != LinkSession.Proto.UNKNOWN) {
            return;
        }
        EventLog.add("ГУ не отвечает " + HU_SILENT_MS / 1000 + " с после подключения — его программа подключения не запущена;"
                + " поможет только переподключить кабель с паузой 5–10 с" + (firstWriteDone ? "" : ". Первая запись в USB не завершилась"));
        String text = getString(R.string.ol_hu_silent_text);
        // И в статусе: уведомления могут быть запрещены.
        setState(State.HANDSHAKE, getString(R.string.ol_hu_silent) + ". " + text);
        getSystemService(NotificationManager.class).notify(HU_SILENT_ID, new Notification.Builder(this, ALERT_CHANNEL)
                .setContentTitle(getString(R.string.ol_hu_silent))
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setAutoCancel(true)
                .build());
    }

    /** Подсказка на телефоне: тап открывает настройки Android Auto, где включается сервер. */
    private void notifyAaServerOff(NotificationManager nm) {
        PendingIntent open = PendingIntent.getActivity(this, 2,
                AaServer.settingsIntent().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE);
        String text = getString(R.string.ol_aa_server_off_text);
        nm.notify(AA_SERVER_OFF_ID, new Notification.Builder(this, ALERT_CHANNEL)
                .setContentTitle(getString(R.string.ol_aa_server_off))
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build());
    }

    /** Команда play от ГУ: начинаем отдавать кадры, первым — ключевой с SPS/PPS. */
    @Override
    public void startVideo(Packets.Video v) {
        startVideoFor(session, v);
    }

    /** Команда play от сеанса owner; от устаревшего (дочитан после переподключения) — без последствий. */
    void startVideoFor(LinkSession owner, Packets.Video v) {
        if (owner != session || !connected) {
            return;
        }
        CarScreen screen = CarScreen.current();
        if (screen == null) {
            carScreenFor(owner, v.width, v.height);
            screen = CarScreen.current();
            if (screen == null) {
                owner.videoStopped();
                return;
            }
        }
        synchronized (this) {
            if (owner != session || !connected) {
                return;
            }
            // Показ начинается с SPS/PPS и ключевого кадра: обычные кадры до них декодер ГУ показать не может.
            outbox.waitKeyFrame = true;
            requestKeyFrame();
            videoWidth = v.width;
            videoHeight = v.height;
            // «Совместимый» режим: кодировщик — на битрейт, который просит ГУ (меньше нагрев и загрузка USB), но не
            // ниже MIN_BITRATE.
            if (!direct && v.bitRate > 0) {
                int target = Math.max(MIN_BITRATE, v.bitRate);
                if (target != encoderBitrate) {
                    screen.setBitrate(target);
                    encoderBitrate = target;
                }
            }
            EventLog.add("ГУ включило видео (" + (direct ? "прямой режим" : "перекодирование") + "): " + v.width + "x" + v.height
                    + ", ГУ просит " + v.bitRate + " бит/с" + (direct ? "" : ", кодировщик — " + encoderBitrate));
            // «Работает» — только когда Android Auto уже на связи; иначе статус ожидания (например, «включите сервер AA»)
            // остаётся, а RUNNING выставит повтор подключения AA, как только тот поднимется.
            if (App.Companion.provide(this).getCommManager().getConnectionState().getValue()
                    instanceof CommManager.ConnectionState.TransportStarted) {
                setState(State.RUNNING, runningText());
            }
        }
    }

    private String runningText() {
        return direct ? getString(R.string.ol_running_direct, directSize.getHeight())
                : getString(R.string.ol_running_transcode, videoWidth, videoHeight, encoderBitrate / 1e6);
    }

    /** ГУ остановило видео: перестаём слать кадры, Android Auto продолжает работать. */
    @Override
    public void stopVideo() {
        if (connected) {
            setState(State.HANDSHAKE, getString(R.string.ol_video_paused));
        }
    }

    /** Полная остановка экрана машины и Android Auto (кабель вынут или «Стоп»). */
    private void tearDownCar() {
        CarScreen screen = CarScreen.current();
        if (screen == null) {
            // Экрана машины ещё нет (ГУ не прислало размеры), а Android Auto уже поднят UsbAttachActivity.
            stopService(new Intent(this, AapService.class));
            return;
        }
        DirectProjection.stop();
        CarProjection.close();
        // Штатная остановка приёмника AA: прощание с AA (ByeBye), выключение self-mode, остановка сервиса.
        sendAap(AapService.ACTION_STOP_SERVICE);
        screen.stop();
    }

    /** Команда уже работающему AapService (он поднят в foreground из UsbAttachActivity). */
    private void sendAap(String action) {
        try {
            startService(new Intent(this, AapService.class).setAction(action));
        } catch (Exception e) {
            EventLog.error("команда Android Auto " + action, e);
        }
    }

    @Override
    public void requestKeyFrame() {
        if (direct) {
            DirectProjection.requestKeyFrame();
            return;
        }
        CarScreen screen = CarScreen.current();
        if (screen != null) {
            screen.requestKeyFrame();
            CarProjection.redraw();
        }
    }

    @Override
    public void touch(int action, float x, float y) {
        if (direct) {
            DirectProjection.touch(action, x, y);
        } else {
            CarProjection.touch(action, x, y);
        }
    }

    @Override
    public void caption(String text) {
    }

    @Override
    public ScreenGeometry geometry(int carWidth, int carHeight) {
        return ScreenGeometry.of(this, carWidth, carHeight);
    }

    @Override
    public String btAddress() {
        String mac = Settings.Secure.getString(getContentResolver(), "bluetooth_address");
        return mac == null || mac.isEmpty() ? "00:00:00:00:00:00" : mac.toUpperCase(Locale.US);
    }

    @Override
    public String phoneUuid() {
        SharedPreferences p = getSharedPreferences("overtakelink", Context.MODE_PRIVATE);
        String id = p.getString("uuid", null);
        if (id == null) {
            id = UUID.randomUUID().toString();
            p.edit().putString("uuid", id).apply();
        }
        return id;
    }

    @Override
    public String phoneBrand() {
        return Build.MANUFACTURER;
    }

    @Override
    public String phoneModel() {
        return Build.MODEL;
    }

    @Override
    public int sdk() {
        return Build.VERSION.SDK_INT;
    }

    @Override
    public void schedule(long delayMs, Runnable task) {
        Timer t = timer;
        if (t == null) {
            return;
        }
        try {
            t.schedule(new TimerTask() {
                @Override
                public void run() {
                    safely("отложенная задача", task);
                }
            }, delayMs);
        } catch (IllegalStateException ignored) {
        }
    }

    @Override
    public void scheduleRepeating(long delayMs, long periodMs, Runnable task) {
        Timer t = timer;
        if (t == null) {
            return;
        }
        try {
            t.schedule(new TimerTask() {
                @Override
                public void run() {
                    safely("повторяющаяся задача", task);
                }
            }, delayMs, periodMs);
        } catch (IllegalStateException ignored) {
        }
    }

    /**
     * Host конкретного сеанса: команды от сеанса, который уже не текущий (старый reader дочитал пакет после
     * переподключения), отбрасываются — иначе он отправил бы ответ в очередь нового или перезапустил его показ.
     * Справочные запросы (размеры, модель телефона) не зависят от сеанса и проходят всегда.
     */
    private final class SessionHost implements LinkSession.Host {
        volatile LinkSession owner;
        /** Очередь записи именно этого сеанса: ответ устаревшего сеанса в очередь нового не попадёт. */
        volatile Outbox box;

        private boolean live() {
            return owner == session && connected;
        }

        @Override
        public void send(byte[] packet) {
            Outbox b = box;
            if (b != null && connected) {
                b.offer(packet);
            }
        }

        /** Команда — в поток команд; от устаревшего сеанса выполнится вхолостую. */
        private void command(Runnable r) {
            try {
                commands.execute(() -> safely("команда ГУ", r));
            } catch (java.util.concurrent.RejectedExecutionException ignored) {
                // Сервис останавливается (или очередь полна — учтено в обработчике).
            }
        }

        /** Обязательная команда (экран или play/stop): хранится последняя, выполнится обязательно. */
        /** Обязательные команды этого сеанса: последняя команда экрана и последняя play/stop. */
        final java.util.concurrent.atomic.AtomicReference<Runnable> pendingScreen = new java.util.concurrent.atomic.AtomicReference<>();
        final java.util.concurrent.atomic.AtomicReference<Runnable> pendingVideo = new java.util.concurrent.atomic.AtomicReference<>();

        /**
         * Слоты свои у каждого сеанса: устаревший сеанс пишет в свои, их никто не разберёт, — команду текущего он
         * вытеснить не может. Без блокировок.
         */
        private void criticalCommand(java.util.concurrent.atomic.AtomicReference<Runnable> slot, Runnable r) {
            slot.set(r);
            scheduleCritical();
        }

        @Override
        public void startVideo(Packets.Video params) {
            // LinkSession переиспользует этот объект — копия на момент команды.
            Packets.Video v = new Packets.Video();
            v.width = params.width;
            v.height = params.height;
            v.frameRate = params.frameRate;
            v.bitRate = params.bitRate;
            v.frameInterval = params.frameInterval;
            criticalCommand(pendingVideo, () -> startVideoFor(owner, v));
        }

        @Override
        public void stopVideo() {
            criticalCommand(pendingVideo, () -> {
                synchronized (OvertakeLinkService.this) {
                    if (live()) {
                        OvertakeLinkService.this.stopVideo();
                    }
                }
            });
        }

        @Override
        public void requestKeyFrame() {
            command(() -> {
                synchronized (OvertakeLinkService.this) {
                    if (live()) {
                        OvertakeLinkService.this.requestKeyFrame();
                    }
                }
            });
        }

        @Override
        public void touch(int action, float x, float y) {
            // Сразу и по порядку: прямой режим отдаёт касание в AA без очереди, совместимый — в свою упорядоченную
            // очередь со слиянием движений. Через ограниченную очередь команд касания не идут: там они могли
            // потеряться и разорвать жест.
            if (live()) {
                OvertakeLinkService.this.touch(action, x, y);
            }
        }

        @Override
        public void caption(String text) {
            command(() -> {
                synchronized (OvertakeLinkService.this) {
                    if (live()) {
                        OvertakeLinkService.this.caption(text);
                    }
                }
            });
        }

        @Override
        public ScreenGeometry geometry(int carWidth, int carHeight) {
            return OvertakeLinkService.this.geometry(carWidth, carHeight);
        }

        @Override
        public void carScreen(int carWidth, int carHeight) {
            criticalCommand(pendingScreen, () -> carScreenFor(owner, carWidth, carHeight));
        }

        @Override
        public String btAddress() {
            return OvertakeLinkService.this.btAddress();
        }

        @Override
        public String phoneUuid() {
            return OvertakeLinkService.this.phoneUuid();
        }

        @Override
        public String phoneBrand() {
            return OvertakeLinkService.this.phoneBrand();
        }

        @Override
        public String phoneModel() {
            return OvertakeLinkService.this.phoneModel();
        }

        @Override
        public int sdk() {
            return OvertakeLinkService.this.sdk();
        }

        @Override
        public void schedule(long delayMs, Runnable task) {
            command(() -> {
                synchronized (OvertakeLinkService.this) {
                    if (live()) {
                        OvertakeLinkService.this.schedule(delayMs, task);
                    }
                }
            });
        }

        @Override
        public void scheduleRepeating(long delayMs, long periodMs, Runnable task) {
            command(() -> {
                synchronized (OvertakeLinkService.this) {
                    if (live()) {
                        OvertakeLinkService.this.scheduleRepeating(delayMs, periodMs, task);
                    }
                }
            });
        }
    }

    /**
     * Очередь записи одного сеанса с пределами: видео — {@link #VIDEO_QUEUE_LIMIT} (дальше P-кадры до ключевого) и
     * {@link #VIDEO_QUEUE_HARD_LIMIT} (дальше и ключевые); всё вместе — {@link #OUT_QUEUE_MAX}: ГУ перестало читать,
     * а кабель на месте — память не растёт (служебные пакеты сверх предела отбрасываются).
     */
    private final class Outbox {
        final LinkedBlockingQueue<byte[]> q = new LinkedBlockingQueue<>();
        volatile boolean waitKeyFrame;
        private long videoDropped;
        private long dropped;

        void offer(byte[] packet) {
            if (q.size() >= OUT_QUEUE_MAX) {
                if (dropped++ % 100 == 0) {
                    EventLog.add("USB: ГУ не забирает данные — очередь полна, пакеты отбрасываются. Отброшено: " + dropped);
                }
                return;
            }
            q.offer(packet);
        }

        /** Брать ли кадр (до упаковки в пакет: отброшенный кадр не копируется). */
        synchronized boolean acceptVideo(boolean keyFrame) {
            if (q.size() > VIDEO_QUEUE_HARD_LIMIT) {
                // ГУ совсем перестало забирать данные: не копим даже ключевые кадры. Как только очередь
                // разойдётся, поток восстановится со следующего ключевого.
                waitKeyFrame = true;
                if (videoDropped++ % 100 == 0) {
                    EventLog.add("Видео: ГУ не забирает данные, очередь полна — отбрасываем и ключевые кадры. Отброшено: " + videoDropped);
                }
                return false;
            }
            if (keyFrame) {
                waitKeyFrame = false;
            } else if (waitKeyFrame) {
                return false;
            } else if (q.size() > VIDEO_QUEUE_LIMIT) {
                waitKeyFrame = true;
                if (this == outbox) {
                    requestKeyFrame();
                }
                if (videoDropped++ % 100 == 0) {
                    EventLog.add("Видео: ГУ не успевает забирать кадры, пропускаем до ключевого. Отброшено: " + videoDropped);
                }
                return false;
            }
            return true;
        }
    }
}
