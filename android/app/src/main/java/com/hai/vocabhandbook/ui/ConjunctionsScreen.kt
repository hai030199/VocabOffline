package com.hai.vocabhandbook.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.hai.vocabhandbook.Screen
import com.hai.vocabhandbook.VocabViewModel
import com.hai.vocabhandbook.data.Conjunction
import com.hai.vocabhandbook.data.PhotoTarget
import com.hai.vocabhandbook.data.fold

/** Liên từ: chế độ bảng tổng quan và chế độ chi tiết theo nhóm */
@Composable
fun ConjunctionsScreen(vm: VocabViewModel, actions: AppActions) {
    var query by rememberSaveable { mutableStateOf("") }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var selected by remember { mutableStateOf<Conjunction?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    val expanded = remember { mutableStateListOf<String>() }

    val grouped = remember(vm.conjunctions, query) {
        val q = fold(query.trim())
        val source = if (q.isEmpty()) vm.conjunctions
        else vm.conjunctions.filter { fold(it.english).contains(q) || fold(it.vietnamese).contains(q) }
        source.groupBy { it.category }.toSortedMap().toList()
    }

    Scaffold(
        containerColor = VC.Board,
        topBar = {
            BoardTopBar("🔗 LIÊN TỪ", onBack = { vm.onBack() }) {
                IconButton(onClick = { vm.push(Screen.ReferenceImage) }) {
                    Icon(Icons.Filled.Image, contentDescription = "Ảnh tham khảo", tint = VC.Cyan)
                }
                IconButton(onClick = actions.pickConjunctionExcel) {
                    Icon(Icons.Filled.AddCircle, contentDescription = "Import liên từ", tint = VC.Orange)
                }
            }
        },
        bottomBar = {
            if (vm.conjunctions.isNotEmpty()) {
                TextButton(
                    onClick = { confirmClear = true },
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                ) {
                    Icon(Icons.Filled.Delete, contentDescription = null, tint = VC.Red)
                    Spacer(Modifier.width(6.dp))
                    Text("Xóa tất cả", color = VC.Red)
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (vm.errorMessage.isNotEmpty()) ErrorBanner(vm.errorMessage) { vm.errorMessage = "" }
            when {
                vm.isParsingConjunctions -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = VC.Orange)
                }
                vm.conjunctions.isEmpty() -> EmptyConjunctions(actions.pickConjunctionExcel)
                else -> {
                    SearchField(
                        value = query, onChange = { query = it }, placeholder = "Tìm liên từ...",
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                    TabRow(selectedTabIndex = tab, containerColor = VC.Board, contentColor = VC.Orange) {
                        Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Bảng tổng quan") })
                        Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Chi tiết") })
                    }
                    if (tab == 0) {
                        ConjunctionTable(grouped, onSpeak = { vm.speech.speak(it.english) }, onTap = { selected = it })
                    } else {
                        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                            grouped.forEach { (category, list) ->
                                val open = category in expanded || query.isNotBlank()
                                item(key = "c:$category") {
                                    Row(
                                        Modifier
                                            .fillMaxWidth()
                                            .clickable { if (category in expanded) expanded.remove(category) else expanded.add(category) }
                                            .padding(horizontal = 16.dp, vertical = 14.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Icon(Icons.Filled.Link, contentDescription = null, tint = VC.Orange)
                                        Spacer(Modifier.width(8.dp))
                                        Text(category, color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                                        Text("${list.size}", color = VC.Gray)
                                        Icon(if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null, tint = VC.Gray)
                                    }
                                }
                                if (open) {
                                    items(list, key = { "i:${it.id}" }) { c ->
                                        Row(
                                            Modifier
                                                .fillMaxWidth()
                                                .background(Color.White.copy(alpha = 0.05f))
                                                .clickable { selected = c }
                                                .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            IconButton(onClick = { vm.speech.speak(c.english) }) {
                                                Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = "Phát âm", tint = VC.Blue)
                                            }
                                            Text(
                                                c.english, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                                                modifier = Modifier.weight(1f),
                                            )
                                            Text(c.vietnamese, color = VC.Cream, fontSize = 15.sp, modifier = Modifier.weight(1f))
                                            IconButton(onClick = { vm.deleteConjunction(c) }) {
                                                Icon(Icons.Filled.Delete, contentDescription = "Xoá", tint = VC.Red.copy(alpha = 0.8f))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    selected?.let { c ->
        ConjunctionDetailDialog(c, onSpeak = { rate -> vm.speech.speak(c.english, rate) }, onDismiss = { selected = null })
    }
    if (confirmClear) {
        ConfirmDialog(
            title = "Xoá toàn bộ ${vm.conjunctions.size} liên từ?",
            message = "Thao tác này không hoàn tác được.",
            confirmText = "Xoá tất cả",
            onConfirm = { vm.clearConjunctions(); confirmClear = false },
            onDismiss = { confirmClear = false },
        )
    }
}

@Composable
private fun ConjunctionTable(
    grouped: List<Pair<String, List<Conjunction>>>,
    onSpeak: (Conjunction) -> Unit,
    onTap: (Conjunction) -> Unit,
) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        items(grouped, key = { it.first }) { (category, list) ->
            val shape = RoundedCornerShape(10.dp)
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .background(Color.White.copy(alpha = 0.06f))
                    .border(1.dp, Color.White.copy(alpha = 0.08f), shape),
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(VC.Orange)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Text(category, color = Color.Black, fontSize = 16.sp, fontWeight = FontWeight.Black, modifier = Modifier.weight(1f))
                    Text("${list.size}", color = Color.Black.copy(alpha = 0.7f), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(Color.White.copy(alpha = 0.05f))
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                ) {
                    Text("ENGLISH", color = VC.Gray, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Text("VIỆT NAM", color = VC.Gray, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                }
                list.forEach { c ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(
                            Modifier.weight(1f).clickable { onSpeak(c) },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.VolumeUp, contentDescription = "Phát âm",
                                tint = VC.Blue, modifier = Modifier.size(14.dp),
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(c.english, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        }
                        Text(
                            c.vietnamese, color = VC.Cream, fontSize = 13.sp,
                            modifier = Modifier.weight(1f).clickable { onTap(c) }.padding(start = 6.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyConjunctions(onImport: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(top = 80.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Filled.Link, contentDescription = null, tint = VC.Orange.copy(alpha = 0.6f), modifier = Modifier.size(60.dp))
        Spacer(Modifier.height(16.dp))
        Text("Chưa có dữ liệu", color = Color.White, fontSize = 20.sp)
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = onImport,
            colors = ButtonDefaults.buttonColors(containerColor = VC.Orange, contentColor = Color.Black),
            shape = RoundedCornerShape(10.dp),
        ) { Text("Import", fontWeight = FontWeight.Bold) }
        Text(
            "File Excel: cột A là liên từ, cột B là nghĩa, mỗi sheet là một nhóm.",
            color = VC.Gray, fontSize = 13.sp, textAlign = TextAlign.Center,
            modifier = Modifier.padding(32.dp),
        )
    }
}

@Composable
private fun ConjunctionDetailDialog(c: Conjunction, onSpeak: (Float) -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(VC.Board)
                .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(16.dp))
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Icon(Icons.Filled.Link, contentDescription = null, tint = VC.Orange, modifier = Modifier.size(52.dp))
            Text(c.english, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Text(c.vietnamese, color = VC.Cream, fontSize = 21.sp, textAlign = TextAlign.Center)
            Text(
                c.category, color = VC.Gray, fontSize = 12.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.White.copy(alpha = 0.1f))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                DetailButton("🔊", "Phát âm", VC.Blue) { onSpeak(0.9f) }
                DetailButton("🐢", "Chậm", VC.Orange) { onSpeak(0.45f) }
            }
            TextButton(onClick = onDismiss) { Text("Đóng", color = VC.Yellow) }
        }
    }
}

@Composable
private fun DetailButton(emoji: String, label: String, color: Color, onClick: () -> Unit) {
    Column(
        Modifier
            .size(width = 100.dp, height = 80.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = 0.1f))
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(emoji, fontSize = 26.sp)
        Text(label, color = color, fontSize = 12.sp)
    }
}

/** Ảnh bảng liên từ do người dùng tự chụp, phóng to bằng 2 ngón, chạm 2 lần để zoom nhanh */
@Composable
fun ReferenceImageScreen(vm: VocabViewModel, actions: AppActions) {
    LaunchedEffect(Unit) { vm.loadReference() }
    val bitmap = vm.referenceImage
    val image = remember(bitmap) { bitmap?.asImageBitmap() }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var menu by remember { mutableStateOf(false) }
    LaunchedEffect(bitmap) {
        scale = 1f
        offset = Offset.Zero
    }

    Scaffold(
        containerColor = VC.Board,
        topBar = {
            BoardTopBar("Ảnh tham khảo", onBack = { vm.onBack() }) {
                if (image != null) {
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Đổi ảnh tham khảo") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text("Chụp ảnh mới") },
                                leadingIcon = { Icon(Icons.Filled.PhotoCamera, contentDescription = null) },
                                onClick = { menu = false; actions.takePhoto(PhotoTarget.REFERENCE) },
                            )
                            DropdownMenuItem(
                                text = { Text("Chọn ảnh khác") },
                                leadingIcon = { Icon(Icons.Filled.PhotoLibrary, contentDescription = null) },
                                onClick = { menu = false; actions.pickPhoto(PhotoTarget.REFERENCE) },
                            )
                            DropdownMenuItem(
                                text = { Text("Xoá ảnh", color = VC.Red) },
                                leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null, tint = VC.Red) },
                                onClick = { menu = false; vm.deleteReference() },
                            )
                        }
                    }
                }
            }
        },
    ) { padding ->
        Box(
            Modifier.padding(padding).fillMaxSize().clipToBounds(),
            contentAlignment = Alignment.Center,
        ) {
            if (image != null) {
                Image(
                    bitmap = image,
                    contentDescription = "Ảnh tham khảo liên từ",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                scale = (scale * zoom).coerceIn(1f, 8f)
                                offset = if (scale > 1f) offset + pan else Offset.Zero
                            }
                        }
                        .pointerInput(Unit) {
                            detectTapGestures(onDoubleTap = {
                                if (scale > 1f) {
                                    scale = 1f
                                    offset = Offset.Zero
                                } else {
                                    scale = 3f
                                }
                            })
                        }
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationX = offset.x
                            translationY = offset.y
                        },
                )
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Icon(Icons.Filled.AddPhotoAlternate, contentDescription = null, tint = VC.Cyan.copy(alpha = 0.7f), modifier = Modifier.size(60.dp))
                    Text("Chưa có ảnh tham khảo", color = Color.White, fontSize = 22.sp)
                    Text(
                        "Chụp hoặc chọn ảnh bảng liên từ của bạn. Ảnh chỉ lưu trên máy này.",
                        color = VC.Gray, fontSize = 14.sp, textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 30.dp),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(
                            onClick = { actions.takePhoto(PhotoTarget.REFERENCE) },
                            colors = ButtonDefaults.buttonColors(containerColor = VC.Cyan, contentColor = Color.Black),
                            shape = RoundedCornerShape(10.dp),
                        ) { Text("Chụp ảnh") }
                        Button(
                            onClick = { actions.pickPhoto(PhotoTarget.REFERENCE) },
                            colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.15f), contentColor = Color.White),
                            shape = RoundedCornerShape(10.dp),
                        ) { Text("Chọn ảnh") }
                    }
                    Text("Hoặc dùng chế độ \"Bảng tổng quan\" để xem nhanh.", color = VC.Cyan, fontSize = 12.sp)
                }
            }
        }
    }
}
