package io.github.queryparams.overtakelink;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.andrerinas.openheadunit.main.SettingsActivity;
import com.andrerinas.openheadunit.utils.ToastUtils;


import io.github.queryparams.overtakelink.link.EventLog;

/** Интерфейс OvertakeLink по макету: Статус · Настройки · Журнал и мастер первой настройки. */
public final class OvertakeLinkActivity extends Activity {
    static final int BG = 0xFF0F1216, CARD = 0xFF181C22, LINE = 0xFF262B33, TEXT = 0xFFE8EAED, MUTED = 0xFFA0A8B3,
            ACCENT = 0xFFFF7A45, OK = 0xFF3DDC97, WARN = 0xFFFFB020, IDLE = 0xFF2E343D;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private LinearLayout content;
    private TextView[] tabs;
    private String screen = "status";
    private int setupStep;
    private volatile boolean aaServerUp;
    private long logVersion = -1;
    /** Состояние, с которым нарисован «Статус» или мастер: изменилось — перерисуем. */
    private String shownSignature = "";
    private TextView logView;

    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            // Перерисовка только при изменениях: пересборка экрана теряет нажатие, попавшее на неё.
            if (screen.equals("status") || screen.equals("setup")) {
                if (!screenSignature().equals(shownSignature)) {
                    render();
                }
            } else if (screen.equals("log") && logView != null && EventLog.version() != logVersion) {
                logVersion = EventLog.version();
                logView.setText(EventLog.tail(300));
            }
            ui.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        EventLog.init(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });

        LinearLayout head = row();
        head.setPadding(dp(20), dp(16), dp(20), dp(8));
        TextView logo = text("OvertakeLink", 22, TEXT, true);
        logo.setText(android.text.Html.fromHtml("Overtake<font color='#FF7A45'>Link</font>", 0));
        head.addView(logo, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        // Экран машины — каким его сообщило ГУ при последнем подключении.
        String car = getSharedPreferences("overtakelink", MODE_PRIVATE).getString("carScreen", null);
        head.addView(text(car == null ? "машина ещё не подключалась" : "экран машины " + car, 12, MUTED, false));
        root.addView(head);

        ScrollView scroll = new ScrollView(this);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(8), dp(16), dp(16));
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        LinearLayout nav = row();
        nav.setBackgroundColor(0xFF12161B);
        nav.setPadding(dp(8), dp(8), dp(8), dp(12));
        String[][] items = {{"status", "Статус"}, {"settings", "Настройки"}, {"log", "Журнал"}};
        tabs = new TextView[items.length];
        for (int i = 0; i < items.length; i++) {
            String id = items[i][0];
            TextView t = text(items[i][1], 13, MUTED, true);
            t.setGravity(Gravity.CENTER);
            t.setPadding(0, dp(12), 0, dp(12));
            t.setOnClickListener(v -> show(id));
            tabs[i] = t;
            nav.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        }
        root.addView(nav);
        setContentView(root);

        boolean firstRun = !getSharedPreferences("overtakelink", MODE_PRIVATE).getBoolean("setupDone", false);
        show(!Terms.accepted(this) ? "terms" : firstRun ? "setup" : "status");
    }

    @Override
    protected void onResume() {
        super.onResume();
        checkAaServer();
        UpdateCheck.maybeCheck(this);
        ui.post(refresh);
    }

    @Override
    protected void onPause() {
        ui.removeCallbacks(refresh);
        super.onPause();
    }

    private void show(String id) {
        // Пока условия не приняты, вкладки ведут только на них.
        screen = Terms.accepted(this) ? id : "terms";
        String[] ids = {"status", "settings", "log"};
        for (int i = 0; i < tabs.length; i++) {
            tabs[i].setTextColor(ids[i].equals(id) ? ACCENT : MUTED);
        }
        render();
    }

    private void render() {
        shownSignature = screenSignature();
        content.removeAllViews();
        logView = null;
        switch (screen) {
            case "setup": renderSetup(); break;
            case "terms": renderTerms(); break;
            case "settings": renderSettings(); break;
            case "log": renderLog(); break;
            default: renderStatus();
        }
    }

    // ---------- Статус ----------

    private void renderStatus() {
        String update = UpdateCheck.availableVersion(this);
        if (update != null) {
            LinearLayout u = card();
            u.addView(text("Доступна версия " + update, 16, TEXT, true));
            u.addView(text("Скачайте APK и установите поверх — настройки сохранятся.", 13, MUTED, false));
            u.addView(button("Скачать", true, v -> startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(UpdateCheck.downloadUrl(this))))));
            content.addView(u, cardLp());
        }
        OvertakeLinkService.State st = OvertakeLinkService.state();
        LinearLayout card = card();
        String title = st == OvertakeLinkService.State.RUNNING ? "Android Auto на экране машины"
                : st == OvertakeLinkService.State.HANDSHAKE ? "Подключаемся к машине" : "Ждём машину";
        card.addView(text(title, 19, TEXT, true));
        card.addView(text(OvertakeLinkService.detail(), 13, MUTED, false));
        int done = st == OvertakeLinkService.State.RUNNING ? 4 : st == OvertakeLinkService.State.HANDSHAKE ? 2 : 0;
        LinearLayout steps = row();
        String[] labels = {"USB", "ГУ", "Android Auto", "Видео"};
        for (int i = 0; i < labels.length; i++) {
            LinearLayout s = new LinearLayout(this);
            s.setOrientation(LinearLayout.VERTICAL);
            s.setGravity(Gravity.CENTER_HORIZONTAL);
            View bar = new View(this);
            bar.setBackground(round(i < done ? OK : (i == done && st == OvertakeLinkService.State.HANDSHAKE ? WARN : IDLE), 2));
            s.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(4)));
            TextView l = text(labels[i], 11, i < done ? TEXT : MUTED, false);
            l.setPadding(0, dp(6), 0, 0);
            s.addView(l);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            lp.setMargins(dp(3), dp(10), dp(3), dp(4));
            steps.addView(s, lp);
        }
        card.addView(steps);
        if (st != OvertakeLinkService.State.WAITING) {
            card.addView(button("Остановить", false, v -> startService(new Intent(this, OvertakeLinkService.class)
                    .setAction(OvertakeLinkService.ACTION_STOP))));
        } else {
            card.addView(button("Подключить вручную", true, v -> startForegroundService(new Intent(this, OvertakeLinkService.class)
                    .setAction(OvertakeLinkService.ACTION_CONNECT_USB))));
        }
        content.addView(card, cardLp());

        LinearLayout ready = card();
        Check[] checks = checks();
        int okCount = 0;
        for (Check c : checks) {
            if (c.ok) okCount++;
        }
        LinearLayout h = row();
        h.addView(text("Готовность", 16, TEXT, true), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        h.addView(text(okCount + " из " + checks.length + (okCount < checks.length ? " · настроить ›" : ""), 13,
                okCount < checks.length ? WARN : OK, false));
        ready.addView(h);
        for (Check c : checks) {
            ready.addView(checkRow(c));
        }
        ready.setOnClickListener(v -> { setupStep = 0; show("setup"); });
        content.addView(ready, cardLp());
    }

    static final class Check {
        final String label;
        final boolean ok;
        final String note;

        Check(String label, boolean ok, String okNote, String badNote) {
            this.label = label;
            this.ok = ok;
            this.note = ok ? okNote : badNote;
        }
    }

    private Check[] checks() {
        // Разрешение на уведомления появилось в Android 13; до него уведомления разрешены всегда.
        boolean notif = Build.VERSION.SDK_INT < 33
                || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
        boolean mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        boolean battery = getSystemService(PowerManager.class).isIgnoringBatteryOptimizations(getPackageName());
        boolean usbDefault = getSharedPreferences("overtakelink", MODE_PRIVATE).getBoolean("usbSeen", false);
        boolean bt = false;
        for (AudioDeviceInfo d : getSystemService(AudioManager.class).getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            if (d.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP) bt = true;
        }
        return new Check[]{
                new Check("Разрешения", notif && mic, "выданы", "выдать"),
                new Check("Батарея без ограничений", battery, "да", "настроить"),
                new Check("Сервер Android Auto", aaServerUp, "работает", "включить"),
                new Check("OvertakeLink открывается по кабелю", usbDefault, "да", "проверится в машине"),
                new Check("Bluetooth-звук", bt, "подключён", "нет подключения")
        };
    }

    private String screenSignature() {
        StringBuilder sb = new StringBuilder(OvertakeLinkService.state().name()).append('|').append(OvertakeLinkService.detail())
                .append('|').append(UpdateCheck.availableVersion(this)).append('|');
        for (Check c : checks()) {
            sb.append(c.ok ? '1' : '0');
        }
        return sb.toString();
    }

    private void checkAaServer() {
        new Thread(() -> aaServerUp = AaServer.isListening(), "ol-aa-check").start();
    }

    // ---------- Условия использования ----------

    private void renderTerms() {
        boolean accepted = Terms.accepted(this);
        LinearLayout card = card();
        card.addView(text(Terms.TITLE, 22, TEXT, true));
        TextView body = text(Terms.TEXT, 14, 0xFFC3C9D1, false);
        body.setPadding(0, dp(12), 0, dp(12));
        card.addView(body);
        card.addView(text("Полный текст лицензии: github.com/" + UpdateCheck.REPO + "/blob/main/LICENSE", 12, MUTED, false));
        if (accepted) {
            card.addView(button("Назад", false, v -> show("settings")));
        } else {
            card.addView(button("Принимаю", true, v -> {
                Terms.accept(this);
                EventLog.add("Условия использования приняты (версия " + Terms.VERSION + ")");
                boolean firstRun = !getSharedPreferences("overtakelink", MODE_PRIVATE).getBoolean("setupDone", false);
                show(firstRun ? "setup" : "status");
            }));
            card.addView(button("Не принимаю — закрыть", false, v -> finishAndRemoveTask()));
        }
        content.addView(card, cardLp());
    }

    // ---------- Первая настройка ----------

    private void renderSetup() {
        String[][] steps = {
                {"Разрешения", "Уведомления — чтобы OvertakeLink работал в фоне со значком в шторке.\nМикрофон — для голосовых команд Android Auto.", "Выдать разрешения"},
                {"Батарея и автозапуск", "HyperOS выгружает фоновые приложения.\nПоставьте «Без ограничений» и включите автозапуск, иначе связь с машиной будет обрываться.", "Открыть настройки батареи"},
                {"Сервер Android Auto", "Android Auto → нажать 10 раз на номер версии → ⋮ → «Запустить сервер головного устройства».\nПосле перезагрузки телефона иногда нужно повторить — OvertakeLink напомнит.", "Открыть Android Auto"},
                {"Кабель и выбор по умолчанию", "Подключите телефон кабелем к USB машины.\nAndroid спросит, чем открыть «QDriveLink» — выберите OvertakeLink и отметьте «Всегда».\nВ первый раз Android Auto попросит на телефоне принять условия и дать доступ к уведомлениям — сделайте это на стоянке.\nРодной QDLink лучше удалить.", "Понятно"},
                {"Bluetooth: звук и руль", "Звук Android Auto идёт в машину по Bluetooth, как сейчас музыка. Проверьте, что телефон подключён к машине.\nГолосовая кнопка на руле открывает Ассистента на экране машины. При первом нажатии Android спросит, чем выполнить, — выберите OvertakeLink и «Всегда».", "Открыть Bluetooth"}
        };
        String[] s = steps[setupStep];
        content.addView(text("Первая настройка · шаг " + (setupStep + 1) + " из " + steps.length, 12, MUTED, false));
        LinearLayout bars = row();
        for (int i = 0; i < steps.length; i++) {
            View b = new View(this);
            b.setBackground(round(i <= setupStep ? ACCENT : IDLE, 2));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(4), 1);
            lp.setMargins(dp(3), dp(12), dp(3), dp(12));
            bars.addView(b, lp);
        }
        content.addView(bars);
        content.addView(text(s[0], 24, TEXT, true));
        TextView body = text(s[1], 15, 0xFFC3C9D1, false);
        body.setPadding(0, dp(12), 0, dp(16));
        content.addView(body);
        // Шаг → проверка готовности: разрешения, батарея, сервер AA, (кабель — без проверки), Bluetooth.
        int[] checkOf = {0, 1, 2, -1, 4};
        if (checkOf[setupStep] >= 0) {
            content.addView(checkRow(checks()[checkOf[setupStep]]));
        }
        content.addView(button(s[2], true, v -> setupAction()));
        LinearLayout nav = row();
        nav.addView(button("Назад", false, v -> {
            if (setupStep > 0) { setupStep--; render(); } else show("status");
        }), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        nav.addView(button(setupStep == steps.length - 1 ? "Готово" : "Дальше", false, v -> {
            if (setupStep < steps.length - 1) {
                setupStep++;
                render();
            } else {
                getSharedPreferences("overtakelink", MODE_PRIVATE).edit().putBoolean("setupDone", true).apply();
                show("status");
            }
        }), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        content.addView(nav);
    }

    private void setupAction() {
        try {
            switch (setupStep) {
                case 0:
                    requestPermissions(Build.VERSION.SDK_INT >= 33
                            ? new String[]{Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.RECORD_AUDIO}
                            : new String[]{Manifest.permission.RECORD_AUDIO}, 1);
                    break;
                case 1:
                    startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:" + getPackageName())));
                    break;
                case 2:
                    openAndroidAuto();
                    break;
                case 3:
                    setupStep++;
                    render();
                    break;
                default:
                    startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));
            }
        } catch (Exception e) {
            ToastUtils.showToast(this, "Не открылось: " + e.getMessage(), Toast.LENGTH_LONG, true);
        }
    }

    private void openAndroidAuto() {
        Intent i = new Intent().setClassName("com.google.android.projection.gearhead",
                "com.google.android.projection.gearhead.companion.settings.DefaultSettingsActivity");
        try {
            startActivity(i);
        } catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:com.google.android.projection.gearhead")));
        }
    }

    // ---------- Настройки ----------

    private void renderSettings() {
        LinearLayout launch = card();
        launch.addView(text("Запуск", 16, TEXT, true));
        launch.addView(text("OvertakeLink " + com.andrerinas.openheadunit.BuildConfig.VERSION_NAME + " · github.com/" + UpdateCheck.REPO, 12, MUTED, false));
        launch.addView(text("Используйте на свой риск: приложение распространяется «как есть», без гарантий. "
                + "Не отвлекайтесь от дороги — настраивайте всё на стоянке.", 12, MUTED, false));
        launch.addView(text("OvertakeLink запускается сам, когда машина подключена кабелем и Android спросил, чем её открыть.", 13, MUTED, false));
        launch.addView(button("Пройти первую настройку заново", false, v -> { setupStep = 0; show("setup"); }));
        launch.addView(button("Условия использования", false, v -> show("terms")));
        content.addView(launch, cardLp());

        LinearLayout aa = card();
        aa.addView(text("Android Auto", 16, TEXT, true));
        aa.addView(text("Размер интерфейса, кодек, звук и прочее — в расширенных настройках встроенного приёмника Android Auto.", 13, MUTED, false));
        aa.addView(button("Расширенные настройки Android Auto", false, v -> startActivity(new Intent(this, SettingsActivity.class))));
        aa.addView(button("Открыть Android Auto", false, v -> openAndroidAuto()));
        content.addView(aa, cardLp());

        LinearLayout video = card();
        video.addView(text("Видео на экран машины", 16, TEXT, true));
        String mode = getSharedPreferences("overtakelink", MODE_PRIVATE).getString(OvertakeLinkService.PREF_VIDEO_MODE, OvertakeLinkService.VIDEO_DEFAULT);
        video.addView(text(OvertakeLinkService.VIDEO_TRANSCODE.equals(mode)
                ? "Совместимый: телефон перепаковывает картинку под экран машины. Медленнее, но работает с любым ГУ."
                : "Прямой: картинка Android Auto идёт в машину без перепаковки — меньше задержка, чётче, телефон меньше греется.", 13, MUTED, false));
        video.addView(button("Прямой (быстрее)", OvertakeLinkService.VIDEO_DIRECT.equals(mode), v -> setVideoMode(OvertakeLinkService.VIDEO_DIRECT)));
        video.addView(button("Прямой облегчённый (720p)", OvertakeLinkService.VIDEO_DIRECT_LIGHT.equals(mode),
                v -> setVideoMode(OvertakeLinkService.VIDEO_DIRECT_LIGHT)));
        video.addView(button("Совместимый (надёжнее)", OvertakeLinkService.VIDEO_TRANSCODE.equals(mode),
                v -> setVideoMode(OvertakeLinkService.VIDEO_TRANSCODE)));
        video.addView(text("Если в прямом режиме на экране машины чёрный экран — попробуйте облегчённый, не помогло — совместимый. "
                + "Применяется при следующем подключении кабеля.", 12, MUTED, false));
        content.addView(video, cardLp());

    }

    private void setVideoMode(String mode) {
        getSharedPreferences("overtakelink", MODE_PRIVATE).edit().putString(OvertakeLinkService.PREF_VIDEO_MODE, mode).apply();
        EventLog.add("Режим видео: " + mode);
        render();
    }

    // ---------- Журнал ----------

    private void renderLog() {
        LinearLayout card = card();
        card.addView(text("Журнал", 16, TEXT, true));
        logView = text(EventLog.tail(300), 11, 0xFFC3C9D1, false);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextIsSelectable(true);
        logVersion = EventLog.version();
        card.addView(logView);
        LinearLayout r = row();
        r.addView(button("Сохранить лог", false, v -> {
            try {
                ToastUtils.showToast(this, "Сохранено: " + EventLog.export(this), Toast.LENGTH_LONG, true);
            } catch (Exception e) {
                ToastUtils.showToast(this, "Не сохранилось: " + e.getMessage(), Toast.LENGTH_LONG, true);
            }
        }), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        r.addView(button("Очистить", false, v -> { EventLog.wipeFile(); render(); }),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        card.addView(r);
        content.addView(card, cardLp());
    }

    // ---------- элементы ----------

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private GradientDrawable round(int color, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    private LinearLayout row() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable g = round(CARD, 20);
        g.setStroke(dp(1), LINE);
        c.setBackground(g);
        c.setPadding(dp(18), dp(16), dp(18), dp(16));
        return c;
    }

    private LinearLayout.LayoutParams cardLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(7), 0, dp(7));
        return lp;
    }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setLineSpacing(0, 1.15f);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private Button button(String label, boolean primary, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(15);
        b.setTextColor(primary ? 0xFF1A0E08 : TEXT);
        GradientDrawable g = round(primary ? ACCENT : Color.TRANSPARENT, 14);
        if (!primary) g.setStroke(dp(1), 0xFF3A4049);
        b.setBackground(g);
        b.setOnClickListener(l);
        // Высота по тексту: длинная подпись переносится, а не обрезается.
        b.setMinHeight(dp(50));
        b.setMinimumHeight(dp(50));
        b.setPadding(dp(16), dp(10), dp(16), dp(10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(3), dp(10), dp(3), 0);
        b.setLayoutParams(lp);
        return b;
    }

    private View checkRow(Check c) {
        LinearLayout r = row();
        r.setPadding(0, dp(7), 0, dp(7));
        TextView dot = text(c.ok ? "✓" : "!", 12, BG, true);
        dot.setGravity(Gravity.CENTER);
        dot.setBackground(round(c.ok ? OK : WARN, 10));
        r.addView(dot, new LinearLayout.LayoutParams(dp(20), dp(20)));
        TextView l = text(c.label, 14, TEXT, false);
        l.setPadding(dp(10), 0, 0, 0);
        r.addView(l, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        r.addView(text(c.note, 12, c.ok ? MUTED : WARN, false));
        return r;
    }
}
