package io.github.queryparams.overtakelink;

import io.github.queryparams.overtakelink.gap.GapService;

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
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.andrerinas.openheadunit.R;
import com.andrerinas.openheadunit.main.SettingsActivity;
import com.andrerinas.openheadunit.utils.ToastUtils;


import io.github.queryparams.overtakelink.link.EventLog;

/** Интерфейс OvertakeLink по макету: Статус · Настройки · Журнал и мастер первой настройки. */
public final class OvertakeLinkActivity extends Activity {
    static final int BG = 0xFF0F1216, CARD = 0xFF181C22, LINE = 0xFF262B33, TEXT = 0xFFE8EAED, MUTED = 0xFFA0A8B3,
            ACCENT = 0xFFFF7A45, OK = 0xFF3DDC97, WARN = 0xFFFFB020, IDLE = 0xFF2E343D, DONATE = 0xFFFF8FB1;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private LinearLayout content;
    private TextView[] tabs;
    private String screen = "status";
    /** Условия показаны и приняты в этом запуске: при каждом открытии приложения они показываются заново. */
    private boolean termsSeen;
    /** Экран, на который вернёт «Назад» из условий, открытых кнопкой в шапке. */
    private String beforeTerms = "status";
    /** Экран, на который вернёт «Назад» из «Поддержать». */
    private String beforeDonate = "status";
    private int setupStep;
    private volatile boolean aaServerUp;
    private long logVersion = -1;
    /** Состояние, с которым нарисован «Статус» или мастер: изменилось — перерисуем. */
    private String shownSignature = "";
    private TextView logView;
    private TextView screenLine;

    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            // Первое подключение GAP Smart Tool при открытом приложении — место в шапке сразу, на любой вкладке.
            renderScreenLine();
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
        UpdateInstaller.cleanup(this);
        super.onCreate(state);
        termsSeen = state != null && state.getBoolean("termsSeen");
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
        head.setPadding(dp(20), dp(16), dp(16), dp(8));
        LinearLayout title = new LinearLayout(this);
        title.setOrientation(LinearLayout.VERTICAL);
        TextView logo = text("OvertakeLink", 22, TEXT, true);
        logo.setText(android.text.Html.fromHtml("Overtake<font color='#FF7A45'>Link</font>", 0));
        title.addView(logo);
        screenLine = text("", 12, MUTED, false);
        title.addView(screenLine);
        head.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        LinearLayout buttons = row();
        head.addView(buttons);
        // «Поддержать» — только в русском интерфейсе: перевод на карту Сбербанка — с российских карт.
        if (donateAvailable()) {
            // Заметно, но не кричит: розовая рамка и лёгкая заливка, как кнопка «Условия» рядом.
            TextView heart = text("\u2665\uFE0E " + getString(R.string.ol_donate), 13, DONATE, true);
            GradientDrawable heartBg = round(0x26FF8FB1, 16);
            heartBg.setStroke(dp(1), DONATE);
            heart.setBackground(heartBg);
            heart.setPadding(dp(14), dp(8), dp(14), dp(8));
            heart.setOnClickListener(v -> {
                if (!screen.equals("donate")) {
                    beforeDonate = screen;
                }
                show("donate");
            });
            LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            hlp.setMargins(0, 0, dp(8), 0);
            buttons.addView(heart, hlp);
        }
        // Условия и отказ от ответственности — всегда под рукой, на любом экране.
        TextView terms = text(getString(R.string.ol_terms_short), 13, TEXT, false);
        GradientDrawable termsBg = round(Color.TRANSPARENT, 16);
        termsBg.setStroke(dp(1), 0xFF3A4049);
        terms.setBackground(termsBg);
        terms.setPadding(dp(14), dp(8), dp(14), dp(8));
        terms.setOnClickListener(v -> {
            if (!screen.equals("terms")) {
                beforeTerms = screen;
            }
            show("terms");
        });
        buttons.addView(terms);
        root.addView(head);
        // Узкий экран или крупный шрифт: название с кнопками в строку не влезает — кнопки строкой под ним. Решает
        // ширина шапки, а не перенос названия: окно расширили — снова одна строка.
        head.addOnLayoutChangeListener((v, l, tp, r, b, ol, ot, or, ob) -> {
            if (r - l == or - ol && head.getTag() != null) {
                return;
            }
            // Ширина по тексту и отступам, без пробного измерения: оно перестроило бы текст уже показанных кнопок.
            int need = textWidth(logo);
            for (int i = 0; i < buttons.getChildCount(); i++) {
                TextView bt = (TextView) buttons.getChildAt(i);
                ViewGroup.MarginLayoutParams m = (ViewGroup.MarginLayoutParams) bt.getLayoutParams();
                need += textWidth(bt) + bt.getPaddingLeft() + bt.getPaddingRight() + m.leftMargin + m.rightMargin;
            }
            boolean stack = need > r - l - head.getPaddingLeft() - head.getPaddingRight();
            if (Boolean.valueOf(stack).equals(head.getTag())) {
                return;
            }
            head.setTag(stack);
            head.post(() -> {
                head.setOrientation(stack ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
                title.setLayoutParams(stack
                        ? new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                        : new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
                LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                blp.setMargins(0, stack ? dp(10) : 0, 0, 0);
                buttons.setLayoutParams(blp);
            });
        });

        ScrollView scroll = new ScrollView(this);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(8), dp(16), dp(16));
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        LinearLayout nav = row();
        nav.setBackgroundColor(0xFF12161B);
        nav.setPadding(dp(8), dp(8), dp(8), dp(12));
        String[][] items = {{"status", getString(R.string.ol_tab_status)}, {"settings", getString(R.string.ol_tab_settings)},
                {"log", getString(R.string.ol_tab_log)}};
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
        FrameLayout frame = new FrameLayout(this);
        frame.addView(root);
        setContentView(frame);
        if (state == null) {
            SplashOverlay.show(frame);
        }

        boolean firstRun = !getSharedPreferences("overtakelink", MODE_PRIVATE).getBoolean("setupDone", false);
        show(firstRun ? "setup" : "status");
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putBoolean("termsSeen", termsSeen);
    }

