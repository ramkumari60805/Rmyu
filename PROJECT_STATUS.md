# RMYU - Project Status

## Project Overview
- App name: RMYU
- Platform: Android
- Package: com.rmyu.app
- Repository: https://github.com/ramkumari60805/Rmyu
- Main branch: main
- Target device: iQOO Z10x
- Android version on target device: Android 16

## Build Configuration
- Android Gradle Plugin: 8.7.3
- Kotlin plugin: 2.0.21
- compileSdk: 34
- minSdk: 26
- targetSdk: 34
- Java/Kotlin JVM target: 17
- Shizuku API/provider: 13.1.5

## Existing Implementation (Code Inspection)
- MainActivity displays RAM and battery temperature information.
- MainActivity includes Shizuku status, permission and test controls.
- MainActivity includes an FPS diagnostic feature.
- OverlayService creates a floating application overlay.
- OverlayService attempts to identify the foreground app and SurfaceFlinger layers.
- OverlayService attempts to estimate FPS using SurfaceFlinger latency data.
- Overlay displays FPS, free RAM and battery temperature/debug information.

## Not Yet Verified
- CPU monitoring implementation is not confirmed.
- FPS measurement accuracy is not verified.
- Actual overlay behavior on iQOO Z10x is not device-tested.
- Shizuku permission and operation on Android 16 are not device-tested.
- A successful APK build, installation and launch are not confirmed.

## Important Technical Notes
- Battery temperature is not necessarily CPU temperature.
- FPS estimates should not be presented as verified measurements until tested.
- Do not mark any feature as working based only on source-code inspection.

## Current Development Priority
1. Verify the current project builds.
2. Install and launch it on the target phone.
3. Test overlay permission and behavior.
4. Test Shizuku connection and permission flow.
5. Validate FPS readings.
6. Inspect and verify CPU monitoring.
7. Improve features only after baseline testing.

## Last Documentation Update
2026-11-26
