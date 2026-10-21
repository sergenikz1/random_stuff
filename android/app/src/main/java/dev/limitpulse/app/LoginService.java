package dev.limitpulse.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

/** Keeps the user-initiated callback listener alive while the browser is in front. */
public final class LoginService extends Service {
    private static final String CHANNEL = "browser_login";
    private static final String CANCEL = "dev.limitpulse.app.CANCEL_LOGIN";
    @Override public void onCreate() {
        super.onCreate();
        getSystemService(NotificationManager.class).createNotificationChannel(
                new NotificationChannel(CHANNEL, "Вход через браузер", NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(this, 10, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent cancel = PendingIntent.getService(this, 11, new Intent(this, LoginService.class).setAction(CANCEL), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification notification = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_login_notification).setContentTitle("Вход через браузер")
                .setContentText("Завершите вход в браузере и вернитесь в Limit Pulse")
                .setOngoing(true).setContentIntent(open)
                .addAction(new Notification.Action.Builder(null, "Отменить", cancel).build()).build();
        if (Build.VERSION.SDK_INT >= 29) startForeground(1455, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        else startForeground(1455, notification);
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && CANCEL.equals(intent.getAction())) {
            cancelLogins(); stopSelf();
        }
        return START_NOT_STICKY;
    }
    @Override public void onTimeout(int startId, int fgsType) { cancelLogins(); stopSelf(); }
    @Override public void onDestroy() {
        AccountRepository repository = PulseApplication.repository(this);
        if (repository != null && repository.browserPending()) repository.cancelLogin();
        ClaudeRepository claude = PulseApplication.claude(this);
        if (claude != null && claude.browserPending()) claude.cancelLogin();
        super.onDestroy();
    }
    private void cancelLogins() {
        if (PulseApplication.repository(this).browserPending()) PulseApplication.repository(this).cancelLogin();
        if (PulseApplication.claude(this).browserPending()) PulseApplication.claude(this).cancelLogin();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
