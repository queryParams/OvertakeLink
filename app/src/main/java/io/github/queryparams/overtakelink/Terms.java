package io.github.queryparams.overtakelink;

import android.content.Context;

/**
 * Условия использования: показываются при первом запуске и до принятия не дают начать работу
 * с машиной. Ничего не ограничивают сверх AGPL-3.0 — только фиксируют, что пользователь знает о
 * рисках. Изменился текст по существу — поднять {@link #VERSION}, условия спросят заново.
 */
public final class Terms {
    public static final int VERSION = 1;

    public static final String TITLE = "Условия использования";

    public static final String TEXT =
            "1. OvertakeLink — бесплатная программа с открытым кодом (лицензия GNU AGPL-3.0). "
            + "Вы можете пользоваться ею, изучать, изменять и распространять на условиях этой лицензии.\n\n"
            + "2. Программа предоставляется «как есть», без каких-либо гарантий. Вы используете её на свой риск. "
            + "Авторы не отвечают за любой ущерб — телефону, головному устройству, автомобилю, данным — "
            + "и за последствия на дороге.\n\n"
            + "3. Безопасность. Не отвлекайтесь от дороги. Настраивайте OvertakeLink и Android Auto только на стоянке. "
            + "Соблюдайте ПДД.\n\n"
            + "4. OvertakeLink — независимый проект и не связан с Geely, Neusoft (QDLink) или Google. "
            + "Названия этих компаний и их продуктов — товарные знаки владельцев.\n\n"
            + "5. OvertakeLink использует режим разработчика Android Auto. Google может изменить его в любой момент — "
            + "тогда OvertakeLink может перестать работать.\n\n"
            + "6. Данные. OvertakeLink не собирает и никуда не отправляет ваши данные. Журнал работы хранится на телефоне, "
            + "передаёте его вы сами. Для проверки обновлений приложение обращается к GitHub.";

    private Terms() {
    }

    public static boolean accepted(Context context) {
        return context.getSharedPreferences("overtakelink", Context.MODE_PRIVATE).getInt("termsAccepted", 0) >= VERSION;
    }

    public static void accept(Context context) {
        context.getSharedPreferences("overtakelink", Context.MODE_PRIVATE).edit().putInt("termsAccepted", VERSION).apply();
    }
}
