# Build and Launch on Android Phone

Build the debug APK and install/launch it on the connected Android phone.

## Steps

1. Set `JAVA_HOME` to Android Studio's bundled JDK:
   ```
   export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
   ```

2. Build and install in one step:
   ```
   ./gradlew installDebug
   ```

3. Launch the app on the physical phone (not emulator) using adb:
   ```
   ADB=~/Library/Android/sdk/platform-tools/adb
   PIXEL=$($ADB devices | grep -v emulator | grep -v 'List of' | grep -v '^$' | head -1 | awk '{print $1}')
   $ADB -s "$PIXEL" shell am start -n com.trackspeed.android/.MainActivity
   ```

If no physical device is connected, fall back to the emulator by picking the first available device from `adb devices`.
