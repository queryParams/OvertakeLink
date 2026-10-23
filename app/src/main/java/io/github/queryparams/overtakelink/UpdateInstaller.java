package io.github.queryparams.overtakelink;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import androidx.core.content.FileProvider;

import com.andrerinas.openheadunit.R;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;

import io.github.queryparams.overtakelink.link.EventLog;

/**
 * Обновление в один тап: скачать APK новой версии и открыть системную установку. Android сам
 * проверяет, что обновление подписано тем же ключом; если GitHub сообщил SHA-256 файла, сверяем и его.
 */
final class UpdateInstaller {

    interface Progress {
        /** Состояние для кнопки; вызывается в главном потоке. null — загрузка закончилась. */
        void show(String text);
    }

    private static volatile boolean running;

    private UpdateInstaller() {
    }

    static void start(Activity activity, Progress progress) {
        String url = UpdateCheck.apkUrl(activity);
        if (url == null) {
            activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(UpdateCheck.downloadUrl(activity))));
            return;
        }
        if (!activity.getPackageManager().canRequestPackageInstalls()) {
            // Разрешение «Установка неизвестных приложений» выдаётся пользователем в настройках.
            progress.show(activity.getString(R.string.ol_update_allow));
            activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + activity.getPackageName())));
            return;
        }
        if (running) {
            return;
        }
        running = true;
        Handler main = new Handler(Looper.getMainLooper());
        String expected = UpdateCheck.apkSha256(activity);
        File dir = new File(activity.getCacheDir(), "update");
        File apk = new File(dir, "OvertakeLink.apk");
        new Thread(() -> {
            String error = null;
            try {
                download(url, dir, apk, expected, percent -> main.post(() -> progress.show(activity.getString(R.string.ol_update_progress, percent))));
            } catch (Exception e) {
                error = e.getMessage();
                EventLog.add("Обновление: загрузка не удалась (" + error + ")");
            }
            running = false;
            String failed = error;
            main.post(() -> {
                if (failed != null) {
                    progress.show(activity.getString(R.string.ol_update_failed));
                    return;
                }
                progress.show(null);
                // Контекст приложения: пока шла загрузка, окно OvertakeLink могли закрыть.
                Context app = activity.getApplicationContext();
                Uri uri = FileProvider.getUriForFile(app, app.getPackageName() + ".fileprovider", apk);
                app.startActivity(new Intent(Intent.ACTION_VIEW)
                        .setDataAndType(uri, "application/vnd.android.package-archive")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK));
            });
        }, "ol-update-download").start();
    }

    private interface Percent {
        void at(int percent);
    }

    private static void download(String url, File dir, File apk, String expectedSha256, Percent percent) throws Exception {
        dir.mkdirs();
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15_000);
        c.setReadTimeout(30_000);
        c.setInstanceFollowRedirects(true);
        try {
            if (c.getResponseCode() != 200) {
                throw new IllegalStateException("HTTP " + c.getResponseCode());
            }
            long total = c.getContentLengthLong();
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(apk)) {
                byte[] buf = new byte[64 * 1024];
                long done = 0;
                int last = -1;
                for (int n; (n = in.read(buf)) > 0; ) {
                    out.write(buf, 0, n);
                    sha.update(buf, 0, n);
                    done += n;
                    int p = total > 0 ? (int) (done * 100 / total) : 0;
                    if (p != last) {
                        last = p;
                        percent.at(p);
                    }
                }
            }
            if (expectedSha256 != null) {
                StringBuilder hex = new StringBuilder();
                for (byte b : sha.digest()) {
                    hex.append(String.format(Locale.US, "%02x", b));
                }
                if (!hex.toString().equalsIgnoreCase(expectedSha256)) {
                    apk.delete();
                    throw new IllegalStateException("контрольная сумма не совпала");
                }
            }
        } finally {
            c.disconnect();
        }
    }
}
