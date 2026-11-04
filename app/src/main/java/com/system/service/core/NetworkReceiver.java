package com.system.service.core;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.util.Log;

public class NetworkReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (action == null) return;

        if (action.equals(ConnectivityManager.CONNECTIVITY_ACTION) ||
            action.equals("android.net.wifi.WIFI_STATE_CHANGED")) {
            
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) {
                NetworkInfo active = cm.getActiveNetworkInfo();
                if (active != null && active.isConnected()) {
                    Log.i("NetworkReceiver", "Network connected: " + active.getTypeName());
                    PersistenceService.start(context);
                }
            }
        }
    }
}