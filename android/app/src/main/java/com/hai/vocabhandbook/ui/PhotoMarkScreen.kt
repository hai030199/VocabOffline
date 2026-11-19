@file:OptIn(ExperimentalMaterial3Api::class)

package com.hai.vocabhandbook.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.hai.vocabhandbook.Screen
import com.hai.vocabhandbook.VocabViewModel
import com.hai.vocabhandbook.ai.AiSettings
import com.hai.vocabhandbook.data.ImageUtils
import com.hai.vocabhandbook.data.MarkStroke
import com.hai.vocabhandbook.data.MarkTool
import com.hai.vocabhandbook.data.PhotoPhase
import com.hai.vocabhandbook.data.PhotoSession
import com.hai.vocabhandbook.data.capitalizedWords

/** Chụp ảnh xong: tô hoặc khoanh từ cần học, bấm Quét để AI đọc, xem lại rồi lưu */
@Composable
fun PhotoMarkScreen(vm: VocabViewModel) {
    val session = vm.photo
    if (session == null) {
        LaunchedEffect(Unit) { vm.closePhoto() }
        return
    }
    val p: PhotoSession = session
    val context = LocalContext.current
    var confirmExit by remember { mutableStateOf(false) }
    var showFull by remember { mutableStateOf(false) }

    fun leave() {
        if (p.phase == PhotoPhase.MARKING && p.strokes.isNotEmpty()) {
            confirmExit = true
        } else {
            vm.closePhoto()
        }
    }
    // Vuốt cạnh màn hình để quay lại dễ chạm nhầm khi đang tô, nên hỏi lại trước khi bỏ
    BackHandler { leave() }

    Scaffold(
        containerColor = VC.Board,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (p.phase == PhotoPhase.MARKING) "Tô từ cần học" else "Từ mới từ ảnh",
                        fontWeight = FontWeight.Bold, fontSize = 18.sp,
                    )
                },
                navigationIcon = { TextButton(onClick = { leave() }) { Text("Hủy", color = VC.Red) } },
                actions = {
                    if (p.phase == PhotoPhase.MARKING) {
                        val enabled = p.strokes.isNotEmpty()
                        TextButton(onClick = { vm.scanPhoto() }, enabled = enabled) {
                            Text("Quét", color = if (enabled) VC.Yellow else VC.Gray, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                        }
                    } else if (p.phase == PhotoPhase.RESULT && p.items.isNotEmpty()) {
                        val n = p.items.count { it.include }
                        TextButton(onClick = { vm.saveLearned() }, enabled = n > 0) {
                            Text("Lưu ($n)", color = if (n > 0) VC.Yellow else VC.Gray, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                        }
                    }
                },
                colors = boardBarColors(),
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (p.phase) {
                PhotoPhase.MARKING -> MarkingView(p)
                PhotoPhase.LOADING -> LoadingView(p)
                PhotoPhase.ERROR -> ErrorView(
                    text = p.errorText,
                    onRetry = { vm.scanPhoto() },
                    onRemark = { p.phase = PhotoPhase.MARKING },
                    onSettings = { vm.push(Screen.AISettings) },
                )
                PhotoPhase.RESULT -> ResultView(vm, p, onShowFull = { showFull = true })
            }
        }
    }

    if (p.showConsent) {
        ConsentDialog(
            onAgree = {
                AiSettings.setConsent(context, true)
                p.showConsent = false
                vm.scanPhoto()
            },
            onDismiss = { p.showConsent = false },
        )
    }
    if (confirmExit) {
        ConfirmDialog(
            title = "Bỏ ảnh này?",
            message = "Các nét tô sẽ mất.",
            confirmText = "Bỏ",
            onConfirm = { confirmExit = false; vm.closePhoto() },
            onDismiss = { confirmExit = false },
        )
    }
    if (showFull) {
        val full = remember(p.marked) { (p.marked ?: p.image).asImageBitmap() }
        Dialog(onDismissRequest = { showFull = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Box(
                Modifier.fillMaxSize().background(Color.Black).clickable { showFull = false },
                contentAlignment = Alignment.Center,
            ) {
                Image(full, contentDescription = "Ảnh đã tô", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            }
        }
    }
}

// MARK: Tô từ

@Composable
private fun MarkingView(p: PhotoSession) {
    Column(Modifier.fillMaxSize()) {
        Text(
            "Tô lên từ muốn thêm, hoặc chọn Bút khoanh để khoanh tròn quanh từ hay cả một vùng.",
            color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            val imageRatio = p.image.width.toFloat() / p.image.height.toFloat()
            val boxRatio = maxWidth / maxHeight
            val w = if (imageRatio > boxRatio) maxWidth else maxHeight * imageRatio
            val h = if (imageRatio > boxRatio) maxWidth / imageRatio else maxHeight
            MarkupCanvas(p, Modifier.size(w, h))
        }
        Row(
            Modifier
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.3f))
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ToolChip("Tô", Icons.Filled.Brush, p.tool == MarkTool.MARKER) { p.tool = MarkTool.MARKER }
            ToolChip("Khoanh", Icons.Filled.Gesture, p.tool == MarkTool.CIRCLE) { p.tool = MarkTool.CIRCLE }
            ToolChip("Tẩy", Icons.Filled.Clear, p.tool == MarkTool.ERASER) { p.tool = MarkTool.ERASER }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { if (p.strokes.isNotEmpty()) p.strokes.removeAt(p.strokes.lastIndex) }, enabled = p.strokes.isNotEmpty()) {
                Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Hoàn tác", tint = if (p.strokes.isNotEmpty()) Color.White else VC.Gray)
            }
            IconButton(onClick = { p.strokes.clear() }, enabled = p.strokes.isNotEmpty()) {
                Icon(Icons.Filled.Delete, contentDescription = "Xoá hết nét tô", tint = if (p.strokes.isNotEmpty()) VC.Red else VC.Gray)
            }
        }
    }
}

