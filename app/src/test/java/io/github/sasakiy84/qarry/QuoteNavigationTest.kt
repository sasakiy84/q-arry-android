package io.github.sasakiy84.qarry

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "ja-w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class QuoteNavigationTest {
    @Test
    fun trainsWhileDisplayIsOffAndUsesTheSavedWeightsAfterTurningDisplayOn() {
        val context = RuntimeEnvironment.getApplication()
        val sentences = listOf(
            "開館後は研究室で新しい地図を見せてもらえる。",
            "古地図が描かれた背景を地元の研究者が解説した。",
        )
        FeedStore(context).use { store ->
            archiveQuoteFixture(store, "older", "Visitors can read the original documents. The library stays open until eight.", "比較用の次の記事")
            archiveQuoteFixture(store, "newer", sentences.joinToString(""), "古地図を読む")
            val quoteStore = QuoteStore(store)
            val encodes = AtomicInteger()
            val learning = QuoteLearning(quoteStore) { texts ->
                encodes.incrementAndGet()
                texts.mapIndexed { index, _ -> FloatArray(QUOTE_EMBEDDING_SIZE).apply { this[index] = 1f } }
            }
            val library = FeedLibrary(store, FeedClient { error("Quote learning must work offline") })
            Robolectric.buildActivity(ComponentActivity::class.java).setup().windowFocusChanged(true).use { controller ->
                val activity = controller.get()
                activity.setContent { MaterialTheme { QarryScreen(library, quoteLearning = learning) {} } }
                val screen = ComposeScreen(activity.window.decorView)
                screen.tapContaining("古地図を読む")
                screen.text(sentences.joinToString(""))
                assertEquals(0, encodes.get())
                activity.onBackPressedDispatcher.onBackPressed()

                screen.tap("設定")
                screen.tap(QUOTE_TRAINING_TITLE)
                val answer = screen.find("引用候補のカード") {
                    it.text() == sentences[1] && SemanticsActions.OnClick in it.config
                }.config[SemanticsActions.OnClick].action!!
                assertTrue(answer())
                answer()
                screen.text("比較用の次の記事")
                assertEquals(1, quoteStore.state().answers)

                screen.tapMatching("スキップ") {
                    it.text() == "スキップ" && SemanticsProperties.Disabled !in it.config
                }
                screen.text("比較できる保存済みの記事がありません")
                assertEquals(1, quoteStore.state().answers)
                assertTrue(quoteStore.state().weights.any { it != 0f })
                activity.onBackPressedDispatcher.onBackPressed()

                screen.tapMatching("引用を表示") {
                    "引用を表示" in it.text() && SemanticsProperties.ToggleableState in it.config
                }
                screen.tap("記事")
                screen.tap("未読のみ")
                screen.tapContaining("古地図を読む")
                screen.text(sentences[1])
                activity.onBackPressedDispatcher.onBackPressed()

                screen.tap("設定")
                screen.tap(QUOTE_TRAINING_TITLE)
                screen.tap("直前の選択を取り消す")
                screen.text("選択済み 0件")
                assertArrayEquals(FloatArray(QUOTE_FEATURE_SIZE), quoteStore.state().weights, 0f)
            }
        }
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "ja-w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class QuoteLayoutTest {
    @Test
    fun displaysACompleteShorterQuoteWhenTheTopCandidateExceedsThreeLines() {
        val long = "The museum reopened its east wing after the investigation, but the evening exhibits " +
            "still close early during the busiest months of the year."
        val short = "The cafe closes at dusk."
        val source = QuoteSource(1, "The museum reopened", "$long $short")
        FeedStore(RuntimeEnvironment.getApplication()).use { store ->
            val learning = QuoteLearning(QuoteStore(store)) { texts ->
                texts.mapIndexed { index, _ ->
                    FloatArray(QUOTE_EMBEDDING_SIZE).apply { this[if (index < 2) 0 else 1] = 1f }
                }
            }
            assertEquals(long, kotlinx.coroutines.runBlocking { learning.forArticle(source) }.first().text)
            Robolectric.buildActivity(ComponentActivity::class.java).setup().windowFocusChanged(true).use { controller ->
                val activity = controller.get()
                activity.setContent {
                    MaterialTheme {
                        CompositionLocalProvider(LocalDensity provides Density(1f, 1.7f)) {
                            ArticleLeadQuote(learning, source, Modifier.width(200.dp))
                        }
                    }
                }
                val screen = ComposeScreen(activity.window.decorView)
                screen.text(short)
                assertFalse(screen.nodes().any { it.text() == long })
            }
        }
    }
}