    @Override
    protected void onResume() {
        super.onResume();
        checkAaServer();
        // Вывод в GAP Smart Tool выбран и не остановлен руками — служба ждёт его (после перезапуска телефона тоже).
        if (GapService.selected(this) && !GapService.stoppedByUser(this) && GapService.state() == OvertakeLinkService.State.WAITING) {
            startForegroundService(new Intent(this, GapService.class).setAction(GapService.ACTION_START));
        }
        UpdateCheck.maybeCheck(this);
        ui.post(refresh);
        UpdateInstaller.visible(this, true);
    }

    @Override
    protected void onPause() {
        UpdateInstaller.visible(this, false);
        ui.removeCallbacks(refresh);
        super.onPause();
    }

    private void show(String id) {
        // При каждом открытии приложения сначала условия: пока их не приняли, вкладки ведут только на них.
        screen = termsSeen && Terms.accepted(this) ? id : "terms";
        String[] ids = {"status", "settings", "log"};
        for (int i = 0; i < tabs.length; i++) {
            tabs[i].setTextColor(ids[i].equals(id) ? ACCENT : MUTED);
        }
        render();
    }

    /** Под названием — место для Android Auto: у GAP Smart Tool — что он прислал, у кабеля — что сообщило ГУ. */
    private void renderScreenLine() {
        android.content.SharedPreferences p = getSharedPreferences("overtakelink", MODE_PRIVATE);
        if (GapService.selected(this)) {
            String gap = p.getString(GapService.PREF_GAP_SCREEN, null);
            setIfChanged(screenLine, gap == null ? getString(R.string.ol_gap_never) : getString(R.string.ol_gap_screen, gap));
        } else {
            String car = p.getString("carScreen", null);
            setIfChanged(screenLine, car == null ? getString(R.string.ol_car_never) : getString(R.string.ol_car_screen, car));
        }
    }

    /** Тот же текст не ставим: лишняя перекладка экрана раз в секунду. */
    private static void setIfChanged(TextView v, String s) {
        if (!s.contentEquals(v.getText())) {
            v.setText(s);
        }
    }

