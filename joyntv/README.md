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
- optional test proxy with manual configuration or automatic public-proxy discovery

The module does **not** bypass Joyn subscriptions, geo rules, entitlements, Widevine or other access controls.

## Test proxy

The proxy feature is disabled by default. In automatic mode the app:

1. resolves the currently selected Joyn market (DE / AT / CH),
2. downloads fresh public HTTP(S) candidates from ProxyScrape and, when needed, Proxifly,
3. rejects transparent/non-HTTPS-capable entries,
4. tests candidates in small parallel batches,
5. verifies the real exit country independently,
6. verifies an HTTPS request to the matching Joyn website,
7. persists and activates only a successful candidate.

Proxy-list downloads always bypass the configured proxy so a dead previous proxy cannot prevent recovery. The normal mode only routes Joyn control-plane hosts through the proxy; the explicit full-proxy option additionally covers media traffic.

Public free proxies are inherently unreliable and untrusted. This mode exists for interoperability testing only. TLS certificate validation is never disabled. A manually configured trusted proxy remains available as the recommended fallback for repeatable tests.

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
