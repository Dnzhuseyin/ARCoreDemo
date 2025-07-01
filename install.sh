#!/bin/bash

echo "ARCore Demo Installation Script"
echo "================================"

# Check if device is connected
if ! adb devices | grep -q "device$"; then
    echo "❌ No Android device found!"
    echo "Please connect your device and enable USB debugging."
    exit 1
fi

echo "✅ Device connected"

# Build the app
echo "🔨 Building the app..."
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew assembleDebug

if [ $? -ne 0 ]; then
    echo "❌ Build failed!"
    exit 1
fi

echo "✅ Build successful"

# Install the app
echo "📱 Installing ARCore Demo..."
adb install -r app/build/outputs/apk/debug/app-debug.apk

if [ $? -ne 0 ]; then
    echo "❌ Installation failed!"
    exit 1
fi

echo "✅ App installed successfully"

# Start logcat for debugging
echo "🔍 Starting logcat (press Ctrl+C to stop)..."
echo "You can now launch the app on your device."
echo ""
adb logcat -s "ARCoreDemo" "AndroidRuntime" "native" "System.err" | grep -E "(com.example.arcoredemo|ARCore|Camera)" 