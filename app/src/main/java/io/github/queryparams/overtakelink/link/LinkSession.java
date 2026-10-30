package io.github.queryparams.overtakelink.link;

import org.json.JSONObject;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/**
 * Протокол QDLink без Android: разбор входящего потока, ответы ГУ, упаковка видео.
 * Всё, что трогает устройство (USB, кодировщик, экран), делает {@link Host}.
 * Протокол «!BIN» (блоки по 512 байт) с прикладными сообщениями «A6A6» — проверен на Geely Atlas Pro.
 * Новый протокол «5A5A» + JSON — по коду родного QDLink 1.9.7, на машине не проверен.
 */
public final class LinkSession {

    public interface Host {
        void send(byte[] packet);

        void startVideo(Packets.Video params);

        void stopVideo();

        void requestKeyFrame();

        /** action — как в MotionEvent: 0 нажатие, 1 отпускание, 2 движение; x, y — пиксели экрана машины. */
        void touch(int action, float x, float y);

        void caption(String text);

        void micData(byte[] b, int off, int len);

        ScreenGeometry geometry(int carWidth, int carHeight);

        /** ГУ сообщило размер экрана: можно заранее поднять экран машины и Android Auto. */
        void carScreen(int carWidth, int carHeight);

        String btAddress();

        String phoneUuid();

        String phoneBrand();

        String phoneModel();

        int sdk();

        void schedule(long delayMs, Runnable task);

        void scheduleRepeating(long delayMs, long periodMs, Runnable task);
    }

    public enum Proto { UNKNOWN, OLD, NEW }

    private final Host host;
    private final Packets.Video video = new Packets.Video();
    private Proto proto = Proto.UNKNOWN;
    private byte[] acc = new byte[1 << 16];
    private int accLen;
    private byte[] screenReq;
    private ScreenGeometry geometry;
    private int carWidth = 800;
    private int carHeight = 480;
    private int appStatusSent;
    private int heartbeatsReceived;
    private boolean legalTimerStarted;
    private boolean videoRunning;
    /** Запросы ключевого кадра от ГУ за показ видео (в журнал — 1-й, 2-й, 4-й…): частые — декодер ГУ не справляется. */
    private int keyFrameRequests;

    public LinkSession(Host host) {
        this.host = host;
    }

    public synchronized Proto proto() {
        return proto;
    }

    /**
     * Раз в 3 с. Пока ГУ молчит — AppStatus (ГУ начинает слушать через ~30 с и отвечает на первый пакет после этого).
     * В протоколе 5A5A родной QDLink всё время шлёт HEARTBEAT раз в 3 с.
     */
    public synchronized void tick() {
        if (proto == Proto.NEW) {
            host.send(NewPackets.json(HEARTBEAT));
            return;
        }
        if (proto == Proto.UNKNOWN) {
            appStatusSent++;
            if (appStatusSent == 1 || appStatusSent % 5 == 0) {
                EventLog.add("→ ГУ: AppStatus (!BIN, cmd 1), попытка " + appStatusSent + ". ГУ обычно отвечает через ~30 с");
            }
            host.send(OldPackets.appStatus(host.sdk()));
        }
    }

    public synchronized void videoStopped() {
        videoRunning = false;
    }

    /** ГУ разрешило видео (команда play) и ещё не остановило его. */
    public synchronized boolean videoPlaying() {
        return videoRunning;
    }

    /** Микрофон ГУ: при включении ГУ шлёт его звук пакетами dataType 12 (приходят в {@link Host#micData}). */
    public synchronized void speech(boolean on) {
        if (proto == Proto.NEW) {
            sendCmd("SPEECH_CTRL", put(new JSONObject(), "SpeechStatus", on ? 1 : 0));
            return;
        }
        EventLog.add("→ ГУ: микрофон машины " + (on ? "включить" : "выключить") + " (!BIN cmd 21, value " + (on ? 1 : 0) + ")");
        host.send(OldPackets.speechControl(on));
    }

    /** Размер кадра для заголовков видеопакетов, если поток не того размера, что экран машины. */
    public synchronized void setFrameSize(int width, int height) {
        video.frameWidth = width;
        video.frameHeight = height;
    }

