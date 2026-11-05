import UIKit

// MARK: - Kết quả trích từ ảnh

struct ExtractedWord: Identifiable, Hashable {
    let id = UUID()
    var word: String
    var pronunciation: String
    var meaning: String
    /// true = nghĩa chép từ ảnh, false = AI tự điền
    var meaningFromImage: Bool
    /// Có lưu từ này hay không (người dùng bỏ tick ở màn xem trước)
    var include = true
}

struct ExtractionResult {
    let words: [ExtractedWord]
    let inputTokens: Int
    let outputTokens: Int
}

/// Đổi nhà cung cấp (Gemini, Claude...) chỉ cần viết thêm 1 struct theo protocol này.
protocol VocabExtractor {
    func extract(from image: UIImage) async throws -> ExtractionResult
}

enum ExtractorError: LocalizedError {
    case missingKey
    case invalidKey
    case rateLimited
    case modelNotFound(String)
    case badRequest(String)
    case server(String)
    case badResponse(String)

    var errorDescription: String? {
        switch self {
        case .missingKey: return "Chưa có Gemini API key. Vào Cài đặt AI để dán key."
        case .invalidKey: return "API key không hợp lệ hoặc chưa được bật Gemini API. Kiểm tra lại key trong Cài đặt AI."
        case .rateLimited: return "Đã chạm giới hạn lượt miễn phí của Gemini. Đợi 1 phút (hoặc sang ngày mai) rồi thử lại."
        case .modelNotFound(let m): return "Không tìm thấy model \"\(m)\". Vào Cài đặt AI đổi sang model Flash khác."
        case .badRequest(let msg): return "Yêu cầu bị từ chối: \(msg)"
        case .server(let msg): return "Máy chủ Gemini đang lỗi, thử lại sau. (\(msg))"
        case .badResponse(let msg): return "Không đọc được kết quả từ AI: \(msg)"
        }
    }
}

// MARK: - Gemini

struct GeminiVocabExtractor: VocabExtractor {
    let apiKey: String
    let model: String

    static let prompt = """
    The image is a photo of a page from an English learning material. In an app, the learner has painted over the new \
    English words/phrases they want to learn, using MAGENTA / PINK marks drawn in the app. A mark is either \
    (a) a thick translucent highlight painted over words, or (b) a thin magenta line drawn around words (a circle, \
    oval, box or freehand loop enclosing them, even if the loop is not fully closed).

    Task:
    1. Return ONLY the English words/phrases covered (fully or mostly) by a magenta highlight, or enclosed by a magenta loop. \
    A loop around a whole area (several words or lines) means the learner wants EVERY English word inside it: \
    return each distinct word inside the loop as its own entry, in reading order, without skipping any word \
    (only skip numbers, symbols and punctuation). Ignore all other text, \
    and ignore any marks that were already printed or drawn on the paper (e.g. yellow highlighter, pen circles). \
    If nothing is marked in magenta, return an empty list.
    2. "word": dictionary form, lowercase (keep capitals for proper nouns). If a mark covers only part of a word, take the \
    whole word. If one mark covers several consecutive words, keep them as one phrase when they form a phrase or \
    collocation (e.g. "take into account"), otherwise return them as separate words. Read the word itself even if the \
    mark makes it harder to see; use the surrounding sentence to resolve unclear letters.
    3. "pronunciation": American English IPA wrapped in slashes, e.g. /əˈkwaɪər/.
    4. "meaning": if the page shows a Vietnamese meaning for that word (handwritten or printed, next to the word or in the margin), \
    copy it exactly and set "meaningFromImage" to true. Otherwise write a short Vietnamese meaning that fits the sentence context \
    (at most 3 senses, separated by commas) and set "meaningFromImage" to false.
    5. No duplicates. Keep the order they appear on the page.
    """

    func extract(from image: UIImage) async throws -> ExtractionResult {
        guard let jpeg = image.resizedForUpload(maxSide: 1600).jpegData(compressionQuality: 0.8) else {
            throw ExtractorError.badResponse("không nén được ảnh")
        }
        do {
            return try await send(jpeg: jpeg, lowThinking: true)
        } catch ExtractorError.badRequest(let msg) where msg.lowercased().contains("think") {
            // Model không hỗ trợ thinkingLevel thì gửi lại không kèm cấu hình này
            return try await send(jpeg: jpeg, lowThinking: false)
        }
    }

