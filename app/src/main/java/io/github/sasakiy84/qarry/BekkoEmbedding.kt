package io.github.sasakiy84.qarry

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtException
import ai.onnxruntime.OrtSession
import android.util.JsonReader
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import java.util.HexFormat
import kotlin.math.sqrt

internal class BekkoEmbedding(private val directory: File) {
    private var session: OrtSession? = null
    private var tokenizer: BekkoTokenizer? = null

    val diskBytes: Long get() = assets.sumOf { File(directory, it.name).length() }

    fun prepare() {
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Could not create model storage")
        for (asset in assets) download(asset)
        embed("検索", 32)
    }

    fun close() {
        session?.close()
        session = null
        tokenizer = null
    }

    fun embed(text: String, tokenLimit: Int): FloatArray = try {
        val environment = OrtEnvironment.getEnvironment()
        val encoding = tokenizer ?: BekkoTokenizer.read(File(directory, "tokenizer.json")).also { tokenizer = it }
        val inference = session ?: OrtSession.SessionOptions().use { options ->
            options.setIntraOpNumThreads(2)
            options.setInterOpNumThreads(1)
            options.setMemoryPatternOptimization(false)
            environment.createSession(File(directory, "model.onnx").absolutePath, options)
        }.also { session = it }
        val tokens = encoding.encode(text, tokenLimit)
        OnnxTensor.createTensor(environment, arrayOf(tokens)).use { ids ->
            OnnxTensor.createTensor(environment, arrayOf(LongArray(tokens.size) { 1L })).use { mask ->
                inference.run(mapOf("input_ids" to ids, "attention_mask" to mask)).use { output ->
                    val hidden = output[0] as OnnxTensor
                    val width = hidden.info.shape.last().toInt()
                    val values = hidden.floatBuffer
                    val vector = FloatArray(EMBEDDING_DIMENSIONS)
                    for (token in tokens.indices) {
                        for (dimension in vector.indices) vector[dimension] += values[token * width + dimension]
                    }
                    // With no padding, normalizing the sum yields the same result as normalizing the mean.
                    val norm = sqrt(vector.sumOf { (it * it).toDouble() }).toFloat()
                    if (!norm.isFinite() || norm <= 0f) throw IOException("Invalid embedding")
                    for (index in vector.indices) vector[index] /= norm
                    vector
                }
            }
        }
    } catch (error: OrtException) {
        throw IOException("Embedding inference failed", error)
    } catch (error: LinkageError) {
        throw IOException("Embedding runtime unavailable", error)
    } catch (error: IllegalArgumentException) {
        throw IOException("Embedding data could not be loaded", error)
    }

    private fun download(asset: ModelAsset) {
        val destination = File(directory, asset.name)
        if (destination.isFile && destination.length() == asset.bytes) return
        val temporary = File.createTempFile("download-", ".tmp", directory)
        val connection = URI("$MODEL_URL/${asset.path}").toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 20_000
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) throw IOException("Model download failed")
            val digest = MessageDigest.getInstance("SHA-256")
            var length = 0L
            connection.inputStream.use { input ->
                temporary.outputStream().buffered().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        if (Thread.currentThread().isInterrupted) throw InterruptedException()
                        val bytes = input.read(buffer)
                        if (bytes < 0) break
                        length += bytes
                        if (length > asset.bytes) throw IOException("Unexpected model size")
                        digest.update(buffer, 0, bytes)
                        output.write(buffer, 0, bytes)
                    }
                }
            }
            if (length != asset.bytes || HexFormat.of().formatHex(digest.digest()) != asset.sha256 ||
                !temporary.renameTo(destination)
            ) throw IOException("Model could not be verified or stored")
        } finally {
            connection.disconnect()
            temporary.delete()
        }
    }

    private data class ModelAsset(val path: String, val name: String, val bytes: Long, val sha256: String)

    private companion object {
        const val MODEL_URL = "https://huggingface.co/hotchpotch/bekko-embedding-v1-a8m/resolve/c721113d59a1d91b447450324f51c4b3332c924a"
        val assets = listOf(
            ModelAsset("onnx/model.onnx", "model.onnx", 130099079, "96d8cc6199e96357b21b2fb12f6d7ffd2d4abc7b182fe94b5468fbd6dc819af7"),
            ModelAsset("tokenizer.json", "tokenizer.json", 34363442, "8bd47075711f75a143d1b78e01a41cc65c1c591b00d3cfeffc23db07adce1392"),
        )
    }
}

