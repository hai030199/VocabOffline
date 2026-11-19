package com.hai.vocabhandbook

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hai.vocabhandbook.ai.AiSettings
import com.hai.vocabhandbook.ai.ExtractedWord
import com.hai.vocabhandbook.ai.ExtractorError
import com.hai.vocabhandbook.ai.GeminiExtractor
import com.hai.vocabhandbook.ai.SecureStore
import com.hai.vocabhandbook.data.Conjunction
import com.hai.vocabhandbook.data.DefaultConjunctions
import com.hai.vocabhandbook.data.FilterItem
import com.hai.vocabhandbook.data.ImageUtils
import com.hai.vocabhandbook.data.PhotoPhase
import com.hai.vocabhandbook.data.PhotoSession
import com.hai.vocabhandbook.data.PhotoTarget
import com.hai.vocabhandbook.data.SpeechManager
import com.hai.vocabhandbook.data.Word
import com.hai.vocabhandbook.data.XlsxReader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Các màn hình của app (điều hướng kiểu ngăn xếp, nút Back quay lại màn trước) */
sealed interface Screen {
    data object Home : Screen
    data class Words(val filter: FilterItem) : Screen
    data object Conjunctions : Screen
    data object ReferenceImage : Screen
    data object PhotoMark : Screen
    data object AISettings : Screen
}

class VocabViewModel(private val app: Application) : AndroidViewModel(app) {

    val speech = SpeechManager(app)

    // MARK: Điều hướng
    val stack = mutableStateListOf<Screen>(Screen.Home)
    val expandedFiles = mutableStateListOf<String>()

    fun push(screen: Screen) {
        stack.add(screen)
    }

    fun onBack() {
        speech.stopAll()
        if (stack.lastOrNull() == Screen.PhotoMark) closePhoto() else pop()
    }

    private fun pop() {
        if (stack.size > 1) stack.removeAt(stack.lastIndex)
    }

    fun toggleExpanded(fileName: String) {
        if (fileName in expandedFiles) expandedFiles.remove(fileName) else expandedFiles.add(fileName)
    }

    // MARK: Dữ liệu
    var words by mutableStateOf<List<Word>>(emptyList())
        private set
    var conjunctions by mutableStateOf<List<Conjunction>>(emptyList())
        private set
    var isLoading by mutableStateOf(false)
        private set
    var isParsingConjunctions by mutableStateOf(false)
        private set
    var errorMessage by mutableStateOf("")

    var pendingWords by mutableStateOf<List<Word>>(emptyList())
        private set
    var pendingFileName by mutableStateOf("")
        private set
    var showImportPreview by mutableStateOf(false)
        private set

    /** Các file Excel đang chờ xem trước (chọn nhiều file thì xử lý lần lượt từng file) */
    private val importQueue = ArrayDeque<Pair<ByteArray, String>>()

    private val wordsFile get() = File(app.filesDir, "saved_vocab.json")
    private val conjunctionsFile get() = File(app.filesDir, "saved_conjunctions.json")
    private val referenceFile get() = File(app.filesDir, "reference_conjunctions.jpg")

    init {
        loadWords()
        loadConjunctions()
    }

    fun wordKeySet(): Set<String> = words.mapTo(HashSet()) { it.word.trim().lowercase() }

    fun isDuplicate(text: String): Boolean {
        val t = text.trim().lowercase()
        return words.any { it.word.trim().lowercase() == t }
    }

