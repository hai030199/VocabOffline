package com.hai.vocabhandbook.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hai.vocabhandbook.VocabViewModel
import com.hai.vocabhandbook.ai.AiSettings
import com.hai.vocabhandbook.ai.GeminiExtractor
import com.hai.vocabhandbook.ai.SecureStore

@Composable
fun AISettingsScreen(vm: VocabViewModel) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    var apiKey by rememberSaveable { mutableStateOf(SecureStore.readApiKey(context) ?: "") }
    var model by rememberSaveable { mutableStateOf(AiSettings.model(context)) }
    var consent by rememberSaveable { mutableStateOf(AiSettings.consent(context)) }
    var showKey by remember { mutableStateOf(false) }
    val stats = remember { AiSettings.stats(context) }

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = VC.Yellow,
        unfocusedBorderColor = Color.White.copy(alpha = 0.25f),
        cursorColor = VC.Yellow,
    )

    Scaffold(
        containerColor = VC.Board,
        topBar = {
            BoardTopBar("Cài đặt AI", onBack = { vm.onBack() }) {
                TextButton(onClick = {
                    SecureStore.saveApiKey(context, apiKey)
                    AiSettings.setModel(context, model)
                    AiSettings.setConsent(context, consent)
                    vm.onBack()
                }) { Text("Lưu", color = VC.Yellow, fontWeight = FontWeight.Bold, fontSize = 17.sp) }
            }
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Title("GEMINI API KEY")
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                placeholder = { Text("Dán API key vào đây") },
                singleLine = true,
                visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, keyboardType = KeyboardType.Password),
                trailingIcon = {
                    IconButton(onClick = { showKey = !showKey }) {
                        Icon(
                            if (showKey) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                            contentDescription = if (showKey) "Ẩn key" else "Hiện key",
                            tint = VC.Gray,
                        )
                    }
                },
                shape = RoundedCornerShape(10.dp),
                colors = fieldColors,
                modifier = Modifier.fillMaxWidth(),
            )
            TextButton(onClick = { uriHandler.openUri("https://aistudio.google.com/apikey") }) {
                Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, tint = VC.Cyan)
                Text("  Lấy key miễn phí ở Google AI Studio", color = VC.Cyan)
            }
            Hint("Key được mã hoá bằng Android Keystore và chỉ lưu trên máy này.")

            Title("MODEL")
            OutlinedTextField(
                value = model,
                onValueChange = { model = it },
                placeholder = { Text(AiSettings.DEFAULT_MODEL) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, keyboardType = KeyboardType.Ascii),
                shape = RoundedCornerShape(10.dp),
                colors = fieldColors,
                modifier = Modifier.fillMaxWidth(),
            )
            TextButton(onClick = { model = AiSettings.DEFAULT_MODEL }) {
                Text("Dùng mặc định (${AiSettings.DEFAULT_MODEL})", color = VC.Yellow)
            }
            Hint(
                "Chỉ đổi khi model này báo lỗi không tìm thấy hoặc hết miễn phí. " +
                    "Khi model chính lỗi mạng hoặc hết lượt, app tự thử lại bằng ${GeminiExtractor.FALLBACK_MODEL}."
            )

            Title("QUYỀN RIÊNG TƯ")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Cho phép gửi ảnh tới Google Gemini", color = Color.White, modifier = Modifier.weight(1f))
                Switch(
                    checked = consent,
                    onCheckedChange = { consent = it },
                    colors = SwitchDefaults.colors(checkedTrackColor = VC.Green),
                )
            }
            Hint("Tắt đi thì app sẽ hỏi lại trước lần quét ảnh tiếp theo. Ngoài lúc bạn bấm Quét, app không gửi dữ liệu nào ra ngoài.")

            Title("ĐÃ DÙNG")
            StatLine("Số lần quét ảnh", "%,d".format(stats.requests))
            StatLine("Token vào", "%,d".format(stats.inputTokens))
            StatLine("Token ra", "%,d".format(stats.outputTokens))
        }
    }
}

@Composable
private fun Title(text: String) {
    Text(text, color = VC.Gray, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp))
}

@Composable
private fun Hint(text: String) {
    Text(text, color = VC.Gray, fontSize = 12.sp)
}

@Composable
private fun StatLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, color = Color.White, modifier = Modifier.weight(1f))
        Text(value, color = VC.Gray)
    }
}
