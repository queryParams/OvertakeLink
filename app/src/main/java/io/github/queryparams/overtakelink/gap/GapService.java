package io.github.queryparams.overtakelink.gap;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.IBinder;
import android.os.PowerManager;

import com.andrerinas.openheadunit.App;
import com.andrerinas.openheadunit.R;
import com.andrerinas.openheadunit.aap.AapService;
import com.andrerinas.openheadunit.connection.CommManager;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import io.github.queryparams.overtakelink.AaServer;
import io.github.queryparams.overtakelink.OvertakeLinkActivity;
import io.github.queryparams.overtakelink.OvertakeLinkService;
import io.github.queryparams.overtakelink.car.CarScreen;
import io.github.queryparams.overtakelink.car.DirectProjection;
import io.github.queryparams.overtakelink.car.TouchPointers;
import io.github.queryparams.overtakelink.link.EventLog;

/**
 * Вывод Android Auto в GAP Smart Tool по Wi-Fi. Сервис ждёт GAP Smart Tool на {@link GapProtocol#PORT} (телефон раздаёт
 * точку доступа, ГУ к ней подключено), на его HELLO поднимает экран нужного размера и Android Auto в прямом режиме:
 * H.264 от AA уходит в GAP Smart Tool как есть, касания приходят обратно. Одно подключение за раз: новое заменяет прежнее.
 * Режим включается в «Настройках» вручную; через QDLink по кабелю в это время не подключаемся.
 */
public final class GapService extends Service {
    public static final String ACTION_START = "io.github.queryparams.overtakelink.GAP_START";
    public static final String ACTION_STOP = "io.github.queryparams.overtakelink.GAP_STOP";
    public static final String PREF_OUTPUT = "output";
    /** Качество картинки в GAP: прямой 1080p / 720p / 480p (значения — как у кабельного режима видео). */
    public static final String PREF_GAP_VIDEO = "gapVideo";
    /** Место для AA, которое GAP прислал при последнем подключении («1724×640»). */
    public static final String PREF_GAP_SCREEN = "gapScreen";
    public static final String OUTPUT_USB = "usb";
    public static final String OUTPUT_GAP = "gap";

    private static final String CHANNEL = "overtakelink";
    private static final int NOTIFICATION_ID = 7;
    /** Больше в очереди не держим вовсе: ГУ не забирает данные — соединение рвётся. Видео в ней — не больше окна. */
    private static final int QUEUE_MAX = GapProtocol.VIDEO_WINDOW + 30;
    /** Ожидание подтверждения дольше этого — заминка Wi-Fi, считаем для журнала (мс). */
    private static final long STALL_MS = 200;
    /** Касаний в секунду больше этого живой палец не даёт: поток мусора — соединение рвётся. */
    private static final int TOUCH_PER_SEC = 250;

    private static volatile OvertakeLinkService.State state = OvertakeLinkService.State.WAITING;
    private static volatile String detail;
    /** GAP Smart Tool сейчас подключён (для шагов на «Статусе»). */
    private static volatile boolean clientConnected;

    private volatile boolean running;
    /** Этот запуск поднимал приёмник AA (остановка без запуска — например, «По кабелю» — его не трогает). */
    private boolean startedAa;
    private ServerSocket server;
    private Client client;
    private PowerManager.WakeLock wakeLock;
    private CarScreen screen;
    private final Object lock = new Object();

    /** «Стоп» нажат руками — сама служба при открытии приложения не поднимается, до «Включить». */
    public static boolean stoppedByUser(Context c) {
        return c.getSharedPreferences("overtakelink", MODE_PRIVATE).getBoolean("gapStopped", false);
    }

    public static String videoMode(Context c) {
        return c.getSharedPreferences("overtakelink", MODE_PRIVATE).getString(PREF_GAP_VIDEO, OvertakeLinkService.VIDEO_DIRECT);
    }

    public static boolean selected(Context c) {
        return OUTPUT_GAP.equals(c.getSharedPreferences("overtakelink", MODE_PRIVATE).getString(PREF_OUTPUT, OUTPUT_USB));
    }

    public static boolean clientConnected() {
        return clientConnected;
    }

    public static OvertakeLinkService.State state() {
        return state;
    }

    public static String detail(Context c) {
        String d = detail;
        return d != null ? d : c.getString(R.string.ol_gap_off);
    }

