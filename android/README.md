# rustTV for Android

Native Android app (Kotlin, Media3/ExoPlayer) with Xtream Codes login, sortable lists, bouquet manager,
vault, parental PIN, gestures, picture-in-picture and multi-view.

- Ready-to-install APK: [`dist/rustv-android.apk`](dist/rustv-android.apk) (also attached to every GitHub release)
- The app checks [`version.json`](version.json) and offers updates itself.

## Build

```bash
echo "sdk.dir=/path/to/android-sdk" > local.properties
# optional, for a signed release build:
cat > keystore.properties <<'P'
storeFile=/path/to/release.jks
storePassword=...
keyAlias=...
keyPassword=...
P
gradle assembleRelease
```

Updates only install over an existing installation when they are signed with the **same key**.
