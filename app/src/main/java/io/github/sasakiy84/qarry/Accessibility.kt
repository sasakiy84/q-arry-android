package io.github.sasakiy84.qarry

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

internal fun Modifier.announceStatus(): Modifier = semantics { liveRegion = LiveRegionMode.Polite }

@Composable
internal fun LoadingIndicator(label: String) {
    CircularProgressIndicator(Modifier.padding(28.dp).semantics { contentDescription = label })
}
