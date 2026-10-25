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
            ACCENT = 0xFFFF7A45, OK = 0xFF3DDC97, WARN = 0xFFFFB020, IDLE = 0xFF2E343D;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private LinearLayout content;
    private TextView[] tabs;
    private String screen = "status";
    /** Условия показаны и приняты в этом запуске: при каждом открытии приложения они показываются заново. */
    private boolean termsSeen;
    /** Экран, на который вернёт «Назад» из условий, открытых кнопкой в шапке. */
    private String beforeTerms = "status";
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
        // Экран машины — каким его сообщило ГУ при последнем подключении.
        String car = getSharedPreferences("overtakelink", MODE_PRIVATE).getString("carScreen", null);
        title.addView(text(car == null ? getString(R.string.ol_car_never) : getString(R.string.ol_car_screen, car), 12, MUTED, false));
        head.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
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
        head.addView(terms);
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
        UpdateCheck.maybeCheck(this);
        ui.post(refresh);
    }

    @Override
    protected void onPause() {
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
            u.addView(text(getString(R.string.ol_update_available, update), 16, TEXT, true));
            u.addView(text(getString(R.string.ol_update_note), 13, MUTED, false));
            Button get = button(getString(R.string.ol_update_install), true, null);
            get.setOnClickListener(v -> UpdateInstaller.start(this,
                    state -> get.setText(state == null ? getString(R.string.ol_update_install) : state)));
            u.addView(get);
            content.addView(u, cardLp());
        }
        OvertakeLinkService.State st = OvertakeLinkService.state();
        LinearLayout card = card();
        card.addView(text(OvertakeLinkService.title(this), 19, TEXT, true));
        card.addView(text(OvertakeLinkService.detail(this), 13, MUTED, false));
        int done = st == OvertakeLinkService.State.RUNNING ? 4 : st == OvertakeLinkService.State.HANDSHAKE ? 2 : 0;
        LinearLayout steps = row();
        String[] labels = {"USB", getString(R.string.ol_step_hu), "Android Auto", getString(R.string.ol_step_video)};
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
                new Check(getString(R.string.ol_check_usb), usbDefault, getString(R.string.ol_yes), getString(R.string.ol_check_usb_bad)),
                new Check(getString(R.string.ol_check_bt), bt, getString(R.string.ol_check_bt_ok), getString(R.string.ol_check_bt_bad))
        };
    }

    private String screenSignature() {
        StringBuilder sb = new StringBuilder(OvertakeLinkService.state().name()).append('|').append(OvertakeLinkService.detail(this))
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

    // ---------- Первая настройка ----------

    private void renderSetup() {
        int[][] steps = {
                {R.string.ol_setup_0_title, R.string.ol_setup_0_text, R.string.ol_setup_0_action},
                {R.string.ol_setup_1_title, R.string.ol_setup_1_text, R.string.ol_setup_1_action},
                {R.string.ol_setup_2_title, R.string.ol_setup_2_text, R.string.ol_setup_2_action},
                {R.string.ol_setup_3_title, R.string.ol_setup_3_text, R.string.ol_setup_3_action},
                {R.string.ol_setup_4_title, R.string.ol_setup_4_text, R.string.ol_setup_4_action}
        };
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
        // Шаг → проверка готовности: разрешения, батарея, сервер AA, (кабель — без проверки), Bluetooth.
        int[] checkOf = {0, 1, 2, -1, 4};
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
            ToastUtils.showToast(this, getString(R.string.ol_open_failed, e.getMessage()), Toast.LENGTH_LONG, true);
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

    private void setVideoMode(String mode) {
        getSharedPreferences("overtakelink", MODE_PRIVATE).edit().putString(OvertakeLinkService.PREF_VIDEO_MODE, mode).apply();
        EventLog.add("Режим видео: " + mode);
        render();
    }

    // ---------- Журнал ----------

    private void renderLog() {
        LinearLayout card = card();
        card.addView(text(getString(R.string.ol_tab_log), 16, TEXT, true));
        logView = text(EventLog.tail(300), 11, 0xFFC3C9D1, false);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextIsSelectable(true);
        logVersion = EventLog.version();
        card.addView(logView);
        LinearLayout r = row();
        r.addView(button(getString(R.string.ol_save_log), false, v -> {
            try {
                ToastUtils.showToast(this, getString(R.string.ol_saved, EventLog.export(this)), Toast.LENGTH_LONG, true);
            } catch (Exception e) {
                ToastUtils.showToast(this, getString(R.string.ol_save_failed, e.getMessage()), Toast.LENGTH_LONG, true);
            }
        }), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        r.addView(button(getString(R.string.ol_clear), false, v -> { EventLog.wipeFile(); render(); }),
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
