package io.github.queryparams.overtakelink;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.KeyEvent;

import com.andrerinas.openheadunit.App;
import com.andrerinas.openheadunit.connection.CommManager;

import io.github.queryparams.overtakelink.car.CarScreen;
import io.github.queryparams.overtakelink.link.EventLog;

/**
 * Кнопка голосового управления на руле: по Bluetooth (HFP) она приходит в телефон командой
 * VOICE_COMMAND. Пока Android Auto на экране машины, передаём ему клавишу поиска — Ассистент
 * открывается на экране ГУ; иначе отдаём команду обычному Ассистенту Google.
 */
public final class VoiceCommandActivity extends Activity {
    private static final String GOOGLE_APP = "com.google.android.googlequicksearchbox";

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        CommManager comm = App.Companion.provide(this).getCommManager();
        if (CarScreen.current() != null && comm.getConnectionState().getValue() instanceof CommManager.ConnectionState.TransportStarted) {
            EventLog.add("Руль: голосовая кнопка → Ассистент на экране машины");
            comm.sendKey(KeyEvent.KEYCODE_SEARCH, true, null, "overtakelink-voice");
            comm.sendKey(KeyEvent.KEYCODE_SEARCH, false, null, "overtakelink-voice");
        } else {
            EventLog.add("Руль: голосовая кнопка без Android Auto → Ассистент на телефоне");
            try {
                startActivity(new Intent(Intent.ACTION_VOICE_COMMAND).setPackage(GOOGLE_APP)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception e) {
                EventLog.error("запуск Ассистента", e);
            }
        }
        finish();
    }
}
