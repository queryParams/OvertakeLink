package io.github.queryparams.overtakelink;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

import com.andrerinas.openheadunit.BuildConfig;
import com.andrerinas.openheadunit.aap.AapService;

/**
 * Невидимый экран, который Android открывает при подключении машины (USB-аксессуар QDriveLink):
 * разрешение на аксессуар выдаётся ему, и он сразу передаёт подключение сервису OvertakeLink.
 */
public final class UsbAttachActivity extends Activity {
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (!Terms.accepted(this)) {
            // Работать с машиной — только после принятия условий: открываем их вместо подключения.
            // В задаче приложения, а не в этой: иначе следующее подключение кабеля лишь вынесет
            // экран на передний план, не запустив подключение.
            startActivity(new Intent(this, OvertakeLinkActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            finish();
            return;
        }
        getSharedPreferences("overtakelink", MODE_PRIVATE).edit().putBoolean("usbSeen", true).apply();
        String sim = BuildConfig.SIM_LINK ? getIntent().getStringExtra(OvertakeLinkService.EXTRA_SIM) : null;
        startForegroundService(sim == null
                ? new Intent(this, OvertakeLinkService.class).setAction(OvertakeLinkService.ACTION_CONNECT_USB)
                : new Intent(this, OvertakeLinkService.class).setAction(OvertakeLinkService.ACTION_CONNECT_SIM)
                        .putExtra(OvertakeLinkService.EXTRA_SIM, sim));
        // Приёмник Android Auto поднимаем в foreground сейчас, пока этот экран виден: из фона Android 12+
        // такой запуск запрещает. Команду «включить режим» OvertakeLink пошлёт ему позже.
        startForegroundService(new Intent(this, AapService.class));
        finish();
    }
}
