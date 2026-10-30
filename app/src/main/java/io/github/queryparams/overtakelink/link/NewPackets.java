package io.github.queryparams.overtakelink.link;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

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
    public static final int TYPE_SPEECH = 12;
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
        byte[] payload = json.getBytes(StandardCharsets.UTF_8);
        int total = HEADER + payload.length;
        ByteBuffer b = ByteBuffer.allocate(Packets.padded(total));
        header(b, total, 0, TYPE_CMD, FORMAT_JSON);
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
}
