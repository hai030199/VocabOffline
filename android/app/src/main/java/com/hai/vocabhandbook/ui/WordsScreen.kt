@file:OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)

package com.hai.vocabhandbook.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hai.vocabhandbook.VocabViewModel
import com.hai.vocabhandbook.data.FilterItem
import com.hai.vocabhandbook.data.Word
import com.hai.vocabhandbook.data.capitalizedWords

/** Danh sách từ của 1 buổi (hoặc tất cả), tìm kiếm chỉ trong phạm vi đang mở */
@Composable
fun WordsScreen(vm: VocabViewModel, filter: FilterItem, actions: AppActions) {
    var query by rememberSaveable { mutableStateOf("") }
    var hideAll by rememberSaveable { mutableStateOf(false) }
    val isAll = filter == FilterItem.ALL
    val scoped = remember(vm.words, filter) {
        if (isAll) vm.words else vm.words.filter { it.fileName == filter.fileName && it.session == filter.session }
    }
    val shown = remember(scoped, query) { if (query.isBlank()) scoped else scoped.filter { it.matches(query) } }

    DisposableEffect(Unit) { onDispose { vm.speech.stopAll() } }

    Scaffold(
        containerColor = VC.Board,
        topBar = {
            BoardTopBar(if (isAll) "Tất cả các file" else filter.session, onBack = { vm.onBack() }) {
                IconButton(onClick = { hideAll = !hideAll }) {
                    Icon(
                        if (hideAll) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        contentDescription = if (hideAll) "Hiện tất cả nghĩa" else "Ẩn tất cả nghĩa",
                        tint = if (hideAll) VC.Red else VC.Yellow,
                    )
                }
                IconButton(onClick = {
                    if (vm.speech.isSpeakingAll) vm.speech.stopAll() else vm.speech.speakAll(shown.map { it.word })
                }) {
                    Icon(
                        if (vm.speech.isSpeakingAll) Icons.Filled.StopCircle else Icons.Filled.PlayCircle,
                        contentDescription = if (vm.speech.isSpeakingAll) "Dừng đọc" else "Đọc tất cả",
                        tint = if (vm.speech.isSpeakingAll) VC.Red else VC.Yellow,
                    )
                }
                IconButton(onClick = actions.pickExcel) {
                    Icon(Icons.Filled.AddCircle, contentDescription = "Thêm file Excel")
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            SearchField(
                value = query,
                onChange = { query = it },
                placeholder = if (isAll) "Tìm trong tất cả..." else "Tìm trong ${filter.session}...",
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
            if (!isAll) {
                Text(
                    "${filter.fileName}, ${scoped.size} từ",
                    color = VC.Green, fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                )
            }
            if (shown.isEmpty()) {
                Text(
                    "Không tìm thấy từ nào 😔",
                    color = VC.Gray,
                    modifier = Modifier.fillMaxWidth().padding(top = 50.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            } else {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(shown, key = { it.id }) { w ->
                        WordCard(
                            word = w,
                            hideAll = hideAll,
                            onSpeak = { vm.speech.speak(w.word) },
                            onDelete = { vm.deleteWord(w) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WordCard(word: Word, hideAll: Boolean, onSpeak: () -> Unit, onDelete: () -> Unit) {
    var cardToggled by rememberSaveable(word.id) { mutableStateOf(false) }
    val hidden = if (hideAll) !cardToggled else cardToggled
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(VC.Card)
            .border(1.dp, Color.White.copy(alpha = 0.1f), shape)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            // Từ và phiên âm nằm cạnh nhau, không đủ chỗ thì phiên âm xuống dòng nguyên khối
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(word.word.capitalizedWords(), color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text(
                    word.pronunciation,
                    color = Color(0xFFD9D9D9),
                    fontSize = 15.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.align(Alignment.CenterVertically),
                )
            }
            Spacer(Modifier.height(6.dp))
            if (hidden) {
                Text("(Đã ẩn nghĩa)", color = VC.Gray.copy(alpha = 0.5f), fontSize = 17.sp, maxLines = 1)
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        word.meaning, color = VC.Cream, fontSize = 19.sp,
                        maxLines = 3, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (word.meaningSource == "ai") {
                        Spacer(Modifier.width(6.dp))
                        Tag("AI", VC.Purple)
                    }
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CircleIconButton(
                if (hidden) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                "Ẩn hoặc hiện nghĩa", VC.Yellow, Color.White.copy(alpha = 0.2f),
            ) { cardToggled = !cardToggled }
            CircleIconButton(Icons.AutoMirrored.Filled.VolumeUp, "Phát âm", Color.White, VC.Blue, onSpeak)
            CircleIconButton(Icons.Filled.Delete, "Xoá từ", VC.Red, Color.White.copy(alpha = 0.2f), onDelete)
        }
    }
}

/** Xem trước khi import file Excel: đếm từ mới / từ đã có */
@Composable
fun ImportPreviewScreen(vm: VocabViewModel) {
    var onlyNew by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val existing = remember(vm.words) { vm.wordKeySet() }
    val pending = vm.pendingWords
    val newWords = remember(pending, existing) { pending.filter { it.word.trim().lowercase() !in existing } }
    val shown = if (onlyNew) newWords else pending

    BackHandler { vm.cancelImport() }

    Scaffold(
        containerColor = VC.Board,
        topBar = {
            TopAppBar(
                title = { Text("Quét từ vựng", fontWeight = FontWeight.Bold) },
                navigationIcon = { TextButton(onClick = { vm.cancelImport() }) { Text("Hủy", color = VC.Red) } },
                actions = {
                    Box {
                        TextButton(onClick = { menu = true }) {
                            Text("Import", color = VC.Yellow, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text("Import tất cả (${pending.size})") },
                                onClick = { menu = false; vm.confirmImport(importAll = true) },
                            )
                            DropdownMenuItem(
                                text = { Text("Chỉ từ mới (${newWords.size})") },
                                enabled = newWords.isNotEmpty(),
                                onClick = { menu = false; vm.confirmImport(importAll = false) },
                            )
                        }
                    }
                },
                colors = boardBarColors(),
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                StatTile("${pending.size}", "Tổng", Color.White, Color.White.copy(alpha = 0.1f), Modifier.weight(1f))
                StatTile("${newWords.size}", "Mới", VC.Green, VC.Green.copy(alpha = 0.15f), Modifier.weight(1f))
                StatTile("${pending.size - newWords.size}", "Cũ", VC.Red, VC.Red.copy(alpha = 0.15f), Modifier.weight(1f))
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "📋 ${vm.pendingFileName}", color = VC.Yellow, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                FilterChip(
                    selected = onlyNew,
                    onClick = { onlyNew = !onlyNew },
                    label = { Text("Chỉ từ mới") },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = VC.Green.copy(alpha = 0.3f),
                        selectedLabelColor = Color.White,
                        labelColor = VC.Green,
                    ),
                )
            }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                items(shown, key = { it.id }) { w ->
                    val dup = w.word.trim().lowercase() in existing
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(if (dup) VC.Red.copy(alpha = 0.08f) else VC.Green.copy(alpha = 0.06f))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Tag(if (dup) "CŨ" else "MỚI", if (dup) VC.Red else VC.Green)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(w.word.capitalizedWords(), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            Text(w.pronunciation, color = VC.Gray, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                            Text(w.meaning, color = VC.Cream, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        IconButton(onClick = { vm.speech.speak(w.word) }) {
                            Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = "Phát âm", tint = VC.Blue)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatTile(value: String, label: String, color: Color, background: Color, modifier: Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(background)
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(value, color = color, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text(label, color = Color.White, fontSize = 11.sp)
    }
}
