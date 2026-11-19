package com.hai.vocabhandbook.data

import org.json.JSONObject
import java.text.Normalizer
import java.util.UUID

data class Word(
    val id: String = UUID.randomUUID().toString(),
    val word: String,
    val pronunciation: String,
    val meaning: String,
    val session: String,
    val fileName: String,
    /** null = import từ Excel, "image" = nghĩa chép từ ảnh, "ai" = AI tự điền nghĩa */
    val meaningSource: String? = null,
) {
    /** Tìm không phân biệt hoa thường và dấu: "dong y" khớp "đồng ý" */
    fun matches(query: String): Boolean {
        val q = fold(query.trim())
        if (q.isEmpty()) return true
        return fold(word).contains(q) || fold(meaning).contains(q) || fold(pronunciation).contains(q)
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("word", word)
        put("pronunciation", pronunciation)
        put("meaning", meaning)
        put("session", session)
        put("fileName", fileName)
        if (meaningSource != null) put("meaningSource", meaningSource)
    }

    companion object {
        fun fromJson(o: JSONObject) = Word(
            id = o.optString("id").ifEmpty { UUID.randomUUID().toString() },
            word = o.optString("word"),
            pronunciation = o.optString("pronunciation"),
            meaning = o.optString("meaning"),
            session = o.optString("session"),
            fileName = o.optString("fileName"),
            meaningSource = if (o.has("meaningSource") && !o.isNull("meaningSource")) o.optString("meaningSource") else null,
        )
    }
}

data class Conjunction(
    val id: String = UUID.randomUUID().toString(),
    val english: String,
    val vietnamese: String,
    val category: String,
    val fileName: String,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("english", english)
        put("vietnamese", vietnamese)
        put("category", category)
        put("fileName", fileName)
    }

    companion object {
        fun fromJson(o: JSONObject) = Conjunction(
            id = o.optString("id").ifEmpty { UUID.randomUUID().toString() },
            english = o.optString("english"),
            vietnamese = o.optString("vietnamese"),
            category = o.optString("category"),
            fileName = o.optString("fileName"),
        )
    }
}

data class FilterItem(val fileName: String, val session: String) {
    companion object {
        val ALL = FilterItem("ALL_FILES", "ALL")
    }
}

private val COMBINING_MARKS = Regex("\\p{Mn}+")

/** Bỏ dấu + về chữ thường; "đ" không phải dấu nên phải đổi tay sang "d" */
fun fold(s: String): String =
    COMBINING_MARKS.replace(Normalizer.normalize(s, Normalizer.Form.NFD), "")
        .replace("đ", "d").replace("Đ", "d")
        .lowercase()

/** Giống .capitalized của Swift: viết hoa chữ đầu mỗi từ */
fun String.capitalizedWords(): String =
    split(" ").joinToString(" ") { part ->
        part.lowercase().replaceFirstChar { it.titlecase() }
    }
