package com.hai.vocabhandbook.ai

import android.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.util.UUID

data class ExtractedWord(
    val id: String = UUID.randomUUID().toString(),
    val word: String,
    val pronunciation: String,
    val meaning: String,
    /** true = nghĩa chép từ ảnh, false = AI tự điền */
    val meaningFromImage: Boolean,
    /** Có lưu từ này hay không (bỏ tick ở màn kết quả) */
    val include: Boolean = true,
)

class ExtractionResult(
    val words: List<ExtractedWord>,
    val inputTokens: Int,
    val outputTokens: Int,
    val model: String,
)

sealed class ExtractorError(message: String) : Exception(message) {
    class MissingKey : ExtractorError("Chưa có Gemini API key. Vào Cài đặt AI để dán key.")
    class InvalidKey : ExtractorError("API key không hợp lệ hoặc chưa được bật Gemini API. Kiểm tra lại key trong Cài đặt AI.")
    class RateLimited : ExtractorError("Đã chạm giới hạn lượt miễn phí của Gemini. Đợi 1 phút (hoặc sau 15:00 giờ Việt Nam) rồi thử lại.")
    class ModelNotFound(model: String) : ExtractorError("Không tìm thấy model \"$model\". Vào Cài đặt AI đổi sang model Flash khác.")
    class BadRequest(val detail: String) : ExtractorError("Yêu cầu bị từ chối: $detail")
    class Server(detail: String) : ExtractorError("Máy chủ Gemini đang lỗi, thử lại sau. ($detail)")
    class BadResponse(detail: String) : ExtractorError("Không đọc được kết quả từ AI: $detail")
}

/** Gửi ảnh đã tô cho Gemini, nhận lại danh sách từ (giống hệt bản iOS) */
class GeminiExtractor(private val apiKey: String, private val model: String) {

    suspend fun extract(jpeg: ByteArray): ExtractionResult = withContext(Dispatchers.IO) {
        try {
            sendWithRetry(jpeg, model)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Model chính lỗi mạng, quá tải hoặc hết lượt thì thử 1 lần với Flash-Lite
            if (model != FALLBACK_MODEL && shouldFallback(e)) sendWithRetry(jpeg, FALLBACK_MODEL) else throw e
        }
    }

    /** Rớt mạng giữa chừng thì tự gửi lại tối đa 2 lần */
    private suspend fun sendWithRetry(jpeg: ByteArray, model: String): ExtractionResult {
        var lastError: IOException? = null
        for (attempt in 0..2) {
            if (attempt > 0) delay(1500L * attempt)
            try {
                return try {
                    send(jpeg, model, lowThinking = true)
                } catch (e: ExtractorError.BadRequest) {
                    // Model không hỗ trợ thinkingLevel thì gửi lại không kèm cấu hình này
                    if (e.detail.contains("think", ignoreCase = true)) send(jpeg, model, lowThinking = false) else throw e
                }
            } catch (e: IOException) {
                lastError = e
            }
        }
        throw lastError ?: IOException("Không gửi được ảnh")
    }

    private fun send(jpeg: ByteArray, model: String, lowThinking: Boolean): ExtractionResult {
        val generationConfig = JSONObject()
            .put("responseMimeType", "application/json")
            .put("responseSchema", responseSchema())
        if (lowThinking) generationConfig.put("thinkingConfig", JSONObject().put("thinkingLevel", "low"))

        val body = JSONObject()
            .put(
                "contents", JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put(
                            "parts", JSONArray()
                                .put(JSONObject().put("text", PROMPT))
                                .put(
                                    JSONObject().put(
                                        "inline_data", JSONObject()
                                            .put("mime_type", "image/jpeg")
                                            .put("data", Base64.encodeToString(jpeg, Base64.NO_WRAP))
                                    )
                                )
                        )
                )
            )
            .put("generationConfig", generationConfig)

