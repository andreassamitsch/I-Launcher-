#!/usr/bin/env python3
"""Separate the external entry task and publish current Media3 metadata."""
from pathlib import Path
import shutil
import sys

def replace_once(path, old, new):
    source = path.read_text(encoding="utf-8")
    if source.count(old) != 1:
        raise RuntimeError(f"{path}: expected one anchor: {old[:120]}")
    path.write_text(source.replace(old, new, 1), encoding="utf-8")

def main(root):
    base = root / "app/src/main/java/com/lagradost/cloudstream3"
    manifest = root / "app/src/main/AndroidManifest.xml"
    # The exported launcher/account entry must not be the base intent of
    # MainActivity's persistent player task. Android otherwise returns
    # START_TASK_TO_FRONT for subsequent NEW_TASK deep links without delivery.
    replace_once(manifest, """            android:name=".ui.account.AccountSelectActivity2"
            android:configChanges=""", """            android:name=".ui.account.AccountSelectActivity2"
            android:taskAffinity="${applicationId}.entry"
            android:excludeFromRecents="true"
            android:launchMode="singleTop"
            android:configChanges=""")
    account = base / "ui/account/AccountSelectActivity2.kt"
    replace_once(account, "import android.annotation.SuppressLint\n", """import android.annotation.SuppressLint
import android.content.Intent
import com.lagradost.cloudstream3.ILauncherPlaybackSession
""")
    replace_once(account, """    @SuppressLint("UnsafeIntentLaunch")
    private fun navigateToMainActivity() {
        hasLoggedIn = true
        // We want to propagate any intent we get here to MainActivity since this is just an intermediary
        openActivity(MainActivity::class.java, baseIntent = intent)
        finish() // Finish the account selection activity
    }""", """    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (hasLoggedIn &&
            !intent.getBooleanExtra(IS_FROM_MAIN_ACTIVITY, false) &&
            !intent.getBooleanExtra(IS_EDITING_FROM_MAIN_ACTIVITY, false)) {
            navigateToMainActivity()
        }
    }

    @SuppressLint("UnsafeIntentLaunch")
    private fun navigateToMainActivity() {
        hasLoggedIn = true
        val forwarded = Intent(intent)
        val internal = forwarded.getBooleanExtra(IS_FROM_MAIN_ACTIVITY, false) ||
            forwarded.getBooleanExtra(IS_EDITING_FROM_MAIN_ACTIVITY, false)
        if (!internal) {
            forwarded.flags = ILauncherPlaybackSession.mainTaskFlags(forwarded.flags)
        }
        // Keep MainActivity non-exported and keep the existing account/security gate.
        Log.i("ILauncherEntry", "forward action=${forwarded.action} scheme=${forwarded.scheme} internal=$internal")
        openActivity(MainActivity::class.java, baseIntent = forwarded)
        finish()
    }""")
    activity = base / "MainActivity.kt"
    replace_once(activity, """    override fun onNewIntent(intent: Intent) {
        handleAppIntent(intent)
        super.onNewIntent(intent)
    }""", """    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        Log.i("ILauncherEntry", "main received action=${intent.action} scheme=${intent.scheme}")
        handleAppIntent(intent)
    }""")
    # Set per-player metadata before media sources and the session are created,
    # including mirrors, episodes, and the same episode's native reload.
    generator = base / "ui/player/GeneratorPlayer.kt"
    replace_once(generator, """            val subtitles = viewModel.state.subtitles
            player.loadPlayer(""", """            val subtitles = viewModel.state.subtitles
            val sessionMeta = when (val meta = currentMeta) {
                is ResultEpisode -> com.lagradost.cloudstream3.ILauncherPlaybackSession.metadata(
                    meta.headerName, meta.name, meta.tvType.isEpisodeBased(), meta.season, meta.episode
                )
                is ExtractorUri -> com.lagradost.cloudstream3.ILauncherPlaybackSession.metadata(
                    meta.headerName, meta.name, meta.tvType.isEpisodeBased(), meta.season, meta.episode
                )
                else -> com.lagradost.cloudstream3.ILauncherPlaybackSession.metadata(null, null, false, null, null)
            }
            (player as? CS3IPlayer)?.bridgeMediaMetadata =
                androidx.media3.common.MediaMetadata.Builder()
                    .setTitle(sessionMeta.title)
                    .setDisplayTitle(sessionMeta.title)
                    .setAlbumTitle(sessionMeta.series)
                    .setSubtitle(sessionMeta.subtitle)
                    .build()
            Log.i("ILauncherMediaSession", "metadata prepared title=${sessionMeta.title} episode=${sessionMeta.subtitle != null}")
            player.loadPlayer(""")
    exo = base / "ui/player/CS3IPlayer.kt"
    replace_once(exo, """class CS3IPlayer : IPlayer {
    private var playerListener""", """class CS3IPlayer : IPlayer {
    internal var bridgeMediaMetadata: androidx.media3.common.MediaMetadata? = null
    private var playerListener""")
    replace_once(exo, """                context,
                mediaSlices,
                subSources,
                currentWindow,""", """                context,
                mediaSlices.map { slice ->
                    val metadata = bridgeMediaMetadata
                    if (metadata == null) slice else slice.copy(
                        mediaItem = slice.mediaItem.buildUpon().setMediaMetadata(metadata).build()
                    )
                },
                subSources,
                currentWindow,""")
    replace_once(exo, """        return exoPlayerBuilder.build().apply {
            setPlayWhenReady(playWhenReady)""", """        return exoPlayerBuilder.build().apply {
            bridgeMediaMetadata?.let { setPlaylistMetadata(it) }
            setPlayWhenReady(playWhenReady)""")
    view = base / "ui/player/PlayerView.kt"
    replace_once(view, """        }
        callbacks?.playerUpdated(player)
    }""", """        } else if (player == null) {
            // A released player must not leave an active stale platform session.
            mMediaSession?.release()
            mMediaSession = null
            exoPlayerView?.player = null
        }
        callbacks?.playerUpdated(player)
    }""")
    here = Path(__file__).parent
    shutil.copyfile(here / "ILauncherPlaybackSession.kt", base / "ILauncherPlaybackSession.kt")
    test = root / "app/src/test/java/com/lagradost/cloudstream3/ILauncherPlaybackSessionTest.kt"
    test.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(here / test.name, test)

if __name__ == "__main__":
    main(Path(sys.argv[1]).resolve())