    /** Упаковка кадра H.264 в видеопакет. */
    public synchronized byte[] videoPacket(byte[] data, int off, int len) {
        if (proto == Proto.NEW) {
            return NewPackets.video(video, data, off, len);
        }
        return OldPackets.video(screenReq, video, data, off, len);
    }

    // ---------- приём ----------

    public synchronized void onBytes(byte[] data, int n) {
        if (accLen + n > acc.length) {
            byte[] bigger = new byte[Math.max(acc.length * 2, accLen + n)];
            System.arraycopy(acc, 0, bigger, 0, accLen);
            acc = bigger;
        }
        System.arraycopy(data, 0, acc, accLen, n);
        accLen += n;
        accLen = parse(acc, accLen);
    }

    private int parse(byte[] b, int len) {
        int pos = 0;
        while (true) {
            int start = findMagic(b, pos, len);
            if (start < 0) {
                int keep = Math.min(3, len - pos);
                logSkipped(b, pos, len - keep);
                System.arraycopy(b, len - keep, b, 0, keep);
                return keep;
            }
            logSkipped(b, pos, start);
            pos = start;
            if (len - pos < 16) {
                break;
            }
            if (b[pos] == '5') {
                int total = OldPackets.i32(b, pos + 4);
                if (total < NewPackets.HEADER || total > (8 << 20)) {
                    EventLog.add("← ГУ: 5A5A с размером " + total + ": " + EventLog.hex(b, pos, 16, 16));
                    pos += 4;
                    continue;
                }
                if (len - pos < total) {
                    break;
                }
                try {
                    setProto(Proto.NEW);
                    handleNew(b, pos, total);
                } catch (RuntimeException e) {
                    EventLog.error("обработка пакета 5A5A, hex " + EventLog.hex(b, pos, total, 96), e);
                }
                pos += total;
                continue;
            }
            int total = OldPackets.i32(b, pos + 8);
            if (total < OldPackets.BLOCK || total > (8 << 20)) {
                total = OldPackets.BLOCK;
            }
            if (len - pos < total) {
                break;
            }
            try {
                setProto(Proto.OLD);
                handleOld(b, pos, total);
            } catch (RuntimeException e) {
                EventLog.error("обработка пакета, hex " + EventLog.hex(b, pos, total, 96), e);
            }
            pos += total;
        }
        System.arraycopy(b, pos, b, 0, len - pos);
        return len - pos;
    }

    private static int findMagic(byte[] b, int from, int len) {
        for (int i = from; i + 4 <= len; i++) {
            if (b[i] == '!' && b[i + 1] == 'B' && b[i + 2] == 'I' && b[i + 3] == 'N') {
                return i;
            }
            if (b[i] == '5' && b[i + 1] == 'A' && b[i + 2] == '5' && b[i + 3] == 'A') {
                return i;
            }
        }
        return -1;
    }

    private static void logSkipped(byte[] b, int from, int to) {
        for (int i = from; i < to; i++) {
            if (b[i] != 0) {
                EventLog.add("← ГУ: пропущено " + (to - from) + " байт вне пакетов: " + EventLog.hex(b, from, to - from, 64));
                return;
            }
        }
    }

    private void setProto(Proto p) {
        if (proto == p) {
            return;
        }
        proto = p;
        EventLog.add(p == Proto.NEW ? "ГУ говорит протоколом 5A5A + JSON (новый QDLink, экспериментально)" : "ГУ говорит протоколом !BIN");
    }

    private static void logUnknown(String what, byte[] b, int off, int total) {
        EventLog.add("← ГУ [" + what + "], " + total + " байт\n    текст: " + EventLog.text(b, off + 16, Math.min(total - 16, 1024))
                + "\n    hex: " + EventLog.hex(b, off, total, 256));
    }

    // ---------- старый протокол !BIN ----------

