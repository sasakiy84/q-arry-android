package io.github.sasakiy84.qarry

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtException
import ai.onnxruntime.OrtSession
import android.content.res.AssetManager
import java.io.IOException
import java.nio.channels.FileChannel
import java.text.Normalizer
import java.util.Locale

internal const val QUOTE_ENCODER_ID = "bekko-static-v1/c18167b/int8-128"
internal const val QUOTE_EMBEDDING_SIZE = 128

internal fun interface QuoteEncoder {
    fun encode(texts: List<String>): List<FloatArray>
}

internal class BekkoQuoteEncoder(private val assets: AssetManager) : QuoteEncoder {
    private val session: OrtSession by lazy {
        val model = assets.openFd("quote/model.onnx").use { descriptor ->
            descriptor.createInputStream().use {
                it.channel.map(FileChannel.MapMode.READ_ONLY, descriptor.startOffset, descriptor.length)
            }
        }
        OrtSession.SessionOptions().use { options ->
            options.setIntraOpNumThreads(1)
            options.setInterOpNumThreads(1)
            options.setMemoryPatternOptimization(false)
            OrtEnvironment.getEnvironment().createSession(model, options)
        }
    }
    private val tokenizer by lazy {
        val vocabulary = HashMap<String, Int>(270_000)
        assets.open("quote/vocab.txt").bufferedReader().useLines { lines ->
            lines.forEachIndexed { index, token -> vocabulary[token] = index }
        }
        if (vocabulary.size != 200_000) throw IOException("Invalid quote vocabulary")
        QuoteWordPiece(vocabulary)
    }

    override fun encode(texts: List<String>): List<FloatArray> = try {
        val tokens = texts.map(tokenizer::encode)
        val width = tokens.maxOf { it.size }.coerceAtLeast(1)
        val ids = tokens.map { it.copyOf(width) }.toTypedArray()
        val masks = Array(tokens.size) { row -> LongArray(width) { if (it < tokens[row].size) 1L else 0L } }
        val environment = OrtEnvironment.getEnvironment()
        OnnxTensor.createTensor(environment, ids).use { input ->
            OnnxTensor.createTensor(environment, masks).use { mask ->
                session.run(mapOf("input_ids" to input, "attention_mask" to mask)).use { result ->
                    val output = result[0] as OnnxTensor
                    if (!output.info.shape.contentEquals(longArrayOf(texts.size.toLong(), QUOTE_EMBEDDING_SIZE.toLong()))) {
                        throw IOException("Unexpected quote encoder output")
                    }
                    val values = output.floatBuffer
                    List(texts.size) { normalizeQuoteVector(FloatArray(QUOTE_EMBEDDING_SIZE).also(values::get)) }
                }
            }
        }
    } catch (error: OrtException) {
        throw IOException("Quote encoder inference failed", error)
    } catch (error: LinkageError) {
        throw IOException("Quote encoder runtime unavailable", error)
    } catch (error: IllegalArgumentException) {
        throw IOException("Quote encoder input could not be loaded", error)
    }
}

internal class QuoteWordPiece(private val vocabulary: Map<String, Int>) {
    fun encode(text: String): LongArray {
        val normalized = Normalizer.normalize(text.take(640).lowercase(Locale.ROOT), Normalizer.Form.NFD)
        val words = buildString {
            normalized.codePoints().forEach { point ->
                when {
                    Character.isWhitespace(point) || Character.isSpaceChar(point) -> append(' ')
                    Character.getType(point) in ignoredTypes || point == 0xfffd -> Unit
                    isBertPunctuation(point) || Character.UnicodeScript.of(point) == Character.UnicodeScript.HAN ->
                        append(' ').appendCodePoint(point).append(' ')
                    else -> appendCodePoint(point)
                }
            }
        }
        val tokens = ArrayList<Long>()
        for (word in words.splitToSequence(' ')) {
            if (word.isEmpty()) continue
            tokens.addAll(wordPieces(word))
            if (tokens.size >= 128) break
        }
        return tokens.take(128).toLongArray()
    }

    private fun wordPieces(word: String): List<Long> {
        val points = word.codePoints().toArray()
        if (points.size > 100) return listOf(0)
        val tokens = ArrayList<Long>()
        var start = 0
        while (start < points.size) {
            var end = points.size
            var token: Int? = null
            while (end > start) {
                val piece = String(points, start, end - start)
                token = vocabulary[if (start == 0) piece else "##$piece"]
                if (token != null) break
                end--
            }
            if (token == null) return listOf(0)
            tokens.add(token.toLong())
            start = end
        }
        return tokens
    }

    private companion object {
        val ignoredTypes = setOf(Character.CONTROL.toInt(), Character.FORMAT.toInt(), Character.NON_SPACING_MARK.toInt())
        val punctuationTypes = setOf(
            Character.CONNECTOR_PUNCTUATION.toInt(), Character.DASH_PUNCTUATION.toInt(),
            Character.START_PUNCTUATION.toInt(), Character.END_PUNCTUATION.toInt(),
            Character.INITIAL_QUOTE_PUNCTUATION.toInt(), Character.FINAL_QUOTE_PUNCTUATION.toInt(),
            Character.OTHER_PUNCTUATION.toInt(),
        )

        fun isBertPunctuation(point: Int): Boolean =
            point in 33..47 || point in 58..64 || point in 91..96 || point in 123..126 ||
                Character.getType(point) in punctuationTypes
    }
}
