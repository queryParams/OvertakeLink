package io.github.queryparams.overtakelink.car;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Bundle;
import android.view.Display;
import android.view.Surface;
import android.view.WindowManager;

import java.nio.ByteBuffer;

import io.github.queryparams.overtakelink.link.EventLog;
import io.github.queryparams.overtakelink.link.Packets;

/**
 * Экран машины: приватный виртуальный дисплей, который рисует прямо во вход кодировщика H.264
 * (так же устроен режим «в приложении» у родного QDLink). На этот дисплей запускается экран
 * проекции Android Auto; кадры кодировщика уходят на ГУ через {@link Sink}.
 */
public final class CarScreen {

    public interface Sink {
        void onCodecConfig(byte[] spsPps);

        void onFrame(byte[] data, int off, int len, boolean keyFrame);
    }

    private static final String DISPLAY_NAME = "OvertakeLink";
    /** Как в записи сеанса родного QDLink с этим ГУ. */
    public static final int FRAME_RATE = 24;
    /** Один повтор последнего кадра после изменения: кодировщик дочищает картинку до полного качества. */
    private static final long REPEAT_FRAME_US = 200_000;

    private static volatile CarScreen current;

    private final int width;
    private final int height;
    private final int dpi;
    private final Sink sink;
    private MediaCodec codec;
    private Surface input;
    private VirtualDisplay display;
    private Thread drainThread;
    private volatile boolean running;
    private volatile boolean resendConfig;
    private byte[] spsPps;

    public CarScreen(int width, int height, int dpi, Sink sink) {
        this.width = width;
        this.height = height;
        this.dpi = dpi;
        this.sink = sink;
    }

    /** Активный экран машины или null. */
    public static CarScreen current() {
        return current;
    }

    public int displayId() {
        VirtualDisplay d = display;
        return d == null ? -1 : d.getDisplay().getDisplayId();
    }

    /** Контекст окна на дисплее машины: по нему Open Headunit считает геометрию для Android Auto. */
    public static Context windowContext(Context base) {
        CarScreen s = current;
        VirtualDisplay d = s == null ? null : s.display;
        if (d == null) {
            return null;
        }
        return base.createWindowContext(d.getDisplay(), WindowManager.LayoutParams.TYPE_PRIVATE_PRESENTATION, null);
    }

