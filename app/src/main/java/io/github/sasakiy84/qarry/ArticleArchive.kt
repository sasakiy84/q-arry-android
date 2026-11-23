package io.github.sasakiy84.qarry

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.IOException

internal class ArticleArchive(private val store: FeedStore, private val client: FeedClient, modelDir: File?) {
    private val embedding = modelDir?.let(::BekkoEmbedding)
    private val downloads = Mutex()
    private val semanticLock = Mutex()
    private val changes = MutableStateFlow(0)
    val revision = changes.asStateFlow()

    suspend fun stats(): SearchStats = runInterruptible(Dispatchers.IO) { store.searchStats(embedding?.diskBytes ?: 0) }

    suspend fun fetchArticle(article: Article): Boolean {
        val url = article.url ?: return false
        val candidate = ArchiveCandidate(article.id, url)
        val saved = downloads.withLock { runInterruptible(Dispatchers.IO) { fetch(candidate) } }
        changes.update { it + 1 }
        return saved
    }

    suspend fun fetchPending(): Int {
        val saved = downloads.withLock {
            runInterruptible(Dispatchers.IO) {
                store.archiveCandidates(System.currentTimeMillis(), 20).count(::fetch)
            }
        }
        syncLexicalIndex()
        semanticLock.withLock {
            runInterruptible(Dispatchers.IO) {
                if (store.semanticEnabled() && embedding != null) {
                    try {
                        indexVectors(embedding, 12)
                    } catch (_: IOException) {
                        // A missing model can be downloaded again from settings. Keyword search stays available.
                    }
                }
            }
        }
        changes.update { it + 1 }
        return saved
    }

    suspend fun enableSemanticSearch(enabled: Boolean) = semanticLock.withLock {
        runInterruptible(Dispatchers.IO) {
            if (enabled) {
                val model = embedding ?: throw IOException("Embedding model unavailable")
                model.prepare()
                indexVectors(model, 12)
                store.setSemanticEnabled(true)
            } else {
                store.setSemanticEnabled(false)
                embedding?.close()
            }
        }
        changes.update { it + 1 }
    }

    suspend fun search(query: String, limit: Int): ArticleSearchResult {
        val words = searchWords(query.take(200)).distinct().take(12)
        if (words.isEmpty()) return ArticleSearchResult(ArticleListing(emptyList(), false))
        syncLexicalIndex()
        val lexical = runInterruptible(Dispatchers.IO) { store.lexicalArticleIds(words, limit + 1) }
        var unavailable = false
        val semantic = semanticLock.withLock {
            runInterruptible(Dispatchers.IO) {
                if (!store.semanticEnabled()) return@runInterruptible emptyList()
                try {
                    val model = embedding ?: throw IOException("Embedding model unavailable")
                    indexVectors(model, 8)
                    val vector = model.embed(query.take(400), 128)
                    store.semanticArticleIds(vector, limit + 1)
                } catch (_: IOException) {
                    unavailable = true
                    emptyList()
                }
            }
        }
        val order = hybridArticleOrder(lexical, semantic)
        val articles = runInterruptible(Dispatchers.IO) { store.searchArticles(order.take(limit), words.first()) }
        return ArticleSearchResult(ArticleListing(articles, order.size > limit), unavailable)
    }

    private suspend fun syncLexicalIndex() {
        while (runInterruptible(Dispatchers.IO) { store.indexArticles(40) } == 40) Unit
    }

    private fun fetch(candidate: ArchiveCandidate): Boolean {
        val body = try {
            client.articleText(candidate.url)
        } catch (_: IOException) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            null
        }
        val now = System.currentTimeMillis()
        return store.saveArchive(candidate, body, now + if (body != null) ARCHIVE_RECHECK else ARCHIVE_RETRY)
    }

    private fun indexVectors(model: BekkoEmbedding, limit: Int) {
        for (document in store.documentsForVectors(limit)) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            val vector = model.embed("${document.title}\n${document.storedBody}", 512)
            store.saveVector(document, vector)
        }
    }
}

private const val ARCHIVE_RETRY = 24 * 60 * 60 * 1000L
private const val ARCHIVE_RECHECK = 30 * ARCHIVE_RETRY
