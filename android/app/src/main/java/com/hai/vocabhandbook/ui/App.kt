package com.hai.vocabhandbook.ui

import android.content.ActivityNotFoundException
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hai.vocabhandbook.Screen
import com.hai.vocabhandbook.VocabViewModel
import com.hai.vocabhandbook.data.PhotoTarget
import java.io.File

/** Các thao tác mở file / máy ảnh / thư viện ảnh, dùng chung cho mọi màn hình */
class AppActions(
    val pickExcel: () -> Unit,
    val pickConjunctionExcel: () -> Unit,
    val takePhoto: (PhotoTarget) -> Unit,
    val pickPhoto: (PhotoTarget) -> Unit,
)

private val EXCEL_TYPES = arrayOf(
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    "application/vnd.ms-excel",
    "application/octet-stream",
)

@Composable
fun VocabApp(vm: VocabViewModel = viewModel()) {
    val context = LocalContext.current
    var photoTarget by rememberSaveable { mutableStateOf(PhotoTarget.VOCAB) }
    var cameraUri by rememberSaveable { mutableStateOf<Uri?>(null) }

    val excelLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        vm.importExcelUris(uris)
    }
    val conjunctionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importConjunctionUri(uri)
    }
    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) vm.onPhotoPicked(uri, photoTarget)
    }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val uri = cameraUri
        if (ok && uri != null) vm.onPhotoPicked(uri, photoTarget)
    }

    val openGallery = { target: PhotoTarget ->
        photoTarget = target
        galleryLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }
    val actions = AppActions(
        pickExcel = { excelLauncher.launch(EXCEL_TYPES) },
        pickConjunctionExcel = { conjunctionLauncher.launch(EXCEL_TYPES) },
        pickPhoto = openGallery,
        takePhoto = { target ->
            photoTarget = target
            val dir = File(context.cacheDir, "camera").apply { mkdirs() }
            val file = File(dir, "photo_${System.currentTimeMillis()}.jpg")
            val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
            cameraUri = uri
            try {
                cameraLauncher.launch(uri)
            } catch (e: ActivityNotFoundException) {
                openGallery(target)   // máy không có app chụp ảnh thì chọn ảnh có sẵn
            }
        },
    )

    // Giữ trạng thái (ô tìm kiếm, vị trí cuộn...) của từng màn khi quay lại
    val stateHolder = rememberSaveableStateHolder()

    if (vm.showImportPreview) {
        ImportPreviewScreen(vm)
        return
    }

    val top = vm.stack.last()
    BackHandler(enabled = vm.stack.size > 1) { vm.onBack() }

    stateHolder.SaveableStateProvider(key = top.toString()) {
        when (top) {
            Screen.Home -> HomeScreen(vm, actions)
            is Screen.Words -> WordsScreen(vm, top.filter, actions)
            Screen.Conjunctions -> ConjunctionsScreen(vm, actions)
            Screen.ReferenceImage -> ReferenceImageScreen(vm, actions)
            Screen.PhotoMark -> PhotoMarkScreen(vm)
            Screen.AISettings -> AISettingsScreen(vm)
        }
    }
}
