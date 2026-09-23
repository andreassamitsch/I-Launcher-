# CloudStream external playback session regression

Problem reported on Android TV: opening series A from I Launcher Watch Next, later opening series B, then Back could reveal series A and still older episode/result screens or hang. CloudStream MainActivity is `singleTask` and keeps its Navigation backstack while the application is in the background.

Expected behavior: on every valid externally requested CloudStream episode, synchronously reset the previous result/player navigation chain to CloudStream home before provider matching begins. While a newer request is being resolved, no older asynchronous provider resolution is allowed to navigate to a player, details, or search. The old fragment's onStop/onDestroy must not mutate the replacement episode resume pointer. A player started from an external request must not place an older series below it in the backstack. In-app playback/navigation is otherwise unchanged.

Device regression sequence: start A via Watch Next; press Home to I Launcher; start B via Watch Next; verify B playback; Back must not reveal A (or an earlier episode of A). Repeat A/B/C rapidly, including one deliberately slow plugin resolution. Repeat via the older `cloudstreamcontinuewatching` intent and the direct `cloudstreamplay` intent. Confirm old playback stops promptly on a new external request, previous progress is retained and I Launcher Watch Next remains correct.

Source implementation: `ILauncherBridgeNavigation.kt`, `apply_external_session_fix.py`; patched into the reproducible CloudStream APK by `cloudstream-bridge.yml`. Passing CI validates patch application and Kotlin compilation, not TV hardware behavior.
