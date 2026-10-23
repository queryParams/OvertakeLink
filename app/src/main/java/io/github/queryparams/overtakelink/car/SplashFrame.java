package io.github.queryparams.overtakelink.car;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.view.Surface;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

/**
 * Один кадр-заставка для экрана машины в прямом режиме (когда картинки от Android Auto нет):
 * текст на чёрном, сжатый в H.264 вместе с SPS/PPS — ГУ показывает его как обычный ключевой кадр.
 */
final class SplashFrame {
    private SplashFrame() {
    }

    static byte[] encode(int width, int height, String text) throws Exception {
        MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline);
        format.setInteger(MediaFormat.KEY_BIT_RATE, 1_000_000);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, 10);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
        MediaCodec codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
        Surface input = null;
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            input = codec.createInputSurface();
            codec.start();
            for (int i = 0; i < 2; i++) {
                Canvas c = input.lockHardwareCanvas();
                draw(c, width, height, text);
                input.unlockCanvasAndPost(c);
            }
            codec.signalEndOfInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean key = false;
            long deadline = System.currentTimeMillis() + 3000;
            while (!key && System.currentTimeMillis() < deadline) {
                int index = codec.dequeueOutputBuffer(info, 100_000);
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat f = codec.getOutputFormat();
                    write(out, f.getByteBuffer("csd-0"));
                    write(out, f.getByteBuffer("csd-1"));
                } else if (index >= 0) {
                    ByteBuffer b = codec.getOutputBuffer(index);
                    b.position(info.offset);
                    b.limit(info.offset + info.size);
                    boolean config = (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
                    if (!config || out.size() == 0) {
                        write(out, b);
                    }
                    key = (info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;
                    codec.releaseOutputBuffer(index, false);
                }
            }
            if (!key) {
                throw new IllegalStateException("кодировщик не выдал ключевой кадр");
            }
            return out.toByteArray();
        } finally {
            codec.release();
            if (input != null) {
                input.release();
            }
        }
    }

    private static void draw(Canvas c, int width, int height, String text) {
        c.drawColor(Color.BLACK);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.WHITE);
        p.setTextSize(height / 9f);
        p.setTextAlign(Paint.Align.CENTER);
        String[] lines = text.split("\n");
        float widest = 0;
        for (String line : lines) {
            widest = Math.max(widest, p.measureText(line));
        }
        if (widest > width * 0.9f) {
            p.setTextSize(p.getTextSize() * width * 0.9f / widest);
        }
        float step = p.getTextSize() * 1.4f;
        float y = height / 2f - step * (lines.length - 1) / 2f + p.getTextSize() / 3f;
        for (String line : lines) {
            c.drawText(line, width / 2f, y, p);
            y += step;
        }
    }

    private static void write(ByteArrayOutputStream out, ByteBuffer b) {
        byte[] data = new byte[b.remaining()];
        b.get(data);
        out.write(data, 0, data.length);
    }
}
