package com.andreassamitsch.ilauncher.system

import android.content.Intent
import android.provider.Settings

internal fun androidSystemSettingsIntent(): Intent = Intent(Settings.ACTION_SETTINGS)
