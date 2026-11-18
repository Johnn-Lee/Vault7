package icu.weierstrass.vault7;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import java.io.InterruptedIOException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;

public final class BackupService extends Service {
    static final String CANCEL = "icu.weierstrass.vault7.CANCEL";
    private static final String CHANNEL = "backup";
    private static final int NOTIFICATION = 7;

    record Status(boolean running, String message) {}
    private static volatile Status status = new Status(false, "还没有运行备份");
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean canceled;

    static Status status() {
        return status;
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (CANCEL.equals(intent.getAction())) {
            if (status.running) {
                canceled = true;
                status = new Status(true, "正在取消…");
            } else {
                stopSelf();
            }
            return START_NOT_STICKY;
        }
        if (status.running) return START_NOT_STICKY;

        NotificationManager notifications = getSystemService(NotificationManager.class);
        notifications.createNotificationChannel(new NotificationChannel(
                CHANNEL, "相册备份", NotificationManager.IMPORTANCE_LOW));
        status = new Status(true, "正在扫描相册…");
        startForeground(NOTIFICATION, notification(status.message));
        new Thread(() -> run(intent), "vault7-backup").start();
        return START_NOT_STICKY;
    }

    private void run(Intent intent) {
        BackupEngine engine = null;
        char[] password = null;
        String result;
        try {
            BackupPlan plan = new BackupPlan(
                    BackupPlan.Mode.valueOf(intent.getStringExtra("mode")),
                    parseDate(intent.getStringExtra("start")), parseDate(intent.getStringExtra("end")),
                    intent.getIntExtra("days", 0), intent.getLongExtra("limit", 0),
                    ZoneId.of(intent.getStringExtra("zone")));
            password = new PasswordStore(this).read();
            engine = new BackupEngine(this, plan, Uri.parse(intent.getStringExtra("folder")),
                    password, this::checkCanceled, this::progress);
            engine.run();
            result = engine.summary();
        } catch (InterruptedIOException e) {
            result = "备份已停止。" + summary(engine);
        } catch (Exception e) {
            String reason = e.getLocalizedMessage();
            result = "备份中断" + (reason == null ? "。" : "：" + reason) + summary(engine);
        } finally {
            if (password != null) Arrays.fill(password, '\0');
        }
        String message = result;
        main.post(() -> {
            status = new Status(false, message);
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        });
    }

    private static String summary(BackupEngine engine) {
        return engine == null ? "" : "\n" + engine.summary();
    }

    private static LocalDate parseDate(String date) {
        return date == null ? null : LocalDate.parse(date);
    }

    private void checkCanceled() throws InterruptedIOException {
        if (canceled) throw new InterruptedIOException("Canceled");
    }

    private void progress(String text) {
        if (canceled) return;
        status = new Status(true, text);
        main.post(() -> {
            if (status.running() && !canceled) {
                getSystemService(NotificationManager.class).notify(NOTIFICATION, notification(text));
            }
        });
    }

    private Notification notification(String text) {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent cancel = PendingIntent.getService(this, 1, new Intent(this, BackupService.class)
                .setAction(CANCEL), PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_vault)
                .setContentTitle("Vault7 相册备份").setContentText(text)
                .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
                .addAction(new Notification.Action.Builder(null, "取消", cancel).build()).build();
    }

    @Override public void onTimeout(int startId, int foregroundServiceType) {
        canceled = true;
        stopSelf();
    }

    @Override public void onDestroy() {
        canceled = true;
        super.onDestroy();
    }
}
