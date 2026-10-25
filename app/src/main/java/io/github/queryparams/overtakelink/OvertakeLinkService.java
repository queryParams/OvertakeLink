package io.github.queryparams.overtakelink;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
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
    /** Больше кадров в очереди к ГУ — уже заметное отставание: пропускаем всё до свежего ключевого кадра. */
    private static final int VIDEO_QUEUE_LIMIT = 3;
    public static final String PREF_VIDEO_MODE = "videoMode";
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
    private static final int DEFAULT_KEY_INTERVAL = 2;

    private static volatile OvertakeLinkService instance;
    private static volatile State state = State.WAITING;
    /** null — сервис ещё не запускался: показываем «вставьте кабель». */
    private static volatile String detail;

    private final LinkedBlockingQueue<byte[]> outQueue = new LinkedBlockingQueue<>();
    private PowerManager.WakeLock wakeLock;
    private Closeable link;
    private InputStream in;
    private OutputStream out;
    private Timer timer;
    private volatile LinkSession session;
    private volatile boolean connected;
    private volatile long connectedAt;
    private long videoDropped;
    /** После отброшенных кадров ждём ключевой: P-кадры без предшественников ломают картинку. */
    private volatile boolean waitKeyFrame;
    private volatile boolean direct;
    /** Кадр AA в прямом режиме — для строки состояния. */
    private volatile DirectProjection.Size directSize;

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
        EventLog.add("OvertakeLink запущен. Телефон: " + Build.MANUFACTURER + " " + Build.MODEL + ", Android " + Build.VERSION.RELEASE);
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
            safely("подключение USB", this::connectUsb);
        } else if (ACTION_CONNECT_SIM.equals(action) && BuildConfig.SIM_LINK) {
            String target = intent.getStringExtra(EXTRA_SIM);
            new Thread(() -> safely("подключение к имитатору ГУ", () -> connectSim(target)), "ol-sim").start();
        } else if (ACTION_STOP.equals(action)) {
            disconnect("остановлено пользователем");
            stopSelf();
        } else if (!connected) {
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        disconnect("сервис остановлен");
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

    private synchronized void connectUsb() {
        if (connected) {
            return;
        }
        UsbManager usb = getSystemService(UsbManager.class);
        UsbAccessory[] list = usb.getAccessoryList();
        if (list == null || list.length == 0) {
            idle(getString(R.string.ol_car_not_found));
            return;
        }
        UsbAccessory acc = list[0];
        EventLog.add("USB: аксессуар «" + acc.getManufacturer() + " / " + acc.getModel() + "», версия " + acc.getVersion());
        if (!usb.hasPermission(acc)) {
            idle(getString(R.string.ol_no_usb_permission));
            return;
        }
        ParcelFileDescriptor pfd = usb.openAccessory(acc);
        if (pfd == null) {
            idle(getString(R.string.ol_usb_busy));
            return;
        }
        open(pfd, new FileInputStream(pfd.getFileDescriptor()), new FileOutputStream(pfd.getFileDescriptor()));
    }

    private synchronized void connectSim(String target) {
        if (connected) {
            return;
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
        LinkSession s = new LinkSession(this);
        session = s;
        outQueue.clear();
        videoDropped = 0;
        waitKeyFrame = false;
        connected = true;
        connectedAt = System.currentTimeMillis();
        setState(State.HANDSHAKE, getString(R.string.ol_waiting_hu));
        timer = new Timer("ol-timer");
        new Thread(() -> readLoop(s), "ol-reader").start();
        new Thread(() -> writeLoop(o), "ol-writer").start();
        timer.schedule(new TimerTask() {
            @Override
            public void run() {
                safely("таймер", s::tick);
            }
        }, 0, 3000);
    }

    private void disconnect(String reason) {
        synchronized (this) {
            if (!connected) {
                return;
            }
            connected = false;
            EventLog.add("USB: соединение закрыто — " + reason);
            if (timer != null) {
                timer.cancel();
                timer = null;
            }
        }
        tearDownCar();
        try {
            link.close();
        } catch (Exception ignored) {
        }
        link = null;
        in = null;
        out = null;
        try {
            EventLog.add("Журнал выгружен: " + EventLog.export(this));
        } catch (Exception e) {
            EventLog.error("выгрузка журнала", e);
        }
        idle(getString(R.string.ol_plug_cable));
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
    private void readLoop(LinkSession s) {
        byte[] buf = new byte[OldPackets.BLOCK];
        try {
            while (connected) {
                int n = in.read(buf, 0, OldPackets.BLOCK);
                if (n < 0) {
                    disconnect("ГУ закрыло канал");
                    return;
                }
                s.onBytes(buf, n);
            }
        } catch (IOException e) {
            disconnect("кабель отключён (" + e.getMessage() + ")");
        } catch (Throwable t) {
            EventLog.error("разбор данных от ГУ", t);
            disconnect("сбой разбора");
        }
    }

    /** Пишет отдельный поток: поток чтения никогда не ждёт записи (иначе обе стороны зависают). */
    private void writeLoop(OutputStream o) {
        try {
            while (connected) {
                byte[] p = outQueue.poll(500, TimeUnit.MILLISECONDS);
                if (p != null) {
                    o.write(p);
                }
            }
        } catch (IOException e) {
            disconnect("ошибка записи: " + e.getMessage());
        } catch (InterruptedException ignored) {
        }
    }

    // ---------- LinkSession.Host ----------

    @Override
    public void send(byte[] packet) {
        if (connected) {
            outQueue.offer(packet);
        }
    }

    private void sendVideo(byte[] packet, boolean keyFrame) {
        if (keyFrame) {
            waitKeyFrame = false;
        } else if (waitKeyFrame) {
            return;
        } else if (outQueue.size() > VIDEO_QUEUE_LIMIT) {
            waitKeyFrame = true;
            requestKeyFrame();
            if (videoDropped++ % 100 == 0) {
                EventLog.add("Видео: ГУ не успевает забирать кадры, пропускаем до ключевого. Отброшено: " + videoDropped);
            }
            return;
        }
        send(packet);
    }

    private String videoMode() {
        return getSharedPreferences("overtakelink", MODE_PRIVATE).getString(PREF_VIDEO_MODE, VIDEO_DEFAULT);
    }

    /**
     * ГУ сообщило размер экрана (через ~12 мс после подключения): сразу поднимаем экран машины
     * и Android Auto, не дожидаясь команды play: AA успевает загрузиться, пока ГУ договаривается.
     */
    @Override
    public void carScreen(int carWidth, int carHeight) {
        if (CarScreen.current() != null) {
            return;
        }
        getSharedPreferences("overtakelink", MODE_PRIVATE).edit().putString("carScreen", carWidth + "×" + carHeight).apply();
        Packets.Video v = new Packets.Video();
        v.width = carWidth;
        v.height = carHeight;
        v.frameRate = CarScreen.FRAME_RATE;
        v.bitRate = BITRATE;
        v.frameInterval = DEFAULT_KEY_INTERVAL;
        LinkSession s = session;
        String mode = videoMode();
        direct = !VIDEO_TRANSCODE.equals(mode);
        DirectProjection.Size size = VIDEO_DIRECT_LIGHT.equals(mode) ? DirectProjection.Size.LIGHT
                : VIDEO_DIRECT_SMALL.equals(mode) ? DirectProjection.Size.SMALL : DirectProjection.Size.FULL;
        directSize = size;
        CarScreen screen = new CarScreen(carWidth, carHeight, CAR_DPI, new CarScreen.Sink() {
            @Override
            public void onCodecConfig(byte[] spsPps) {
                if (s.videoPlaying()) {
                    sendVideo(s.videoPacket(spsPps, 0, spsPps.length), true);
                }
            }

            @Override
            public void onFrame(byte[] data, int off, int len, boolean keyFrame) {
                if (s.videoPlaying()) {
                    sendVideo(s.videoPacket(data, off, len), keyFrame);
                }
            }
        });
        try {
            if (direct) {
                screen.startDirect(this);
            } else {
                screen.start(this, v);
            }
        } catch (Exception e) {
            EventLog.error("запуск экрана машины", e);
            screen.stop();
            setState(State.HANDSHAKE, getString(direct ? R.string.ol_video_failed_direct : R.string.ol_video_failed, e.getMessage()));
            return;
        }
        if (direct) {
            s.setFrameSize(size.getWidth(), size.getHeight());
            DirectProjection.start(this, carWidth, carHeight, size, (data, off, len, keyFrame) -> {
                if (s.videoPlaying()) {
                    sendVideo(s.videoPacket(data, off, len), keyFrame);
                }
            });
        } else {
            CarProjection.show(this);
        }
        sendAap(AapService.ACTION_START_SELF_MODE);
        setState(State.HANDSHAKE, getString(R.string.ol_aa_starting));
        NotificationManager nm = getSystemService(NotificationManager.class);
        retryAaUntilStarted(nm);
        if (AaServer.isListening()) {
            nm.cancel(AA_SERVER_OFF_ID);
        } else {
            // Обычно после перезагрузки телефона: AA не запускает свой сервер сам.
            EventLog.add("Сервер Android Auto выключен — показываем подсказку на экране машины и телефона");
            String howTo = getString(R.string.ol_how_to_start);
            if (direct) {
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
    private void retryAaUntilStarted(NotificationManager nm) {
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
                    if (!connected || aa instanceof CommManager.ConnectionState.TransportStarted) {
                        cancel();
                        return;
                    }
                    boolean failed = aa instanceof CommManager.ConnectionState.Error
                            || aa instanceof CommManager.ConnectionState.Disconnected;
                    if (failed && AaServer.isListening()) {
                        EventLog.add("Сервер Android Auto доступен — подключаем Android Auto снова");
                        nm.cancel(AA_SERVER_OFF_ID);
                        sendAap(AapService.ACTION_START_SELF_MODE);
                        setState(State.HANDSHAKE, getString(R.string.ol_aa_starting));
                    }
                });
            }
        }, 3000, 3000);
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
        CarScreen screen = CarScreen.current();
        if (screen == null) {
            carScreen(v.width, v.height);
            screen = CarScreen.current();
            if (screen == null) {
                session.videoStopped();
                return;
            }
        }
        // Показ начинается с SPS/PPS и ключевого кадра: обычные кадры до них декодер ГУ показать не может.
        waitKeyFrame = true;
        requestKeyFrame();
        EventLog.add("ГУ включило видео (" + (direct ? "прямой режим" : "перекодирование") + "): " + v.width + "x" + v.height
                + ", ГУ просит " + v.bitRate + " бит/с" + (direct ? "" : ", кодировщик — " + BITRATE));
        setState(State.RUNNING, direct ? getString(R.string.ol_running_direct, directSize.getHeight())
                : getString(R.string.ol_running_transcode, v.width, v.height, BITRATE / 1e6));
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
    public void micData(byte[] b, int off, int len) {
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
}