    private void handleOld(byte[] b, int off, int total) {
        int dataType = OldPackets.i32(b, off + 4);
        int action = OldPackets.i32(b, off + 28);
        switch (dataType) {
            case OldPackets.DT_CONTROL: {
                int cmd = OldPackets.i32(b, off + 68);
                int value = OldPackets.i32(b, off + 72);
                if (cmd == OldPackets.CMD_HEARTBEAT) {
                    if (heartbeatsReceived++ % 20 == 0) {
                        EventLog.add("← ГУ: сердцебиение (!BIN cmd 10, value " + value + "), всего " + heartbeatsReceived);
                    }
                    if (value == 1) {
                        host.send(OldPackets.reply(b, off, true, 1));
                    }
                    return;
                }
                if (cmd == OldPackets.CMD_KEY_FRAME && action == OldPackets.ACTION_REQUEST) {
                    keyFrameRequests++;
                    if (Integer.bitCount(keyFrameRequests) == 1) {
                        EventLog.add("← ГУ: запрос ключевого кадра (!BIN cmd 17), " + keyFrameRequests + "-й за показ видео");
                    }
                    if (videoRunning) {
                        host.requestKeyFrame();
                    }
                    return;
                }
                EventLog.add("← ГУ: !BIN управление, action " + action + ", cmd " + cmd + ", value " + value
                        + "\n    hex: " + EventLog.hex(b, off, Math.min(total, 224), 224));
                if (action != OldPackets.ACTION_REQUEST) {
                    host.caption("ГУ: !BIN action " + action + " cmd " + cmd + " value " + value);
                    return;
                }
                onOldCommand(b, off, cmd, value);
                return;
            }
            case OldPackets.DT_SCREEN:
                onOldScreen(b, off, action);
                return;
            case OldPackets.DT_TOUCH: {
                short act = OldPackets.i16(b, off + 66);
                short x = OldPackets.i16(b, off + 68);
                short y = OldPackets.i16(b, off + 70);
                // По записи сеанса: 0x8000 — нажатие, 0x8001 — движение, 0x8002 — отпускание.
                int touchAction = act == Short.MIN_VALUE ? 0 : act == Short.MIN_VALUE + 1 ? 2 : 1;
                if (touchAction != 2) {
                    EventLog.add("← ГУ: касание " + (touchAction == 0 ? "нажатие" : "отпускание") + " x " + x + ", y " + y);
                }
                host.touch(touchAction, x, y);
                return;
            }
            case OldPackets.DT_SPEECH:
                if (total > OldPackets.BLOCK) {
                    int size = OldPackets.i32(b, off + 288);
                    int n = size > 0 && size <= total - OldPackets.BLOCK ? size : total - OldPackets.BLOCK;
                    host.micData(b, off + OldPackets.BLOCK, n);
                } else {
                    logUnknown("!BIN речь без данных", b, off, total);
                }
                return;
            case OldPackets.DT_HU_MSG:
                onOldAppPacket(b, off, total, action);
                return;
            default:
                logUnknown("!BIN тип " + dataType + ", action " + action, b, off, total);
                host.caption("ГУ: !BIN тип " + dataType);
        }
    }

