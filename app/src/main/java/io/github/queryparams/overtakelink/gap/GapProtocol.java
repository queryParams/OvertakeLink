package io.github.queryparams.overtakelink.gap;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import io.github.queryparams.overtakelink.car.TouchPointers;

/**
 * Протокол OvertakeLink ↔ GAP Smart Tool по Wi-Fi. GAP Smart Tool подключается по TCP к шлюзу своей сети — телефону,
 * раздающему точку доступа, — на {@link #PORT}. Пакет: тип (1 байт), длина данных (4 байта, BE), данные.
 * Копия этого описания — в GAP Smart Tool (AaProtocol): менять только вместе.
 */
public final class GapProtocol {
    public static final int PORT = 47621;
    /** Версия протокола OL; с 3 — касания несколькими пальцами в {@link #TOUCH}. GAP узнаёт её из {@link #INFO}. */
    public static final int VERSION = 3;
    /** GAP старше не берём: без протокола 2 (подтверждение кадров) поток видео не держится. */
    public static final int MIN_GAP = 2;

    /** GAP → OL: версия (u16), ширина и высота экрана для AA (u16, u16). */
    public static final int HELLO = 1;
    /** OL → GAP: флаги (u8: 1 — SPS/PPS, 2 — ключевой кадр), NAL Annex-B. */
    public static final int VIDEO = 2;
    /**
     * GAP → OL: действие (u8: 0 — нажатие, 1 — отпускание, 2 — движение), x, y в пикселях кадра (u16, u16) — один
     * палец, ровно 5 байт. С версии 3 (GAP узнал её из {@link #INFO}) — кадр со всеми пальцами: действие (u8: 0 —
     * нажатие, 1 — отпускание, 2 — движение, 3 — отмена), номер нажатого или отпущенного пальца (u8), число пальцев
     * (u8, от 1), на палец — номер (u8), x, y (u16, u16): 3 + 5 × n байт, с пятью байтами не путается.
     */
    public static final int TOUCH = 3;
    /** GAP → OL: нужен ключевой кадр (декодер только что создан). */
    public static final int KEYFRAME = 4;
    /** Обе стороны раз в секунду; 5 с тишины — связь пропала. */
    public static final int PING = 5;
    /** OL → GAP: текст состояния для экрана (UTF-8), пустой — состояние снято. */
    public static final int STATUS = 6;
    /** OL → GAP: кадр AA и поля интерфейса: ширина, высота, поле по ширине, поле по высоте (u16 × 4). */
    public static final int GEOMETRY = 7;
    /**
     * GAP → OL, с версии 2: сколько пакетов видео GAP забрал в декодер или выбросил (u16). Больше {@link #VIDEO_WINDOW}
     * неподтверждённых OL не шлёт: ждёт, и Android Auto на телефоне притормаживает сам.
     */
    public static final int ACK = 8;
    /**
     * OL → GAP, с версии 3: версия протокола OL (u16), сразу после принятого HELLO. Старый GAP неизвестный пакет
     * пропускает и шлёт касания одним пальцем; новый — кадрами со всеми пальцами, только узнав версию 3.
     */
    public static final int INFO = 9;
    /** Неподтверждённых пакетов видео в пути, не больше. */
    public static final int VIDEO_WINDOW = 8;

    public static final int VIDEO_CONFIG = 1;
    public static final int VIDEO_KEY = 2;

    /** Больше этого пакет не бывает: защита от мусора в потоке. */
    public static final int MAX_LEN = 4 << 20;
    /** Текст состояния — пара строк; длиннее — мусор. */
    public static final int MAX_STATUS = 1024;
    public static final int SILENCE_MS = 5000;

    private GapProtocol() {
    }

    public static final class Packet {
        public final int type;
        public final byte[] data;

        Packet(int type, byte[] data) {
            this.type = type;
            this.data = data;
        }

        public int u16(int at) {
            return ((data[at] & 0xff) << 8) | (data[at + 1] & 0xff);
        }
    }

    public static Packet read(DataInputStream in) throws IOException {
        int type = in.readUnsignedByte();
        int len = in.readInt();
        if (len < 0 || len > MAX_LEN) {
            throw new IOException("пакет " + type + " длиной " + len);
        }
        if (type == STATUS && len > MAX_STATUS) {
            throw new IOException("состояние длиной " + len);
        }
        int min = type == HELLO ? 6 : type == TOUCH ? 5 : type == VIDEO ? 1 : type == GEOMETRY ? 8 : type == ACK ? 2 : 0;
        if (len < min) {
            // Обрезанный пакет известного типа — поток испорчен: соединение закрывается, процесс не падает.
            throw new IOException("пакет " + type + " короче " + min + " байт: " + len);
        }
        byte[] d = new byte[len];
        in.readFully(d);
        return new Packet(type, d);
    }

    /** Пакет целиком одним вызовом записи: заголовок и данные не разрываются другим потоком. */
    public static void write(DataOutputStream out, int type, byte[] data, int off, int len) throws IOException {
        synchronized (out) {
            out.writeByte(type);
            out.writeInt(len);
            out.write(data, off, len);
            out.flush();
        }
    }

    /**
     * Пакет {@link #TOUCH} → кадр касаний, оба формата; действия протокола совпадают с MotionEvent (0 нажатие,
     * 1 отпускание, 2 движение, 3 отмена). null — пакет испорчен.
     */
    public static TouchPointers.Frame touch(Packet p) {
        byte[] d = p.data;
        int a = d[0] & 0xff;
        if (d.length == 5) {
            return TouchPointers.finger(0, a <= 1 ? a : 2, p.u16(1), p.u16(3));
        }
        int n = d[2] & 0xff;
        if (a > 3 || n == 0 || d.length != 3 + 5 * n) {
            return null;
        }
        List<TouchPointers.Pointer> pointers = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            int at = 3 + 5 * i;
            pointers.add(new TouchPointers.Pointer(d[at] & 0xff, p.u16(at + 1), p.u16(at + 3)));
        }
        return new TouchPointers.Frame(a, d[1] & 0xff, pointers);
    }

    public static byte[] u16s(int... v) {
        byte[] b = new byte[v.length * 2];
        for (int i = 0; i < v.length; i++) {
            b[i * 2] = (byte) (v[i] >> 8);
            b[i * 2 + 1] = (byte) v[i];
        }
        return b;
    }
}
