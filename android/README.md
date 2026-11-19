# Vocab Handbook cho Android

Bản Android viết bằng Kotlin và Jetpack Compose, cùng tính năng với bản iOS:
- Nhập file Excel từ vựng (chọn nhiều file, xem trước từ mới và từ cũ)
- Tìm toàn bộ hoặc tìm trong từng buổi, không cần gõ dấu
- Ẩn nghĩa để tự kiểm tra, nghe đọc từng từ hoặc đọc cả buổi
- Liên từ: bảng tổng quan, chi tiết theo nhóm, ảnh tham khảo zoom được
- Chụp ảnh, tô hoặc khoanh từ, Gemini đọc và lấy nghĩa, lưu vào "Từ thêm trong quá trình học"

## Build ra APK

Không cần cài Android Studio. Mỗi lần đẩy code trong thư mục `android/` lên nhánh `main`,
GitHub Actions (file `.github/workflows/android.yml`) tự build và đăng file `VocabHandbook.apk`
lên trang Releases của repo, tag `android-latest`.

Khoá ký APK lấy từ 2 secret `ANDROID_KEYSTORE_BASE64` và `ANDROID_KEYSTORE_PASSWORD`.
Thiếu secret thì APK ký tạm bằng khoá debug.

## Build trên máy (nếu muốn)

Mở thư mục `android/` bằng Android Studio, hoặc chạy:

```
cd android
./gradlew assembleDebug
```

File ra ở `app/build/outputs/apk/debug/app-debug.apk`.

## Cấu trúc code

- `data/XlsxReader.kt`: đọc file .xlsx không cần thư viện ngoài
- `VocabViewModel.kt`: kho từ, liên từ, import, lưu JSON, điều hướng
- `ai/GeminiExtractor.kt`: gửi ảnh đã tô cho Gemini, tự thử lại và chuyển sang Flash-Lite khi lỗi
- `ai/AiSettings.kt`: API key mã hoá bằng Android Keystore, model, quyền gửi ảnh
- `ui/`: các màn hình
