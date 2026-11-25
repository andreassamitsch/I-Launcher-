# TCL performance regression – phase 1

Baseline: dev.557, Android 14, 1920x1080 logical UI, 60 Hz display. ADB gfxinfo: 2494 frames, 1354 janky (54.29%), p50 77 ms, p95 150 ms, p99 300 ms. A six-second observation with launcher foreground produced 244 frames, 123 janky; media-card focus verified separately. CPU sample: 112% of one core. No thermal throttling reported. These are observational baseline values, not a controlled isolated A/B experiment.

Changes: static artwork-colored glow and border at previous mid-Breath intensity, no infinite card animation, remembered static shadow modifier, delayed cancellable color decode, software pixel analysis off main dispatcher. Home hero coalesces distinct focus changes for 180 ms; crossfade reduced from 230 to 120 ms. Same-identity EPG updates remain immediate. Focus graph, row layout, card activation, source ordering and updater unchanged.

Hardware test after installing signed APK: allow startup loads to settle; on the same home card record gfxinfo and CPU twice separated by 6 seconds without remote input. Then reset gfxinfo and navigate six LEFT/RIGHT steps in a rail, DOWN to next rail, LEFT/RIGHT, UP; repeat identically for both builds if available. Record frame count, jank ratio, p50/p95, CPU, and check correct hero, visible colored halo, no Y drift, one UP to nav, playback/details, focus restoration. Avoid interpreting gfxinfo GPU sentinel values as real GPU duration. No performance claim until measured.

Next phases if indicated by measurements: reduce preview query/invalidations and hidden-channel work, review hero text auto-scroll, vertical row composition and optimized build. Do not change row keyline or provider behavior in this initial experiment.