@Composable
private fun ToolChip(title: String, icon: ImageVector, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .height(40.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) VC.Mark.copy(alpha = 0.35f) else Color.White.copy(alpha = 0.1f))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(4.dp))
        Text(title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}

/** Ảnh + lớp vẽ đè. Nét lưu theo tỉ lệ khung ảnh để ghép lại đúng chỗ trên ảnh gốc */
@Composable
private fun MarkupCanvas(p: PhotoSession, modifier: Modifier) {
    val bitmap = remember(p.image) { p.image.asImageBitmap() }
    val current = remember { mutableStateListOf<Offset>() }
    var currentWidth by remember { mutableFloatStateOf(0f) }
    val tool = p.tool

    Box(modifier) {
        Image(bitmap, contentDescription = "Ảnh cần tô", contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize())
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(tool) {
                    val w = size.width.toFloat()
                    val h = size.height.toFloat()
                    // Bút tô nét to như bút dạ quang, bút khoanh nét mảnh
                    val widthFraction = if (tool == MarkTool.MARKER) 26.dp.toPx() / w else 5.dp.toPx() / w
                    fun norm(o: Offset) = Offset((o.x / w).coerceIn(0f, 1f), (o.y / h).coerceIn(0f, 1f))
                    fun erase(o: Offset) {
                        val n = norm(o)
                        p.strokes.removeAll { s -> s.points.any { (it - n).getDistance() < 0.04f } }
                    }
                    fun commit() {
                        if (current.isNotEmpty()) p.strokes.add(MarkStroke(current.toList(), widthFraction, tool))
                        current.clear()
                    }
                    detectDragGestures(
                        onDragStart = { o ->
                            if (tool == MarkTool.ERASER) {
                                erase(o)
                            } else {
                                currentWidth = widthFraction
                                current.clear()
                                current.add(norm(o))
                            }
                        },
                        onDragEnd = { if (tool != MarkTool.ERASER) commit() },
                        onDragCancel = { if (tool != MarkTool.ERASER) commit() },
                        onDrag = { change, _ ->
                            change.consume()
                            if (tool == MarkTool.ERASER) erase(change.position) else current.add(norm(change.position))
                        },
                    )
                },
        ) {
            p.strokes.forEach { drawMark(it.points, it.widthFraction, it.tool) }
            if (current.isNotEmpty()) drawMark(current, currentWidth, tool)
        }
    }
}

