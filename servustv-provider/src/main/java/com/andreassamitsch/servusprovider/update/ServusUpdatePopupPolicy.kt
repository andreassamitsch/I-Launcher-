package com.andreassamitsch.servusprovider.update

/** Session-local deferral, matching the Joyn popup: newer versions are never suppressed. */
internal class ServusUpdatePopupPolicy {
    private var dismissedVersion: Int? = null

    fun shouldShow(versionCode: Int): Boolean = dismissedVersion != versionCode
    fun dismiss(versionCode: Int) { dismissedVersion = versionCode }
    fun reopen() { dismissedVersion = null }

    companion object {
        fun info(state: ServusUpdateState): ServusUpdateInfo? = when (state) {
            is ServusUpdateState.Available -> state.info
            is ServusUpdateState.Downloading -> state.info
            is ServusUpdateState.ReadyToInstall -> state.info
            else -> null
        }
    }
}
