package io.github.queryparams.overtakelink;

import android.content.Intent;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;

/**
 * Сервер головного устройства Android Auto (порт 5277). Проверяем, не подключаясь к нему: AA отдаёт
 * каждое принятое соединение своему car-сервису и ждёт его без тайм-аута, так что пробное
 * подключение вешает сервер. Вместо этого пытаемся на миг занять его порт: занят — значит, AA слушает.
 */
public final class AaServer {
    private AaServer() {
    }

    /** Настройки Android Auto, где в меню «⋮» включается сервер головного устройства. */
    public static Intent settingsIntent() {
        return new Intent().setClassName("com.google.android.projection.gearhead",
                "com.google.android.projection.gearhead.companion.settings.DefaultSettingsActivity");
    }

    /** Блокирует на миллисекунды: вызывать не из главного потока. */
    public static boolean isListening() {
        try (ServerSocket probe = new ServerSocket()) {
            probe.setReuseAddress(false);
            probe.bind(new InetSocketAddress(5277));
            return false;
        } catch (IOException e) {
            return true;
        }
    }
}