private fun DrawScope.drawMark(points: List<Offset>, widthFraction: Float, tool: MarkTool) {
    if (points.isEmpty()) return
    val strokeWidth = widthFraction * size.width
    val color = if (tool == MarkTool.MARKER) VC.Mark.copy(alpha = ImageUtils.MARKER_ALPHA) else VC.Mark
    val first = Offset(points[0].x * size.width, points[0].y * size.height)
    if (points.size == 1) {
        drawCircle(color, radius = strokeWidth / 2, center = first)
        return
    }
    val path = Path().apply {
        moveTo(first.x, first.y)
        for (i in 1 until points.size) lineTo(points[i].x * size.width, points[i].y * size.height)
    }
    drawPath(path, color, style = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

// MARK: Đang quét / lỗi / kết quả

@Composable
private fun LoadingView(p: PhotoSession) {
    // Giữ màn hình sáng khi đang chờ AI, tắt màn hình giữa chừng dễ bị ngắt kết nối
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    val preview = remember(p.marked) { (p.marked ?: p.image).asImageBitmap() }
    Column(
        Modifier.fillMaxSize().padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Image(
            preview, contentDescription = null, contentScale = ContentScale.Fit,
            modifier = Modifier.heightIn(max = 280.dp).clip(RoundedCornerShape(10.dp)),
            alpha = 0.7f,
        )
        Spacer(Modifier.height(24.dp))
        CircularProgressIndicator(color = VC.Yellow)
        Spacer(Modifier.height(12.dp))
        Text("AI đang đọc các từ bạn vừa tô...", color = Color.White)
    }
}

@Composable
private fun ErrorView(text: String, onRetry: () -> Unit, onRemark: () -> Unit, onSettings: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Filled.Warning, contentDescription = null, tint = VC.Orange, modifier = Modifier.size(48.dp))
        Spacer(Modifier.height(16.dp))
        Text(text, color = Color.White, textAlign = TextAlign.Center)
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Pill("Thử lại", Icons.Filled.Refresh, primary = true, onClick = onRetry)
            Pill("Tô lại", Icons.Filled.Brush, primary = false, onClick = onRemark)
            Pill("Cài đặt AI", Icons.Filled.Settings, primary = false, onClick = onSettings)
        }
    }
}

