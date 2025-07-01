#!/bin/bash

# Set JAVA_HOME to Android Studio's JDK
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"

# Check if JAVA_HOME exists
if [ ! -d "$JAVA_HOME" ]; then
    echo "Error: Android Studio JDK not found at $JAVA_HOME"
    echo "Please make sure Android Studio is installed or adjust the JAVA_HOME path"
    exit 1
fi

echo "Using JAVA_HOME: $JAVA_HOME"
echo "Building ARCore Demo project..."

# Run the Gradle build
./gradlew build

echo "Build completed!" 