    private void onOldCommand(byte[] b, int off, int cmd, int value) {
        switch (cmd) {
            case OldPackets.CMD_VERSION: {
                String carType = EventLog.text(b, off + 72, 32).replace("·", "").trim();
                String other = EventLog.text(b, off + 104, 16).replace("·", "").trim();
                int w = OldPackets.i32(b, off + 128);
                int h = OldPackets.i32(b, off + 132);
                carWidth = w > 0 ? w : 800;
                carHeight = h > 0 ? h : 480;
                geometry = host.geometry(carWidth, carHeight);
                EventLog.add("ГУ: тип «" + carType + "», «" + other + "», экран " + carWidth + "x" + carHeight
                        + ", поля @120/@124: " + OldPackets.i32(b, off + 120) + "/" + OldPackets.i32(b, off + 124));
                host.send(OldPackets.reply(b, off, false, host.sdk(), geometry.phoneLong, geometry.phoneShort,
                        geometry.outAppWidth, geometry.outAppHeight));
                EventLog.add("→ ГУ: версия телефона (!BIN): " + geometry);
                host.send(OldPackets.upgradeStatus());
                EventLog.add("→ ГУ: статус обновления (!BIN cmd 13, value 5)");
                host.carScreen(carWidth, carHeight);
                break;
            }
            case OldPackets.CMD_LAND_MODE:
                // Разрешение 2 — как в записи сеанса родного QDLink 1.9.7 (нет права рисовать поверх окон).
                host.send(OldPackets.reply(b, off, true, value, 2));
                EventLog.add("→ ГУ: ответ на ориентацию " + value);
                break;
            case OldPackets.CMD_MIRROR_SUPPORT: {
                // Порядок как в записи сеанса родного QDLink 1.9.7: phoneready, phonereadynew, затем ответ.
                byte[] reply = OldPackets.reply(b, off, false, 1);
                later(300, () -> sendApp("phoneready"));
                later(600, () -> sendApp("phonereadynew"));
                later(1000, () -> {
                    host.send(reply);
                    EventLog.add("→ ГУ: зеркалирование поддерживается");
                });
                break;
            }
            case OldPackets.CMD_PLAY_STATUS:
                if (value == 1) {
                    startVideo();
                } else {
                    stopVideo();
                }
                break;
            default:
                host.caption("ГУ: !BIN cmd " + cmd + " value " + value);
        }
    }

    private void onOldScreen(byte[] b, int off, int action) {
        if (action == OldPackets.SCREEN_ACTION_ARGS) {
            video.frameRate = OldPackets.i32(b, off + 64);
            video.bitRate = OldPackets.i32(b, off + 68);
            video.frameInterval = OldPackets.i32(b, off + 72);
            EventLog.add("← ГУ: параметры видео (!BIN action 18): " + video.frameRate + " к/с, " + video.bitRate
                    + " бит/с, интервал " + video.frameInterval);
            return;
        }
        screenReq = new byte[OldPackets.BLOCK];
        System.arraycopy(b, off, screenReq, 0, OldPackets.BLOCK);
        OldPackets.applyScreenDefaults(screenReq);
        int w = OldPackets.i32(b, off + 64);
        int h = OldPackets.i32(b, off + 68);
        int enc = OldPackets.i32(b, off + 80);
        int fps = OldPackets.i32(b, off + 180);
        int bitRate = OldPackets.i32(b, off + 184);
        int interval = OldPackets.i32(b, off + 188);
        int dataW = OldPackets.i32(b, off + 116);
        int dataH = OldPackets.i32(b, off + 120);
        video.width = w > 0 ? w : carWidth;
        video.height = h > 0 ? h : carHeight;
        video.encodingType = enc;
        video.frameRate = fps > 0 ? fps : 24;
        video.bitRate = bitRate > 0 ? bitRate : Math.max(dataW * dataH * 3, video.width * video.height * 3);
        video.frameInterval = interval > 0 ? interval : 4;
        EventLog.add("← ГУ: запрос захвата экрана (!BIN тип 1, action " + action + "): " + w + "x" + h + ", кодек " + enc
                + ", " + fps + " к/с, " + bitRate + " бит/с, интервал " + interval
                + "\n    hex 0..208: " + EventLog.hex(b, off, 208, 208));
        host.send(OldPackets.speechArgs());
        EventLog.add("→ ГУ: параметры микрофона (!BIN тип 12)");
    }

    // ---------- прикладной слой A6A6 ----------

    private void onOldAppPacket(byte[] b, int off, int total, int action) {
        if (action != OldPackets.ACTION_REQUEST) {
            EventLog.add("← ГУ: ответ на прикладное сообщение, action " + action + ", статус " + OldPackets.i32(b, off + 68));
            return;
        }
        int size = OldPackets.i32(b, off + 64);
        int dataOff = off + OldPackets.BLOCK;
        if (size <= 0 || size > total - OldPackets.BLOCK) {
            logUnknown("!BIN тип 3 с размером " + size, b, off, total);
            return;
        }
        String text = new String(b, dataOff, size, StandardCharsets.UTF_8);
        AppMessages.Message m = AppMessages.parse(b, dataOff, size);
        if (m == null) {
            EventLog.add("← ГУ [прикладное, не разобрано]: " + text);
            return;
        }
        EventLog.add("← ГУ [" + m.appId + "] " + m.logicId + " " + m.data + "\n    " + text);
        host.caption("ГУ: " + m.logicId);
        onAppMessage(m);
    }

