# TCL performance regression – phase 1

Baseline: dev.557, Android 14, 1920x1080 logical UI, 60 Hz display. ADB gfxinfo: 2494 frames, 1354 janky (54.29%), p50 77 ms, p95 150 ms, p99 300 ms. A six-second observation with launcher foreground produced 244 frames, 123 janky; media-card focus verified separately. CPU sample: 112% of one core. No thermal throttling reported. These are observational baseline values, not a controlled isolated A/B experiment.

Changes: static artwork-colored glow and border at previous mid-Breath intensity, no infinite card animation, remembered static shadow modifier, delayed cancellable color decode, software pixel analysis off main dispatcher. Home hero coalesces distinct focus changes for 180 ms; crossfade reduced from 230 to 120 ms. Same-identity EPG updates remain immediate. Focus graph, row layout, card activation, source ordering and updater unchanged.

Hardware test after installing signed APK: allow startup loads to settle; on the same home card record gfxinfo and CPU twice separated by 6 seconds without remote input. Then reset gfxinfo and navigate six LEFT/RIGHT steps in a rail, DOWN to next rail, LEFT/RIGHT, UP; repeat identically for both builds if available. Record frame count, jank ratio, p50/p95, CPU, and check correct hero, visible colored halo, no Y drift, one UP to nav, playback/details, focus restoration. Avoid interpreting gfxinfo GPU sentinel values as real GPU duration. No performance claim until measured.

Next phases if indicated by measurements: reduce preview query/invalidations and hidden-channel work, review hero text auto-scroll, vertical row composition and optimized build. Do not change row keyline or provider behavior in this initial experiment.

## Phase 2 – Google-TV-inspired rendering

- LazyColumn with stable row keys and row-owned index alignment; no per-row root-coordinate/scroll-state subscriptions. Horizontal lists retain their original keys and restore by source identity after restoring the vertical row.
- Hero state is read in a separate composition scope; pending or displayed changes no longer invalidate Home rows.
- Glow uses a bounded 8-entry half-resolution neutral alpha-mask cache with draw-time tint. No recurring blur or continuous breath clock. Original artwork sampling stays cancellable.
- Updater builds the existing debug source set with opt-in non-debuggable R8 optimization, stable signing, and conservative reflection keeps. Normal local debug builds remain unchanged. ADB gfxinfo and logcat remain usable; run-as access to private diagnostic files is not available in the optimized APK. Verify provider diagnostics separately with normal debug APK if needed.
- Custom app Baseline Profiles are deferred until a representative navigation trace and Macrobenchmark device/test setup can be validated; do not invent profile entries.

Required device checks: same 30s UP/DOWN and LEFT/RIGHT sequence as dev.561, 6s idle; zero Y drift on horizontal navigation; one UP from first row; last-row keyline; restore Watch Next, Live TV, Apps and configuration after returning; verify artwork glow and TMDB/EPG parsing in optimized build. No APK considered hardware tested until these pass.
