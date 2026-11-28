package io.github.sasakiy84.qarry

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
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
internal fun rememberArticleMetadata(articleUrl: String?, fetchIfMissing: Boolean = true): ArticleMetadata? {
    val library = LocalContext.current.feedLibrary()
    val pageUrl = remember(articleUrl) { httpsUrl(articleUrl) }
    var metadata by remember(library, pageUrl, fetchIfMissing) {
        mutableStateOf(pageUrl?.let(library::cachedArticleMetadata))
    }
    if (pageUrl != null && fetchIfMissing) {
        LaunchedEffect(library, pageUrl) {
            metadata = library.articleMetadata(pageUrl)
        }
    }
    return metadata
}

@Stable
internal class ArticleImage(preferredUrl: String?, fallbackUrl: String?) {
    private val preferred = httpsUrl(preferredUrl)
    private val fallback = httpsUrl(fallbackUrl)?.takeUnless { it == preferred }
    private var preferredFailed by mutableStateOf(false)
    private var fallbackFailed by mutableStateOf(false)

    val url: String?
        get() = preferred?.takeUnless { preferredFailed } ?: fallback?.takeUnless { fallbackFailed }

    fun markFailed(url: String) {
        when (url) {
            preferred -> preferredFailed = true
            fallback -> fallbackFailed = true
        }
    }
}

@Composable
internal fun rememberArticleImage(preferredUrl: String?, fallbackUrl: String?): ArticleImage =
    remember(preferredUrl, fallbackUrl) { ArticleImage(preferredUrl, fallbackUrl) }

@Composable
internal fun ArticleThumbnail(image: ArticleImage, modifier: Modifier = Modifier) {
    val imageUrl = image.url
    var imageLoaded by remember(imageUrl) { mutableStateOf(false) }
    Box(
        modifier.clip(RoundedCornerShape(1.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
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
        if (imageUrl != null) {
            AsyncImage(
                model = imageUrl,
                contentDescription = null,
                modifier = Modifier.matchParentSize(),
                contentScale = ContentScale.Crop,
                onSuccess = { imageLoaded = true },
                onError = { image.markFailed(imageUrl) },
            )
        }
    }
}
