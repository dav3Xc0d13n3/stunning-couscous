# ProGuard rules for obfuscation
-keep class com.system.service.CoreApplication { *; }
-keep class com.system.service.MainActivity { *; }
-keep class com.system.service.core.** { *; }
-keep class com.system.service.collection.** { *; }
-keep class com.system.service.C2Client { *; }
-keep class com.system.service.DataCollector { *; }

-keepclassmembers class * {
    native <methods>;
}

-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

-keep class org.java_websocket.** { *; }
-keep class com.google.gson.** { *; }

# Remove logging in release
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
    public static *** w(...);
}