    private void onAppMessage(AppMessages.Message m) {
        switch (m.logicId) {
            case "BTADDRESS":
                sendApp("BTADDRESS", AppMessages.unitString(host.btAddress()));
                break;
            case "HUINFO": {
                List<Object> v = m.data.isEmpty() ? List.of() : AppMessages.values(m.data.get(0));
                EventLog.add("ГУ: HUINFO поля " + v);
                boolean watch = false;
                if (v.size() > 4 && v.get(4) != null) {
                    try {
                        watch = new JSONObject(v.get(4).toString()).optInt("legal_app_watch") == 1;
                    } catch (Exception ignored) {
                    }
                }
                boolean legalWatch = watch;
                later(500, () -> {
                    sendApp("phoneinitok");
                    sendApp("NEEDUPGRADE", AppMessages.unitInt(1));
                    if (legalWatch) {
                        EventLog.add("ГУ включило контроль приложений: раз в секунду сообщаем, что приложение разрешено");
                        startLegalTimer();
                    }
                });
                break;
            }
            case "CARBTADDRESS":
                sendApp("COMPARE_OK");
                break;
            case "BT_AUTO_CONNECTED":
                sendApp("BT_AUTO_CONNECTED", AppMessages.tuple("(is)", AppMessages.unitInt(2), AppMessages.unitString("")));
                break;
            case "ASSISTANT-04": {
                List<Object> v = m.data.isEmpty() ? List.of() : AppMessages.values(m.data.get(0));
                String version = v.isEmpty() || v.get(0) == null ? "" : v.get(0).toString();
                sendApp("SUBAPP_PKGMD5", AppMessages.tuple("(sss)", AppMessages.unitString(version),
                        AppMessages.unitString(""), AppMessages.unitString("")));
                break;
            }
            default:
                break;
        }
    }

    private void sendApp(String logicId, String... data) {
        String msg = AppMessages.create(AppMessages.PHONE_APP_ID, logicId, data);
        EventLog.add("→ ГУ [прикладное] " + logicId + ": " + msg);
        host.send(AppMessages.packet(msg));
    }

    private void later(long delayMs, Runnable task) {
        host.schedule(delayMs, () -> {
            synchronized (LinkSession.this) {
                task.run();
            }
        });
    }

    private void startLegalTimer() {
        if (legalTimerStarted) {
            return;
        }
        legalTimerStarted = true;
        byte[] packet = AppMessages.packet(AppMessages.create(AppMessages.PHONE_APP_ID, "LEGAL_APP_ON", AppMessages.unitInt(1)));
        host.scheduleRepeating(1000, 1000, () -> host.send(packet));
    }

    // ---------- новый протокол 5A5A ----------

    private static final String HEARTBEAT = "{\"CMD\":\"HEARTBEAT\"}";

    private void handleNew(byte[] b, int off, int total) {
        int ext = ByteBuffer.wrap(b, off + 8, 2).getShort() & 0xffff;
        int msgType = b[off + 10] & 0xff;
        int fmt = b[off + 13] & 0xff;
        int reserved = b[off + 14] & 0xff;
        int bodyOff = off + NewPackets.HEADER + ext;
        int bodyLen = total - NewPackets.HEADER - ext;
        if (bodyLen < 0) {
            logUnknown("5A5A: доп. заголовок длиннее пакета", b, off, total);
            return;
        }
        switch (msgType) {
            case NewPackets.TYPE_CMD:
                if (fmt == NewPackets.FORMAT_JSON) {
                    onCommand(new String(b, bodyOff, bodyLen, StandardCharsets.UTF_8).trim());
                } else {
                    logUnknown("5A5A команда в формате " + fmt, b, off, total);
                }
                return;
            case NewPackets.TYPE_TOUCH:
                onTouch(b, bodyOff, bodyLen);
                return;
            case NewPackets.TYPE_SPEECH:
                host.micData(b, bodyOff, bodyLen);
                return;
            default:
                logUnknown("5A5A тип " + msgType + ", формат " + fmt + ", резерв " + reserved, b, off, total);
                host.caption("ГУ: 5A5A тип " + msgType);
        }
    }

