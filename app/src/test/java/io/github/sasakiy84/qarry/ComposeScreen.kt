package io.github.sasakiy84.qarry

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.node.RootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import org.junit.Assert.assertTrue
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowDialog
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URI
import java.time.Duration

internal class ComposeScreen(private val root: View, private val width: Int = 360, private val height: Int = 800) {
    private val canvas by lazy { Canvas(Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)) }

    fun text(label: String): SemanticsNode = find(label) { it.text() == label }

    fun tap(label: String) = tapMatching(label) { it.text() == label }

    fun tapContaining(label: String) = tapMatching(label) { label in it.text() }

    fun tapDescription(label: String) = tapMatching(label) {
        label in it.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() }
    }

    fun tapMatching(label: String, matches: (SemanticsNode) -> Boolean) {
        val node = find(label) { matches(it) && SemanticsActions.OnClick in it.config }
        assertTrue(checkNotNull(node.config[SemanticsActions.OnClick].action).invoke())
    }

    fun enterText(value: String) {
        val action = find("text input") { SemanticsActions.SetText in it.config }.config[SemanticsActions.SetText].action
        assertTrue(checkNotNull(action).invoke(AnnotatedString(value)))
    }

    fun submitFromKeyboard() {
        val action = find("IME action") { SemanticsActions.OnImeAction in it.config }.config[SemanticsActions.OnImeAction].action
        assertTrue(checkNotNull(action).invoke())
    }

    fun inputValue(): String = find("editable text") {
        SemanticsProperties.EditableText in it.config
    }.config[SemanticsProperties.EditableText].text

    fun dialogWith(label: String): ComposeScreen = waitFor(label) {
        val dialog = ShadowDialog.getLatestDialog()
        if (dialog?.isShowing != true) return@waitFor null
        val screen = ComposeScreen(checkNotNull(dialog.window).decorView, width, height)
        screen.advanceFrames(2)
        screen.takeIf { it.nodes().any { node -> label in node.text() } }
    }

    fun find(label: String = "matching node", predicate: (SemanticsNode) -> Boolean): SemanticsNode =
        waitFor(label) { nodes().firstOrNull(predicate) }

    fun nodes(): List<SemanticsNode> = root.views().filterIsInstance<RootForTest>()
        .flatMap { it.semanticsOwner.rootSemanticsNode.semanticTree() }.toList()

    fun advanceFrames(frames: Int) {
        repeat(frames) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
            root.layoutAt(width, height)
        }
        root.draw(canvas)
    }

    private fun <T : Any> waitFor(label: String, probe: () -> T?): T {
        repeat(100) {
            advanceFrames(2)
            probe()?.let { return it }
            // SQLite and the mocked feed transport can complete on real IO threads.
            Thread.sleep(10)
        }
        val visibleText = nodes().map { it.text() }.filter(String::isNotBlank)
        error("Timed out waiting for $label. Visible text: $visibleText")
    }
}

internal fun View.layoutAt(width: Int, height: Int) {
    measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
    layout(0, 0, width, height)
}

internal fun View.views(): Sequence<View> = sequence {
    yield(this@views)
    if (this@views is ViewGroup) repeat(childCount) { yieldAll(getChildAt(it).views()) }
}

internal fun SemanticsNode.semanticTree(): Sequence<SemanticsNode> =
    sequenceOf(this) + children.asSequence().flatMap { it.semanticTree() }

internal fun SemanticsNode.text(): String =
    config.getOrElse(SemanticsProperties.Text) { emptyList() }.joinToString(" ") { it.text }

internal fun fixtureWebsiteClient(address: String, page: () -> String): FeedClient = FeedClient { requested ->
    object : HttpURLConnection(URI(requested).toURL()) {
        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy() = false
        override fun getResponseCode() = if (requested == address) HTTP_OK else HTTP_NOT_FOUND
        override fun getContentType() = "text/html; charset=UTF-8"
        override fun getInputStream() = ByteArrayInputStream(page().toByteArray())
    }
}