        val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
        val conn = url.openConnection() as HttpURLConnection
        val (status, text) = try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 30_000
            conn.readTimeout = 120_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("x-goog-api-key", apiKey)
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            code to (stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: "")
        } finally {
            conn.disconnect()
        }
        if (status !in 200..299) throw mapError(status, text, model)

        val root = try {
            JSONObject(text)
        } catch (e: Exception) {
            throw ExtractorError.BadResponse("phản hồi không phải JSON")
        }
        val candidate = root.optJSONArray("candidates")?.optJSONObject(0)
        val parts = candidate?.optJSONObject("content")?.optJSONArray("parts")
        val answer = StringBuilder()
        if (parts != null) {
            for (i in 0 until parts.length()) {
                val part = parts.optJSONObject(i) ?: continue
                if (part.optBoolean("thought", false)) continue
                if (part.has("text")) answer.append(part.optString("text"))
            }
        }
        if (answer.isEmpty()) {
            val reason = candidate?.optString("finishReason")?.ifEmpty { null }
                ?: root.optJSONObject("promptFeedback")?.optString("blockReason")?.ifEmpty { null }
                ?: "trống"
            throw ExtractorError.BadResponse("AI không trả về nội dung ($reason)")
        }

        val items = try {
            JSONObject(stripFences(answer.toString())).getJSONArray("words")
        } catch (e: Exception) {
            throw ExtractorError.BadResponse("JSON sai định dạng")
        }
        val seen = HashSet<String>()
        val words = ArrayList<ExtractedWord>()
        for (i in 0 until items.length()) {
            val o = items.optJSONObject(i) ?: continue
            val word = o.optString("word").trim()
            if (word.isEmpty() || !seen.add(word.lowercase())) continue
            words.add(
                ExtractedWord(
                    word = word,
                    pronunciation = o.optString("pronunciation").trim(),
                    meaning = o.optString("meaning").trim(),
                    meaningFromImage = o.optBoolean("meaningFromImage", false),
                )
            )
        }
        val usage = root.optJSONObject("usageMetadata")
        return ExtractionResult(
            words = words,
            inputTokens = usage?.optInt("promptTokenCount", 0) ?: 0,
            outputTokens = (usage?.optInt("candidatesTokenCount", 0) ?: 0) + (usage?.optInt("thoughtsTokenCount", 0) ?: 0),
            model = model,
        )
    }

    companion object {
        /** Model dự phòng khi model chính quá tải, hết lượt hoặc mất kết nối liên tục */
        const val FALLBACK_MODEL = "gemini-3.5-flash-lite"

        val PROMPT = """
The image is a photo of a page from an English learning material. In an app, the learner has painted over the new English words/phrases they want to learn, using MAGENTA / PINK marks drawn in the app. A mark is either (a) a thick translucent highlight painted over words, or (b) a thin magenta line drawn around words (a circle, oval, box or freehand loop enclosing them, even if the loop is not fully closed).

Task:
1. Return ONLY the English words/phrases covered (fully or mostly) by a magenta highlight, or enclosed by a magenta loop. A loop around a whole area (several words or lines) means the learner wants EVERY English word inside it: return each distinct word inside the loop as its own entry, in reading order, without skipping any word (only skip numbers, symbols and punctuation). Ignore all other text, and ignore any marks that were already printed or drawn on the paper (e.g. yellow highlighter, pen circles). If nothing is marked in magenta, return an empty list.
2. "word": dictionary form, lowercase (keep capitals for proper nouns). If a mark covers only part of a word, take the whole word. If one mark covers several consecutive words, keep them as one phrase when they form a phrase or collocation (e.g. "take into account"), otherwise return them as separate words. Read the word itself even if the mark makes it harder to see; use the surrounding sentence to resolve unclear letters.
3. "pronunciation": American English IPA wrapped in slashes, e.g. /əˈkwaɪər/.
4. "meaning": if the page shows a Vietnamese meaning for that word (handwritten or printed, next to the word or in the margin), copy it exactly and set "meaningFromImage" to true. Otherwise write a short Vietnamese meaning that fits the sentence context (at most 3 senses, separated by commas) and set "meaningFromImage" to false.
5. No duplicates. Keep the order they appear on the page.
""".trim()

        private fun responseSchema(): JSONObject {
            val str = { JSONObject().put("type", "STRING") }
            val item = JSONObject()
                .put("type", "OBJECT")
                .put(
                    "properties", JSONObject()
                        .put("word", str())
                        .put("pronunciation", str())
                        .put("meaning", str())
                        .put("meaningFromImage", JSONObject().put("type", "BOOLEAN"))
                )
                .put("required", JSONArray(listOf("word", "pronunciation", "meaning", "meaningFromImage")))
            return JSONObject()
                .put("type", "OBJECT")
                .put("properties", JSONObject().put("words", JSONObject().put("type", "ARRAY").put("items", item)))
                .put("required", JSONArray(listOf("words")))
        }

        private fun stripFences(s: String): String {
            var t = s.trim()
            if (t.startsWith("```")) t = t.replace("```json", "").replace("```", "")
            return t.trim()
        }

        private fun mapError(status: Int, body: String, model: String): ExtractorError {
            val msg = runCatching { JSONObject(body).optJSONObject("error")?.optString("message") }.getOrNull()
                ?.ifEmpty { null } ?: body.ifEmpty { "HTTP $status" }
            return when (status) {
                400 -> if (msg.contains("API key", ignoreCase = true)) ExtractorError.InvalidKey() else ExtractorError.BadRequest(msg)
                401, 403 -> ExtractorError.InvalidKey()
                404 -> ExtractorError.ModelNotFound(model)
                429 -> ExtractorError.RateLimited()
                in 500..599 -> ExtractorError.Server("HTTP $status")
                else -> ExtractorError.BadRequest(msg)
            }
        }

        private fun shouldFallback(e: Exception): Boolean = when (e) {
            is IOException -> true
            is ExtractorError.RateLimited, is ExtractorError.Server, is ExtractorError.ModelNotFound -> true
            else -> false
        }

        /** Đổi lỗi sang câu dễ hiểu để hiện cho người dùng */
        fun describe(e: Throwable): String = when (e) {
            is ExtractorError -> e.message ?: "Lỗi không rõ"
            is UnknownHostException ->
                "Không kết nối được tới Gemini. Kiểm tra wifi hoặc 4G, tắt VPN nếu có rồi thử lại."
            is SocketTimeoutException, is IOException ->
                "Kết nối tới Gemini bị ngắt giữa chừng (đã thử lại 3 lần và thử cả $FALLBACK_MODEL). " +
                    "Kiểm tra mạng, tắt VPN nếu có, giữ màn hình mở khi đang quét rồi thử lại. (${e.javaClass.simpleName})"
            else -> "Lỗi: ${e.message ?: e.javaClass.simpleName}"
        }
    }
}
