package io.github.queryparams.overtakelink;

import android.content.Context;
import android.content.SharedPreferences;

import com.andrerinas.openheadunit.BuildConfig;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

import io.github.queryparams.overtakelink.link.EventLog;

/**
 * Новая версия OvertakeLink: самый свежий релиз на GitHub, включая бета-версии (их GitHub не считает
 * «последним» релизом), не чаще раза в час; неудачная проверка повторяется при следующем открытии. Найденное запоминается, экран «Статус» показывает
 * ссылку на загрузку.
 */
public final class UpdateCheck {
    public static final String REPO = "queryParams/OvertakeLink";
    /** GitHub без входа даёт 60 запросов в час с одного адреса — раз в час с запасом. */
    private static final long INTERVAL_MS = 60 * 60 * 1000L;
    private static final String PREFS = "overtakelink";

    private static volatile boolean checking;

    private UpdateCheck() {
    }

    /** Версия новее установленной или null. */
    public static String availableVersion(Context context) {
        String v = prefs(context).getString("updateVersion", null);
        return v != null && newer(v, BuildConfig.VERSION_NAME) ? v : null;
    }

    public static String downloadUrl(Context context) {
        return prefs(context).getString("updateUrl", "https://github.com/" + REPO + "/releases");
    }

    /** Прямая ссылка на APK новой версии или null (тогда — страница релиза). */
    public static String apkUrl(Context context) {
        return prefs(context).getString("updateApk", null);
    }

    /** SHA-256 APK, если GitHub его сообщил, иначе null. */
    public static String apkSha256(Context context) {
        return prefs(context).getString("updateApkSha256", null);
    }

    /** В фоне; результат появится на экране при следующей перерисовке. */
    public static void maybeCheck(Context context) {
        SharedPreferences p = prefs(context);
        long now = System.currentTimeMillis();
        if (checking || now - p.getLong("updateCheckedAt", 0) < INTERVAL_MS) {
            return;
        }
        checking = true;
        Context app = context.getApplicationContext();
        new Thread(() -> {
            try {
                check(app);
            } finally {
                checking = false;
            }
        }, "ol-update").start();
    }

    private static void check(Context context) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL("https://api.github.com/repos/" + REPO + "/releases?per_page=10").openConnection();
            c.setConnectTimeout(10_000);
            c.setReadTimeout(10_000);
            c.setRequestProperty("Accept", "application/vnd.github+json");
            c.setRequestProperty("User-Agent", "OvertakeLink/" + BuildConfig.VERSION_NAME);
            if (c.getResponseCode() != 200) {
                EventLog.add("Обновление: GitHub ответил HTTP " + c.getResponseCode());
                return;
            }
            JSONObject release = newest(new JSONArray(read(c.getInputStream())));
            // Время — только после ответа GitHub: неудачная проверка (нет сети) повторится при следующем открытии.
            prefs(context).edit().putLong("updateCheckedAt", System.currentTimeMillis()).apply();
            if (release == null) {
                return;
            }
            String version = release.getString("tag_name").replaceFirst("^v", "");
            String apk = null;
            String sha256 = null;
            JSONArray assets = release.optJSONArray("assets");
            for (int i = 0; assets != null && i < assets.length(); i++) {
                JSONObject a = assets.getJSONObject(i);
                if (a.optString("name").endsWith(".apk")) {
                    apk = a.optString("browser_download_url", null);
                    String digest = a.optString("digest", "");
                    sha256 = digest.startsWith("sha256:") ? digest.substring(7) : null;
                    break;
                }
            }
            prefs(context).edit()
                    .putString("updateVersion", version)
                    .putString("updateUrl", release.optString("html_url", downloadUrl(context)))
                    .putString("updateApk", apk)
                    .putString("updateApkSha256", sha256)
                    .apply();
            if (newer(version, BuildConfig.VERSION_NAME)) {
                EventLog.add("Обновление: доступна версия " + version);
            }
        } catch (Exception e) {
            EventLog.add("Обновление: проверить не удалось (" + e.getMessage() + ")");
        } finally {
            if (c != null) {
                c.disconnect();
            }
        }
    }

    /** Опубликованный релиз с наибольшей версией (черновики пропускаем). */
    private static JSONObject newest(JSONArray releases) throws Exception {
        JSONObject best = null;
        for (int i = 0; i < releases.length(); i++) {
            JSONObject r = releases.getJSONObject(i);
            if (r.optBoolean("draft")) {
                continue;
            }
            if (best == null || newer(r.getString("tag_name").replaceFirst("^v", ""), best.getString("tag_name").replaceFirst("^v", ""))) {
                best = r;
            }
        }
        return best;
    }

    /** «0.10» новее «0.9»: сравнение по числам, нечисловые хвосты («-beta») не учитываются. */
    static boolean newer(String candidate, String installed) {
        String[] a = candidate.split("[^0-9]+");
        String[] b = installed.split("[^0-9]+");
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int x = i < a.length && !a[i].isEmpty() ? Integer.parseInt(a[i]) : 0;
            int y = i < b.length && !b[i].isEmpty() ? Integer.parseInt(b[i]) : 0;
            if (x != y) {
                return x > y;
            }
        }
        return false;
    }

    private static String read(InputStream in) throws Exception {
        try (InputStream s = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = s.read(buf)) > 0; ) {
                out.write(buf, 0, n);
            }
            return out.toString("UTF-8");
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
