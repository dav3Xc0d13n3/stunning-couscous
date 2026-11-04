#!/bin/bash
# Build script for Android Spyware

set -e

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP_DIR="$PROJECT_DIR/app"

echo "[+] Building Android Spyware..."
echo "[+] Project: $PROJECT_DIR"

# Check for Android SDK
if [ -z "$ANDROID_HOME" ] && [ -z "$ANDROID_SDK_ROOT" ]; then
    echo "[-] ANDROID_HOME not set. Please set it to your Android SDK path."
    echo "    export ANDROID_HOME=\$HOME/Android/Sdk"
    exit 1
fi

SDK_PATH=${ANDROID_HOME:-$ANDROID_SDK_ROOT}
echo "[+] Using SDK: $SDK_PATH"

# Check for gradle wrapper or install
if [ ! -f "$PROJECT_DIR/gradlew" ]; then
    echo "[+] Generating Gradle wrapper..."
    cd "$PROJECT_DIR"
    gradle wrapper --gradle-version 8.5 2>/dev/null || {
        echo "[-] Gradle not found. Install gradle first."
        exit 1
    }
fi

chmod +x "$PROJECT_DIR/gradlew"

# Build APK
echo "[+] Building release APK..."
cd "$PROJECT_DIR"
./gradlew clean assembleRelease

APK_PATH="$APP_DIR/build/outputs/apk/release/app-release.apk"
if [ -f "$APK_PATH" ]; then
    echo "[+] Build successful!"
    echo "[+] APK: $APK_PATH"
    echo "[+] Size: $(du -h "$APK_PATH" | cut -f1)"
    
    # Optional: sign with debug key for testing
    if command -v apksigner &> /dev/null; then
        echo "[+] APK ready for installation"
        echo "    Install: adb install -r $APK_PATH"
    fi
else
    echo "[-] Build failed - APK not found"
    exit 1
fi