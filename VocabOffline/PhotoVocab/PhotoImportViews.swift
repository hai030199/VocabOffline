import SwiftUI
import UIKit
import PencilKit

/// Ảnh vừa chụp/chọn, dùng làm item cho .sheet(item:)
struct CapturedPhoto: Identifiable {
    let id = UUID()
    let image: UIImage
}

enum PhotoAction { case camera, library, settings }

// MARK: - Camera

struct CameraPicker: UIViewControllerRepresentable {
    let onPick: (UIImage) -> Void
    @Environment(\.dismiss) private var dismiss

    func makeUIViewController(context: Context) -> UIImagePickerController {
        let picker = UIImagePickerController()
        picker.sourceType = .camera
        picker.delegate = context.coordinator
        return picker
    }

    func updateUIViewController(_ uiViewController: UIImagePickerController, context: Context) {}

    func makeCoordinator() -> Coordinator { Coordinator(self) }

    final class Coordinator: NSObject, UIImagePickerControllerDelegate, UINavigationControllerDelegate {
        let parent: CameraPicker
        init(_ parent: CameraPicker) { self.parent = parent }

        func imagePickerController(_ picker: UIImagePickerController,
                                   didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey: Any]) {
            if let img = info[.originalImage] as? UIImage { parent.onPick(img) }
            parent.dismiss()
        }

        func imagePickerControllerDidCancel(_ picker: UIImagePickerController) { parent.dismiss() }
    }
}

// MARK: - Lớp tô (PencilKit) đặt đè lên ảnh

/// Màu bút tô trong app. Chọn hồng tím để không lẫn với dạ quang vàng có sẵn trong sách.
let appMarkColor = UIColor(red: 1.0, green: 0.18, blue: 0.67, alpha: 1)

/// Bút tô (dạ quang, nét to) / Bút khoanh (nét mảnh để khoanh vùng) / Tẩy
enum MarkTool { case marker, circle, eraser }

struct MarkupCanvas: UIViewRepresentable {
    @Binding var drawing: PKDrawing
    var tool: MarkTool

    func makeUIView(context: Context) -> PKCanvasView {
        let c = PKCanvasView()
        c.drawingPolicy = .anyInput            // vẽ bằng ngón tay
        c.backgroundColor = .clear
        c.isOpaque = false
        c.isScrollEnabled = false
        c.overrideUserInterfaceStyle = .light   // tránh PencilKit tự đảo màu mực ở dark mode
        c.drawing = drawing
        c.delegate = context.coordinator
        return c
    }

    func updateUIView(_ c: PKCanvasView, context: Context) {
        context.coordinator.parent = self
        switch tool {
        case .marker: c.tool = PKInkingTool(.marker, color: appMarkColor, width: 26)
        case .circle: c.tool = PKInkingTool(.pen, color: appMarkColor, width: 5)
        case .eraser: c.tool = PKEraserTool(.vector)
        }
        // Hoàn tác / xoá hết từ bên ngoài thì đồng bộ vào canvas
        if c.drawing.strokes.count != drawing.strokes.count { c.drawing = drawing }
    }

    func makeCoordinator() -> Coordinator { Coordinator(self) }

    final class Coordinator: NSObject, PKCanvasViewDelegate {
        var parent: MarkupCanvas
        init(_ parent: MarkupCanvas) { self.parent = parent }
        func canvasViewDrawingDidChange(_ canvasView: PKCanvasView) { parent.drawing = canvasView.drawing }
    }
}

// MARK: - Luồng: tô từ, quét, xem trước

struct PhotoVocabSheet: View {
    let image: UIImage
    @ObservedObject var viewModel: VocabularyViewModel
    @ObservedObject var speech: SpeechManager
    let onSaved: (FilterItem) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var phase: Phase = .marking
    @State private var drawing = PKDrawing()
    @State private var tool: MarkTool = .marker
    @State private var canvasSize: CGSize = .zero
    @State private var markedImage: UIImage? = nil
    @State private var items: [ExtractedWord] = []
    @State private var errorText = ""
    @State private var usageText = ""
    @State private var showSettings = false
    @State private var showFullImage = false
    @State private var showConsent = false