    private void onCommand(String json) {
        JSONObject root;
        try {
            root = new JSONObject(json);
        } catch (Exception e) {
            EventLog.add("← ГУ: JSON не разобран: " + json);
            return;
        }
        String cmd = root.optString("CMD", "");
        if ("HEARTBEAT".equals(cmd)) {
            if (heartbeatsReceived++ % 20 == 0) {
                EventLog.add("← ГУ: HEARTBEAT, всего " + heartbeatsReceived);
            }
            return;
        }
        EventLog.add("← ГУ: " + json);
        JSONObject para = root.optJSONObject("PARA");
        if (para == null) {
            para = new JSONObject();
        }
        switch (cmd) {
            case "CAR_INFO":
                onCarInfo(para);
                break;
            case "VIDEO_SUP_REQ": {
                JSONObject rsp = new JSONObject();
                put(rsp, "VideoFormat", 3);
                put(rsp, "VideoSupport", 1);
                sendCmd("VIDEO_SUP_RSP", rsp);
                break;
            }
            case "VIDEO_ARGS": {
                int w = para.optInt("Width", 0);
                int h = para.optInt("Height", 0);
                video.width = w > 0 ? w : carWidth;
                video.height = h > 0 ? h : carHeight;
                video.encodingType = para.optInt("EncodingType", 3);
                video.frameRate = para.optInt("FrameRate", 24);
                video.bitRate = para.optInt("BitRate", 0);
                video.frameInterval = para.optInt("FrameInterval", 4);
                JSONObject speech = new JSONObject();
                put(speech, "EncodingType", 1);
                put(speech, "SampleRate", 16000);
                put(speech, "ChannelConfig", 1);
                put(speech, "AudioFormat", 16);
                sendCmd("SPEECH_ARGS", speech);
                break;
            }
            case "VIDEO_CTRL":
                if (para.optInt("PlayStatus", 0) == 1) {
                    startVideo();
                } else {
                    stopVideo();
                }
                break;
            case "KEY_FRAME_REQ":
                keyFrameRequests++;
                if (videoRunning) {
                    host.requestKeyFrame();
                }
                break;
            case "LAND_MODE_REQ": {
                // Как у родного QDLink без права рисовать поверх окон: Authority 2.
                JSONObject rsp = new JSONObject();
                put(rsp, "Orientation", para.optInt("Orientation", 0));
                put(rsp, "Authority", 2);
                put(rsp, "StatusArg", 0);
                sendCmd("LAND_MODE_RSP", rsp);
                break;
            }
            case "DISCONNECT_REQ":
                stopVideo();
                break;
            default:
                host.caption("ГУ: " + cmd);
        }
    }

    /** Ответ как у родного QDLink 1.9.7 (linkconnection.a.B0 и z0): PHONE_INFO, затем UPDATE_NOTIFY 5. */
    private void onCarInfo(JSONObject para) {
        int w = para.optInt("CarWidth", 0);
        int h = para.optInt("CarHeight", 0);
        carWidth = w > 0 ? w : 800;
        carHeight = h > 0 ? h : 480;
        geometry = host.geometry(carWidth, carHeight);
        EventLog.add("ГУ: " + para.optString("CarFactory") + " " + para.optString("CarType") + ", протокол "
                + para.optString("Version") + ", экран " + carWidth + "x" + carHeight);
        JSONObject info = new JSONObject();
        put(info, "PhoneWidth", geometry.phoneLong);
        put(info, "PhoneHeight", geometry.phoneShort);
        put(info, "MirrorWidth", geometry.outAppWidth);
        put(info, "MirrorHeight", geometry.outAppHeight);
        put(info, "PhoneWidthInApp", carWidth);
        put(info, "PhoneHeightInApp", carHeight);
        put(info, "MirrorWidthInApp", carWidth);
        put(info, "MirrorHeightInApp", carHeight);
        put(info, "MirrorTypeSupport", para.optInt("MirrorTypeReq", 0));
        put(info, "PhoneBrand", host.phoneBrand());
        put(info, "PhoneModel", host.phoneModel());
        put(info, "PhoneName", host.phoneModel());
        put(info, "PhoneUUID", host.phoneUuid());
        put(info, "PhoneSystemTime", 0);
        put(info, "Platform", 0);
        put(info, "PlatformVersion", String.valueOf(host.sdk()));
        put(info, "Version", "1.9.7");
        put(info, "PhoneFeature", put(new JSONObject(), "PassistMobileNum", ""));
        sendCmd("PHONE_INFO", info);
        sendCmd("UPDATE_NOTIFY", put(new JSONObject(), "UpdateStatus", 5));
        host.carScreen(carWidth, carHeight);
    }

