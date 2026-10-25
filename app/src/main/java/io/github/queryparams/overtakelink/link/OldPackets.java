package io.github.queryparams.overtakelink.link;

import java.nio.ByteBuffer;

/**
 * Старый бинарный протокол QDLink («!BIN», блоки по 512 байт, big-endian).
 *
 * Общая часть блока: "!BIN", int dataType @4, int totalSize @8, int headerSize @12,
 * int commonHeaderSize @16, int requestHeaderSize @20, int responseHeaderSize @24,
 * int action @28, 32 байта метки @32, дальше поля запроса с @64 и ответа с @192.
 */
public final class OldPackets {
    public static final int BLOCK = 512;

    public static final int DT_CONTROL = 0;
    public static final int DT_SCREEN = 1;
    public static final int DT_HU_MSG = 3;
    public static final int DT_TOUCH = 10;
    public static final int DT_SPEECH = 12;

    public static final int ACTION_REQUEST = 1;

    public static final int CMD_APP_STATUS = 1;
    public static final int CMD_VERSION = 3;
    public static final int CMD_LAND_MODE = 5;
    public static final int CMD_HEARTBEAT = 10;
    public static final int CMD_PLAY_STATUS = 12;
    public static final int CMD_UPGRADE = 13;
    public static final int CMD_MIRROR_SUPPORT = 16;
    public static final int CMD_KEY_FRAME = 17;
    /** Голос (класс n): value 1 — ГУ начинает слать звук своего микрофона (dataType 12), 0 — перестаёт. */
    public static final int CMD_SPEECH = 21;
    public static final int CMD_HOME = 20;
    public static final int SCREEN_ACTION_ARGS = 18;

    private OldPackets() {
    }

    public static int i32(byte[] b, int off) {
        return ByteBuffer.wrap(b, off, 4).getInt();
    }

    public static short i16(byte[] b, int off) {
        return ByteBuffer.wrap(b, off, 2).getShort();
    }

    private static ByteBuffer fresh(int dataType, int total, int req, int rsp, int action) {
        ByteBuffer b = ByteBuffer.allocate(BLOCK);
        b.put((byte) '!').put((byte) 'B').put((byte) 'I').put((byte) 'N');
        b.putInt(dataType).putInt(total).putInt(BLOCK).putInt(64).putInt(req).putInt(rsp).putInt(action);
        for (int i = 0; i < 32; i++) {
            b.put((byte) (i + 32));
        }
        return b;
    }

    /** Первое сообщение телефона после подключения (класс AppStatus). */
    public static byte[] appStatus(int sdk) {
        ByteBuffer b = fresh(DT_CONTROL, BLOCK, 128, 128, 2);
        b.putInt(64, 0).putInt(68, CMD_APP_STATUS).putInt(72, 1).putInt(76, sdk).putInt(80, 2);
        return b.array();
    }

    /** Сообщение «обновление не требуется» (класс o: cmd 13, value 5). */
    public static byte[] upgradeStatus() {
        ByteBuffer b = fresh(DT_CONTROL, BLOCK, 128, 128, 2);
        b.putInt(64, 0).putInt(68, CMD_UPGRADE).putInt(72, 5);
        return b.array();
    }

    /** Параметры звука микрофона (класс m: dataType 12). */
    public static byte[] speechArgs() {
        ByteBuffer b = fresh(DT_SPEECH, BLOCK, 16, 16, 0);
        b.putInt(64, 1).putInt(68, 16000).putInt(72, 1).putInt(76, 16);
        return b.array();
    }

    /** Включить или выключить микрофон ГУ (класс n: action 2, @64 = 1, cmd 21). */
    public static byte[] speechControl(boolean on) {
        ByteBuffer b = fresh(DT_CONTROL, BLOCK, 128, 128, 2);
        b.putInt(64, 1).putInt(68, CMD_SPEECH).putInt(72, on ? 1 : 0);
        return b.array();
    }

    /** Ответ на запрос ГУ: общая часть и метка из запроса, поля @64.. и ответ @192.. */
    public static byte[] reply(byte[] req, int off, boolean copyValue, int... response) {
        byte[] out = new byte[BLOCK];
        System.arraycopy(req, off, out, 0, 72);
        if (copyValue) {
            System.arraycopy(req, off + 72, out, 72, 4);
        }
        ByteBuffer b = ByteBuffer.wrap(out);
        for (int i = 0; i < response.length; i++) {
            b.putInt(192 + i * 4, response[i]);
        }
        return out;
    }

    /**
     * Поля частоты, битрейта и интервала в запросе экрана (тип 1) с умолчаниями, как у родного приложения в записи сеанса:
     * O@180 = 24, если 0; P@184 = ширина@116 × высота@120 × 3, если 0; Q@188 = 4, если 0; R, S, T = O, P, Q.
     * Параметры action 18 идут только в кодировщик, в заголовок кадра они не попадают.
     */
    public static void applyScreenDefaults(byte[] req) {
        ByteBuffer b = ByteBuffer.wrap(req);
        int fps = b.getInt(180) == 0 ? 24 : b.getInt(180);
        int bitRate = b.getInt(184) == 0 ? b.getInt(116) * b.getInt(120) * 3 : b.getInt(184);
        int interval = b.getInt(188) == 0 ? 4 : b.getInt(188);
        b.putInt(180, fps).putInt(184, bitRate).putInt(188, interval);
        b.putInt(192, fps).putInt(196, bitRate).putInt(200, interval);
    }

    /**
     * Видеопакет (класс l, метод ConnectionManager.v): заголовок 512 байт — запрос ГУ на захват
     * экрана (dataType 1) с подставленными полями кадра, затем данные H.264, дополнение до 512.
     */
    public static byte[] video(byte[] screenReq, Packets.Video v, byte[] data, int off, int len) {
        int total = Packets.padded(BLOCK + len);
        byte[] out = new byte[total];
        System.arraycopy(screenReq, 0, out, 0, BLOCK);
        ByteBuffer b = ByteBuffer.wrap(out);
        if (i32(out, 12) == 0) {
            b.put(0, (byte) '!').put(1, (byte) 'B').put(2, (byte) 'I').put(3, (byte) 'N');
            b.putInt(4, DT_SCREEN).putInt(12, BLOCK).putInt(16, 64).putInt(20, 28).putInt(24, 88);
        }
        b.putInt(8, total);
        b.putInt(92, len);
        b.putInt(100, v.phoneWidth).putInt(104, v.phoneHeight);
        b.putInt(116, v.frameWidth > 0 ? v.frameWidth : v.width).putInt(120, v.frameHeight > 0 ? v.frameHeight : v.height);
        b.putInt(132, 3);
        b.putInt(136, v.angle);
        b.putInt(140, v.orientation);
        b.putInt(148, 1920).putInt(152, 1080);
        b.putInt(204, v.appCapture);
        System.arraycopy(data, off, out, BLOCK, len);
        return out;
    }
}