    private enum Phase { case marking, loading, result, error }
    private let boardColor = Color(red: 0.05, green: 0.2, blue: 0.1)
    private let cream = Color(red: 1, green: 0.9, blue: 0.6)

    private var selectedCount: Int { items.filter(\.include).count }
    private var hasStrokes: Bool { !drawing.strokes.isEmpty }

    var body: some View {
        NavigationStack {
            ZStack {
                boardColor.ignoresSafeArea()
                switch phase {
                case .marking: markingView
                case .loading: loadingView
                case .error: errorView
                case .result: resultView
                }
            }
            .navigationTitle(phase == .marking ? "Tô từ cần học" : "Từ mới từ ảnh")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Hủy") { dismiss() }.foregroundColor(.red)
                }
                ToolbarItem(placement: .primaryAction) {
                    switch phase {
                    case .marking:
                        Button("Quét") { Task { await run() } }
                            .font(.headline.bold()).foregroundColor(.yellow).disabled(!hasStrokes)
                    case .result where !items.isEmpty:
                        Button("Lưu (\(selectedCount))") { save() }
                            .font(.headline.bold()).foregroundColor(.yellow).disabled(selectedCount == 0)
                    default:
                        EmptyView()
                    }
                }
            }
            .sheet(isPresented: $showSettings) { AISettingsView() }
            .sheet(isPresented: $showFullImage) { fullImageView }
            .sheet(isPresented: $showConsent) {
                AIConsentView {
                    AISettings.geminiConsent = true
                    showConsent = false
                    Task { await run() }
                }
            }
        }
        .preferredColorScheme(.dark)
        .interactiveDismissDisabled()   // vuốt xuống để tô không làm đóng màn
    }

    // MARK: Tô từ

    private var markingView: some View {
        VStack(spacing: 0) {
            Text("Tô lên từ muốn thêm, hoặc chọn Bút khoanh để khoanh tròn quanh từ hay cả một vùng.")
                .font(.footnote).foregroundColor(.white.opacity(0.85))
                .multilineTextAlignment(.center).padding(.horizontal, 16).padding(.vertical, 8)

            GeometryReader { geo in
                let fit = Self.aspectFit(image.size, in: geo.size)
                ZStack {
                    Image(uiImage: image).resizable().scaledToFit()
                    MarkupCanvas(drawing: $drawing, tool: tool)
                }
                .frame(width: fit.width, height: fit.height)
                .position(x: geo.size.width / 2, y: geo.size.height / 2)
                .onAppear { canvasSize = fit }
                .onChange(of: geo.size) { _, s in canvasSize = Self.aspectFit(image.size, in: s) }
            }

            HStack(spacing: 8) {
                toolButton("Tô", "highlighter", selected: tool == .marker) { tool = .marker }
                toolButton("Khoanh", "lasso", selected: tool == .circle) { tool = .circle }
                toolButton("Tẩy", "eraser", selected: tool == .eraser) { tool = .eraser }
                Spacer()
                Button {
                    if !drawing.strokes.isEmpty { drawing.strokes.removeLast() }
                } label: { Image(systemName: "arrow.uturn.backward").frame(width: 44, height: 44) }
                    .foregroundColor(.white).disabled(!hasStrokes).accessibilityLabel("Hoàn tác")
                Button { drawing = PKDrawing() } label: {
                    Image(systemName: "trash").frame(width: 44, height: 44)
                }.foregroundColor(.red).disabled(!hasStrokes).accessibilityLabel("Xoá hết nét tô")
            }
            .padding(.horizontal, 12).padding(.vertical, 6)
            .background(Color.black.opacity(0.3))
        }
    }

    private func toolButton(_ title: String, _ icon: String, selected: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Label(title, systemImage: icon).font(.subheadline.bold()).lineLimit(1)
                .padding(.horizontal, 10).frame(height: 40)
                .background(selected ? Color(appMarkColor).opacity(0.35) : Color.white.opacity(0.1))
                .foregroundColor(.white).cornerRadius(10)
        }.buttonStyle(.plain)
    }

    // MARK: Đang quét / lỗi / kết quả

    private var loadingView: some View {
        VStack(spacing: 20) {
            Image(uiImage: markedImage ?? image).resizable().scaledToFit().frame(maxHeight: 280)
                .cornerRadius(10).opacity(0.7)
            ProgressView("AI đang đọc các từ bạn vừa tô...").tint(.yellow).foregroundColor(.white)
        }.padding()
    }

    private var errorView: some View {
        VStack(spacing: 16) {
            Image(systemName: "exclamationmark.triangle.fill").font(.system(size: 48)).foregroundColor(.orange)
            Text(errorText).foregroundColor(.white).multilineTextAlignment(.center).padding(.horizontal)
            HStack(spacing: 10) {
                pillButton("Thử lại", "arrow.clockwise", primary: true) { Task { await run() } }
                pillButton("Tô lại", "highlighter", primary: false) { phase = .marking }
                pillButton("Cài đặt AI", "gearshape.fill", primary: false) { showSettings = true }
            }
        }.padding()
    }

    private func pillButton(_ title: String, _ icon: String, primary: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Label(title, systemImage: icon).font(.subheadline.bold())
                .padding(.horizontal, 14).padding(.vertical, 10)
                .background(primary ? Color.yellow : Color.white.opacity(0.15))
                .foregroundColor(primary ? .black : .white).cornerRadius(10)
        }.buttonStyle(.plain)
    }

    private var resultView: some View {
        VStack(spacing: 0) {
            HStack(spacing: 12) {
                Button { showFullImage = true } label: {
                    Image(uiImage: markedImage ?? image).resizable().scaledToFill()
                        .frame(width: 56, height: 56).clipped().cornerRadius(8)
                }.buttonStyle(.plain).accessibilityLabel("Xem ảnh đã tô")
                VStack(alignment: .leading, spacing: 4) {
                    Text("Tìm thấy \(items.count) từ bạn đã tô").font(.subheadline.bold()).foregroundColor(.white)
                    Text("Lưu vào: \(VocabularyViewModel.learnedFileName)").font(.caption).foregroundColor(.green)
                    if !usageText.isEmpty { Text(usageText).font(.caption2).foregroundColor(.gray) }
                }
                Spacer()
                Button { phase = .marking } label: {
                    Label("Tô lại", systemImage: "highlighter").font(.caption.bold())
                }.foregroundColor(.yellow)
            }.padding(12)

            if items.isEmpty {
                VStack(spacing: 10) {
                    Image(systemName: "highlighter").font(.system(size: 40)).foregroundColor(.gray)
                    Text("AI không đọc được từ nào trong vùng tô.\nThử tô lại đè kín chữ hơn.")
                        .multilineTextAlignment(.center).foregroundColor(.gray)
                }.frame(maxHeight: .infinity)
            } else {
                List {
                    ForEach($items) { $item in
                        row($item)
                            .listRowBackground(item.include ? Color.white.opacity(0.07) : Color.white.opacity(0.02))
                    }
                }.scrollContentBackground(.hidden)
            }
        }
    }

    private func row(_ item: Binding<ExtractedWord>) -> some View {
        let w = item.wrappedValue
        let dup = viewModel.isDuplicate(text: w.word)
        return HStack(alignment: .top, spacing: 10) {
            Button { item.include.wrappedValue.toggle() } label: {
                Image(systemName: w.include ? "checkmark.circle.fill" : "circle")
                    .font(.system(size: 24)).foregroundColor(w.include ? .yellow : .gray)
                    .frame(width: 32, height: 32)
            }.buttonStyle(.plain).accessibilityLabel(w.include ? "Bỏ chọn" : "Chọn")

            VStack(alignment: .leading, spacing: 4) {
                HStack(spacing: 6) {
                    Text(w.word.capitalized).font(.system(size: 18, weight: .bold, design: .rounded))
                        .foregroundColor(.white).fixedSize(horizontal: false, vertical: true)
                    if dup { badge("CŨ", .red) }
                }
                Text(w.pronunciation).font(.system(size: 13, design: .monospaced)).foregroundColor(.gray)
                TextField("Nghĩa", text: item.meaning, axis: .vertical)
                    .font(.system(size: 15)).foregroundColor(cream)
                if w.meaningFromImage { badge("NGHĨA TỪ ẢNH", .green) } else { badge("AI ĐIỀN NGHĨA", .purple) }
            }
            Spacer(minLength: 0)
            Button { speech.speak(w.word) } label: {
                Image(systemName: "speaker.wave.2.fill").foregroundColor(.blue).frame(width: 36, height: 36)
            }.buttonStyle(.plain).accessibilityLabel("Phát âm")
        }
        .padding(.vertical, 4)
        .opacity(w.include ? 1 : 0.5)
    }

    private func badge(_ text: String, _ color: Color) -> some View {
        Text(text).font(.system(size: 9, weight: .heavy)).foregroundColor(.white)
            .padding(.horizontal, 5).padding(.vertical, 2).background(color.opacity(0.8)).cornerRadius(4)
    }

    private var fullImageView: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            Image(uiImage: markedImage ?? image).resizable().scaledToFit()
        }.onTapGesture { showFullImage = false }
    }

    // MARK: Actions

    /// Ghép ảnh gốc + nét tô thành 1 ảnh để gửi cho AI
    private func renderMarkedImage() -> UIImage {
        let base = image.resizedForUpload(maxSide: 1600)
        guard canvasSize.width > 0 else { return base }
        let scale = base.size.width / canvasSize.width
        var overlay = UIImage()
        UITraitCollection(userInterfaceStyle: .light).performAsCurrent {
            overlay = drawing.image(from: CGRect(origin: .zero, size: canvasSize), scale: scale)
        }
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        return UIGraphicsImageRenderer(size: base.size, format: format).image { _ in
            base.draw(at: .zero)
            overlay.draw(in: CGRect(origin: .zero, size: base.size))
        }
    }

    @MainActor
    private func run() async {
        guard hasStrokes else { phase = .marking; return }
        // Chưa đồng ý gửi ảnh cho AI bên thứ ba thì hỏi trước, không gửi gì cả
        guard AISettings.geminiConsent else { showConsent = true; return }
        let marked = renderMarkedImage()
        markedImage = marked
        phase = .loading
        guard let key = KeychainStore.read(.geminiAPIKey), !key.isEmpty else {
            errorText = ExtractorError.missingKey.localizedDescription
            phase = .error
            return
        }
        let extractor: VocabExtractor = GeminiVocabExtractor(apiKey: key, model: AISettings.model)
        do {
            let r = try await extractor.extract(from: marked)
            AIUsageStats.record(input: r.inputTokens, output: r.outputTokens)
            items = r.words.map { found in
                var item = found
                item.include = !viewModel.isDuplicate(text: found.word)   // từ đã có thì mặc định bỏ tick
                return item
            }
            usageText = "\(AISettings.model), \(r.inputTokens + r.outputTokens) token"
            phase = .result
        } catch let e as ExtractorError {
            errorText = e.localizedDescription
            phase = .error
        } catch {
            errorText = "Lỗi kết nối: \(error.localizedDescription)"
            phase = .error
        }
    }

    private func save() {
        let filter = viewModel.addLearnedWords(items.filter(\.include))
        onSaved(filter)
        dismiss()
    }

    private static func aspectFit(_ size: CGSize, in box: CGSize) -> CGSize {
        guard size.width > 0, size.height > 0, box.width > 0, box.height > 0 else { return .zero }
        let s = min(box.width / size.width, box.height / size.height)
        return CGSize(width: size.width * s, height: size.height * s)
    }
}

