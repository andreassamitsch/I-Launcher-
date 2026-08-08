package com.andreassamitsch.ilauncher.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Text
import com.andreassamitsch.ilauncher.model.InstalledApp

@Composable
fun AppCard(
    app: InstalledApp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val icon = remember(app.icon) { app.icon.asImageBitmap() }

    Card(
        onClick = onClick,
        modifier = modifier.width(176.dp),
        scale = CardDefaults.scale(focusedScale = 1.06f),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Image(
                bitmap = icon,
                contentDescription = null,
                modifier = Modifier.size(58.dp),
            )
            Text(
                text = app.label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