    private void render() {
        renderScreenLine();
        shownSignature = screenSignature();
        content.removeAllViews();
        logView = null;
        switch (screen) {
            case "setup": renderSetup(); break;
            case "terms": renderTerms(); break;
            case "donate": renderDonate(); break;
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
            u.addView(text(getString(R.string.ol_update_available, update), 16, TEXT, true));
            u.addView(text(getString(R.string.ol_update_note), 13, MUTED, false));
            Button get = button(UpdateInstaller.label(this), true, null);
            UpdateInstaller.Progress progress = state -> get.setText(state == null ? UpdateInstaller.label(this) : state);
            // Загрузка идёт (экран перерисовали или открыли заново) — ход показывает эта кнопка.
            UpdateInstaller.attach(progress);
            get.setOnClickListener(v -> UpdateInstaller.start(this, progress));
            u.addView(get);
            content.addView(u, cardLp());
        }
        boolean gap = GapService.selected(this);
        OvertakeLinkService.State st = gap ? GapService.state() : OvertakeLinkService.state();
        LinearLayout card = card();
        card.addView(text(gap ? GapService.title(this) : OvertakeLinkService.title(this), 19, TEXT, true));
        card.addView(text(gap ? GapService.detail(this) : OvertakeLinkService.detail(this), 13, MUTED, false));
        int done = st == OvertakeLinkService.State.RUNNING ? 4 : st == OvertakeLinkService.State.HANDSHAKE
                ? (gap && !GapService.clientConnected() ? 0 : 2) : 0;
        LinearLayout steps = row();
        String[] labels = gap ? new String[]{"Wi-Fi", "GAP Smart Tool", "Android Auto", getString(R.string.ol_step_video)}
                : new String[]{"USB", getString(R.string.ol_step_hu), "Android Auto", getString(R.string.ol_step_video)};
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
        if (gap) {
            card.addView(st != OvertakeLinkService.State.WAITING
                    ? button(getString(R.string.ol_stop), false, v -> startService(new Intent(this, GapService.class).setAction(GapService.ACTION_STOP)))
                    : button(getString(R.string.ol_gap_start), true, v -> startForegroundService(new Intent(this, GapService.class)
                            .setAction(GapService.ACTION_START))));
        } else if (st != OvertakeLinkService.State.WAITING) {
            card.addView(button(getString(R.string.ol_stop), false, v -> startService(new Intent(this, OvertakeLinkService.class)
                    .setAction(OvertakeLinkService.ACTION_STOP))));
        } else {
            card.addView(button(getString(R.string.ol_connect_manually), true, v -> startForegroundService(new Intent(this, OvertakeLinkService.class)
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
        h.addView(text(getString(R.string.ol_ready), 16, TEXT, true), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        h.addView(text(getString(R.string.ol_ready_count, okCount, checks.length) + (okCount < checks.length ? getString(R.string.ol_ready_fix) : ""), 13,
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
        // Вывод в GAP идёт по Wi-Fi телефона: вместо проверки кабеля — на связи ли GAP Smart Tool (ГУ в сети
        // телефона). Включена ли раздача, на новых Android приложению не узнать — проверяем то, что нужно.
        boolean gap = GapService.selected(this);
        boolean bt = false;
        for (AudioDeviceInfo d : getSystemService(AudioManager.class).getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            if (d.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP) bt = true;
        }
        return new Check[]{
                new Check(getString(R.string.ol_check_permissions), notif && mic,
                        getString(R.string.ol_check_permissions_ok), getString(R.string.ol_check_permissions_bad)),
                new Check(getString(R.string.ol_check_battery), battery, getString(R.string.ol_yes), getString(R.string.ol_check_battery_bad)),
                new Check(getString(R.string.ol_check_aa_server), aaServerUp,
                        getString(R.string.ol_check_aa_server_ok), getString(R.string.ol_check_aa_server_bad)),
                gap ? new Check(getString(R.string.ol_check_hotspot), GapService.clientConnected(),
                        getString(R.string.ol_check_hotspot_ok), getString(R.string.ol_check_hotspot_bad))
                        : new Check(getString(R.string.ol_check_usb), usbDefault, getString(R.string.ol_yes), getString(R.string.ol_check_usb_bad)),
                new Check(getString(R.string.ol_check_bt), bt, getString(R.string.ol_check_bt_ok), getString(R.string.ol_check_bt_bad))
        };
    }

    private String screenSignature() {
        StringBuilder sb = new StringBuilder(OvertakeLinkService.state().name()).append('|').append(OvertakeLinkService.detail(this))
                .append('|').append(GapService.state().name()).append('|').append(GapService.detail(this))
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
        LinearLayout card = card();
        card.addView(text(getString(R.string.ol_terms_title), 22, TEXT, true));
        TextView body = text(getString(R.string.ol_terms_text), 14, 0xFFC3C9D1, false);
        body.setPadding(0, dp(12), 0, dp(12));
        card.addView(body);
        card.addView(text(getString(R.string.ol_terms_license, "github.com/" + UpdateCheck.REPO + "/blob/main/LICENSE"), 12, MUTED, false));
        if (termsSeen) {
            card.addView(button(getString(R.string.ol_back), false, v -> show(beforeTerms)));
        } else {
            card.addView(button(getString(R.string.ol_accept), true, v -> {
                if (!Terms.accepted(this)) {
                    EventLog.add("Условия использования приняты (версия " + Terms.VERSION + ")");
                }
                Terms.accept(this);
                termsSeen = true;
                boolean firstRun = !getSharedPreferences("overtakelink", MODE_PRIVATE).getBoolean("setupDone", false);
                show(firstRun ? "setup" : "status");
            }));
            card.addView(button(getString(R.string.ol_decline), false, v -> finishAndRemoveTask()));
        }
        content.addView(card, cardLp());
    }

    // ---------- Поддержать ----------

    private boolean donateAvailable() {
        return "ru".equals(getResources().getConfiguration().getLocales().get(0).getLanguage());
    }

    private void renderDonate() {
        LinearLayout card = card();
        TextView heart = text("\u2665\uFE0E", 34, DONATE, false);
        heart.setGravity(Gravity.CENTER);
        card.addView(heart);
        TextView title = text(getString(R.string.ol_donate_title), 18, TEXT, true);
        title.setGravity(Gravity.CENTER);
        card.addView(title);
        TextView body = text(getString(R.string.ol_donate_text), 14, 0xFFC3C9D1, false);
        body.setPadding(0, dp(12), 0, dp(12));
        card.addView(body);
        card.addView(button(getString(R.string.ol_donate_sbp), true, v -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.ol_donate_url))));
            } catch (Exception e) {
                ToastUtils.showToast(this, getString(R.string.ol_donate_open_failed, e.getMessage()), Toast.LENGTH_LONG, true);
            }
        }));
        TextView note = text(getString(R.string.ol_donate_sbp_note), 12, MUTED, false);
        note.setGravity(Gravity.CENTER);
        card.addView(note);
        TextView thanks = text(getString(R.string.ol_donate_thanks), 13, MUTED, false);
        thanks.setGravity(Gravity.CENTER);
        thanks.setPadding(0, dp(12), 0, dp(4));
        card.addView(thanks);
        card.addView(button(getString(R.string.ol_back), false, v -> show(beforeDonate)));
        content.addView(card, cardLp());
    }

    // ---------- Первая настройка ----------

    private void renderSetup() {
        int[][] steps = {
                {R.string.ol_setup_0_title, R.string.ol_setup_0_text, R.string.ol_setup_0_action},
                {R.string.ol_setup_1_title, R.string.ol_setup_1_text, R.string.ol_setup_1_action},
                {R.string.ol_setup_2_title, R.string.ol_setup_2_text, R.string.ol_setup_2_action},
                {R.string.ol_setup_3_title, R.string.ol_setup_3_text, R.string.ol_setup_3_action},
                {R.string.ol_setup_4_title, R.string.ol_setup_4_text, R.string.ol_setup_4_action}
        };
        // В режиме GAP шаг про кабель — про точку доступа.
        if (GapService.selected(this)) {
            steps[3] = new int[]{R.string.ol_setup_3_gap_title, R.string.ol_setup_3_gap_text, R.string.ol_setup_3_gap_action};
        }
        int[] s = steps[setupStep];
        content.addView(text(getString(R.string.ol_setup_progress, setupStep + 1, steps.length), 12, MUTED, false));
        LinearLayout bars = row();
        for (int i = 0; i < steps.length; i++) {
            View b = new View(this);
            b.setBackground(round(i <= setupStep ? ACCENT : IDLE, 2));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(4), 1);
            lp.setMargins(dp(3), dp(12), dp(3), dp(12));
            bars.addView(b, lp);
        }
        content.addView(bars);
        content.addView(text(getString(s[0]), 24, TEXT, true));
        TextView body = text(getString(s[1]), 15, 0xFFC3C9D1, false);
        body.setPadding(0, dp(12), 0, dp(16));
        content.addView(body);
        // Шаг → проверка готовности: разрешения, батарея, сервер AA, (кабель — без проверки; точка доступа в GAP — с ней), Bluetooth.
        int[] checkOf = {0, 1, 2, GapService.selected(this) ? 3 : -1, 4};
        if (checkOf[setupStep] >= 0) {
            content.addView(checkRow(checks()[checkOf[setupStep]]));
        }
        content.addView(button(getString(s[2]), true, v -> setupAction()));
        LinearLayout nav = row();
        nav.addView(button(getString(R.string.ol_back), false, v -> {
            if (setupStep > 0) { setupStep--; render(); } else show("status");
        }), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        nav.addView(button(getString(setupStep == steps.length - 1 ? R.string.ol_done : R.string.ol_next), false, v -> {
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
                    if (checks()[0].ok) {
                        nextSetupStep();
                    } else {
                        requestPermissions(SETUP_PERMISSIONS, 1);
                    }
                    break;
                case 1:
                    if (checks()[1].ok) {
                        nextSetupStep();
                        break;
                    }
                    try {
                        startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                Uri.parse("package:" + getPackageName())));
                    } catch (Exception e) {
                        // Некоторые прошивки не дают этот диалог — тогда общий список оптимизации батареи.
                        startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
                    }
                    break;
                case 2:
                    openAndroidAuto();
                    break;
                case 3:
                    if (!GapService.selected(this)) {
                        nextSetupStep();
                        break;
                    }
                    // Настройки раздачи — всегда: и включить, и посмотреть пароль сети для ГУ. Действие экрана раздачи
                    // не публичное, но есть у большинства прошивок; нет — общий раздел «Сеть».
                    try {
                        startActivity(new Intent("android.settings.TETHER_SETTINGS"));
                    } catch (Exception e) {
                        startActivity(new Intent(Settings.ACTION_WIRELESS_SETTINGS));
                    }
                    break;
                default:
                    startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));
            }
        } catch (Exception e) {
            ToastUtils.showToast(this, getString(R.string.ol_open_failed, e.getMessage()), Toast.LENGTH_LONG, true);
        }
    }

    private static final String[] SETUP_PERMISSIONS = Build.VERSION.SDK_INT >= 33
            ? new String[]{Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.RECORD_AUDIO}
            : new String[]{Manifest.permission.RECORD_AUDIO};

    private void nextSetupStep() {
        setupStep++;
        render();
    }

    /**
     * Отказ «больше не спрашивать» (или два отказа подряд): Android перестаёт показывать запрос, и кнопка
     * молчала бы. Тогда ведём в настройки приложения — там разрешения включаются вручную.
     */
    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != 1 || !screen.equals("setup")) {
            return;
        }
        boolean blocked = false;
        for (int i = 0; i < permissions.length; i++) {
            if (results[i] != PackageManager.PERMISSION_GRANTED && !shouldShowRequestPermissionRationale(permissions[i])) {
                blocked = true;
            }
        }
        if (checks()[0].ok) {
            nextSetupStep();
        } else if (blocked) {
            ToastUtils.showToast(this, getString(R.string.ol_permissions_in_settings), Toast.LENGTH_LONG, true);
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())));
        } else {
            render();
        }
    }

    private void openAndroidAuto() {
        try {
            startActivity(AaServer.settingsIntent());
        } catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:com.google.android.projection.gearhead")));
        }
    }

    // ---------- Настройки ----------

    private void renderSettings() {
        LinearLayout launch = card();
        launch.addView(text(getString(R.string.ol_launch), 16, TEXT, true));
        launch.addView(text("OvertakeLink " + com.andrerinas.openheadunit.BuildConfig.VERSION_NAME + " · github.com/" + UpdateCheck.REPO, 12, MUTED, false));
        launch.addView(text(getString(R.string.ol_launch_risk), 12, MUTED, false));
        launch.addView(text(getString(R.string.ol_launch_auto), 13, MUTED, false));
        Button check = button(getString(R.string.ol_update_check), false, null);
        check.setOnClickListener(v -> {
            check.setText(R.string.ol_update_checking);
            UpdateCheck.checkNow(this, ok -> {
                check.setText(R.string.ol_update_check);
                String update = UpdateCheck.availableVersion(this);
                if (update != null) {
                    show("status");
                } else {
                    ToastUtils.showToast(this, ok ? getString(R.string.ol_update_latest, com.andrerinas.openheadunit.BuildConfig.VERSION_NAME)
                            : getString(R.string.ol_update_check_failed), Toast.LENGTH_LONG, true);
                }
            });
        });
        launch.addView(check);
        launch.addView(button(getString(R.string.ol_setup_again), false, v -> { setupStep = 0; show("setup"); }));
        content.addView(launch, cardLp());

        LinearLayout aa = card();
        aa.addView(text("Android Auto", 16, TEXT, true));
        aa.addView(text(getString(R.string.ol_aa_note), 13, MUTED, false));
        aa.addView(button(getString(R.string.ol_aa_advanced), false, v -> startActivity(new Intent(this, SettingsActivity.class))));
        aa.addView(button(getString(R.string.ol_open_aa), false, v -> openAndroidAuto()));
        content.addView(aa, cardLp());

        LinearLayout output = card();
        output.addView(text(getString(R.string.ol_output_title), 16, TEXT, true));
        boolean gap = GapService.selected(this);
        output.addView(text(getString(gap ? R.string.ol_output_gap_note : R.string.ol_output_usb_note), 13, MUTED, false));
        output.addView(button(getString(R.string.ol_output_usb), !gap, v -> setOutput(GapService.OUTPUT_USB)));
        output.addView(button(getString(R.string.ol_output_gap), gap, v -> setOutput(GapService.OUTPUT_GAP)));
        content.addView(output, cardLp());

        if (gap) {
            // В GAP картинка всегда прямая: «Совместимого» нет, качество — своё, кабельное не трогаем.
            LinearLayout q = card();
            q.addView(text(getString(R.string.ol_gap_video_title), 16, TEXT, true));
            q.addView(text(getString(R.string.ol_gap_video_note), 13, MUTED, false));
            String g = GapService.videoMode(this);
            q.addView(button(getString(R.string.ol_gap_video_1080), OvertakeLinkService.VIDEO_DIRECT.equals(g), v -> setGapVideo(OvertakeLinkService.VIDEO_DIRECT)));
            q.addView(button(getString(R.string.ol_gap_video_720), OvertakeLinkService.VIDEO_DIRECT_LIGHT.equals(g), v -> setGapVideo(OvertakeLinkService.VIDEO_DIRECT_LIGHT)));
            q.addView(button(getString(R.string.ol_gap_video_480), OvertakeLinkService.VIDEO_DIRECT_SMALL.equals(g), v -> setGapVideo(OvertakeLinkService.VIDEO_DIRECT_SMALL)));
            q.addView(text(getString(R.string.ol_gap_video_hint), 12, MUTED, false));
            content.addView(q, cardLp());
            return;
        }
        LinearLayout video = card();
        video.addView(text(getString(R.string.ol_video_title), 16, TEXT, true));
        String mode = getSharedPreferences("overtakelink", MODE_PRIVATE).getString(OvertakeLinkService.PREF_VIDEO_MODE, OvertakeLinkService.VIDEO_DEFAULT);
        video.addView(text(OvertakeLinkService.VIDEO_TRANSCODE.equals(mode)
                ? getString(R.string.ol_video_transcode_note) : getString(R.string.ol_video_direct_note), 13, MUTED, false));
        video.addView(button(getString(R.string.ol_video_direct), OvertakeLinkService.VIDEO_DIRECT.equals(mode), v -> setVideoMode(OvertakeLinkService.VIDEO_DIRECT)));
        video.addView(button(getString(R.string.ol_video_direct_light), OvertakeLinkService.VIDEO_DIRECT_LIGHT.equals(mode),
                v -> setVideoMode(OvertakeLinkService.VIDEO_DIRECT_LIGHT)));
        video.addView(button(getString(R.string.ol_video_direct_small), OvertakeLinkService.VIDEO_DIRECT_SMALL.equals(mode),
                v -> setVideoMode(OvertakeLinkService.VIDEO_DIRECT_SMALL)));
        video.addView(button(getString(R.string.ol_video_transcode), OvertakeLinkService.VIDEO_TRANSCODE.equals(mode),
                v -> setVideoMode(OvertakeLinkService.VIDEO_TRANSCODE)));
        video.addView(text(getString(R.string.ol_video_hint), 12, MUTED, false));
        content.addView(video, cardLp());
    }

    /** Вывод выбирается только вручную: в GAP Smart Tool — служба ждёт его по Wi-Fi; по кабелю — служба GAP останавливается. */
    private void setOutput(String output) {
        getSharedPreferences("overtakelink", MODE_PRIVATE).edit().putString(GapService.PREF_OUTPUT, output).apply();
        EventLog.add("Вывод Android Auto: " + (GapService.OUTPUT_GAP.equals(output) ? "в GAP Smart Tool по Wi-Fi" : "по кабелю (QDLink)"));
        if (GapService.OUTPUT_GAP.equals(output)) {
            // Экран и приёмник AA — у одного режима за раз. Останавливаем без оглядки на состояние: кабельное
            // подключение могло ещё открывать аксессуар (состояние пока WAITING) и запустилось бы уже при GAP.
            startService(new Intent(this, OvertakeLinkService.class).setAction(OvertakeLinkService.ACTION_STOP));
            startForegroundService(new Intent(this, GapService.class).setAction(GapService.ACTION_START));
        } else {
            // Так же без оглядки: служба GAP могла только что запуститься и ещё не объявить состояние.
            startService(new Intent(this, GapService.class).setAction(GapService.ACTION_STOP));
        }
        render();
    }

    private void setGapVideo(String mode) {
        getSharedPreferences("overtakelink", MODE_PRIVATE).edit().putString(GapService.PREF_GAP_VIDEO, mode).apply();
        EventLog.add("Качество в GAP Smart Tool: " + mode);
        render();
    }

    private void setVideoMode(String mode) {
        getSharedPreferences("overtakelink", MODE_PRIVATE).edit().putString(OvertakeLinkService.PREF_VIDEO_MODE, mode).apply();
        EventLog.add("Режим видео: " + mode);
        render();
    }

    // ---------- Журнал ----------

    private void renderLog() {
        LinearLayout card = card();
        card.addView(text(getString(R.string.ol_tab_log), 16, TEXT, true));
        // Кнопки — над журналом: до них не листать 300 строк.
        LinearLayout r = row();
        r.addView(button(getString(R.string.ol_save_log), false, v -> new Thread(() -> {
            // Чтение и запись всего журнала — не на главном потоке; результат — плашкой.
            String msg;
            try {
                msg = getString(R.string.ol_saved, EventLog.export(this));
            } catch (Exception e) {
                msg = getString(R.string.ol_save_failed, e.getMessage());
            }
            String m = msg;
            ui.post(() -> ToastUtils.showToast(this, m, Toast.LENGTH_LONG, true));
        }, "ol-log-save").start()), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        r.addView(button(getString(R.string.ol_clear), false, v -> new Thread(() -> {
            // Очистка ждёт идущую выгрузку журнала — не на главном потоке.
            EventLog.wipeFile();
            ui.post(this::render);
        }, "ol-log-wipe").start()),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        card.addView(r);
        logView = text(EventLog.tail(300), 11, 0xFFC3C9D1, false);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextIsSelectable(true);
        logVersion = EventLog.version();
        card.addView(logView);
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

    private static int textWidth(TextView t) {
        return (int) Math.ceil(android.text.Layout.getDesiredWidth(t.getText(), t.getPaint()));
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
