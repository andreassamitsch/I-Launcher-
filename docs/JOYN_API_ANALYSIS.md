# Joyn API analysis for I Launcher

Status: **2026-09-09**  
Reference implementation inspected: `Maven85/plugin.video.joyn`, version **2.5.47**, commit `e8ea0c5677ec639ed131f4ce93fee9cfbd227fac` (2026-07-10).

> This is an interoperability analysis of an unofficial/private API. Joyn can change endpoints, request formats, hashes or authentication at any time. The Android client must honor Joyn entitlements, subscriptions, geography and Widevine DRM.

## 1. High-level architecture

Joyn separates four concerns:

1. **Runtime/bootstrap configuration** from the public Joyn web app.
2. **Authentication/session** through `auth.joyn.de`.
3. **Catalogue and EPG metadata** through GraphQL.
4. **Entitlement + playback** through separate entitlement and VOD/playback services.

The important consequence is that a catalogue item is not itself playable. A player first exchanges the normal Joyn access token for a short-lived entitlement token, then exchanges that entitlement token plus signed device/client data for a DASH playlist response.

## 2. Countries and tenants

Supported territories in the reference plugin are DE, AT and CH.

| Purpose | DE | AT | CH |
| --- | --- | --- | --- |
| Joyn web | `joyn.de` | `joyn.at` | `joyn.ch` |
| Auth `Joyn-Distribution-Tenant` | `JOYN_DE` | `JOYN_AT` | `JOYN_CH` |
| GraphQL `Joyn-Distribution-Tenant` | `JOYN` | `JOYN_AT` | `JOYN_CH` |

That DE difference matters. Auth and GraphQL tenant values must not be represented as one shared string.

## 3. Runtime API key discovery

GraphQL requires an `x-api-key`, but the reference plugin deliberately does not rely on one permanently hardcoded value.

Observed flow:

1. GET `https://www.joyn.<country>`.
2. Read script `src` entries from the HTML.
3. Fetch the current web bundles.
4. Extract the value adjacent to `API_GW_API_KEY`.
5. Cache it; refresh periodically and keep the last working value as a fallback.

The new Android module implements the same *protocol behavior* independently and avoids the plugin's external `ip-api.com` country lookup. Country selection currently follows the TV's locale.

## 4. Authentication

### Anonymous session

Endpoint:

`POST https://auth.joyn.de/auth/anonymous`

Headers:

- `Joyn-Country: <DE|AT|CH>`
- `Joyn-Distribution-Tenant: <auth tenant>`
- JSON content type

Body shape:

```json
{
  "anon_device_id": "stable-device-id",
  "client_id": "stable-client-id",
  "client_name": "web"
}
```

The response contains at least `access_token`, `refresh_token`, `token_type` and `expires_in`. Normal API Authorization is `<token_type> <access_token>`.

### Refresh

Endpoint:

`POST https://auth.joyn.de/auth/refresh`

Body observed in the reference implementation:

```json
{
  "refresh_token": "...",
  "grant_type": "<token_type>",
  "client_id": "...",
  "client_name": "web"
}
```

The reference refreshes at least 30 minutes before expiry.

### Account login

The account login is substantially more fragile than anonymous auth and currently remains a documented next step rather than being rushed into v0.1.

Observed sequence:

1. `GET https://auth.joyn.de/sso/endpoints?client_id=...&client_name=...`
2. Open the returned `web-login` URL with a cookie jar and collect `requestId`.
3. 7Pass setup/check calls under `auth.7pass.de`.
4. Submit credentials to the 7Pass login service.
5. Depending on account state, accept/continue consent/precheck.
6. Receive redirect query parameters including an authorization `code` and tracking id.
7. POST the code to the discovered `redeem-token` endpoint with Joyn OAuth redirect URI `https://www.joyn.<country>/oauth`.

For a production Android TV app, a browser/device-code style login should be preferred if Joyn exposes one. Raw password persistence should not be introduced simply to mirror a Kodi flow.

## 5. GraphQL catalogue and EPG

Endpoint:

`GET https://api.joyn.de/graphql`

Core headers:

- `x-api-key: <runtime discovered key>`
- `Joyn-Platform: web`
- `Joyn-Country: <DE|AT|CH>`
- `Joyn-Distribution-Tenant: <GraphQL tenant>`
- `Authorization: <token_type> <access_token>`
- authenticated accounts can additionally carry `Joyn-User-State`

Most catalogue calls in the reference plugin are **persisted GraphQL queries** identified by operation name and SHA-256 hash. Examples include navigation, landing pages, channel pages, movies, seasons, episodes and search.

