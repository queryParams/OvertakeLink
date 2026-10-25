package io.github.queryparams.overtakelink;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Заставка при запуске: сверху падает «Link» и, отскочив, встаёт по центру; из буквы L влево
 * вылетает «Overtake» — с наклоном, как на скорости, и оранжевым следом; «Link» сдвигается вправо.
 * Потом слой растворяется. Кадры считаются по часам, а не системными аниматорами: заставка идёт
 * целиком, даже если анимации в настройках Android выключены.
 */
final class SplashOverlay {
    private static final float TEXT_SP = 36;
    private static final long DROP_MS = 800;
    private static final long OVERTAKE_AT_MS = 850;
    private static final long OVERTAKE_MS = 520;
    private static final long STREAK_MS = 600;
    private static final long FADE_AT_MS = OVERTAKE_AT_MS + OVERTAKE_MS + 350;
    private static final long FADE_MS = 250;
    private static final PathInterpolator FALL = new PathInterpolator(0.3f, 0f, 0.4f, 1f);
    private static final PathInterpolator RUSH = new PathInterpolator(0.15f, 0.9f, 0.2f, 1f);
    /** Падение с отскоком: доля времени → смещение вверх в долях высоты отскока (1 = 22 dp). */
    private static final float[][] BOUNCE = {{0.60f, 0f}, {0.74f, 1f}, {0.86f, 0f}, {0.93f, 0.27f}, {1f, 0f}};

    private SplashOverlay() {
    }

    static void show(FrameLayout parent) {
        Context c = parent.getContext();
        float dp = c.getResources().getDisplayMetrics().density;

        FrameLayout overlay = new FrameLayout(c);
        overlay.setBackgroundColor(0xFF000000);
        overlay.setClickable(true);

        // След «обгона»: три оранжевые полосы, уходящие влево от центра.
        View[] streaks = new View[3];
        for (int i = 0; i < streaks.length; i++) {
            View s = new View(c);
            GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                    new int[]{0x00FF7A45, OvertakeLinkActivity.ACCENT});
            g.setCornerRadius(dp);
            s.setBackground(g);
            s.setAlpha(0f);
            s.setPivotX(140 * dp);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams((int) (140 * dp), (int) (2 * dp), Gravity.CENTER);
            lp.topMargin = (int) ((i - 1) * 13 * dp);
            overlay.addView(s, lp);
            streaks[i] = s;
        }

        LinearLayout word = new LinearLayout(c);
        word.setOrientation(LinearLayout.HORIZONTAL);
        TextView over = label(c, "Overtake", OvertakeLinkActivity.TEXT);
        TextView link = label(c, "Link", OvertakeLinkActivity.ACCENT);
        int overWidth = (int) Math.ceil(over.getPaint().measureText("Overtake"));
        // «Overtake» прижат к букве L и показывается по мере роста рамки — словно выезжает из неё.
        FrameLayout clip = new FrameLayout(c);
        clip.setClipChildren(true);
        clip.addView(over, new FrameLayout.LayoutParams(overWidth, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END));
        word.addView(clip, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT));
        word.addView(link);
        overlay.addView(word, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        parent.addView(overlay, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        float drop = c.getResources().getDisplayMetrics().heightPixels / 2f;
        long start = SystemClock.uptimeMillis();
        Runnable frame = new Runnable() {
            @Override
            public void run() {
                long t = SystemClock.uptimeMillis() - start;
                link.setTranslationY(fall(t, drop, 22 * dp));

                float p = RUSH.getInterpolation(progress(t, OVERTAKE_AT_MS, OVERTAKE_MS));
                ViewGroup.LayoutParams lp = clip.getLayoutParams();
                int width = Math.round(overWidth * p);
                if (lp.width != width) {
                    lp.width = width;
                    clip.setLayoutParams(lp);
                }
                // Наклон «на скорости»: −18° в начале, к концу выпрямляется.
                over.getPaint().setTextSkewX(-0.32f * (1f - p));
                over.invalidate();

                for (int i = 0; i < streaks.length; i++) {
                    float q = progress(t, OVERTAKE_AT_MS + i * 50L, STREAK_MS);
                    streaks[i].setAlpha(q < 0.3f ? q / 0.3f : (1f - q) / 0.7f);
                    streaks[i].setScaleX(q);
                    streaks[i].setTranslationX(-(70 + 60 * q) * dp);
                }

                overlay.setAlpha(1f - progress(t, FADE_AT_MS, FADE_MS));
                if (t < FADE_AT_MS + FADE_MS) {
                    overlay.postOnAnimation(this);
                } else {
                    parent.removeView(overlay);
                }
            }
        };
        frame.run();
    }

    /** Смещение «Link» по вертикали: падение сверху до центра, затем два затухающих отскока. */
    private static float fall(long t, float drop, float bounce) {
        float x = progress(t, 0, DROP_MS);
        if (x < BOUNCE[0][0]) {
            return -drop * (1f - FALL.getInterpolation(x / BOUNCE[0][0]));
        }
        for (int i = 1; i < BOUNCE.length; i++) {
            if (x <= BOUNCE[i][0]) {
                float k = (x - BOUNCE[i - 1][0]) / (BOUNCE[i][0] - BOUNCE[i - 1][0]);
                float e = (1f - (float) Math.cos(Math.PI * k)) / 2f;
                return -bounce * (BOUNCE[i - 1][1] + (BOUNCE[i][1] - BOUNCE[i - 1][1]) * e);
            }
        }
        return 0f;
    }

    private static float progress(long t, long at, long duration) {
        return Math.max(0f, Math.min(1f, (t - at) / (float) duration));
    }

    private static TextView label(Context c, String s, int color) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, TEXT_SP);
        t.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        t.setIncludeFontPadding(false);
        t.setSingleLine(true);
        return t;
    }
}
