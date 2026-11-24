package io.github.queryparams.overtakelink.link;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import io.github.queryparams.overtakelink.car.TouchPointers;

/**
 * Новый протокол QDLink («5A5A», big-endian), по родному QDLink 1.9.7 (классы h0.a, h0.d, linkconnection.a).
 *
 * Заголовок 16 байт: "5A5A", int totalSize @4, short extHeaderSize @8, byte msgType @10, byte @11,
 * byte @12, byte payloadFormat @13, byte reserved @14, byte @15. totalSize — без дополнения: по USB
 * пакет дополняется нулями до кратного 512.
 */
public final class NewPackets {
    public static final int HEADER = 16;
    public static final int VIDEO_EXT = 32;

    public static final int TYPE_CMD = 0;
    public static final int TYPE_VIDEO = 1;
    public static final int TYPE_TOUCH = 2;
    public static final int TYPE_APP = 13;
    public static final int TYPE_CUSTOM = 99;

    public static final int FORMAT_BINARY = 0;
    public static final int FORMAT_JSON = 1;
    public static final int FORMAT_VIDEO = 2;

    private NewPackets() {
    }

    private static void header(ByteBuffer b, int total, int extSize, int msgType, int payloadFormat) {
        b.put((byte) '5').put((byte) 'A').put((byte) '5').put((byte) 'A');
        b.putInt(total);
        b.putShort((short) extSize);
        b.put((byte) msgType);
        b.put((byte) 0);
        b.put((byte) 0);
        b.put((byte) payloadFormat);
        b.put((byte) 0);
        b.put((byte) 0);
    }

    /** Команда {"CMD":…,"PARA":…} (msgType 0, формат JSON). */
    public static byte[] json(String json) {
        return jsonPacket(TYPE_CMD, json);
    }

    /** Прикладное сообщение {"AppID":…,"FunctionID":…,"Para":…} (msgType 13, формат JSON), как linkconnection.a.k0. */
    public static byte[] app(String json) {
        return jsonPacket(TYPE_APP, json);
    }

    private static byte[] jsonPacket(int msgType, String json) {
        byte[] payload = json.getBytes(StandardCharsets.UTF_8);
        int total = HEADER + payload.length;
        ByteBuffer b = ByteBuffer.allocate(Packets.padded(total));
        header(b, total, 0, msgType, FORMAT_JSON);
        b.put(payload);
        return b.array();
    }

    /**
     * Видеопакет (linkconnection.a.l0): заголовок, расширение 32 байта (класс h0.d), данные H.264 с @48.
     * Размер в расширении — размер кадра в потоке; частота, битрейт и интервал — из VIDEO_ARGS ГУ.
     */
    public static byte[] video(Packets.Video v, byte[] data, int off, int len) {
        int total = HEADER + VIDEO_EXT + len;
        ByteBuffer b = ByteBuffer.allocate(Packets.padded(total));
        header(b, total, VIDEO_EXT, TYPE_VIDEO, FORMAT_VIDEO);
        b.putShort((short) VIDEO_EXT);
        b.put((byte) 1);
        b.put((byte) 0);
        b.putInt(v.frameWidth > 0 ? v.frameWidth : v.width);
        b.putInt(v.frameHeight > 0 ? v.frameHeight : v.height);
        b.putShort((short) v.angle);
        b.put((byte) v.orientation);
        b.put((byte) v.encodingType);
        b.putInt(v.frameRate);
        b.putInt(v.bitRate);
        b.putInt(v.frameInterval);
        b.put((byte) v.appCapture);
        b.position(HEADER + VIDEO_EXT);
        b.put(data, off, len);
        return b.array();
    }

    /**
     * Касание (тип 2, класс h0.e): int action, byte число пальцев, на палец — byte номер, byte действие (1 нажатие,
     * 2 отпускание, 3 движение), float x, float y. Родной QDLink берёт только палец 0, мы — все. Все пальцы пакета —
     * один момент: сначала одно движение со всеми, затем нажатия и отпускания по порядку, каждое — с местом всех
     * пальцев. null — пакет испорчен (короткий, обрезанный, неизвестное действие, координата не число): его не
     * применяют даже частично.
     */
    public static List<TouchPointers.Frame> touchFrames(byte[] b, int off, int len) {
        int count = len >= 5 ? b[off + 4] & 0xff : -1;
        if (count < 0 || len - 5 < count * 10) {
            return null;
        }
        ByteBuffer bb = ByteBuffer.wrap(b, off + 5, len - 5);
        List<TouchPointers.Pointer> all = new ArrayList<>(count);
        // Нажатия и отпускания: [номер, действие] — кадры из них собираются, когда известны места всех пальцев.
        List<int[]> changes = new ArrayList<>();
        boolean moved = false;
        for (int i = 0; i < count; i++) {
            int id = bb.get() & 0xff;
            int fingerAction = bb.get();
            float x = bb.getFloat();
            float y = bb.getFloat();
            if (fingerAction < 1 || fingerAction > 3 || !Float.isFinite(x) || !Float.isFinite(y)) {
                return null;
            }
            all.add(new TouchPointers.Pointer(id, x, y));
            // Действия кадра — как в MotionEvent: 0 нажатие, 1 отпускание, 2 движение.
            if (fingerAction == 3) {
                moved = true;
            } else {
                changes.add(new int[]{id, fingerAction == 1 ? 0 : 1});
            }
        }
        List<TouchPointers.Frame> out = new ArrayList<>(changes.size() + 1);
        if (moved) {
            out.add(new TouchPointers.Frame(2, 0, all));
        }
        for (int[] c : changes) {
            out.add(new TouchPointers.Frame(c[1], c[0], all));
        }
        return out;
    }
}
