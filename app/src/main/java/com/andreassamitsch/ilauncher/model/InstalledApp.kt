package com.andreassamitsch.ilauncher.model

import android.content.ComponentName
import android.graphics.Bitmap

data class InstalledApp(
    val packageName: String,
    val label: String,
    val componentName: ComponentName,
    val icon: Bitmap,
)