    // MARK: Import Excel từ vựng
    fun importExcelUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val failed = ArrayList<String>()
            val loaded = withContext(Dispatchers.IO) {
                uris.mapNotNull { uri ->
                    runCatching { readUri(uri) }.getOrElse {
                        failed.add(displayName(uri))
                        null
                    }
                }
            }
            if (failed.isNotEmpty()) errorMessage = "Không đọc được file ${failed.joinToString(", ")}."
            importQueue.addAll(loaded)
            if (!isLoading && !showImportPreview) processNextImport()
        }
    }

    private fun processNextImport() {
        val next = importQueue.removeFirstOrNull() ?: return
        parseExcelPreview(next.first, next.second)
    }

    private fun parseExcelPreview(data: ByteArray, fileName: String) {
        isLoading = true
        errorMessage = ""
        viewModelScope.launch {
            val result = runCatching { withContext(Dispatchers.Default) { parseVocab(data, fileName) } }
            isLoading = false
            result.onSuccess { parsed ->
                if (parsed.isEmpty()) {
                    errorMessage = "Không tìm thấy từ nào trong file $fileName."
                    processNextImport()
                } else {
                    pendingWords = parsed
                    pendingFileName = fileName
                    showImportPreview = true
                }
            }.onFailure {
                errorMessage = "Lỗi đọc file $fileName: ${it.message}"
                processNextImport()
            }
        }
    }

    fun confirmImport(importAll: Boolean) {
        if (importAll) {
            // Import tất cả = thay toàn bộ dữ liệu cũ của file này bằng bản mới
            words = words.filter { it.fileName != pendingFileName } + pendingWords
        } else {
            // Chỉ từ mới = giữ nguyên dữ liệu cũ, thêm những từ chưa có
            val existing = wordKeySet()
            words = words + pendingWords.filter { it.word.trim().lowercase() !in existing }
        }
        saveWords()
        finishPreview()
    }

    fun cancelImport() = finishPreview()

    private fun finishPreview() {
        pendingWords = emptyList()
        pendingFileName = ""
        showImportPreview = false
        if (importQueue.isNotEmpty()) {
            viewModelScope.launch {
                delay(300)
                processNextImport()
            }
        }
    }

    // MARK: Import Excel liên từ
    fun importConjunctionUri(uri: Uri) {
        isParsingConjunctions = true
        errorMessage = ""
        viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val (data, fileName) = readUri(uri)
                    fileName to parseConjunctions(data, fileName)
                }
            }
            isParsingConjunctions = false
            result.onSuccess { (fileName, parsed) ->
                conjunctions = conjunctions.filter { it.fileName != fileName } + parsed
                saveConjunctions()
            }.onFailure { errorMessage = "Lỗi: ${it.message}" }
        }
    }

    fun clearAll() {
        words = emptyList()
        errorMessage = ""
        expandedFiles.clear()
        saveWords()
    }

    fun clearConjunctions() {
        conjunctions = emptyList()
        saveConjunctions()
    }

    fun deleteWord(word: Word) {
        words = words.filter { it.id != word.id }
        saveWords()
    }

    fun deleteConjunction(c: Conjunction) {
        conjunctions = conjunctions.filter { it.id != c.id }
        saveConjunctions()
    }

    // MARK: Từ thêm từ ảnh chụp
    /** Lưu các từ đã chọn vào danh mục "Từ thêm trong quá trình học", buổi = ngày hôm nay */
    private fun addLearnedWords(items: List<ExtractedWord>): FilterItem {
        val session = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        words = words + items.map {
            Word(
                word = it.word, pronunciation = it.pronunciation, meaning = it.meaning,
                session = session, fileName = LEARNED_FILE_NAME,
                meaningSource = if (it.meaningFromImage) "image" else "ai",
            )
        }
        saveWords()
        return FilterItem(LEARNED_FILE_NAME, session)
    }

    var photo by mutableStateOf<PhotoSession?>(null)
        private set

    fun onPhotoPicked(uri: Uri, target: PhotoTarget) {
        viewModelScope.launch {
            val maxSide = if (target == PhotoTarget.VOCAB) 2400 else 4096
            val bitmap = try {
                withContext(Dispatchers.IO) { ImageUtils.load(app, uri, maxSide) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                errorMessage = "Không đọc được ảnh đã chọn."
                return@launch
            }
            when (target) {
                PhotoTarget.VOCAB -> {
                    photo = PhotoSession(bitmap)
                    push(Screen.PhotoMark)
                }
                PhotoTarget.REFERENCE -> saveReference(bitmap)
            }
        }
    }

    fun closePhoto() {
        photo = null
        if (stack.lastOrNull() == Screen.PhotoMark) pop()
    }

    fun scanPhoto() {
        val p = photo ?: return
        if (p.strokes.isEmpty()) {
            p.phase = PhotoPhase.MARKING
            return
        }
        // Chưa đồng ý gửi ảnh cho AI bên thứ ba thì hỏi trước, không gửi gì cả
        if (!AiSettings.consent(app)) {
            p.showConsent = true
            return
        }
        val strokes = p.strokes.toList()
        p.phase = PhotoPhase.LOADING
        viewModelScope.launch {
            val marked = withContext(Dispatchers.Default) { ImageUtils.renderMarked(p.image, strokes) }
            p.marked = marked
            val key = withContext(Dispatchers.IO) { SecureStore.readApiKey(app) }
            if (key.isNullOrBlank()) {
                p.errorText = ExtractorError.MissingKey().message ?: ""
                p.phase = PhotoPhase.ERROR
                return@launch
            }
            try {
                // Chất lượng 0.7: chữ vẫn đọc rõ, ảnh nhẹ để upload nhanh
                val jpeg = withContext(Dispatchers.Default) { ImageUtils.jpeg(marked, 70) }
                val r = GeminiExtractor(key, AiSettings.model(app)).extract(jpeg)
                AiSettings.recordUsage(app, r.inputTokens, r.outputTokens)
                val existing = wordKeySet()
                p.items.clear()
                // Từ đã có trong kho thì mặc định bỏ tick
                p.items.addAll(r.words.map { it.copy(include = it.word.trim().lowercase() !in existing) })
                p.usageText = "${r.model}, ${r.inputTokens + r.outputTokens} token"
                p.phase = PhotoPhase.RESULT
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                p.errorText = GeminiExtractor.describe(e)
                p.phase = PhotoPhase.ERROR
            }
        }
    }

    fun saveLearned() {
        val p = photo ?: return
        val chosen = p.items.filter { it.include }
        if (chosen.isEmpty()) return
        val filter = addLearnedWords(chosen)
        photo = null
        if (stack.lastOrNull() == Screen.PhotoMark) pop()
        if (filter.fileName !in expandedFiles) expandedFiles.add(filter.fileName)
        push(Screen.Words(filter))
    }

    // MARK: Ảnh tham khảo liên từ (chỉ lưu trên máy, không đóng gói vào app)
    var referenceImage by mutableStateOf<Bitmap?>(null)
        private set

    fun loadReference() {
        if (referenceImage != null) return
        viewModelScope.launch {
            referenceImage = withContext(Dispatchers.IO) {
                if (referenceFile.exists()) BitmapFactory.decodeFile(referenceFile.path) else null
            }
        }
    }

    private suspend fun saveReference(bitmap: Bitmap) {
        val saved = withContext(Dispatchers.IO) {
            val r = ImageUtils.resized(bitmap, 3000)   // đủ nét để zoom, không quá nặng
            referenceFile.writeBytes(ImageUtils.jpeg(r, 90))
            r
        }
        referenceImage = saved
    }

    fun deleteReference() {
        referenceFile.delete()
        referenceImage = null
    }

    // MARK: Đọc file người dùng chọn
    private fun readUri(uri: Uri): Pair<ByteArray, String> {
        val bytes = app.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalStateException("Không mở được file")
        return bytes to displayName(uri).substringBeforeLast('.')
    }

    private fun displayName(uri: Uri): String {
        val fromProvider: String? = try {
            app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        } catch (e: Exception) {
            null
        }
        if (!fromProvider.isNullOrBlank()) return fromProvider
        return uri.lastPathSegment?.substringAfterLast('/') ?: "File"
    }

    // MARK: Lưu trữ JSON trong bộ nhớ riêng của app
    private fun writeAtomic(file: File, text: String) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) {
            file.writeText(text)
            tmp.delete()
        }
    }

    private fun saveWords() {
        runCatching { writeAtomic(wordsFile, JSONArray().apply { words.forEach { put(it.toJson()) } }.toString()) }
            .onFailure { errorMessage = "Lỗi lưu dữ liệu: ${it.message}" }
    }

    private fun loadWords() {
        words = runCatching {
            val arr = JSONArray(wordsFile.readText())
            (0 until arr.length()).map { Word.fromJson(arr.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    private fun saveConjunctions() {
        runCatching {
            writeAtomic(conjunctionsFile, JSONArray().apply { conjunctions.forEach { put(it.toJson()) } }.toString())
        }.onFailure { errorMessage = "Lỗi lưu liên từ: ${it.message}" }
    }

    private fun loadConjunctions() {
        if (!conjunctionsFile.exists()) {
            conjunctions = DefaultConjunctions.all()
            saveConjunctions()
            return
        }
        conjunctions = runCatching {
            val arr = JSONArray(conjunctionsFile.readText())
            (0 until arr.length()).map { Conjunction.fromJson(arr.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    override fun onCleared() {
        speech.shutdown()
    }

    companion object {
        const val LEARNED_FILE_NAME = "Từ thêm trong quá trình học"

        /** Giống bản iOS: ô có dấu "/" là phiên âm, ô trước là từ, ô sau là nghĩa */
        fun parseVocab(data: ByteArray, fileName: String): List<Word> {
            val out = ArrayList<Word>()
            for (sheet in XlsxReader.read(data)) {
                for (row in sheet.rows) {
                    for ((idx, value) in row.withIndex()) {
                        if (value.contains("/") && value.length > 2) {
                            val w = if (idx > 0) row[idx - 1] else ""
                            val m = if (idx < row.size - 1) row[idx + 1] else ""
                            if (w.isNotEmpty() && !w.all { it.isDigit() }) {
                                out.add(
                                    Word(
                                        word = w.trim(), pronunciation = value.trim(), meaning = m.trim(),
                                        session = sheet.name, fileName = fileName,
                                    )
                                )
                            }
                            break
                        }
                    }
                }
            }
            return out
        }

        /** Cột A là liên từ tiếng Anh, cột B là nghĩa, tên sheet là nhóm */
        fun parseConjunctions(data: ByteArray, fileName: String): List<Conjunction> {
            val out = ArrayList<Conjunction>()
            for (sheet in XlsxReader.read(data)) {
                for (row in sheet.rows) {
                    if (row.size >= 2) {
                        val e = row[0].trim()
                        val v = row[1].trim()
                        if (e.isNotEmpty() && v.isNotEmpty()) {
                            out.add(Conjunction(english = e, vietnamese = v, category = sheet.name, fileName = fileName))
                        }
                    }
                }
            }
            return out
        }
    }
}
