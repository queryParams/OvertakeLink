package io.github.queryparams.overtakelink.link;

/** Параметры видео для ГУ и выравнивание пакетов по блокам USB. */
public final class Packets {
    public static final int BLOCK = 512;

    private Packets() {
    }

    /** Параметры видеозаголовка: размер, поворот, частота и битрейт, размер экрана телефона. */
    public static final class Video {
        public int width;
        public int height;
        public int angle = 90;
        public int orientation = 1;
        public int encodingType = 3;
        public int frameRate = 24;
        public int bitRate;
        public int frameInterval = 4;
        public int appCapture = 1;
        public int phoneWidth;
        public int phoneHeight;
    }

    /** Размер, дополненный нулями до кратного 512: по USB пакеты идут целыми блоками. */
    public static int padded(int size) {
        int rest = size % BLOCK;
        return rest == 0 ? size : size + BLOCK - rest;
    }
}