    private func send(jpeg: Data, lowThinking: Bool) async throws -> ExtractionResult {
        let url = URL(string: "https://generativelanguage.googleapis.com/v1beta/models/\(model):generateContent")!
        var req = URLRequest(url: url, timeoutInterval: 60)
        req.httpMethod = "POST"
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.setValue(apiKey, forHTTPHeaderField: "x-goog-api-key")

        // Từ Gemini 3.6 trở đi temperature/top_p/top_k bị bỏ (sắp báo lỗi 400), nên không gửi nữa
        var generationConfig: [String: Any] = [
            "responseMimeType": "application/json",
            "responseSchema": Self.responseSchema
        ]
        // Việc đơn giản, để suy nghĩ ở mức thấp cho nhanh và ít token (3.8 Flash hỗ trợ low/medium/high)
        if lowThinking { generationConfig["thinkingConfig"] = ["thinkingLevel": "low"] }

        let body: [String: Any] = [
            "contents": [[
                "role": "user",
                "parts": [
                    ["text": Self.prompt],
                    ["inline_data": ["mime_type": "image/jpeg", "data": jpeg.base64EncodedString()]]
                ]
            ]],
            "generationConfig": generationConfig
        ]
        req.httpBody = try JSONSerialization.data(withJSONObject: body)

        let (data, response) = try await URLSession.shared.data(for: req)
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        guard (200..<300).contains(status) else { throw Self.mapError(status: status, data: data, model: model) }

        let decoded = try JSONDecoder().decode(GeminiResponse.self, from: data)
        let text = decoded.candidates?.first?.content?.parts?
            .filter { $0.thought != true }
            .compactMap { $0.text }
            .joined() ?? ""
        guard !text.isEmpty else {
            let reason = decoded.candidates?.first?.finishReason ?? decoded.promptFeedback?.blockReason ?? "trống"
            throw ExtractorError.badResponse("AI không trả về nội dung (\(reason))")
        }

        let payload: Payload
        do {
            payload = try JSONDecoder().decode(Payload.self, from: Data(Self.stripFences(text).utf8))
        } catch {
            throw ExtractorError.badResponse("JSON sai định dạng")
        }

        var seen = Set<String>()
        let words = payload.words.compactMap { w -> ExtractedWord? in
            let word = w.word.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !word.isEmpty, seen.insert(word.lowercased()).inserted else { return nil }
            return ExtractedWord(
                word: word,
                pronunciation: w.pronunciation.trimmingCharacters(in: .whitespacesAndNewlines),
                meaning: w.meaning.trimmingCharacters(in: .whitespacesAndNewlines),
                meaningFromImage: w.meaningFromImage)
        }

        let usage = decoded.usageMetadata
        return ExtractionResult(
            words: words,
            inputTokens: usage?.promptTokenCount ?? 0,
            outputTokens: (usage?.candidatesTokenCount ?? 0) + (usage?.thoughtsTokenCount ?? 0))
    }

    // MARK: Helpers

    private static let responseSchema: [String: Any] = [
        "type": "OBJECT",
        "properties": [
            "words": [
                "type": "ARRAY",
                "items": [
                    "type": "OBJECT",
                    "properties": [
                        "word": ["type": "STRING"],
                        "pronunciation": ["type": "STRING"],
                        "meaning": ["type": "STRING"],
                        "meaningFromImage": ["type": "BOOLEAN"]
                    ],
                    "required": ["word", "pronunciation", "meaning", "meaningFromImage"]
                ]
            ]
        ],
        "required": ["words"]
    ]

    private static func stripFences(_ s: String) -> String {
        var t = s.trimmingCharacters(in: .whitespacesAndNewlines)
        if t.hasPrefix("```") {
            t = t.replacingOccurrences(of: "```json", with: "").replacingOccurrences(of: "```", with: "")
        }
        return t.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private static func mapError(status: Int, data: Data, model: String) -> ExtractorError {
        let msg = (try? JSONDecoder().decode(GeminiErrorBody.self, from: data))?.error.message
            ?? String(data: data, encoding: .utf8) ?? "HTTP \(status)"
        switch status {
        case 400:
            if msg.localizedCaseInsensitiveContains("API key") { return .invalidKey }
            return .badRequest(msg)
        case 401, 403: return .invalidKey
        case 404: return .modelNotFound(model)
        case 429: return .rateLimited
        case 500...599: return .server("HTTP \(status)")
        default: return .badRequest(msg)
        }
    }

    private struct Payload: Decodable {
        struct Item: Decodable {
            let word: String
            let pronunciation: String
            let meaning: String
            let meaningFromImage: Bool
        }
        let words: [Item]
    }

    private struct GeminiResponse: Decodable {
        struct Candidate: Decodable {
            struct Content: Decodable {
                struct Part: Decodable { let text: String?; let thought: Bool? }
                let parts: [Part]?
            }
            let content: Content?
            let finishReason: String?
        }
        struct Usage: Decodable {
            let promptTokenCount: Int?
            let candidatesTokenCount: Int?
            let thoughtsTokenCount: Int?
        }
        struct Feedback: Decodable { let blockReason: String? }
        let candidates: [Candidate]?
        let usageMetadata: Usage?
        let promptFeedback: Feedback?
    }

    private struct GeminiErrorBody: Decodable {
        struct Inner: Decodable { let message: String }
        let error: Inner
    }
}

// MARK: - Resize ảnh trước khi gửi (ảnh 12MP gửi thẳng thì tốn token và chậm)

extension UIImage {
    func resizedForUpload(maxSide: CGFloat) -> UIImage {
        let longest = max(size.width, size.height)
        let scale = longest > maxSide ? maxSide / longest : 1
        let target = CGSize(width: (size.width * scale).rounded(), height: (size.height * scale).rounded())
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        // draw(in:) cũng xoay ảnh về đúng chiều nếu ảnh chụp dọc
        return UIGraphicsImageRenderer(size: target, format: format).image { _ in
            draw(in: CGRect(origin: .zero, size: target))
        }
    }
}
