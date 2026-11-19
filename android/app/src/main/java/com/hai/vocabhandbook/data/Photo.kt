package com.hai.vocabhandbook.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.Path
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import com.hai.vocabhandbook.ai.ExtractedWord
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

enum class PhotoPhase { MARKING, LOADING, RESULT, ERROR }

/** Bút tô (dạ quang, nét to), bút khoanh (nét mảnh để khoanh vùng), tẩy */
enum class MarkTool { MARKER, CIRCLE, ERASER }

/** Ảnh lấy về để làm gì: thêm từ mới, hay làm ảnh tham khảo của mục Liên từ */
enum class PhotoTarget { VOCAB, REFERENCE }

/** Một nét tô. Toạ độ và độ dày tính theo tỉ lệ 0..1 của khung ảnh nên không phụ thuộc kích thước màn hình */
class MarkStroke(val points: List<Offset>, val widthFraction: Float, val tool: MarkTool)

/** Trạng thái màn tô ảnh, giữ trong ViewModel để xoay màn hình không mất */
class PhotoSession(val image: Bitmap) {
    var phase by mutableStateOf(PhotoPhase.MARKING)
    var tool by mutableStateOf(MarkTool.MARKER)
    val strokes = mutableStateListOf<MarkStroke>()
    var marked by mutableStateOf<Bitmap?>(null)
    val items = mutableStateListOf<ExtractedWord>()
    var errorText by mutableStateOf("")
    var usageText by mutableStateOf("")
    var showConsent by mutableStateOf(false)
}

object ImageUtils {
    /** Màu bút tô trong app: hồng tím để không lẫn với dạ quang vàng có sẵn trong sách */
    const val MARK_R = 255
    const val MARK_G = 46
    const val MARK_B = 171
    const val MARKER_ALPHA = 0.45f

    /** Đọc ảnh (tự xoay đúng chiều theo EXIF), thu nhỏ khi đọc để không tốn bộ nhớ */
    fun load(context: Context, uri: Uri, maxSide: Int): Bitmap {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val longest = max(info.size.width, info.size.height)
            var sample = 1
            while (longest / sample > maxSide) sample *= 2
            decoder.setTargetSampleSize(sample)
        }
    }

    fun resized(bitmap: Bitmap, maxSide: Int): Bitmap {
        val longest = max(bitmap.width, bitmap.height)
        if (longest <= maxSide) return bitmap
        val scale = maxSide.toFloat() / longest
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).roundToInt().coerceAtLeast(1),
            (bitmap.height * scale).roundToInt().coerceAtLeast(1),
            true
        )
    }

    fun jpeg(bitmap: Bitmap, quality: Int): ByteArray =
        ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()

    /** Ghép ảnh gốc (thu về 1600px) + nét tô thành 1 ảnh để gửi cho AI */
    fun renderMarked(image: Bitmap, strokes: List<MarkStroke>): Bitmap {
        val base = resized(image, 1600).copy(Bitmap.Config.ARGB_8888, true)
        val canvas = android.graphics.Canvas(base)
        val w = base.width.toFloat()
        val h = base.height.toFloat()
        for (s in strokes) {
            if (s.points.isEmpty()) continue
            val alpha = if (s.tool == MarkTool.MARKER) MARKER_ALPHA else 1f
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.argb((alpha * 255).roundToInt(), MARK_R, MARK_G, MARK_B)
                strokeWidth = s.widthFraction * w
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
                style = Paint.Style.STROKE
            }
            if (s.points.size == 1) {
                paint.style = Paint.Style.FILL
                canvas.drawCircle(s.points[0].x * w, s.points[0].y * h, paint.strokeWidth / 2, paint)
            } else {
                val path = Path()
                path.moveTo(s.points[0].x * w, s.points[0].y * h)
                for (i in 1 until s.points.size) path.lineTo(s.points[i].x * w, s.points[i].y * h)
                canvas.drawPath(path, paint)
            }
        }
        return base
    }
}
