#!/usr/bin/env bash
set -e

echo "=== Android QEMU Emulator Connected ==="
adb devices

echo "Installing Local Bots APK..."
APK_FILE=$(find build-artifacts -name "*.apk" | head -n 1)
echo "Target APK: $APK_FILE"
adb install -r "$APK_FILE"

echo "Clearing Logcat..."
adb logcat -c

echo "Starting MainActivity..."
adb shell am start -n com.localbots.app/.MainActivity

echo "Waiting for app initialization (15 seconds)..."
sleep 15

echo "Checking running process..."
PID=$(adb shell pidof com.localbots.app | tr -d '\r\n')
echo "Detected App Process PID: '$PID'"

if [ -z "$PID" ]; then
    echo "CRITICAL: App process is not running! Checking Logcat for crash details:"
    adb logcat -d | grep -C 5 -E "AndroidRuntime|FATAL|com.localbots.app" || adb logcat -d | tail -n 50
    exit 1
fi

echo "✓ Local Bots app is running stably on QEMU Android Emulator with PID $PID!"

echo "=== Top Activity Information ==="
adb shell dumpsys activity activities | grep -E "mResumedActivity|topResumedActivity|mFocusedApp" || true

echo "=== Capturing Smoke Test Screenshot ==="
adb exec-out screencap -p > app_smoke_test.png || true
if [ -f app_smoke_test.png ]; then
    ls -lh app_smoke_test.png
fi

echo "Recent Local Bots Logcat:"
adb logcat -d | grep -i -E "LocalBots|MainActivity|ThemeManager|SettingsRepository|SetupViewModel" || true
exit 0
