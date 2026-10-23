package io.github.queryparams.overtakelink;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;

/**
 * Сервер головного устройства Android Auto (порт 5277). Проверяем, не подключаясь к нему: AA отдаёт
 * каждое принятое соединение своему car-сервису и ждёт его без тайм-аута, так что пробное
 * подключение вешает сервер. Вместо этого пытаемся на миг занять его порт: занят — значит, AA слушает.
 */
public final class AaServer {
    public static final String HOW_TO_START = "Включите на телефоне сервер Android Auto\n"
            + "(Android Auto → ⋮ → «Запустить сервер головного устройства»)\nи переподключите кабель";

    private AaServer() {
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