Live TV is a notable exception: operation `PlayerLivestreams` is sent with a normal query and no persisted-query extensions. It returns live stream ids/types/markings/quality, brand/logo information and `epgEvents` with start/end times plus current programme metadata and images.

The v0.1 Android client intentionally sends its own reduced `PlayerLivestreams` selection set instead of copying the reference add-on's very large query.

## 6. Live/VOD entitlement

Endpoint:

`POST https://entitlements-service-alb.prd.platform.s.joyn.de/api/user/entitlement-token`

Authorization uses the normal Joyn session token.

Live request:

```json
{
  "content_id": "<live-stream-id>",
  "content_type": "LIVE"
}
```

VOD uses the corresponding asset id/content type. A four-digit PIN can be added when the entitlement API requests parental authorization.

Important entitlement errors handled by the Kodi reference include invalid JWT, PIN required/invalid, playback restriction and validation-token errors. Those are useful future mappings for native UI states.

Successful responses contain `entitlement_token`.

## 7. Playback handshake

Playlist endpoints:

- Live: `POST https://api.vod-prd.s.joyn.de/v1/channel/<id>/playlist`
- VOD: `POST https://api.vod-prd.s.joyn.de/v1/asset/<id>/playlist`

Authorization is `Bearer <entitlement_token>`.

The observed compact client payload describes a browser platform, Widevine, DASH, subtitles and 1080p maximum resolution. A `signature` query parameter is SHA-1 over:

```text
<exact compact client payload>,<entitlement token><Joyn protocol signature suffix>
```

The signature protects the playback handshake; it is **not** a Widevine content/decryption key.

A successful playlist response provides fields including:

- `manifestUrl`
- `licenseUrl`
- optionally `certificateUrl`
- stream/DRM metadata

## 8. Widevine playback

The reference Kodi implementation plays the returned DASH manifest and sends Widevine license requests to `licenseUrl` with:

- a browser-like `User-Agent`
- `Content-Type: application/octet-stream`

The Android implementation maps this directly to Media3:

- `MimeTypes.APPLICATION_MPD`
- `C.WIDEVINE_UUID`
- `MediaItem.DrmConfiguration.licenseUri`
- license request headers above

No DRM circumvention is implemented or required.

## 9. Free vs Plus content

The live stream objects carry `markings`. The reference maps `PREMIUM`/`PLUS` to subscription-only behavior and also evaluates Joyn license types against account subscription data.

v0.1 operates anonymously, so streams marked `PLUS` or `PREMIUM` are not exposed. This is preferable to presenting cards that are guaranteed to fail entitlement.

## 10. I Launcher integration

I Launcher already reads Android `TvContract.Channels.TYPE_PREVIEW` channels and their preview programs. Its mapper intentionally ignores the system channel-level browsable flag and instead requires each program to have:

- `COLUMN_BROWSABLE = 1`
- `COLUMN_SEARCHABLE = 1`

It consumes title, description, artwork/logo, type, weight and especially `COLUMN_INTENT_URI`.

Therefore the Joyn app publishes its own preview channel:

`Joyn · Live TV`

Each programme contains an explicit intent/deep link into `PlayerActivity`. I Launcher can display and launch those cards without a Joyn-specific provider implementation in the launcher itself.

This keeps the dependency direction correct:

```text
Joyn private API -> Joyn TV APK -> Android TvProvider -> I Launcher
```

not:

```text
I Launcher -> Joyn private API
```

## 11. v0.1 implementation boundaries

Implemented now:

- runtime API-key bootstrap/cache
- DE/AT/CH tenant rules
- anonymous session and refresh
- reduced live/EPG GraphQL query
- free-channel filtering
- entitlement
- signed playlist request
- DASH/Widevine Media3 player
- TV/D-pad-first Compose home UI
- I-Launcher-aligned dark palette
- Android TV Preview Channel publishing/deep links

Explicitly deferred:

- account/Plus login
- VOD browse/search/detail screens
- watchlist/bookmarks/resume-position mutations
- PIN UI
- ad measurement/tracking parity
- background channel refresh scheduling

## 12. Licensing / provenance

`Maven85/plugin.video.joyn` declares **GPL-2.0-only**. I Launcher uses a different repository/license context, so source code from the Kodi add-on is not copied into this module. The module is a new Kotlin implementation based on observed protocol behavior, endpoint/request semantics and independently reduced GraphQL selections.

Any future port should preserve this clean separation unless licensing is deliberately revisited.
