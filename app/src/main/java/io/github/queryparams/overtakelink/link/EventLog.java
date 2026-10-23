package io.github.queryparams.overtakelink.link;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

/**
 * Журнал: в памяти (для экрана), в logcat и в файл приложения.
 * Файл переживает падения; выгрузка в «Загрузки» перезаписывает один и тот же файл за запуск.
 * Процесс может стартовать до разблокировки телефона (приёмник загрузки), когда файлы приложения
 * ещё недоступны: тогда файл открывается при первой записи после разблокировки.
 */
public final class EventLog {
    private static final String TAG = "OL";
    private static final int MAX_LINES = 5000;
    private static final long MAX_FILE = 8L << 20;
    private static final ArrayDeque<String> lines = new ArrayDeque<>();
    private static final SimpleDateFormat TIME = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);
    private static long version;
    private static File file;
    private static BufferedWriter writer;
    private static Uri exportUri;
    /** Строк в памяти, ещё не записанных в файл. */
    private static int unsaved;
    private static String exportName;

    private EventLog() {
    }

    public static synchronized void init(Context context) {
        if (file != null) {
            return;
        }
        file = new File(context.getFilesDir(), "overtakelink-log.txt");
        open();
    }

    /** Журнал запущен приложением (в модульных тестах Open Headunit — нет: там нет ни Context, ни logcat). */
    public static synchronized boolean isStarted() {
        return file != null;
    }

    /** Открыть файл, дописав в него то, что накопилось в памяти, пока он был недоступен. */
    private static boolean open() {
        try {
            if (file.length() > MAX_FILE) {
                File old = new File(file.getParentFile(), "overtakelink-log.old.txt");
                old.delete();
                file.renameTo(old);
            }
            writer = new BufferedWriter(new FileWriter(file, true));
            if (unsaved > 0) {
                int skip = Math.max(0, lines.size() - unsaved);
                for (String line : lines) {
                    if (skip-- > 0) {
                        continue;
                    }
                    writer.write(line);
                    writer.newLine();
                }
                writer.flush();
                unsaved = 0;
            }
            return true;
        } catch (IOException e) {
            writer = null;
            return false;
        }
    }

    public static synchronized void add(String msg) {
        String line = TIME.format(new Date()) + "  " + msg;
        Log.i(TAG, msg);
        lines.addLast(line);
        while (lines.size() > MAX_LINES) {
            lines.removeFirst();
        }
        version++;
        unsaved = Math.min(unsaved + 1, MAX_LINES);
        if (file == null || (writer == null && !open())) {
            return;
        }
        try {
            writer.write(line);
            writer.newLine();
            writer.flush();
            unsaved = 0;
        } catch (IOException e) {
            writer = null;
        }
    }

    public static void error(String what, Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        add("ОШИБКА: " + what + "\n" + sw);
    }

    public static synchronized long version() {
        return version;
    }

    public static synchronized String tail(int count) {
        StringBuilder sb = new StringBuilder();
        int skip = Math.max(0, lines.size() - count);
        for (String line : lines) {
            if (skip-- > 0) {
                continue;
            }
            sb.append(line).append('\n');
        }
        return sb.toString();
    }

    public static synchronized void clear() {
        lines.clear();
        version++;
    }

    /** Копирует файл журнала в Download/OvertakeLink/ (один файл на запуск приложения). */
    public static synchronized String export(Context context) throws Exception {
        if (writer != null) {
            writer.flush();
        }
        byte[] data = file != null && unsaved == 0 && file.exists()
                ? Files.readAllBytes(file.toPath())
                : tail(MAX_LINES).getBytes(StandardCharsets.UTF_8);
        if (exportUri == null) {
            exportName = "overtakelink-log-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date()) + ".txt";
            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME, exportName);
            values.put(MediaStore.Downloads.MIME_TYPE, "text/plain");
            values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/OvertakeLink");
            exportUri = context.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (exportUri == null) {
                throw new IllegalStateException("MediaStore не создал файл");
            }
        }
        try (OutputStream out = context.getContentResolver().openOutputStream(exportUri, "wt")) {
            out.write(data);
        }
        return "Download/OvertakeLink/" + exportName;
    }

    /** Полностью очищает файл журнала (по кнопке). */
    public static synchronized void wipeFile() {
        if (file == null) {
            return;
        }
        try {
            if (writer != null) {
                writer.close();
            }
            writer = new BufferedWriter(new FileWriter(file, false));
        } catch (IOException ignored) {
        }
        lines.clear();
        version++;
    }

    public static String hex(byte[] data, int off, int len, int max) {
        StringBuilder sb = new StringBuilder();
        int n = Math.min(len, max);
        for (int i = 0; i < n; i++) {
            if (i > 0 && i % 32 == 0) {
                sb.append("\n    ");
            } else if (i > 0) {
                sb.append(' ');
            }
            sb.append(String.format(Locale.US, "%02x", data[off + i] & 0xff));
        }
        if (len > max) {
            sb.append(" … (+").append(len - max).append(" байт)");
        }
        return sb.toString();
    }

    public static String text(byte[] data, int off, int len) {
        return new String(data, off, len, StandardCharsets.UTF_8).replaceAll("[^\\x20-\\x7e\\u0400-\\u04ff]", "·");
    }
}
