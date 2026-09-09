# Joyn TV for I Launcher

Standalone Android-TV application module for Joyn interoperability experiments and a lightweight live-TV client.

## v0.1 scope

- Android TV / D-pad first UI in the visual language of I Launcher
- DE / AT / CH tenant handling (country follows the TV locale)
- dynamic discovery and caching of Joyn's current web API gateway key
- anonymous Joyn session + refresh
- GraphQL live channel and EPG catalogue
- entitlement handshake
- DASH playback with Widevine through AndroidX Media3
- Android `TvContract` preview channel `Joyn · Live TV`
- explicit deep links from preview programs into the internal player
- premium/Plus-marked streams are hidden in anonymous mode

The module does **not** bypass Joyn subscriptions, geo rules, entitlements, Widevine or other access controls.

## Build

From the repository root:

```bash
gradle :joyntv:testDebugUnitTest :joyntv:assembleDebug
```

APK output:

```text
joyntv/build/outputs/apk/debug/joyntv-debug.apk
```

## Why this is a separate app module

I Launcher already consumes Android TV Preview Channels generically. Keeping Joyn as a separate package (`com.andreassamitsch.joyntv`) avoids coupling private streaming APIs to the launcher process. If Joyn changes an endpoint, only this APK needs updating.

See [`../docs/JOYN_API_ANALYSIS.md`](../docs/JOYN_API_ANALYSIS.md) for the reverse-engineered protocol map and implementation notes.