    public Display display() {
        VirtualDisplay d = display;
        return d == null ? null : d.getDisplay();
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    /** Прямой режим: дисплей без поверхности — только размеры экрана машины для расчётов AA. */
    public void startDirect(Context context) {
        DisplayManager dm = context.getSystemService(DisplayManager.class);
        display = dm.createVirtualDisplay(DISPLAY_NAME, width, height, dpi, null,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY | DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION);
        current = this;
    }

    public void start(Context context, Packets.Video v) throws Exception {
        configureCodec(v);
        input = codec.createInputSurface();
        codec.start();
        DisplayManager dm = context.getSystemService(DisplayManager.class);
        display = dm.createVirtualDisplay(DISPLAY_NAME, width, height, dpi, input,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY | DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION);
        running = true;
        drainThread = new Thread(this::drainLoop, "ol-encoder");
        drainThread.start();
        current = this;
        EventLog.add("Экран машины: виртуальный дисплей #" + displayId() + " " + width + "x" + height + " @" + dpi + " dpi, кодировщик «"
                + codec.getName() + "», " + v.frameRate + " к/с, " + v.bitRate + " бит/с, ключевой кадр раз в " + v.frameInterval + " с");
    }

    private void configureCodec(Packets.Video v) throws Exception {
        // Baseline, как поток родного приложения в записи сеанса. Уровень 4.0 вмещает 1920x590; без уровня и без профиля — запасные варианты.
        int[][] variants = {
                {MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline, MediaCodecInfo.CodecProfileLevel.AVCLevel4},
                {MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline, 0},
                {0, 0},
        };
        Exception last = null;
        for (int[] variant : variants) {
            MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            format.setInteger(MediaFormat.KEY_BIT_RATE, v.bitRate);
            format.setInteger(MediaFormat.KEY_FRAME_RATE, v.frameRate);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, v.frameInterval);
            format.setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, REPEAT_FRAME_US);
            // Низкая задержка: кадр на выходе сразу, без накопления; приоритет реального времени.
            format.setInteger(MediaFormat.KEY_LATENCY, 1);
            format.setInteger(MediaFormat.KEY_PRIORITY, 0);
            if (variant[0] != 0) {
                format.setInteger(MediaFormat.KEY_PROFILE, variant[0]);
            }
            if (variant[1] != 0) {
                format.setInteger(MediaFormat.KEY_LEVEL, variant[1]);
            }
            MediaCodec c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            try {
                c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                codec = c;
                return;
            } catch (Exception e) {
                last = e;
                EventLog.add("Экран машины: кодировщик не принял " + format + ": " + e);
                c.release();
            }
        }
        throw last;
    }

    public void requestKeyFrame() {
        resendConfig = true;
        MediaCodec c = codec;
        if (c != null) {
            Bundle b = new Bundle();
            b.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
            try {
                c.setParameters(b);
            } catch (IllegalStateException ignored) {
            }
        }
    }

    /** Битрейт, который попросило ГУ (приходит позже, чем поднимается экран). */
    public void setBitRate(int bitRate) {
        MediaCodec c = codec;
        if (c != null && bitRate > 0) {
            Bundle b = new Bundle();
            b.putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, bitRate);
            try {
                c.setParameters(b);
            } catch (IllegalStateException ignored) {
            }
        }
    }

    public void stop() {
        if (current == this) {
            current = null;
        }
        running = false;
        if (drainThread != null) {
            try {
                drainThread.join(1000);
            } catch (InterruptedException ignored) {
            }
        }
        if (display != null) {
            display.release();
            display = null;
        }
        if (codec != null) {
            try {
                codec.stop();
            } catch (Exception ignored) {
            }
            codec.release();
            codec = null;
        }
        if (input != null) {
            input.release();
            input = null;
        }
        EventLog.add("Экран машины: остановлен");
    }

    private void drainLoop() {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        byte[] buf = new byte[0];
        while (running) {
            int index;
            try {
                index = codec.dequeueOutputBuffer(info, 100_000);
            } catch (Exception e) {
                if (running) {
                    EventLog.error("кодировщик экрана машины", e);
                }
                return;
            }
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                MediaFormat f = codec.getOutputFormat();
                ByteBuffer sps = f.getByteBuffer("csd-0");
                ByteBuffer pps = f.getByteBuffer("csd-1");
                spsPps = new byte[sps.remaining() + pps.remaining()];
                sps.get(spsPps, 0, sps.remaining());
                pps.get(spsPps, spsPps.length - pps.remaining(), pps.remaining());
                EventLog.add("Экран машины: SPS+PPS " + spsPps.length + " байт: " + EventLog.hex(spsPps, 0, spsPps.length, 64));
                sink.onCodecConfig(spsPps);
                continue;
            }
            if (index < 0) {
                continue;
            }
            try {
                if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && info.size > 0) {
                    boolean key = (info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;
                    if (key && resendConfig && spsPps != null) {
                        resendConfig = false;
                        sink.onCodecConfig(spsPps);
                    }
                    ByteBuffer out = codec.getOutputBuffer(index);
                    if (buf.length < info.size) {
                        buf = new byte[info.size];
                    }
                    out.position(info.offset);
                    out.get(buf, 0, info.size);
                    sink.onFrame(buf, 0, info.size, key);
                }
                codec.releaseOutputBuffer(index, false);
            } catch (Exception e) {
                if (running) {
                    EventLog.error("выдача кадра экрана машины", e);
                }
                return;
            }
        }
    }
}
