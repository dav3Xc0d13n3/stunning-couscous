package com.system.service.core;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        Log.i("BootReceiver", "Received: " + action);

        if (action != null) {
            switch (action) {
                case Intent.ACTION_BOOT_COMPLETED:
                case "android.intent.action.QUICKBOOT_POWERON":
                case Intent.ACTION_MY_PACKAGE_REPLACED:
                    PersistenceService.start(context);
                    WatchdogService.start(context);
                    break;
                case Intent.ACTION_POWER_CONNECTED:
                    // Good time to sync
                    PersistenceService.start(context);
                    break;
            }
        }
    }
}