package io.github.queryparams.overtakelink.car;

import java.io.ByteArrayOutputStream;

/**
 * Переписывает H.264 SPS так, чтобы декодер показывал только прямоугольник кадра (frame cropping).
 * Android Auto рисует интерфейс посередине кадра стандартного размера, остальное — поля;
 * с обрезкой ГУ видит ровно свой экран без перекодирования.
 */
public final class SpsCrop {
    private SpsCrop() {
    }

    /** Результат: новый NAL SPS (без стартового кода) и полный размер кадра в потоке. */
    public static final class Result {
        public final byte[] nal;
        public final int codedWidth;
        public final int codedHeight;
        public final int profile;
        /** level_idc: 40 — уровень 4.0 и т. д. */
        public final int level;

        Result(byte[] nal, int codedWidth, int codedHeight, int profile, int level) {
            this.nal = nal;
            this.codedWidth = codedWidth;
            this.codedHeight = codedHeight;
            this.profile = profile;
            this.level = level;
        }
    }

    /**
     * @param nal SPS без стартового кода, начиная с заголовка NAL
     * @param left отступ видимой области слева (округляется вниз до шага обрезки)
     * @param top отступ видимой области сверху (округляется вниз до шага обрезки)
     */
    public static Result crop(byte[] nal, int off, int len, int left, int top, int width, int height) {
        byte[] rbsp = unescape(nal, off + 1, len - 1);
        BitReader r = new BitReader(rbsp);
        BitWriter w = new BitWriter();
        int profile = r.u(8);
        w.u(profile, 8);
        w.u(r.u(8), 8);
        int level = r.u(8);
        w.u(level, 8);
        w.ue(r.ue());
        int chromaFormat = 1;
        boolean separatePlanes = false;
        if (profile == 100 || profile == 110 || profile == 122 || profile == 244 || profile == 44 || profile == 83
                || profile == 86 || profile == 118 || profile == 128 || profile == 138 || profile == 139 || profile == 134 || profile == 135) {
            chromaFormat = r.ue();
            w.ue(chromaFormat);
            if (chromaFormat == 3) {
                separatePlanes = r.u(1) == 1;
                w.u(separatePlanes ? 1 : 0, 1);
            }
            w.ue(r.ue());
            w.ue(r.ue());
            w.u(r.u(1), 1);
            int scaling = r.u(1);
            w.u(scaling, 1);
            if (scaling == 1) {
                for (int i = 0; i < (chromaFormat != 3 ? 8 : 12); i++) {
                    int present = r.u(1);
                    w.u(present, 1);
                    if (present == 1) {
                        copyScalingList(r, w, i < 6 ? 16 : 64);
                    }
                }
            }
        }
        w.ue(r.ue());
        int pocType = r.ue();
        w.ue(pocType);
        if (pocType == 0) {
            w.ue(r.ue());
        } else if (pocType == 1) {
            w.u(r.u(1), 1);
            w.se(r.se());
            w.se(r.se());
            int cycle = r.ue();
            w.ue(cycle);
            for (int i = 0; i < cycle; i++) {
                w.se(r.se());
            }
        }
        w.ue(r.ue());
        w.u(r.u(1), 1);
        int widthMbs = r.ue() + 1;
        w.ue(widthMbs - 1);
        int heightUnits = r.ue() + 1;
        w.ue(heightUnits - 1);
        int frameMbsOnly = r.u(1);
        w.u(frameMbsOnly, 1);
        if (frameMbsOnly == 0) {
            w.u(r.u(1), 1);
        }
        w.u(r.u(1), 1);
        if (r.u(1) == 1) {
            r.ue();
            r.ue();
            r.ue();
            r.ue();
        }
        int codedWidth = widthMbs * 16;
        int codedHeight = heightUnits * 16 * (2 - frameMbsOnly);
        int arrayType = separatePlanes ? 0 : chromaFormat;
        int unitX = arrayType == 0 || arrayType == 3 ? 1 : 2;
        int unitY = (arrayType == 1 ? 2 : 1) * (2 - frameMbsOnly);
        int cropLeft = left / unitX;
        int cropTop = top / unitY;
        int right = Math.max(0, (codedWidth - cropLeft * unitX - width) / unitX);
        int bottom = Math.max(0, (codedHeight - cropTop * unitY - height) / unitY);
        w.u(1, 1);
        w.ue(cropLeft);
        w.ue(right);
        w.ue(cropTop);
        w.ue(bottom);
        // Остаток (VUI) копируем как есть, до стоп-бита RBSP.
        int end = r.stopBitPosition();
        while (r.position() < end) {
            w.u(r.u(1), 1);
        }
        w.u(1, 1);
        w.align();
        byte[] out = escape(w.toByteArray());
        byte[] result = new byte[out.length + 1];
        result[0] = nal[off];
        System.arraycopy(out, 0, result, 1, out.length);
        return new Result(result, codedWidth, codedHeight, profile, level);
    }