    public static String title(Context c) {
        return c.getString(state == OvertakeLinkService.State.RUNNING ? R.string.ol_gap_running
                : state == OvertakeLinkService.State.HANDSHAKE ? R.string.ol_gap_searching : R.string.ol_gap_off_title);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "OvertakeLink", NotificationManager.IMPORTANCE_LOW));
        wakeLock = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OvertakeLink:gap");
        wakeLock.setReferenceCounted(false);
    }

    /**
     * Микрофон для Ассистента в фоне: тип microphone берётся, пока приложение на экране (старт — из него), как у
     * OvertakeLinkService; доступ получает весь процесс, включая приёмник AA.
     */
    private void startForegroundWithMicrophone() {
        int types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE;
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            types |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
        }
        try {
            startForeground(NOTIFICATION_ID, notification(), types);
        } catch (RuntimeException e) {
            EventLog.add("GAP Smart Tool: микрофон для Ассистента недоступен — " + e.getMessage());
            startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForegroundWithMicrophone();
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            getSharedPreferences("overtakelink", MODE_PRIVATE).edit().putBoolean("gapStopped", true).apply();
            stopSelf();
            return START_NOT_STICKY;
        }
        getSharedPreferences("overtakelink", MODE_PRIVATE).edit().putBoolean("gapStopped", false).apply();
        if (!running) {
            // Android Auto поднимается сразу, пока приложение на экране: из фона Android не даст запустить его службу.
            try {
                startForegroundService(new Intent(this, AapService.class));
            } catch (Exception e) {
                // Без приёмника AA слушать GAP Smart Tool незачем: режим выключается, «Включить» на экране — заново.
                EventLog.error("запуск Android Auto для GAP Smart Tool", e);
                setState(OvertakeLinkService.State.WAITING, getString(R.string.ol_gap_aa_failed));
                stopSelf();
                return START_NOT_STICKY;
            }
            running = true;
            startedAa = true;
            new Thread(this::acceptLoop, "ol-gap-accept").start();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        ServerSocket s;
        synchronized (lock) {
            running = false;
            s = server;
            server = null;
        }
        try {
            if (s != null) {
                s.close();
            }
        } catch (IOException ignored) {
        }
        Client c;
        synchronized (lock) {
            c = client;
        }
        if (c != null) {
            c.close("сервис остановлен");
        }
        // Приёмник AA общий с кабельным режимом: останавливаем, только если поднимал его этот вывод.
        if (startedAa) {
            stopService(new Intent(this, AapService.class));
        }
        // Остановка из-за отказа (порт, Android Auto) уже объявила WAITING с причиной — её оставляем на экране.
        setState(OvertakeLinkService.State.WAITING, state == OvertakeLinkService.State.WAITING ? detail : null);
        wakeLock.release();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void setState(OvertakeLinkService.State s, String d) {
        state = s;
        detail = d;
        if (running) {
            getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, notification());
        }
    }

    private Notification notification() {
        return notification(this);
    }

    /** Уведомление вывода в GAP Smart Tool; его же показывает приёмник AA, пока этот вывод работает. */
    public static Notification notification(Context c) {
        PendingIntent open = PendingIntent.getActivity(c, 0, new Intent(c, OvertakeLinkActivity.class), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(c, 2, new Intent(c, GapService.class).setAction(ACTION_STOP),
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

    private void acceptLoop() {
        ServerSocket s = null;
        try {
            s = new ServerSocket();
            synchronized (lock) {
                // Остановили, пока поток стартовал, — порт не занимаем.
                if (!running) {
                    s.close();
                    return;
                }
                server = s;
            }
            s.setReuseAddress(true);
            s.bind(new InetSocketAddress(GapProtocol.PORT));
            EventLog.add("GAP Smart Tool: ждём подключения на порту " + GapProtocol.PORT);
            setState(OvertakeLinkService.State.HANDSHAKE, getString(R.string.ol_gap_searching_detail));
            while (running) {
                Socket sock = s.accept();
                String foreign = foreignNetwork(sock);
                if (foreign != null) {
                    EventLog.add("GAP Smart Tool: отклонено подключение " + sock.getInetAddress().getHostAddress()
                            + " не из точки доступа — " + foreign);
                    sock.close();
                    continue;
                }
                sock.setTcpNoDelay(true);
                EventLog.add("GAP Smart Tool: подключился " + sock.getInetAddress().getHostAddress());
                Client old;
                Client c = new Client(sock);
                synchronized (lock) {
                    // Сервис остановили, пока принимали, — клиента не публикуем: AA после остановки не поднимется.
                    if (!running) {
                        sock.close();
                        return;
                    }
                    old = client;
                    client = c;
                }
                if (old != null) {
                    old.close("подключился заново");
                }
                c.start();
            }
        } catch (IOException e) {
            if (running) {
                EventLog.add("GAP Smart Tool: порт " + GapProtocol.PORT + " не открылся — " + e.getMessage());
                setState(OvertakeLinkService.State.WAITING, getString(R.string.ol_gap_port_failed, e.getMessage()));
                // «Включить» снова поднимет слушатель с нуля.
                stopSelf();
            }
        } finally {
            if (s != null) {
                try {
                    s.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    /**
     * Подключение пришло не из точки доступа телефона — почему (для журнала); из точки доступа — null. Чужое — с самого
     * телефона и из сети, к которой телефон подключён сам (Wi-Fi кафе, мобильная, VPN): у таких сетей есть выход в
     * интернет. Точку доступа Android 16 тоже показывает сетью («локальная сеть» раздачи), но без выхода в интернет.
     */
    private String foreignNetwork(Socket sock) {
        if (sock.getInetAddress().isLoopbackAddress()) {
            return "с самого телефона";
        }
        try {
            // Адрес отправителя — один из адресов телефона (приложение на телефоне стучится в адрес раздачи).
            if (NetworkInterface.getByInetAddress(sock.getInetAddress()) != null) {
                return "с самого телефона";
            }
            NetworkInterface nif = NetworkInterface.getByInetAddress(sock.getLocalAddress());
            if (nif == null) {
                return "интерфейс не найден";
            }
            ConnectivityManager cm = getSystemService(ConnectivityManager.class);
            for (Network n : cm.getAllNetworks()) {
                LinkProperties lp = cm.getLinkProperties(n);
                if (lp == null || !nif.getName().equals(lp.getInterfaceName())) {
                    continue;
                }
                NetworkCapabilities nc = cm.getNetworkCapabilities(n);
                if (nc == null) {
                    return "сеть " + nif.getName() + " пропала, свойства не прочитались";
                }
                if (nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                    return "сеть телефона " + nif.getName() + " с выходом в интернет: " + nc;
                }
                EventLog.add("GAP Smart Tool: " + nif.getName() + " — сеть без выхода в интернет, считаем точкой доступа: " + nc);
            }
            return null;
        } catch (IOException e) {
            return "интерфейс не определился: " + e.getMessage();
        }
    }

    /** Одно подключение GAP Smart Tool: чтение, запись, экран и Android Auto этого подключения. */
    private final class Client {
        private final Socket sock;
        private final DataOutputStream out;
        private final LinkedBlockingQueue<Object[]> q = new LinkedBlockingQueue<>();
        private volatile boolean open = true;
        /** До первого ключевого кадра обычные кадры не шлём: декодеру GAP не с чего начать. */
        private volatile boolean waitKey = true;
        /** Пакеты видео, ушедшие в GAP без подтверждения; под {@link #window}. */
        private int inFlight;
        private final Object window = new Object();
        /** Заминки (ждали подтверждения дольше STALL_MS) за минуту и самая долгая — для журнала; под window. */
        private int stalls;
        private long longestStall;
        private volatile long lastIn = System.currentTimeMillis();
        /** Что ушло в GAP последним; проверка и отправка — под {@link #geometryLock}: тик и поток видео. */
        private int[] sentGeometry;
        private final Object geometryLock = new Object();
        private String sentStatus;
        private boolean keyAsked;

        Client(Socket sock) throws IOException {
            this.sock = sock;
            out = new DataOutputStream(new BufferedOutputStream(sock.getOutputStream(), 64 << 10));
        }

        void start() {
            new Thread(this::readLoop, "ol-gap-read").start();
            new Thread(this::writeLoop, "ol-gap-write").start();
            new Thread(this::tickLoop, "ol-gap-tick").start();
        }

        /** Сверх QUEUE_MAX не копим: тик увидит переполнение и закроет соединение. false — пакет не принят. */
        boolean send(int type, byte[] data) {
            return open && q.size() < QUEUE_MAX && q.offer(new Object[]{type, data});
        }

        /** Геометрия кадра AA — в GAP, если изменилась с прошлой отправки. */
        private int[] sendGeometry() {
            // Чтение, проверка и отправка — одним шагом: иначе тик отправил бы прочитанную раньше (устаревшую) после
            // свежей или запомнил бы новую, не успев отправить, — и поток видео пустил бы SPS первым.
            synchronized (geometryLock) {
                int[] g = DirectProjection.geometry();
                if (!Arrays.equals(g, sentGeometry)) {
                    sentGeometry = g;
                    EventLog.add("GAP Smart Tool: кадр AA " + g[0] + "x" + g[1] + ", поля " + g[2] + "x" + g[3]);
                    send(GapProtocol.GEOMETRY, GapProtocol.u16s(g));
                }
                return g;
            }
        }

        /**
         * Поток видео AA. Окно GAP занято — ждём подтверждения: пока ждём, OL не подтверждает кадр Android Auto, и тот
         * сам притормаживает при заминке Wi-Fi (как с беспроводной машиной) — кадры не копятся и не выбрасываются.
         */
        void video(byte[] data, int off, int len, boolean key) {
            boolean config = len > 4 && (data[off + 4] & 31) == 7;
            if (waitKey && !key && !config) {
                return;
            }
            if (!awaitWindow()) {
                return;
            }
            if (key) {
                waitKey = false;
            }
            // Декодер GAP настраивается по геометрии на первом SPS: она — раньше SPS, уже после выбора размера AA
            // (прежняя у GAP — от другого качества, а AA мог выбрать запасной размер).
            if (config) {
                sendGeometry();
            }
            byte[] p = new byte[len + 1];
            p[0] = (byte) ((config ? GapProtocol.VIDEO_CONFIG : 0) | (key ? GapProtocol.VIDEO_KEY : 0));
            System.arraycopy(data, off, p, 1, len);
            synchronized (window) {
                if (send(GapProtocol.VIDEO, p)) {
                    inFlight++;
                }
            }
        }

        /** Ждать места в окне; false — подключение закрыто (или GAP не подтверждает кадры — тогда рвём его). */
        private boolean awaitWindow() {
            long start = 0;
            synchronized (window) {
                while (open && inFlight >= GapProtocol.VIDEO_WINDOW) {
                    long now = android.os.SystemClock.elapsedRealtime();
                    if (start == 0) {
                        start = now;
                    } else if (now - start > GapProtocol.SILENCE_MS) {
                        break;
                    }
                    try {
                        window.wait(500);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                }
                if (start != 0) {
                    long waited = android.os.SystemClock.elapsedRealtime() - start;
                    if (waited >= STALL_MS) {
                        stalls++;
                        longestStall = Math.max(longestStall, waited);
                    }
                }
                if (open && inFlight < GapProtocol.VIDEO_WINDOW) {
                    return true;
                }
            }
            if (open) {
                // Только сокет: поток видео держит замок отправки DirectProjection, а close() берёт lock — с новым hello()
                // (lock, затем замок отправки) это взаимоблокировка. Подключение закроет поток чтения.
                EventLog.add("GAP Smart Tool: кадры не подтверждаются " + GapProtocol.SILENCE_MS / 1000 + " с — переподключаемся");
                try {
                    sock.close();
                } catch (IOException ignored) {
                }
            }
            return false;
        }

        private void acked(int n) {
            synchronized (window) {
                // Лишнее подтверждение (из прошлого подключения GAP) окно не расширяет.
                inFlight = Math.max(0, inFlight - n);
                window.notifyAll();
            }
        }

        private void readLoop() {
            try {
                DataInputStream in = new DataInputStream(new BufferedInputStream(sock.getInputStream()));
                long touchSec = 0;
                int touches = 0;
                while (open) {
                    GapProtocol.Packet p = GapProtocol.read(in);
                    lastIn = System.currentTimeMillis();
                    switch (p.type) {
                        case GapProtocol.HELLO:
                            hello(p.u16(0), p.u16(2), p.u16(4));
                            break;
                        case GapProtocol.TOUCH:
                            long sec = lastIn / 1000;
                            touches = sec == touchSec ? touches + 1 : 1;
                            touchSec = sec;
                            if (touches > TOUCH_PER_SEC) {
                                close("слишком много касаний — поток испорчен");
                                return;
                            }
                            TouchPointers.Frame f = GapProtocol.touch(p);
                            if (f == null) {
                                close("испорченное касание");
                                return;
                            }
                            // Касания — только от текущего клиента: заменённый мог дочитать пакет уже в новый показ.
                            // Под замком до конца передачи: новый показ не начнётся между проверкой и касанием.
                            synchronized (lock) {
                                if (client != this || !open) {
                                    return;
                                }
                                DirectProjection.touch(f);
                            }
                            break;
                        case GapProtocol.ACK:
                            acked(p.u16(0));
                            break;
                        case GapProtocol.KEYFRAME:
                            // Обычные кадры не останавливаем: пока ждёт ключевой, GAP отбрасывает их сам, а запоздавший
                            // запрос (GAP уже восстановился) иначе заморозил бы исправную картинку до следующего IDR.
                            DirectProjection.requestKeyFrame();
                            break;
                        default:
                            break;
                    }
                }
            } catch (IOException e) {
                close("связь пропала (" + e.getMessage() + ")");
            }
        }

        private void writeLoop() {
            try {
                while (open) {
                    Object[] p = q.poll(500, TimeUnit.MILLISECONDS);
                    if (p != null) {
                        byte[] d = (byte[]) p[1];
                        GapProtocol.write(out, (Integer) p[0], d, 0, d.length);
                    }
                }
            } catch (IOException e) {
                close("ошибка отправки (" + e.getMessage() + ")");
            } catch (InterruptedException ignored) {
            }
        }

        /** Раз в секунду: сердцебиение, состояние AA для экрана GAP Smart Tool и поля кадра. */
        private void tickLoop() {
            while (open) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    return;
                }
                if (System.currentTimeMillis() - lastIn > GapProtocol.SILENCE_MS) {
                    close("GAP Smart Tool молчит " + GapProtocol.SILENCE_MS / 1000 + " с");
                    return;
                }
                if (q.size() >= QUEUE_MAX) {
                    close("ГУ не принимает данные");
                    return;
                }
                send(GapProtocol.PING, new byte[0]);
                logStalls();
                // Проверка «это текущий клиент после своего HELLO» и действия с общим AA — одним куском под замком:
                // закрытие или замена клиента между ними не дадут старому тику поднять AA или переписать состояние.
                synchronized (lock) {
                    if (screen == null || client != this || !hello || !open || !running) {
                        continue;
                    }
                    tick();
                }
            }
        }

        private long stallsLoggedAt = android.os.SystemClock.elapsedRealtime();

        /** Раз в минуту — сколько раз Android Auto ждал Wi-Fi (тик). */
        private void logStalls() {
            long now = android.os.SystemClock.elapsedRealtime();
            if (now - stallsLoggedAt < 60_000) {
                return;
            }
            stallsLoggedAt = now;
            int n;
            long longest;
            synchronized (window) {
                n = stalls;
                longest = longestStall;
                stalls = 0;
                longestStall = 0;
            }
            if (n > 0) {
                EventLog.add("GAP Smart Tool: за минуту Wi-Fi притормаживал " + n + " раз, до " + longest
                        + " мс — Android Auto ждал");
            }
        }

        /** Под lock. */
        private void tick() {
            CommManager.ConnectionState aa = App.Companion.provide(GapService.this).getCommManager().getConnectionState().getValue();
            boolean up = aa instanceof CommManager.ConnectionState.TransportStarted;
            if (up) {
                if (!keyAsked) {
                    // AA уже работал до этого подключения: кадры идут только при изменениях — нужен ключевой сейчас.
                    keyAsked = true;
                    DirectProjection.requestKeyFrame();
                }
                int[] g = sendGeometry();
                status("");
                if (state != OvertakeLinkService.State.RUNNING) {
                    setState(OvertakeLinkService.State.RUNNING, getString(R.string.ol_gap_running_detail, g[0] - g[2], g[1] - g[3]));
                }
            } else if (!AaServer.isListening()) {
                String how = getString(R.string.ol_how_to_start);
                status(how);
                setState(OvertakeLinkService.State.HANDSHAKE, how.replace('\n', ' '));
            } else {
                boolean failed = aa instanceof CommManager.ConnectionState.Error
                        || aa instanceof CommManager.ConnectionState.Disconnected;
                status(getString(R.string.ol_gap_aa_starting));
                // Пока AA не на связи — не «работает», даже если только что работал.
                setState(OvertakeLinkService.State.HANDSHAKE, getString(R.string.ol_gap_aa_starting));
                if (failed) {
                    EventLog.add("GAP Smart Tool: Android Auto не на связи — подключаем снова");
                    sendAap(AapService.ACTION_START_SELF_MODE);
                }
            }
        }

        private void status(String s) {
            if (!s.equals(sentStatus)) {
                sentStatus = s;
                send(GapProtocol.STATUS, s.getBytes(StandardCharsets.UTF_8));
            }
        }

        /** Свой HELLO принят: до него тик не трогает общий AA (экран мог остаться от прежнего клиента). */
        private boolean hello;

        private void hello(int version, int w, int h) {
            EventLog.add("GAP Smart Tool: протокол " + version + ", экран для Android Auto " + w + "x" + h);
            if (w < 100 || h < 100) {
                close("неверный размер экрана " + w + "x" + h);
                return;
            }
            if (version < GapProtocol.MIN_GAP) {
                // Старый GAP кадры не подтверждает — отказ, как любому неверному HELLO.
                close("протокол " + version + " устарел, нужен " + GapProtocol.MIN_GAP + " — обновите GAP Smart Tool");
                return;
            }
            synchronized (lock) {
                if (client != this || !open || !running) {
                    return;
                }
                stopScreen();
                getSharedPreferences("overtakelink", MODE_PRIVATE).edit().putString(PREF_GAP_SCREEN, w + "×" + h).apply();
                DirectProjection.Size size = OvertakeLinkService.directSize(videoMode(GapService.this));
                float fit = DirectProjection.fit(w, h, size);
                int sw = Math.round(w * fit);
                int sh = Math.round(h * fit);
                CarScreen sc = new CarScreen(sw, sh, 160, new CarScreen.Sink() {
                    @Override
                    public void onCodecConfig(byte[] spsPps) {
                    }

                    @Override
                    public void onFrame(byte[] data, int off, int len, boolean keyFrame) {
                    }

                    @Override
                    public void onEncoderFailed(String why) {
                    }
                });
                sc.startDirect(GapService.this);
                sc.publish();
                screen = sc;
                clientConnected = true;
                wakeLock.acquire();
                // Своя версия — раньше видео: GAP выберет формат касаний до первого кадра, а не посреди жеста.
                send(GapProtocol.INFO, GapProtocol.u16s(GapProtocol.VERSION));
                DirectProjection.start(GapService.this, sw, sh, size, this::video, true, fit);
                hello = true;
                keyAsked = false;
                // AA ещё работает с прошлого подключения (Wi-Fi переподключился быстрее закрытия старого клиента):
                // перезапустить сеанс — SPS/PPS и размер под новый экран AA шлёт только при запуске кодировщика.
                if (App.Companion.provide(GapService.this).getCommManager().getConnectionState().getValue()
                        instanceof CommManager.ConnectionState.TransportStarted) {
                    sendAap(AapService.ACTION_DISCONNECT);
                }
                sendAap(AapService.ACTION_START_SELF_MODE);
                // Под тем же замком: остановка сервиса сразу после HELLO не будет перезаписана «запуском».
                setState(OvertakeLinkService.State.HANDSHAKE, getString(R.string.ol_gap_aa_starting));
            }
        }

        void close(String why) {
            synchronized (lock) {
                if (!open) {
                    return;
                }
                open = false;
                EventLog.add("GAP Smart Tool: отключился — " + why);
                synchronized (window) {
                    window.notifyAll();
                }
                if (client == this) {
                    client = null;
                    clientConnected = false;
                    stopScreen();
                    // Сеанс AA закрыть: иначе он продолжит работать (и звучать) без экрана, пока ждём нового клиента.
                    sendAap(AapService.ACTION_DISCONNECT);
                    sendAap(AapService.ACTION_STOP_SELF_MODE);
                    wakeLock.release();
                    if (running) {
                        setState(OvertakeLinkService.State.HANDSHAKE, getString(R.string.ol_gap_searching_detail));
                    }
                }
            }
            try {
                sock.close();
            } catch (IOException ignored) {
            }
        }
    }

    /** Под lock. */
    private void stopScreen() {
        if (screen != null) {
            DirectProjection.stop();
            screen.stop();
            screen = null;
        }
    }

    private void sendAap(String action) {
        try {
            startService(new Intent(this, AapService.class).setAction(action));
        } catch (Exception e) {
            EventLog.error("команда Android Auto " + action, e);
        }
    }
}
