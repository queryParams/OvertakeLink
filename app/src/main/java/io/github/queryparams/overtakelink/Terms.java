package io.github.queryparams.overtakelink;

import android.content.Context;

/**
 * Условия использования: показываются при первом запуске и до принятия не дают начать работу
 * с машиной. Ничего не ограничивают сверх AGPL-3.0 — только фиксируют, что пользователь знает о
 * рисках. Текст — строка ol_terms_text; изменился по существу — поднять {@link #VERSION}, условия спросят заново.
 */
public final class Terms {
    public static final int VERSION = 1;

    private Terms() {
    }

    public static boolean accepted(Context context) {
        return context.getSharedPreferences("overtakelink", Context.MODE_PRIVATE).getInt("termsAccepted", 0) >= VERSION;
    }

    public static void accept(Context context) {
        context.getSharedPreferences("overtakelink", Context.MODE_PRIVATE).edit().putInt("termsAccepted", VERSION).apply();
    }
}
