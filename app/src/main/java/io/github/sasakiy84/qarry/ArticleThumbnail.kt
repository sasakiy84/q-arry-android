package io.github.sasakiy84.qarry

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage

@Composable
fun ArticleThumbnail(
    articleUrl: String?,
    thumbnailUrl: String?,
    modifier: Modifier = Modifier,
) {
    val library = LocalContext.current.feedLibrary()
    val directUrl = remember(thumbnailUrl) { httpsUrl(thumbnailUrl) }
    val pageUrl = remember(articleUrl) { httpsUrl(articleUrl) }
    var directImageFailed by remember(pageUrl, directUrl) { mutableStateOf(false) }
    var imageLoaded by remember(pageUrl, directUrl) { mutableStateOf(false) }
    var fallbackUrl by remember(pageUrl, directUrl) { mutableStateOf<String?>(null) }

    LaunchedEffect(library, pageUrl, directUrl, directImageFailed) {
        if (pageUrl != null && (directUrl == null || directImageFailed)) {
            fallbackUrl = library.articleThumbnail(pageUrl)?.takeUnless { it == directUrl }
        }
    }

    Box(
        modifier.clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (!imageLoaded) {
            Icon(
                painterResource(R.drawable.ic_articles),
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val imageUrl = if (directImageFailed) fallbackUrl else directUrl ?: fallbackUrl
        if (imageUrl != null) {
            AsyncImage(
                model = imageUrl,
                contentDescription = null,
                modifier = Modifier.matchParentSize(),
                contentScale = ContentScale.Crop,
                onSuccess = { imageLoaded = true },
                onError = {
                    imageLoaded = false
                    if (imageUrl == directUrl) directImageFailed = true
                },
            )
        }
    }
}
