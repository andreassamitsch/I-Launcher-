#!/usr/bin/env python3
"""Apply after the existing bridge and Watch Next patches, before compilation."""
from pathlib import Path
import sys


def patch(path: Path, old: str, new: str, expected: int = 1) -> None:
    text = path.read_text(encoding='utf-8')
    count = text.count(old)
    if count != expected and not (expected == 1 and count == 2 and old.startswith('                launchPreparedPlayback(activity, prepared)')):
        raise RuntimeError(f'{path}: expected {expected} anchor(s), found {count}: {old[:160]!r}')
    path.write_text(text.replace(old, new, expected), encoding='utf-8')


def main(root: Path) -> None:
    p = root / 'app/src/main/java/com/lagradost/cloudstream3'
    activity = p / 'MainActivity.kt'
    bridge = p / 'ILauncherDirectPlay.kt'

    # SingleTask retains fragments from prior launcher intents. Reset before async resume lookup.
    patch(activity,
        '''                        ILauncherBridgeNavigation.clearExistingPlayer(targetActivity)
                        ioSafe {
                            val resumeWatching = HomeViewModel.getResumeWatching().orEmpty()''',
        '''                        val launchToken = ILauncherBridgeNavigation.beginExternalLaunch(targetActivity)
                        ioSafe {
                            val resumeWatching = HomeViewModel.getResumeWatching().orEmpty()''')
    patch(activity,
        '''                            main {
                                targetActivity.loadSearchResult(
                                    resumeWatchingCard,
                                    START_ACTION_RESUME_LATEST
                                )
                            }''',
        '''                            ILauncherBridgeNavigation.navigateExternal(targetActivity, launchToken) {
                                targetActivity.loadSearchResult(
                                    resumeWatchingCard,
                                    START_ACTION_RESUME_LATEST
                                )
                            }''')

    patch(bridge,
        '''        Log.i(TAG, "start kind=${request.kind} selection=${request.providerSelection} identity=$identity")
        val loading = ILauncherBridgeLoading.show''',
        '''        Log.i(TAG, "start kind=${request.kind} selection=${request.providerSelection} identity=$identity")
        val launchToken = ILauncherBridgeNavigation.beginExternalLaunch(activity)
        val loading = ILauncherBridgeLoading.show''')
    patch(bridge,
        '''                val providers = awaitProviders(activity)
                val orderedProviders = orderProviders(activity, providers, request)''',
        '''                val providers = awaitProviders(activity)
                if (!ILauncherBridgeNavigation.isCurrentExternalLaunch(launchToken)) {
                    loading.dismiss()
                    return@ioSafe
                }
                val orderedProviders = orderProviders(activity, providers, request)''')
    patch(bridge,
        '''                if (request.providerSelection == ProviderSelection.Choose) {
                    loading.dismiss()
                    main { showProviderChooser(activity, request, providers, orderedProviders) }''',
        '''                if (request.providerSelection == ProviderSelection.Choose) {
                    loading.dismiss()
                    ILauncherBridgeNavigation.navigateExternal(activity, launchToken) {
                        showProviderChooser(activity, request, providers, orderedProviders, launchToken)
                    }''')
    patch(bridge,
        '''val executed = resolveAndExecuteAutomatic(activity, orderedProviders, request, loading)
                if (loading.isCancelled()) return@ioSafe''',
        '''val executed = resolveAndExecuteAutomatic(activity, orderedProviders, request, loading, launchToken)
                if (loading.isCancelled() || !ILauncherBridgeNavigation.isCurrentExternalLaunch(launchToken)) {
                    loading.dismiss()
                    return@ioSafe
                }''')
    patch(bridge,
        '''                if (!loading.isCancelled()) {
                    Log.w(TAG, "bridge failed identity=$identity; opening CloudStream search")
                    fallbackToSearch(activity, request.title)
                }''',
        '''                if (!loading.isCancelled() && ILauncherBridgeNavigation.isCurrentExternalLaunch(launchToken)) {
                    Log.w(TAG, "bridge failed identity=$identity; opening CloudStream search")
                    fallbackToSearch(activity, request.title, launchToken)
                }''')
    patch(bridge,
        '''        loading: ILauncherBridgeLoading,
    ): Boolean = coroutineScope {''',
        '''        loading: ILauncherBridgeLoading,
        launchToken: Long,
    ): Boolean = coroutineScope {''')
    patch(bridge,
        '''        for ((index, job) in jobs.withIndex()) {
            if (loading.isCancelled()) {''',
        '''        for ((index, job) in jobs.withIndex()) {
            if (!ILauncherBridgeNavigation.isCurrentExternalLaunch(launchToken)) {
                jobs.forEach { it.cancel() }
                return@coroutineScope false
            }
            if (loading.isCancelled()) {''')
    patch(bridge,
        '''            val match = job.await() ?: continue''',
        '''            val match = job.await() ?: continue
            if (!ILauncherBridgeNavigation.isCurrentExternalLaunch(launchToken)) {
                jobs.forEach { it.cancel() }
                return@coroutineScope false
            }''')
    patch(bridge,
        '''                openResolvedDetails(activity, match.response)
                rememberLastProvider(activity, request, match.response.apiName)''',
        '''                openResolvedDetails(activity, match.response, launchToken)
                if (ILauncherBridgeNavigation.isCurrentExternalLaunch(launchToken)) {
                    rememberLastProvider(activity, request, match.response.apiName)
                }''')
    patch(bridge,
        '''            if (hasPlayableLinks(prepared)) {
                jobs.drop(index + 1).forEach { it.cancel() }''',
        '''            if (hasPlayableLinks(prepared)) {
                if (!ILauncherBridgeNavigation.isCurrentExternalLaunch(launchToken)) {
                    jobs.forEach { it.cancel() }
                    return@coroutineScope false
                }
                jobs.drop(index + 1).forEach { it.cancel() }''')
    patch(bridge,
        '''                launchPreparedPlayback(activity, prepared)
                rememberLastProvider(activity, request, prepared.providerName)''',
        '''                launchPreparedPlayback(activity, prepared, launchToken)
                if (ILauncherBridgeNavigation.isCurrentExternalLaunch(launchToken)) {
                    rememberLastProvider(activity, request, prepared.providerName)
                }''')

    # Manual provider selection must carry the token from the original external request as well.
    patch(bridge,
        '''        providers: List<MainAPI>,
    ) {
        val labels = providers.map { it.name }.toTypedArray()''',
        '''        providers: List<MainAPI>,
        launchToken: Long,
    ) {
        val labels = providers.map { it.name }.toTypedArray()''')
    patch(bridge,
        '''                val provider = providers.getOrNull(which) ?: return@setSingleChoiceItems''',
        '''                if (!ILauncherBridgeNavigation.isCurrentExternalLaunch(launchToken)) return@setSingleChoiceItems
                val provider = providers.getOrNull(which) ?: return@setSingleChoiceItems''')
    patch(bridge,
        '''                    if (selectedLoading.isCancelled()) return@ioSafe
                    main {
                        if (match == null) {''',
        '''                    if (selectedLoading.isCancelled() || !ILauncherBridgeNavigation.isCurrentExternalLaunch(launchToken)) {
                        selectedLoading.dismiss()
                        return@ioSafe
                    }
                    main {
                        if (!ILauncherBridgeNavigation.isCurrentExternalLaunch(launchToken)) {
                            selectedLoading.dismiss()
                            return@main
                        }
                        if (match == null) {''')
    patch(bridge,
        '''executeSelectedMatch(activity, request, match, selectedLoading)''',
        '''executeSelectedMatch(activity, request, match, selectedLoading, launchToken)''')
    patch(bridge,
        '''        loading: ILauncherBridgeLoading? = null,
    ) {''',
        '''        loading: ILauncherBridgeLoading? = null,
        launchToken: Long,
    ) {''')
    patch(bridge,
        '''            openResolvedDetails(activity, match.response)
            rememberLastProvider(activity, request, match.response.apiName)''',
        '''            openResolvedDetails(activity, match.response, launchToken)
            if (ILauncherBridgeNavigation.isCurrentExternalLaunch(launchToken)) {
                rememberLastProvider(activity, request, match.response.apiName)
            }''')
    patch(bridge,
        '''                launchPreparedPlayback(activity, prepared)
                rememberLastProvider(activity, request, prepared.providerName)''',
        '''                if (!ILauncherBridgeNavigation.isCurrentExternalLaunch(launchToken)) return@ioSafe
                launchPreparedPlayback(activity, prepared, launchToken)
                rememberLastProvider(activity, request, prepared.providerName)''')
    patch(bridge,
        '''    private fun launchPreparedPlayback(activity: FragmentActivity, prepared: PreparedPlayback) {
        ILauncherBridgeNavigation.replacePlayer(
            activity,
            GeneratorPlayer.newInstance(prepared.generator, prepared.index, prepared.syncData),
        )
    }''',
        '''    private fun launchPreparedPlayback(activity: FragmentActivity, prepared: PreparedPlayback, launchToken: Long) {
        ILauncherBridgeNavigation.replacePlayer(
            activity,
            GeneratorPlayer.newInstance(prepared.generator, prepared.index, prepared.syncData),
            launchToken,
        )
    }''')
    patch(bridge,
        '''    private fun openResolvedDetails(activity: FragmentActivity, response: LoadResponse) {
        main { activity.loadResult(response.url, response.apiName, response.name) }
    }''',
        '''    private fun openResolvedDetails(activity: FragmentActivity, response: LoadResponse, launchToken: Long) {
        ILauncherBridgeNavigation.navigateExternal(activity, launchToken) {
            activity.loadResult(response.url, response.apiName, response.name)
        }
    }''')
    # Older fallback buttons still have two arguments. Their direct user action begins a fresh
    # navigation generation; the automatic and error paths can supply their original token.
    patch(bridge,
        '''    private fun fallbackToSearch(activity: FragmentActivity, title: String) {
        main {
            // SearchViewModel''',
        '''    private fun fallbackToSearch(
        activity: FragmentActivity,
        title: String,
        launchToken: Long = ILauncherBridgeNavigation.beginExternalLaunch(activity),
    ) {
        ILauncherBridgeNavigation.navigateExternal(activity, launchToken) {
            // SearchViewModel''')
    text = bridge.read_text(encoding='utf-8')
    if 'launchPreparedPlayback(activity, prepared)' in text or 'openResolvedDetails(activity, match.response)' in text:
        raise RuntimeError('Unprotected player/detail navigation remains')
    print('Applied CloudStream external playback session and stale-request guards')


if __name__ == '__main__':
    if len(sys.argv) != 2:
        raise SystemExit('usage: apply_external_session_fix.py <cloudstream-source-root>')
    main(Path(sys.argv[1]).resolve())
