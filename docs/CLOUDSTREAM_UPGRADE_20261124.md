# CloudStream 4.9.1 upstream migration candidate

Pinned official master: a28e6468f54cfe420feebd205d3d21d08ed0cde8 (2026-11-22). Previous known bridge base: a72f9e6c3f2e25eb74ce0e7d6cc56dc33c130288; published fallback remains 4.8.0-PRE-I-Launcher.92.

Candidate builds must not publish to cloudstream-downloads. Preserve package identity, signing, version code monotonicity, plugin settings, account data, Watch Next provider publication and exact handoff, previous navigation lifecycle fixes, availability guard, episode monitor and updater. Validate patch anchors, unit tests, signed APK and real TV upgrade from .92 before publishing.

Open follow-up: provider-independent series identity using verified external IDs (TMDB/IMDb; no title-only merges), account isolation, preserving explicit watched/unwatched intent and progress, removing stale duplicate platform rows without deleting source history, and background episode monitoring with bounded scheduling, initialized provider runtime and network constraints. Force-stop is an Android platform boundary.

Device tests: same-provider S1E3 -> S1E7, cached old URI, S1E10 -> S2E1, manually mark viewed, two providers for one series, distinct same-title series, explicit rewatch, cold start, player backstack, new episode availability, in-place .92 upgrade.
