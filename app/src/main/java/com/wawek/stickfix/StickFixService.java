package com.wawek.stickfix;

import android.app.AppOpsManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;

/**
 * Foreground service: keeps the daemon connection alive and switches the live
 * profile to match whichever app is in front. The remapping itself runs in
 * stickfixd, so input latency does not depend on this process.
 */
public class StickFixService extends Service implements Engine.Listener {
    private static final String CHANNEL = "stickfix";
    private static final int NOTIF_ID = 1;
    private static final long POLL_MS = 1000;
    private static final long RESTART_MS = 20_000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Engine engine;
    private UsageStatsManager usage;
    private long lastQuery;
    private String status = "Waiting for stickfixd";

    static void start(Context ctx) {
        ctx.startForegroundService(new Intent(ctx, StickFixService.class));
    }

    static boolean hasUsageAccess(Context ctx) {
        AppOpsManager ops = ctx.getSystemService(AppOpsManager.class);
        int mode = ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.getPackageName());
        return mode == AppOpsManager.MODE_ALLOWED;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        engine = Engine.get(this);
        usage = getSystemService(UsageStatsManager.class);
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "StickFix", NotificationManager.IMPORTANCE_LOW));
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIF_ID, buildNotification());
        }
        engine.addListener(this);
        handler.post(poll);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(poll);
        engine.removeListener(this);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            String pkg = foregroundPackage();
            if (pkg != null) engine.setForegroundPackage(pkg);
            // Start at boot, and bring the daemon back if it dies.
            if (engine.enabled() && !engine.isConnected()
                    && System.currentTimeMillis() - engine.lastLaunchMs() > RESTART_MS && PServer.available()) {
                engine.startDaemon(null);
            }
            handler.postDelayed(this, POLL_MS);
        }
    };

    /** Last app that came to the front, or null if unknown / no usage access. */
    private String foregroundPackage() {
        if (!hasUsageAccess(this)) return null;
        long now = System.currentTimeMillis();
        long from = lastQuery == 0 ? now - 60_000 : lastQuery - 2000;
        lastQuery = now;
        UsageEvents events = usage.queryEvents(from, now);
        UsageEvents.Event e = new UsageEvents.Event();
        String last = null;
        while (events.hasNextEvent()) {
            events.getNextEvent(e);
            if (e.getEventType() == UsageEvents.Event.ACTIVITY_RESUMED) last = e.getPackageName();
        }
        return last;
    }

    @Override
    public void onConnection(boolean connected, String info) {
        status = connected ? "Remapping" : engine.enabled() ? "Starting remapper" : "Stopped";
        updateNotification();
    }

    @Override
    public void onActiveProfile(Profile p) {
        updateNotification();
    }

    private void updateNotification() {
        getSystemService(NotificationManager.class).notify(NOTIF_ID, buildNotification());
    }

    private Notification buildNotification() {
        Profile p = engine == null ? null : engine.activeProfile();
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_stick)
                .setContentTitle("StickFix: " + status)
                .setContentText(p == null ? "" : "Profile: " + p.label)
                .setContentIntent(open)
                .setOngoing(true)
                .build();
    }
}
