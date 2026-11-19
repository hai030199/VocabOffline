@file:OptIn(ExperimentalMaterial3Api::class)

package com.hai.vocabhandbook.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Bảng màu giống bản iOS (nền bảng đen xanh, chữ nghĩa màu kem) */
object VC {
    val Board = Color(0xFF0D331A)
    val Cream = Color(0xFFFFE699)
    val Yellow = Color(0xFFFFD60A)
    val Orange = Color(0xFFFF9F0A)
    val Blue = Color(0xFF0A84FF)
    val Red = Color(0xFFFF453A)
    val Green = Color(0xFF30D158)
    val Purple = Color(0xFFBF5AF2)
    val Cyan = Color(0xFF64D2FF)
    val Gray = Color(0xFF8E8E93)
    val Mark = Color(0xFFFF2EAB)
    val Card = Color.White.copy(alpha = 0.08f)
}

@Composable
fun VocabTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = VC.Yellow,
            onPrimary = Color.Black,
            secondary = VC.Orange,
            background = VC.Board,
            onBackground = Color.White,
            surface = VC.Board,
            onSurface = Color.White,
            surfaceVariant = Color(0xFF1C4A2C),
            surfaceContainer = Color(0xFF173F27),
            surfaceContainerHigh = Color(0xFF1C4A2C),
            surfaceContainerHighest = Color(0xFF22553A),
        ),
        content = content
    )
}

@Composable
fun BoardTopBar(
    title: String,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    TopAppBar(
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold) },
        navigationIcon = {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Quay lại")
                }
            }
        },
        actions = actions,
        colors = boardBarColors(),
    )
}

@Composable
fun boardBarColors() = TopAppBarDefaults.topAppBarColors(
    containerColor = VC.Board,
    titleContentColor = Color.White,
    navigationIconContentColor = VC.Yellow,
    actionIconContentColor = VC.Yellow,
)

@Composable
fun SearchField(value: String, onChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = modifier.fillMaxWidth(),
        singleLine = true,
        placeholder = { Text(placeholder, color = VC.Gray) },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, tint = VC.Gray) },
        trailingIcon = {
            if (value.isNotEmpty()) {
                IconButton(onClick = { onChange("") }) {
                    Icon(Icons.Filled.Close, contentDescription = "Xoá tìm kiếm", tint = VC.Gray)
                }
            }
        },
        shape = RoundedCornerShape(12.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = Color.White.copy(alpha = 0.08f),
            unfocusedContainerColor = Color.White.copy(alpha = 0.08f),
            focusedBorderColor = VC.Yellow,
            unfocusedBorderColor = Color.Transparent,
            cursorColor = VC.Yellow,
        ),
    )
}

@Composable
fun Tag(text: String, color: Color) {
    Text(
        text,
        color = Color.White,
        fontSize = 9.sp,
        fontWeight = FontWeight.Black,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(color.copy(alpha = 0.85f))
            .padding(horizontal = 5.dp, vertical = 2.dp),
    )
}

@Composable
fun CircleIconButton(
    icon: ImageVector,
    contentDescription: String,
    tint: Color,
    background: Color,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(background)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = contentDescription, tint = tint, modifier = Modifier.size(18.dp))
    }
}

@Composable
fun SectionHeader(text: String) {
    Text(
        text,
        color = VC.Gray,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 6.dp),
    )
}

@Composable
fun ErrorBanner(message: String, onClose: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(VC.Red.copy(alpha = 0.18f))
            .padding(start = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(message, color = Color(0xFFFF8A80), fontSize = 14.sp, modifier = Modifier.weight(1f))
        IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Đóng", tint = Color(0xFFFF8A80)) }
    }
}

@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmText, color = VC.Red, fontWeight = FontWeight.Bold) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Hủy", color = Color.White) } },
    )
}