    private static void copyScalingList(BitReader r, BitWriter w, int size) {
        int last = 8;
        int next = 8;
        for (int j = 0; j < size; j++) {
            if (next != 0) {
                int delta = r.se();
                w.se(delta);
                next = (last + delta + 256) % 256;
            }
            last = next == 0 ? last : next;
        }
    }

    static byte[] unescape(byte[] b, int off, int len) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(len);
        int zeros = 0;
        for (int i = off; i < off + len; i++) {
            int v = b[i] & 0xff;
            if (zeros >= 2 && v == 3) {
                zeros = 0;
                continue;
            }
            zeros = v == 0 ? zeros + 1 : 0;
            out.write(v);
        }
        return out.toByteArray();
    }

    static byte[] escape(byte[] rbsp) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(rbsp.length + 8);
        int zeros = 0;
        for (byte x : rbsp) {
            int v = x & 0xff;
            if (zeros >= 2 && v <= 3) {
                out.write(3);
                zeros = 0;
            }
            zeros = v == 0 ? zeros + 1 : 0;
            out.write(v);
        }
        return out.toByteArray();
    }

    private static final class BitReader {
        private final byte[] b;
        private int pos;

        BitReader(byte[] b) {
            this.b = b;
        }

        int position() {
            return pos;
        }

        int u(int n) {
            int v = 0;
            for (int i = 0; i < n; i++) {
                v = (v << 1) | ((b[pos >> 3] >> (7 - (pos & 7))) & 1);
                pos++;
            }
            return v;
        }

        int ue() {
            int zeros = 0;
            while (u(1) == 0) {
                zeros++;
            }
            return zeros == 0 ? 0 : (1 << zeros) - 1 + u(zeros);
        }

        int se() {
            int k = ue();
            return (k & 1) == 1 ? (k + 1) / 2 : -(k / 2);
        }

        /** Позиция последнего единичного бита (стоп-бит RBSP). */
        int stopBitPosition() {
            for (int i = b.length * 8 - 1; i >= 0; i--) {
                if (((b[i >> 3] >> (7 - (i & 7))) & 1) == 1) {
                    return i;
                }
            }
            return 0;
        }
    }

    private static final class BitWriter {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        private int cur;
        private int bits;

        void u(int v, int n) {
            for (int i = n - 1; i >= 0; i--) {
                cur = (cur << 1) | ((v >> i) & 1);
                if (++bits == 8) {
                    out.write(cur);
                    cur = 0;
                    bits = 0;
                }
            }
        }

        void ue(int v) {
            int x = v + 1;
            int len = 32 - Integer.numberOfLeadingZeros(x);
            u(0, len - 1);
            u(x, len);
        }

        void se(int v) {
            ue(v > 0 ? 2 * v - 1 : -2 * v);
        }

        void align() {
            while (bits != 0) {
                u(0, 1);
            }
        }

        byte[] toByteArray() {
            return out.toByteArray();
        }
    }
}
