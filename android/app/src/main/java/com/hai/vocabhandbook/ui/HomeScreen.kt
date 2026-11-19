package com.hai.vocabhandbook.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hai.vocabhandbook.Screen
import com.hai.vocabhandbook.VocabViewModel
import com.hai.vocabhandbook.data.FilterItem
import com.hai.vocabhandbook.data.PhotoTarget
import com.hai.vocabhandbook.data.Word
import com.hai.vocabhandbook.data.capitalizedWords

/** Màn chính (giống thanh bên của bản iOS): tìm toàn bộ, liên từ, danh sách file và buổi */
@Composable
fun HomeScreen(vm: VocabViewModel, actions: AppActions) {
    var query by rememberSaveable { mutableStateOf("") }
    var photoMenu by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    val words = vm.words

    val structure = remember(words) {
        words.groupBy { it.fileName }
            .mapValues { entry -> entry.value.map { it.session }.distinct().sorted() }
            .toSortedMap()
    }
    val counts = remember(words) { words.groupingBy { it.fileName to it.session }.eachCount() }
    val results = remember(words, query) { if (query.isBlank()) emptyList() else words.filter { it.matches(query) } }

    Scaffold(
        containerColor = VC.Board,
        topBar = {
            BoardTopBar("SỔ TỪ VỰNG") {
                Box {
                    IconButton(onClick = { photoMenu = true }) {
                        Icon(Icons.Filled.PhotoCamera, contentDescription = "Lấy từ mới từ ảnh")
                    }
                    DropdownMenu(expanded = photoMenu, onDismissRequest = { photoMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("Chụp ảnh trang sách") },
                            leadingIcon = { Icon(Icons.Filled.PhotoCamera, contentDescription = null) },
                            onClick = { photoMenu = false; actions.takePhoto(PhotoTarget.VOCAB) },
                        )
                        DropdownMenuItem(
                            text = { Text("Chọn ảnh có sẵn") },
                            leadingIcon = { Icon(Icons.Filled.PhotoLibrary, contentDescription = null) },
                            onClick = { photoMenu = false; actions.pickPhoto(PhotoTarget.VOCAB) },
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("Cài đặt AI") },
                            leadingIcon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                            onClick = { photoMenu = false; vm.push(Screen.AISettings) },
                        )
                    }
                }
                IconButton(onClick = actions.pickExcel) {
                    Icon(Icons.Filled.AddCircle, contentDescription = "Thêm file Excel")
                }
                IconButton(onClick = { confirmClear = true }, enabled = words.isNotEmpty()) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = "Xoá tất cả từ vựng",
                        tint = if (words.isNotEmpty()) VC.Red else VC.Gray.copy(alpha = 0.4f),
                    )
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            SearchField(
                value = query,
                onChange = { query = it },
                placeholder = "Tìm trong tất cả file Excel",
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
            if (vm.isLoading) {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 12.dp), color = VC.Yellow)
            }
            if (vm.errorMessage.isNotEmpty()) ErrorBanner(vm.errorMessage) { vm.errorMessage = "" }

            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
                if (query.isNotBlank()) {
                    item { SectionHeader("KẾT QUẢ TRONG TẤT CẢ FILE (${results.size})") }
                    if (results.isEmpty()) {
                        item { Text("Không tìm thấy từ nào", color = VC.Gray, modifier = Modifier.padding(16.dp)) }
                    }
                    items(results, key = { it.id }) { w ->
                        SearchResultRow(
                            word = w,
                            onSpeak = { vm.speech.speak(w.word) },
                            onOpen = { vm.push(Screen.Words(FilterItem(w.fileName, w.session))) },
                        )
                    }
                } else {
                    item { SectionHeader("TỔNG HỢP") }
                    item {
                        NavRow(
                            title = "Tất cả các file", count = words.size, countColor = VC.Yellow,
                            icon = Icons.AutoMirrored.Filled.MenuBook, iconTint = VC.Yellow, bold = true,
                        ) { vm.push(Screen.Words(FilterItem.ALL)) }
                    }
                    item {
                        NavRow(
                            title = "Liên từ (Conjunctions)", count = vm.conjunctions.size, countColor = VC.Orange,
                            icon = Icons.Filled.Link, iconTint = VC.Orange, bold = true,
                        ) { vm.push(Screen.Conjunctions) }
                    }
                    item { SectionHeader("THEO FILE EXCEL") }
                    if (words.isEmpty()) {
                        item { EmptyState(vm.errorMessage, actions.pickExcel) }
                    }
                    structure.forEach { (file, sessions) ->
                        val expanded = file in vm.expandedFiles
                        item(key = "f:$file") {
                            FileHeader(file, expanded) { vm.toggleExpanded(file) }
                        }
                        if (expanded) {
                            items(sessions, key = { "s:$file:$it" }) { s ->
                                NavRow(title = s, count = counts[file to s] ?: 0, countColor = VC.Gray, indent = true) {
                                    vm.push(Screen.Words(FilterItem(file, s)))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (confirmClear) {
        ConfirmDialog(
            title = "Xoá toàn bộ ${words.size} từ vựng?",
            message = "Thao tác này không hoàn tác được.",
            confirmText = "Xoá tất cả",
            onConfirm = {
                vm.speech.stopAll()
                vm.clearAll()
                confirmClear = false
            },
            onDismiss = { confirmClear = false },
        )
    }
}

@Composable
private fun NavRow(
    title: String,
    count: Int,
    countColor: Color,
    icon: ImageVector? = null,
    iconTint: Color = Color.White,
    bold: Boolean = false,
    indent: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = if (indent) 36.dp else 16.dp, end = 12.dp, top = 13.dp, bottom = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
        }
        Text(
            title,
            color = Color.White,
            fontSize = 16.sp,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.weight(1f),
        )
        Text("$count", color = countColor, fontSize = 15.sp)
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = VC.Gray)
    }
}

@Composable
private fun FileHeader(name: String, expanded: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(name, color = VC.Green, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null, tint = VC.Green)
    }
}

@Composable
private fun SearchResultRow(word: Word, onSpeak: () -> Unit, onOpen: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(word.word.capitalizedWords(), color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Text(word.meaning, color = VC.Cream, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("${word.fileName} › ${word.session}", color = VC.Green, fontSize = 12.sp)
        }
        IconButton(onClick = onSpeak) {
            Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = "Phát âm", tint = VC.Blue)
        }
    }
}

@Composable
private fun EmptyState(errorMessage: String, onAdd: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = null, tint = VC.Gray.copy(alpha = 0.5f), modifier = Modifier.size(70.dp))
        Spacer(Modifier.height(16.dp))
        Text("Chưa có dữ liệu", color = Color.White, fontSize = 22.sp)
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = onAdd,
            colors = ButtonDefaults.buttonColors(containerColor = VC.Yellow, contentColor = Color.Black),
            shape = RoundedCornerShape(10.dp),
            contentPadding = PaddingValues(horizontal = 30.dp, vertical = 15.dp),
        ) {
            Icon(Icons.Filled.TableChart, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Thêm file Excel", fontWeight = FontWeight.Bold)
        }
        if (errorMessage.isNotEmpty()) {
            Text(errorMessage, color = VC.Red, modifier = Modifier.padding(16.dp))
        }
        Text(
            "File Excel cần có cột từ, cột phiên âm (có dấu /), cột nghĩa. Mỗi sheet là một buổi học.",
            color = VC.Gray,
            fontSize = 13.sp,
            modifier = Modifier.padding(horizontal = 32.dp, vertical = 12.dp).background(Color.Transparent),
        )
    }
}