// MARK: - Cài đặt AI

struct AISettingsView: View {
    @Environment(\.dismiss) private var dismiss
    @State private var apiKey = KeychainStore.read(.geminiAPIKey) ?? ""
    @State private var model = AISettings.model
    @State private var consent = AISettings.geminiConsent
    private let stats = AIUsageStats.load()

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    SecureField("Dán API key vào đây", text: $apiKey)
                        .textInputAutocapitalization(.never).autocorrectionDisabled()
                    Link(destination: URL(string: "https://aistudio.google.com/apikey")!) {
                        Label("Lấy key miễn phí ở Google AI Studio", systemImage: "arrow.up.right.square")
                    }
                } header: { Text("Gemini API key") } footer: {
                    Text("Key được lưu trong Keychain của máy, không nằm trong file dữ liệu.")
                }

                Section {
                    TextField(AISettings.defaultModel, text: $model)
                        .textInputAutocapitalization(.never).autocorrectionDisabled()
                    Button("Dùng mặc định (\(AISettings.defaultModel))") { model = AISettings.defaultModel }
                } header: { Text("Model") } footer: {
                    Text("Chỉ đổi khi model này báo lỗi không tìm thấy hoặc hết miễn phí.")
                }

                Section {
                    Toggle("Cho phép gửi ảnh tới Google Gemini", isOn: $consent)
                } header: { Text("Quyền riêng tư") } footer: {
                    Text("Tắt đi thì app sẽ hỏi lại trước lần quét ảnh tiếp theo. Ngoài lúc bạn bấm Quét, app không gửi dữ liệu nào ra ngoài.")
                }

                Section("Đã dùng") {
                    LabeledContent("Số lần quét ảnh", value: "\(stats.requests)")
                    LabeledContent("Token vào", value: stats.inputTokens.formatted())
                    LabeledContent("Token ra", value: stats.outputTokens.formatted())
                }
            }
            .navigationTitle("Cài đặt AI").navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Đóng") { dismiss() } }
                ToolbarItem(placement: .primaryAction) {
                    Button("Lưu") {
                        KeychainStore.save(apiKey, for: .geminiAPIKey)
                        AISettings.model = model
                        AISettings.geminiConsent = consent
                        dismiss()
                    }.bold()
                }
            }
        }
        .preferredColorScheme(.dark)
    }
}

