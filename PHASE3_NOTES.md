# Phase 3 – TMDB development notes

Temporary development notes for the Phase-3 branch. The stable architecture/status will be folded into `README.md`, `ROADMAP.md` and `ARCHITECTURE.md` before the phase is completed.

Current implementation:

- unified `MediaItem` / `MediaSource` model
- Android Watch Next media-type mapping
- deterministic title/year/season/episode parsing
- confidence-based TMDB candidate matching
- Retrofit/OkHttp TMDB client using a bearer read-access token supplied only through build environment/Gradle properties
- Room cache for mappings, media metadata and episode metadata
- negative match caching
- 30-day refresh / 180-day hard cache limit
- TMDB image configuration caching and size selection
- source-first Watch Next rendering followed by bounded asynchronous TMDB enrichment
- source ordering, source filters and source deep links remain unchanged

The build works without a TMDB token and then behaves like the Phase-2 source-only implementation. A live TMDB device test requires `IL_TMDB_READ_ACCESS_TOKEN` to be configured outside the repository.
