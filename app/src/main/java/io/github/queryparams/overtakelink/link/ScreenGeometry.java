package io.github.queryparams.overtakelink.link;

import android.content.Context;
import android.graphics.Point;
import android.hardware.display.DisplayManager;
import android.view.Display;

/**
 * Размеры, которые телефон сообщает ГУ: окно приложения — экран машины (с чётными сторонами),
 * «зеркало» — экран телефона, вписанный в экран машины с сохранением пропорций. Для телефона
 * 3200×1440 и экрана 1920×590 это 1920×590 и 1312×590 — как в записи сеанса родного приложения.
 */
public final class ScreenGeometry {
    public final int phoneLong;
    public final int phoneShort;
    public final int inAppWidth;
    public final int inAppHeight;
    public final int outAppWidth;
    public final int outAppHeight;

    private ScreenGeometry(int phoneLong, int phoneShort, int inW, int inH, int outW, int outH) {
        this.phoneLong = phoneLong;
        this.phoneShort = phoneShort;
        this.inAppWidth = inW;
        this.inAppHeight = inH;
        this.outAppWidth = outW;
        this.outAppHeight = outH;
    }

    public static ScreenGeometry of(Context context, int carW, int carH) {
        Display d = context.getSystemService(DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY);
        Point p = new Point();
        d.getRealSize(p);
        return compute(Math.max(p.x, p.y), Math.min(p.x, p.y), carW, carH);
    }

    static ScreenGeometry compute(int phoneLong, int phoneShort, int carW, int carH) {
        int inW = even(carW);
        int inH = even(carH);
        if (carW <= carH) {
            return new ScreenGeometry(phoneLong, phoneShort, inW, inH, inW, inH);
        }
        float carRatio = carW / (float) carH;
        float phoneRatio = phoneLong / (float) phoneShort;
        int outW;
        int outH;
        if (carRatio > phoneRatio) {
            outW = (int) (inH * phoneRatio);
            outH = inH;
        } else {
            outW = inW;
            outH = (int) (inW / phoneRatio);
        }
        return new ScreenGeometry(phoneLong, phoneShort, inW, inH, even(outW), even(outH));
    }

    private static int even(int v) {
        return v % 2 != 0 ? v + 1 : v;
    }

    @Override
    public String toString() {
        return "телефон " + phoneLong + "x" + phoneShort + ", в приложении " + inAppWidth + "x" + inAppHeight
                + ", зеркало " + outAppWidth + "x" + outAppHeight;
    }
}