// MARK: - Xin đồng ý trước khi gửi ảnh cho AI bên thứ ba (App Store guideline 5.1.2(i))

struct AIConsentView: View {
    let onAgree: () -> Void
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    Image(systemName: "hand.raised.fill").font(.system(size: 44)).foregroundColor(.yellow)
                    Text("Gửi ảnh cho AI để nhận diện từ").font(.title2.bold())

                    info("Gửi gì", "Ảnh bạn vừa chụp hoặc chọn, kèm các nét tô của bạn. Ảnh được thu nhỏ trước khi gửi. App không gửi sổ từ vựng hay thông tin nào khác của bạn.")
                    info("Gửi cho ai", "Google, qua dịch vụ Gemini API, bằng API key của chính bạn.")
                    info("Để làm gì", "Đọc các từ bạn đã tô, lấy phiên âm và nghĩa tiếng Việt.")
                    info("Lưu ý", "Với gói miễn phí của Gemini API, Google có thể dùng nội dung gửi lên để cải thiện sản phẩm của họ. Đừng chụp trang có thông tin cá nhân.")
                    info("Thay đổi", "Bạn có thể tắt quyền này bất cứ lúc nào trong Cài đặt AI.")

                    Link("Điều khoản Gemini API của Google", destination: URL(string: "https://ai.google.dev/gemini-api/terms")!)
                        .font(.footnote)
                }
                .padding(20)
            }
            .safeAreaInset(edge: .bottom) {
                VStack(spacing: 8) {
                    Button(action: onAgree) {
                        Text("Đồng ý và quét").font(.headline).frame(maxWidth: .infinity).frame(height: 50)
                            .background(Color.yellow).foregroundColor(.black).cornerRadius(12)
                    }
                    Button("Không đồng ý") { dismiss() }.frame(height: 44).foregroundColor(.white)
                }
                .padding(.horizontal, 20).padding(.top, 8).background(.ultraThinMaterial)
            }
            .navigationBarTitleDisplayMode(.inline)
        }
        .preferredColorScheme(.dark)
    }

    private func info(_ title: String, _ text: String) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(title).font(.subheadline.bold()).foregroundColor(.yellow)
            Text(text).font(.subheadline).foregroundColor(.white.opacity(0.9)).fixedSize(horizontal: false, vertical: true)
        }
    }
}
