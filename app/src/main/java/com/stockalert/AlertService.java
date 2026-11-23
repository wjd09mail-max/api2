package com.stockalert;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import com.stockalert.core.AppCore;
import com.stockalert.core.Kakao;
import com.stockalert.core.Net;
import com.stockalert.core.Store;

import java.io.File;
import java.io.InputStream;

/** 백그라운드 감시를 유지하는 포그라운드 서비스 */
public class AlertService extends Service {
    public static final String ACTION_STOP = "com.stockalert.action.STOP";
    static final String CH_STATUS = "status";
    static final String CH_ALERT = "alerts";
    static final int STATUS_ID = 1;

    private static volatile AppCore core;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;
    private int nid = 100;

    public static AppCore core() {
        return core;
    }

    public static void start(Context c) {
        Intent i = new Intent(c, AlertService.class);
        c.startForegroundService(i);
    }

    private NotificationManager nm() {
        return (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
    }

    private PendingIntent openIntent() {
        Intent i = new Intent(this, MainActivity.class);
        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private Notification statusNotif(String text) {
        Intent stop = new Intent(this, AlertService.class);
        stop.setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 1, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Action stopAction = new Notification.Action.Builder(
                Icon.createWithResource(this, R.drawable.ic_stat), "종료", stopPi).build();
        return new Notification.Builder(this, CH_STATUS)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("📡 조건검색 알림")
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setContentIntent(openIntent())
                .addAction(stopAction)
                .build();
    }

    private final Kakao.Notifier notifier = new Kakao.Notifier() {
        @Override
        public void alert(String text) {
            String[] lines = text.split("\n", 2);
            String title = lines[0];
            String body = lines.length > 1 ? lines[1] : "";
            Notification n = new Notification.Builder(AlertService.this, CH_ALERT)
                    .setSmallIcon(R.drawable.ic_stat)
                    .setContentTitle(title)
                    .setContentText(body.replace("\n", " · "))
                    .setStyle(new Notification.BigTextStyle().bigText(body))
                    .setContentIntent(openIntent())
                    .setAutoCancel(true)
                    .setShowWhen(true)
                    .setWhen(System.currentTimeMillis())
                    .setCategory(Notification.CATEGORY_MESSAGE)
                    .build();
            int id;
            synchronized (AlertService.this) {
                nid = nid >= 180 ? 100 : nid + 1;
                id = nid;
            }
            nm().notify(id, n);
        }

        @Override
        public void status(String text) {
            nm().notify(STATUS_ID, statusNotif(text));
        }
    };

    private void createChannels() {
        NotificationChannel alerts = new NotificationChannel(CH_ALERT, "주식 알림", NotificationManager.IMPORTANCE_HIGH);
        alerts.setDescription("조건 편입, 등락률, 급등락, 지수 알림");
        alerts.enableVibration(true);
        NotificationChannel status = new NotificationChannel(CH_STATUS, "감시 상태", NotificationManager.IMPORTANCE_LOW);
        status.setDescription("백그라운드 감시 상태 표시");
        status.setShowBadge(false);
        nm().createNotificationChannel(alerts);
        nm().createNotificationChannel(status);
    }

    private String readAsset(String name) throws Exception {
        InputStream in = getAssets().open(name);
        return Net.readAll(in);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannels();
        Notification n = statusNotif("시작 중…");
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(STATUS_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(STATUS_ID, n);
        }
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "stockalert:engine");
        wakeLock.setReferenceCounted(false);
        wakeLock.acquire();
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wm != null) {
            int mode = Build.VERSION.SDK_INT >= 29 ? WifiManager.WIFI_MODE_FULL_LOW_LATENCY : WifiManager.WIFI_MODE_FULL_HIGH_PERF;
            wifiLock = wm.createWifiLock(mode, "stockalert:wifi");
            wifiLock.setReferenceCounted(false);
            wifiLock.acquire();
        }
        if (core == null) {
            final File cfgFile = new File(getFilesDir(), "config.json");
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        AppCore c = new AppCore(cfgFile, readAsset("index.html"), notifier);
                        c.startAll();
                        core = c;
                    } catch (Exception e) {
                        Store.Logx.add("앱 시작 실패: " + e.getMessage(), "error");
                        notifier.status("시작 실패: " + e.getMessage());
                    }
                }
            }, "startup").start();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            shutdown();
            stopForeground(Service.STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }
        return START_STICKY;
    }

    private void shutdown() {
        AppCore c = core;
        core = null;
        if (c != null) {
            final AppCore cc = c;
            new Thread(new Runnable() {
                @Override
                public void run() {
                    cc.stopAll();
                }
            }, "shutdown").start();
        }
    }

    @Override
    public void onDestroy() {
        shutdown();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