@Composable
private fun Pill(title: String, icon: ImageVector, primary: Boolean, onClick: () -> Unit) {
    val fg = if (primary) Color.Black else Color.White
    Row(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (primary) VC.Yellow else Color.White.copy(alpha = 0.15f))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(4.dp))
        Text(title, color = fg, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun ResultView(vm: VocabViewModel, p: PhotoSession, onShowFull: () -> Unit) {
    val thumb = remember(p.marked) { (p.marked ?: p.image).asImageBitmap() }
    val existing = remember(vm.words) { vm.wordKeySet() }
    Column(Modifier.fillMaxSize().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Image(
                thumb, contentDescription = "Xem ảnh đã tô", contentScale = ContentScale.Crop,
                modifier = Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)).clickable(onClick = onShowFull),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Tìm thấy ${p.items.size} từ bạn đã tô", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Text("Lưu vào: ${VocabViewModel.LEARNED_FILE_NAME}", color = VC.Green, fontSize = 12.sp)
                if (p.usageText.isNotEmpty()) Text(p.usageText, color = VC.Gray, fontSize = 11.sp)
            }
            TextButton(onClick = { p.phase = PhotoPhase.MARKING }) { Text("Tô lại", color = VC.Yellow, fontWeight = FontWeight.Bold) }
        }
        if (p.items.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(Icons.Filled.Brush, contentDescription = null, tint = VC.Gray, modifier = Modifier.size(40.dp))
                Spacer(Modifier.height(10.dp))
                Text(
                    "AI không đọc được từ nào trong vùng tô.\nThử tô lại đè kín chữ hơn.",
                    color = VC.Gray, textAlign = TextAlign.Center,
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                itemsIndexed(p.items, key = { _, item -> item.id }) { _, item ->
                    val dup = item.word.trim().lowercase() in existing
                    fun update(change: (com.hai.vocabhandbook.ai.ExtractedWord) -> com.hai.vocabhandbook.ai.ExtractedWord) {
                        val i = p.items.indexOfFirst { it.id == item.id }
                        if (i >= 0) p.items[i] = change(p.items[i])
                    }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(Color.White.copy(alpha = if (item.include) 0.07f else 0.02f))
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        IconButton(onClick = { update { it.copy(include = !it.include) } }) {
                            Icon(
                                if (item.include) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                                contentDescription = if (item.include) "Bỏ chọn" else "Chọn",
                                tint = if (item.include) VC.Yellow else VC.Gray,
                            )
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    item.word.capitalizedWords(), color = Color.White.copy(alpha = if (item.include) 1f else 0.5f),
                                    fontSize = 18.sp, fontWeight = FontWeight.Bold,
                                )
                                if (dup) {
                                    Spacer(Modifier.width(6.dp))
                                    Tag("CŨ", VC.Red)
                                }
                            }
                            Text(item.pronunciation, color = VC.Gray, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                            TextField(
                                value = item.meaning,
                                onValueChange = { v -> update { it.copy(meaning = v) } },
                                textStyle = TextStyle(color = VC.Cream, fontSize = 15.sp),
                                placeholder = { Text("Nghĩa", color = VC.Gray) },
                                modifier = Modifier.fillMaxWidth(),
                                colors = TextFieldDefaults.colors(
                                    focusedContainerColor = Color.White.copy(alpha = 0.06f),
                                    unfocusedContainerColor = Color.Transparent,
                                    focusedIndicatorColor = VC.Yellow,
                                    unfocusedIndicatorColor = Color.White.copy(alpha = 0.15f),
                                    cursorColor = VC.Yellow,
                                ),
                            )
                            if (item.meaningFromImage) Tag("NGHĨA TỪ ẢNH", VC.Green) else Tag("AI ĐIỀN NGHĨA", VC.Purple)
                        }
                        IconButton(onClick = { vm.speech.speak(item.word) }) {
                            Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = "Phát âm", tint = VC.Blue)
                        }
                    }
                }
            }
        }
    }
}

// MARK: Xin đồng ý trước khi gửi ảnh cho AI bên thứ ba

@Composable
fun ConsentDialog(onAgree: () -> Unit, onDismiss: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.PanTool, contentDescription = null, tint = VC.Yellow) },
        title = { Text("Gửi ảnh cho AI để nhận diện từ") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ConsentInfo("Gửi gì", "Ảnh bạn vừa chụp hoặc chọn, kèm các nét tô của bạn. Ảnh được thu nhỏ trước khi gửi. App không gửi sổ từ vựng hay thông tin nào khác của bạn.")
                ConsentInfo("Gửi cho ai", "Google, qua dịch vụ Gemini API, bằng API key của chính bạn.")
                ConsentInfo("Để làm gì", "Đọc các từ bạn đã tô, lấy phiên âm và nghĩa tiếng Việt.")
                ConsentInfo("Lưu ý", "Với gói miễn phí của Gemini API, Google có thể dùng nội dung gửi lên để cải thiện sản phẩm của họ. Đừng chụp trang có thông tin cá nhân.")
                ConsentInfo("Thay đổi", "Bạn có thể tắt quyền này bất cứ lúc nào trong Cài đặt AI.")
                Text(
                    "Điều khoản Gemini API của Google",
                    color = VC.Cyan, fontSize = 13.sp,
                    modifier = Modifier.clickable { uriHandler.openUri("https://ai.google.dev/gemini-api/terms") },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onAgree) { Text("Đồng ý và quét", color = VC.Yellow, fontWeight = FontWeight.Bold) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Không đồng ý", color = Color.White) } },
    )
}

@Composable
private fun ConsentInfo(title: String, text: String) {
    Column {
        Text(title, color = VC.Yellow, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Text(text, color = Color.White.copy(alpha = 0.9f), fontSize = 14.sp)
    }
}