    /**
     * Касание (тип 2, класс h0.e): int action, byte число пальцев, на палец — byte номер, byte действие
     * (1 нажатие, 2 отпускание, 3 движение), float x, float y. Родной QDLink берёт только палец 0.
     */
    private void onTouch(byte[] b, int off, int len) {
        if (len < 5) {
            EventLog.add("← ГУ: короткое касание: " + EventLog.hex(b, off, len, 64));
            return;
        }
        ByteBuffer bb = ByteBuffer.wrap(b, off, len);
        bb.getInt();
        int count = bb.get() & 0xff;
        for (int i = 0; i < count && bb.remaining() >= 10; i++) {
            int id = bb.get();
            int fingerAction = bb.get();
            float x = bb.getFloat();
            float y = bb.getFloat();
            if (id != 0) {
                continue;
            }
            int action = fingerAction == 2 ? 1 : fingerAction == 3 ? 2 : 0;
            if (action != 2) {
                EventLog.add(String.format(Locale.US, "← ГУ: касание %s x %.0f, y %.0f",
                        action == 0 ? "нажатие" : "отпускание", x, y));
            }
            host.touch(action, x, y);
        }
    }

    private void sendCmd(String cmd, JSONObject para) {
        JSONObject o = new JSONObject();
        put(o, "CMD", cmd);
        put(o, "PARA", para);
        String json = o.toString();
        EventLog.add("→ ГУ: " + json);
        host.send(NewPackets.json(json));
    }

    private static JSONObject put(JSONObject o, String key, Object value) {
        try {
            return o.put(key, value);
        } catch (org.json.JSONException e) {
            throw new IllegalArgumentException(key, e);
        }
    }

    // ---------- видео ----------

    private void startVideo() {
        if (videoRunning) {
            host.requestKeyFrame();
            return;
        }
        if (video.width <= 0 || video.height <= 0) {
            video.width = carWidth;
            video.height = carHeight;
        }
        if (proto == Proto.OLD && screenReq == null) {
            screenReq = new byte[OldPackets.BLOCK];
            OldPackets.applyScreenDefaults(screenReq);
            EventLog.add("Видео: ГУ не прислало запрос захвата экрана, заголовок строится с нуля");
        }
        if (geometry == null) {
            geometry = host.geometry(carWidth, carHeight);
        }
        // Как в записи сеанса с ГУ: горизонталь, поворот 90°, захват «в приложении».
        video.orientation = 1;
        video.angle = 90;
        video.appCapture = 1;
        video.phoneWidth = geometry.phoneLong;
        video.phoneHeight = geometry.phoneShort;
        if (video.frameRate <= 0) {
            video.frameRate = 24;
        }
        if (video.bitRate <= 0) {
            video.bitRate = video.width * video.height * 3;
        }
        if (video.frameInterval <= 0) {
            video.frameInterval = 4;
        }
        videoRunning = true;
        keyFrameRequests = 0;
        host.startVideo(video);
    }

    private void stopVideo() {
        if (videoRunning) {
            videoRunning = false;
            host.stopVideo();
        }
    }
}