internal class BekkoTokenizer private constructor(
    private val vocabulary: Map<String, Int>,
    private val rules: LongArray,
    private val mergedToken: IntArray,
    private val addedTokens: Map<String, Int>,
) {
    private val fallback = IntArray(256) {
        vocabulary["<0x${HexFormat.of().withUpperCase().toHexDigits(it.toByte())}>"] ?: vocabulary.getValue("<unk>")
    }
    private val addedPattern = Regex(addedTokens.keys.sortedByDescending(String::length).joinToString("|") {
        if (it == "<mask>") "\\s*" + Regex.escape(it) else Regex.escape(it)
    })

    fun encode(text: String, maxTokens: Int): LongArray {
        val input = text.take(4096)
        val result = ArrayList<Long>(maxTokens).apply { add(2L) }
        var offset = 0
        for (match in addedPattern.findAll(input)) {
            appendText(input.substring(offset, match.range.first), result, maxTokens - 1)
            if (result.size == maxTokens - 1) break
            result.add(addedTokens.getValue(match.value.trimStart().takeIf { match.value.endsWith("<mask>") } ?: match.value).toLong())
            offset = match.range.last + 1
        }
        if (result.size < maxTokens - 1) appendText(input.substring(offset), result, maxTokens - 1)
        result.add(1L)
        return result.toLongArray()
    }

    private fun appendText(text: String, result: MutableList<Long>, limit: Int) {
        if (text.isEmpty()) return
        val normalized = text.replace(' ', '▁').let { if (it.startsWith('▁')) it else "▁$it" }
        val segments = normalized.splitToSequence(Regex("(?!\\A)(?=▁)"))
        for (segment in segments) {
            val tokens = ArrayList<Int>()
            segment.codePoints().forEach { point ->
                val character = String(Character.toChars(point))
                val token = vocabulary[character]
                if (token != null) tokens.add(token)
                else character.encodeToByteArray().forEach { tokens.add(fallback[it.toInt() and 255]) }
            }
            while (tokens.size > 1) {
                var bestRank = Int.MAX_VALUE
                var bestIndex = 0
                for (index in 0 until tokens.lastIndex) {
                    val key = pairKey(tokens[index], tokens[index + 1])
                    val match = rules.binarySearch(key)
                    val position = if (match >= 0) match else -match - 1
                    if (position == rules.size || rules[position] ushr RANK_BITS != key ushr RANK_BITS) continue
                    val rank = (rules[position] and RANK_MASK).toInt()
                    if (rank < bestRank) {
                        bestRank = rank
                        bestIndex = index
                    }
                }
                if (bestRank == Int.MAX_VALUE) break
                tokens[bestIndex] = mergedToken[bestRank]
                tokens.removeAt(bestIndex + 1)
            }
            for (token in tokens) {
                if (result.size == limit) return
                result.add(token.toLong())
            }
            if (result.size == limit) return
        }
    }

    companion object {
        private const val TOKEN_BITS = 18
        private const val RANK_BITS = 20
        private const val RANK_MASK = (1L shl RANK_BITS) - 1

        private fun pairKey(left: Int, right: Int): Long =
            ((left.toLong() shl TOKEN_BITS) or right.toLong()) shl RANK_BITS

        fun read(path: File): BekkoTokenizer {
            val vocabulary = HashMap<String, Int>(345_000)
            val rules = LongArray(580_604)
            val merged = IntArray(rules.size)
            val addedTokens = mutableMapOf<String, Int>()
            JsonReader(path.bufferedReader()).use { reader ->
                reader.beginObject()
                while (reader.hasNext()) {
                    val name = reader.nextName()
                    if (name == "added_tokens") {
                        reader.beginArray()
                        while (reader.hasNext()) {
                            var text = ""
                            var id = 0
                            reader.beginObject()
                            while (reader.hasNext()) {
                                when (reader.nextName()) {
                                    "content" -> text = reader.nextString()
                                    "id" -> id = reader.nextInt()
                                    else -> reader.skipValue()
                                }
                            }
                            reader.endObject()
                            if (text.isNotEmpty()) addedTokens[text] = id
                        }
                        reader.endArray()
                        continue
                    }
                    if (name != "model") {
                        reader.skipValue()
                        continue
                    }
                    reader.beginObject()
                    while (reader.hasNext()) {
                        when (reader.nextName()) {
                            "vocab" -> {
                                reader.beginObject()
                                while (reader.hasNext()) vocabulary[reader.nextName()] = reader.nextInt()
                                reader.endObject()
                            }
                            "merges" -> {
                                var rank = 0
                                reader.beginArray()
                                while (reader.hasNext()) {
                                    reader.beginArray()
                                    val left = reader.nextString()
                                    val right = reader.nextString()
                                    reader.endArray()
                                    rules[rank] = pairKey(vocabulary.getValue(left), vocabulary.getValue(right)) or rank.toLong()
                                    merged[rank++] = vocabulary.getValue(left + right)
                                }
                                reader.endArray()
                                if (rank != rules.size) throw IOException("Unexpected tokenizer version")
                            }
                            else -> reader.skipValue()
                        }
                    }
                    reader.endObject()
                }
            }
            rules.sort()
            if (addedTokens.isEmpty()) throw IOException("Tokenizer has no special tokens")
            return BekkoTokenizer(vocabulary, rules, merged, addedTokens)
        }
    }
}
