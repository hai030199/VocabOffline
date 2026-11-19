package com.hai.vocabhandbook.data

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/**
 * Đọc file .xlsx không cần thư viện ngoài: file xlsx là 1 file zip chứa các file XML.
 * Đọc đúng kiểu ô (chuỗi dùng chung, chuỗi trực tiếp, số) nên không bị lẫn số thành chữ.
 */
object XlsxReader {

    class Sheet(val name: String, val rows: List<List<String>>)

    fun read(bytes: ByteArray): List<Sheet> {
        // Tên file trong zip so sánh không phân biệt hoa thường (vài phần mềm ghi SharedStrings.xml)
        val entries = HashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) entries[entry.name.removePrefix("/").lowercase()] = zip.readBytes()
                entry = zip.nextEntry
            }
        }
        val workbook = entries["xl/workbook.xml"]
            ?: throw IllegalArgumentException("File không đúng định dạng .xlsx")
        val shared = entries["xl/sharedstrings.xml"]?.let { parseSharedStrings(it) } ?: emptyList()
        val rels = entries["xl/_rels/workbook.xml.rels"]?.let { parseRels(it) } ?: emptyMap()

        return parseWorkbook(workbook).mapNotNull { (name, relId) ->
            val target = rels[relId] ?: return@mapNotNull null
            val path = if (target.startsWith("/")) target.removePrefix("/") else "xl/" + target.removePrefix("./")
            val data = entries[path.lowercase()] ?: return@mapNotNull null
            Sheet(name, parseSheet(data, shared))
        }
    }

    private fun parser(data: ByteArray): XmlPullParser = Xml.newPullParser().apply {
        setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        setInput(ByteArrayInputStream(data), null)
    }

    /** Tên thẻ bỏ tiền tố namespace, ví dụ "x:c" thành "c" */
    private fun XmlPullParser.local(): String = name.substringAfter(':')

    private fun XmlPullParser.attr(localName: String): String? {
        for (i in 0 until attributeCount) {
            if (getAttributeName(i).substringAfter(':') == localName) return getAttributeValue(i)
        }
        return null
    }

    /** Danh sách (tên sheet, mã liên kết) theo đúng thứ tự trong file */
    private fun parseWorkbook(data: ByteArray): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        val p = parser(data)
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG && p.local() == "sheet") {
                val name = p.attr("name")
                val id = p.attr("id")
                if (name != null && id != null) out.add(name to id)
            }
            ev = p.next()
        }
        return out
    }

    private fun parseRels(data: ByteArray): Map<String, String> {
        val out = HashMap<String, String>()
        val p = parser(data)
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG && p.local() == "Relationship") {
                val id = p.attr("Id")
                val target = p.attr("Target")
                if (id != null && target != null) out[id] = target
            }
            ev = p.next()
        }
        return out
    }

    private fun parseSharedStrings(data: ByteArray): List<String> {
        val out = ArrayList<String>()
        val p = parser(data)
        var sb: StringBuilder? = null
        var inText = false
        var phonetic = 0   // bỏ qua phần phiên âm <rPh> (Excel tiếng Nhật)
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            when (ev) {
                XmlPullParser.START_TAG -> when (p.local()) {
                    "si" -> sb = StringBuilder()
                    "rPh" -> phonetic++
                    "t" -> inText = true
                }
                XmlPullParser.TEXT -> if (inText && phonetic == 0) sb?.append(p.text)
                XmlPullParser.END_TAG -> when (p.local()) {
                    "si" -> {
                        out.add(sb?.toString() ?: "")
                        sb = null
                    }
                    "rPh" -> phonetic--
                    "t" -> inText = false
                }
            }
            ev = p.next()
        }
        return out
    }

    /** Mỗi dòng là danh sách giá trị các ô có trong file (ô trống không ghi trong file thì bỏ qua) */
    private fun parseSheet(data: ByteArray, shared: List<String>): List<List<String>> {
        val rows = ArrayList<List<String>>()
        val p = parser(data)
        var row: ArrayList<String>? = null
        var type: String? = null
        val value = StringBuilder()
        var inCell = false
        var inValue = false
        var inText = false
        var phonetic = 0
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            when (ev) {
                XmlPullParser.START_TAG -> when (p.local()) {
                    "row" -> row = ArrayList()
                    "c" -> {
                        inCell = true
                        type = p.attr("t")
                        value.setLength(0)
                    }
                    "v" -> inValue = true
                    "t" -> inText = true
                    "rPh" -> phonetic++
                }
                XmlPullParser.TEXT ->
                    if (inCell && phonetic == 0 && (inValue || inText)) value.append(p.text)
                XmlPullParser.END_TAG -> when (p.local()) {
                    "v" -> inValue = false
                    "t" -> inText = false
                    "rPh" -> phonetic--
                    "c" -> {
                        row?.add(cellValue(type, value.toString(), shared))
                        inCell = false
                    }
                    "row" -> {
                        row?.let { rows.add(it) }
                        row = null
                    }
                }
            }
            ev = p.next()
        }
        return rows
    }

    private fun cellValue(type: String?, raw: String, shared: List<String>): String = when (type) {
        "s" -> raw.trim().toIntOrNull()?.let { shared.getOrNull(it) } ?: ""
        "b" -> if (raw.trim() == "1") "TRUE" else "FALSE"
        else -> raw   // inlineStr, str (kết quả công thức), số
    }
}
