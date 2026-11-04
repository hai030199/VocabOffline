import SwiftUI
import CoreXLSX
import AVFoundation
import UniformTypeIdentifiers
import PhotosUI

// MARK: - Data Models
struct VocabularyWord: Identifiable, Hashable, Codable {
    let id: UUID
    let word: String
    let pronunciation: String
    let meaning: String
    let session: String
    let fileName: String
    /// nil = import từ Excel, "image" = nghĩa chép từ ảnh, "ai" = AI tự điền nghĩa
    let meaningSource: String?
    init(id: UUID = UUID(), word: String, pronunciation: String, meaning: String, session: String, fileName: String,
         meaningSource: String? = nil) {
        self.id = id; self.word = word; self.pronunciation = pronunciation
        self.meaning = meaning; self.session = session; self.fileName = fileName
        self.meaningSource = meaningSource
    }
}

struct Conjunction: Identifiable, Hashable, Codable {
    let id: UUID
    let english: String
    let vietnamese: String
    let category: String
    let fileName: String
    init(id: UUID = UUID(), english: String, vietnamese: String, category: String, fileName: String) {
        self.id = id; self.english = english; self.vietnamese = vietnamese
        self.category = category; self.fileName = fileName
    }
}

struct FilterItem: Hashable { let fileName: String; let session: String }

extension VocabularyWord {
    /// Tìm không phân biệt hoa thường và dấu: "dong y" khớp "đồng ý"
    func matches(_ query: String) -> Bool {
        let q = Self.fold(query.trimmingCharacters(in: .whitespaces))
        if q.isEmpty { return true }
        return Self.fold(word).contains(q) || Self.fold(meaning).contains(q)
            || Self.fold(pronunciation).contains(q)
    }
    /// Bỏ dấu + hoa thường; "đ" không phải dấu nên phải đổi tay sang "d"
    static func fold(_ s: String) -> String {
        s.folding(options: [.caseInsensitive, .diacriticInsensitive], locale: Locale(identifier: "vi_VN"))
            .replacingOccurrences(of: "đ", with: "d").replacingOccurrences(of: "Đ", with: "d")
    }
}

// MARK: - Speech Manager
class SpeechManager: NSObject, ObservableObject, AVSpeechSynthesizerDelegate {
    private let synthesizer = AVSpeechSynthesizer()
    @Published var isSpeakingAll = false
    private var queue: [String] = []
    override init() { super.init(); synthesizer.delegate = self }
    func configureAudioSession() {
        do {
            let s = AVAudioSession.sharedInstance()
            try s.setCategory(.playback, mode: .default, options: [.duckOthers])
            try s.setActive(true, options: [])
        } catch { print("Lỗi âm thanh: \(error)") }
    }
    func speak(_ text: String, rate: Float = 0.45) {
        stopAll(); configureAudioSession()
        let u = AVSpeechUtterance(string: text)
        u.voice = AVSpeechSynthesisVoice(language: "en-US"); u.rate = rate
        synthesizer.speak(u)
    }
    func speakAll(words: [String]) {
        stopAll(); configureAudioSession(); queue = words
        if let f = queue.first { isSpeakingAll = true; speakNext(f) }
    }
    func stopAll() { isSpeakingAll = false; queue.removeAll(); synthesizer.stopSpeaking(at: .immediate) }
    private func speakNext(_ text: String) {
        let u = AVSpeechUtterance(string: text)
        u.voice = AVSpeechSynthesisVoice(language: "en-US"); u.rate = 0.45
        synthesizer.speak(u)
    }
    func speechSynthesizer(_ s: AVSpeechSynthesizer, didFinish u: AVSpeechUtterance) {
        if isSpeakingAll {
            if !queue.isEmpty { queue.removeFirst() }
            if let n = queue.first { speakNext(n) } else { isSpeakingAll = false }
        }
    }
}

// MARK: - ViewModel (JSON Local DB)
class VocabularyViewModel: ObservableObject {
    @Published var words: [VocabularyWord] = []
    @Published var conjunctions: [Conjunction] = []
    @Published var isLoading = false
    @Published var errorMessage = ""
    @Published var isParsingConjunctions = false
    @Published var pendingWords: [VocabularyWord] = []
    @Published var pendingFileName: String = ""
    @Published var showImportPreview = false
    /// Các file Excel đang chờ xem trước (chọn nhiều file thì xử lý lần lượt từng file)
    private var importQueue: [(data: Data, fileName: String)] = []

    private let fileURL: URL = {
        let d = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        return d.appendingPathComponent("saved_vocab.json")
    }()
    private let conjunctionURL: URL = {
        let d = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        return d.appendingPathComponent("saved_conjunctions.json")
    }()

    init() { loadWords(); loadConjunctions() }

    func isDuplicateWord(_ word: VocabularyWord) -> Bool { isDuplicate(text: word.word) }
    func isDuplicate(text: String) -> Bool {
        let t = text.trimmingCharacters(in: .whitespaces).lowercased()
        return words.contains { $0.word.lowercased() == t }
    }

    // MARK: Từ thêm từ ảnh chụp
    static let learnedFileName = "Từ thêm trong quá trình học"
    private static let sessionFormatter: DateFormatter = {
        let f = DateFormatter(); f.dateFormat = "yyyy-MM-dd"; return f   // dạng này sắp xếp đúng theo ngày
    }()

    /// Lưu các từ đã chọn vào danh mục "Từ thêm trong quá trình học", buổi = ngày hôm nay
    @discardableResult
    func addLearnedWords(_ items: [ExtractedWord]) -> FilterItem {
        let session = Self.sessionFormatter.string(from: Date())
        let new = items.map {
            VocabularyWord(word: $0.word, pronunciation: $0.pronunciation, meaning: $0.meaning,
                           session: session, fileName: Self.learnedFileName,
                           meaningSource: $0.meaningFromImage ? "image" : "ai")
        }
        words.append(contentsOf: new)
        saveWords()
        return FilterItem(fileName: Self.learnedFileName, session: session)
    }

    func parseExcelPreview(data: Data, fileName: String) {
        isLoading = true; errorMessage = ""
        DispatchQueue.global(qos: .userInitiated).async {
            var parsed: [VocabularyWord] = []
            do {
                let file = try XLSXFile(data: data)
                guard let wb = try file.parseWorkbooks().first else { throw CocoaError(.fileReadCorruptFile) }
                let rels = try file.parseWorksheetPathsAndNames(workbook: wb)
                for (sheetName, path) in rels {
                    guard let sn = sheetName else { continue }
                    let ws = try file.parseWorksheet(at: path)
                    guard let ss = try file.parseSharedStrings() else { continue }
                    for row in ws.data?.rows ?? [] {
                        var cols: [(String, String)] = []
                        for c in row.cells {
                            let v: String
                            if let val = c.value, let i = Int(val), i < ss.items.count {
                                v = ss.items[i].text ?? ""
                            } else if let val = c.value { v = val } else { v = "" }
                            cols.append((String(c.reference.column.value), v))
                        }
                        for (idx, item) in cols.enumerated() {
                            if item.1.contains("/") && item.1.count > 2 {
                                let w = idx > 0 ? cols[idx-1].1 : ""
                                let m = idx < cols.count - 1 ? cols[idx+1].1 : ""
                                if !w.isEmpty && !w.allSatisfy({ $0.isNumber }) {
                                    parsed.append(VocabularyWord(
                                        word: w.trimmingCharacters(in: .whitespacesAndNewlines),
                                        pronunciation: item.1.trimmingCharacters(in: .whitespacesAndNewlines),
                                        meaning: m.trimmingCharacters(in: .whitespacesAndNewlines),
                                        session: sn, fileName: fileName))
                                }
                                break
                            }
                        }
                    }
                }
                DispatchQueue.main.async {
                    self.isLoading = false
                    if parsed.isEmpty {
                        self.errorMessage = "Không tìm thấy từ nào trong file \(fileName)."
                        self.processNextImport()
                        return
                    }
                    self.pendingWords = parsed
                    self.pendingFileName = fileName
                    self.showImportPreview = true
                }
            } catch {
                DispatchQueue.main.async {
                    self.errorMessage = "Lỗi đọc file \(fileName): \(error.localizedDescription)"
                    self.isLoading = false
                    self.processNextImport()
                }
            }
        }
    }

    func enqueueExcelImports(_ files: [(data: Data, fileName: String)]) {
        importQueue.append(contentsOf: files)
        if !isLoading && !showImportPreview { processNextImport() }
    }
    private func processNextImport() {
        guard !importQueue.isEmpty else { return }
        let next = importQueue.removeFirst()
        parseExcelPreview(data: next.data, fileName: next.fileName)
    }

    func confirmImport(importAll: Bool) {
        if importAll {
            // Import tất cả = thay toàn bộ dữ liệu cũ của file này bằng bản mới
            words.removeAll { $0.fileName == pendingFileName }
            words.append(contentsOf: pendingWords)
        } else {
            // Chỉ từ mới = giữ nguyên dữ liệu cũ, thêm những từ chưa có
            words.append(contentsOf: pendingWords.filter { !isDuplicateWord($0) })
        }
        saveWords()
        finishPreview()
    }
    func cancelImport() { finishPreview() }
    private func finishPreview() {
        pendingWords.removeAll(); pendingFileName = ""; showImportPreview = false
        // Đợi sheet đóng hẳn rồi mới mở file tiếp theo
        if !importQueue.isEmpty {
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.6) { self.processNextImport() }
        }
    }

    func parseExcel(data: Data, fileName: String, isConjunctionFile: Bool = false) {
        isParsingConjunctions = true; errorMessage = ""
        DispatchQueue.global(qos: .userInitiated).async {
            do {
                let file = try XLSXFile(data: data)
                guard let wb = try file.parseWorkbooks().first else { throw CocoaError(.fileReadCorruptFile) }
                let rels = try file.parseWorksheetPathsAndNames(workbook: wb)
                var parsed: [Conjunction] = []
                for (sn, path) in rels {
                    guard let sn = sn else { continue }
                    let ws = try file.parseWorksheet(at: path)
                    guard let ss = try file.parseSharedStrings() else { continue }
                    for row in ws.data?.rows ?? [] {
                        var cells: [String] = []
                        for c in row.cells {
                            if let v = c.value, let i = Int(v), i < ss.items.count {
                                cells.append(ss.items[i].text ?? "")
                            } else if let v = c.value { cells.append(v) } else { cells.append("") }
                        }
                        if cells.count >= 2 {
                            let e = cells[0].trimmingCharacters(in: .whitespacesAndNewlines)
                            let v = cells[1].trimmingCharacters(in: .whitespacesAndNewlines)
                            if !e.isEmpty && !v.isEmpty {
                                parsed.append(Conjunction(english: e, vietnamese: v, category: sn, fileName: fileName))
                            }
                        }
                    }
                }
                DispatchQueue.main.async {
                    self.conjunctions.removeAll { $0.fileName == fileName }
                    self.conjunctions.append(contentsOf: parsed)
                    self.isParsingConjunctions = false
                    self.saveConjunctions()
                }
            } catch {
                DispatchQueue.main.async {
                    self.errorMessage = "Lỗi: \(error.localizedDescription)"
                    self.isParsingConjunctions = false
                }
            }
        }
    }

    func clearAll() { words.removeAll(); errorMessage = ""; saveWords() }
    func clearConjunctions() { conjunctions.removeAll(); saveConjunctions() }
    func deleteWord(word: VocabularyWord) { words.removeAll { $0.id == word.id }; saveWords() }
    func deleteConjunction(_ c: Conjunction) { conjunctions.removeAll { $0.id == c.id }; saveConjunctions() }

    private func saveWords() {
        do { let d = try JSONEncoder().encode(words); try d.write(to: fileURL) }
        catch { print("Lỗi lưu: \(error)") }
    }
    private func loadWords() {
        do { let d = try Data(contentsOf: fileURL); words = try JSONDecoder().decode([VocabularyWord].self, from: d) }
        catch { }
    }
    private func saveConjunctions() {
        do { let d = try JSONEncoder().encode(conjunctions); try d.write(to: conjunctionURL) }
        catch { print("Lỗi lưu LT: \(error)") }
    }
    private func loadConjunctions() {
        do { let d = try Data(contentsOf: conjunctionURL); conjunctions = try JSONDecoder().decode([Conjunction].self, from: d) }
        catch { conjunctions = VocabularyViewModel.defaultConjunctions; saveConjunctions() }
    }

    static let defaultConjunctions: [Conjunction] = [
        .init(english: "because", vietnamese: "bởi vì", category: "NGUYÊN NHÂN", fileName: "Mặc định"),
        .init(english: "because of", vietnamese: "bởi vì (trước danh từ)", category: "NGUYÊN NHÂN", fileName: "Mặc định"),
        .init(english: "since", vietnamese: "bởi vì, từ khi", category: "NGUYÊN NHÂN", fileName: "Mặc định"),
        .init(english: "as", vietnamese: "bởi vì, khi, như", category: "NGUYÊN NHÂN", fileName: "Mặc định"),
        .init(english: "due to", vietnamese: "do, vì", category: "NGUYÊN NHÂN", fileName: "Mặc định"),
        .init(english: "thanks to", vietnamese: "nhờ vào", category: "NGUYÊN NHÂN", fileName: "Mặc định"),
        .init(english: "owing to", vietnamese: "do, vì", category: "NGUYÊN NHÂN", fileName: "Mặc định"),
        .init(english: "on account of", vietnamese: "vì, do", category: "NGUYÊN NHÂN", fileName: "Mặc định"),
        .init(english: "so", vietnamese: "vì vậy, do đó", category: "KẾT QUẢ", fileName: "Mặc định"),
        .init(english: "therefore", vietnamese: "do đó, vì vậy", category: "KẾT QUẢ", fileName: "Mặc định"),
        .init(english: "thus", vietnamese: "như vậy, vì thế", category: "KẾT QUẢ", fileName: "Mặc định"),
        .init(english: "hence", vietnamese: "do đó, từ đó", category: "KẾT QUẢ", fileName: "Mặc định"),
        .init(english: "as a result", vietnamese: "kết quả là", category: "KẾT QUẢ", fileName: "Mặc định"),
        .init(english: "consequently", vietnamese: "do đó, kết quả", category: "KẾT QUẢ", fileName: "Mặc định"),
        .init(english: "for this reason", vietnamese: "vì lý do này", category: "KẾT QUẢ", fileName: "Mặc định"),
        .init(english: "although", vietnamese: "mặc dù", category: "NHƯỢNG BỘ", fileName: "Mặc định"),
        .init(english: "though", vietnamese: "mặc dù", category: "NHƯỢNG BỘ", fileName: "Mặc định"),
        .init(english: "even though", vietnamese: "ngay cả khi", category: "NHƯỢNG BỘ", fileName: "Mặc định"),
        .init(english: "in spite of", vietnamese: "mặc dù (trước danh từ)", category: "NHƯỢNG BỘ", fileName: "Mặc định"),
        .init(english: "despite", vietnamese: "mặc dù (trước danh từ)", category: "NHƯỢNG BỘ", fileName: "Mặc định"),
        .init(english: "however", vietnamese: "tuy nhiên", category: "NHƯỢNG BỘ", fileName: "Mặc định"),
        .init(english: "nevertheless", vietnamese: "tuy nhiên, mặc dù vậy", category: "NHƯỢNG BỘ", fileName: "Mặc định"),
        .init(english: "nonetheless", vietnamese: "tuy nhiên", category: "NHƯỢNG BỘ", fileName: "Mặc định"),
        .init(english: "on the other hand", vietnamese: "mặt khác", category: "NHƯỢNG BỘ", fileName: "Mặc định"),
        .init(english: "on the contrary", vietnamese: "ngược lại", category: "NHƯỢNG BỘ", fileName: "Mặc định"),
        .init(english: "whereas", vietnamese: "trong khi, mặc dù", category: "NHƯỢNG BỘ", fileName: "Mặc định"),
        .init(english: "while", vietnamese: "trong khi, mặc dù", category: "NHƯỢNG BỘ", fileName: "Mặc định"),
        .init(english: "and", vietnamese: "và", category: "ĐIỀU HỢP", fileName: "Mặc định"),
        .init(english: "both...and", vietnamese: "cả...và", category: "ĐIỀU HỢP", fileName: "Mặc định"),
        .init(english: "not only...but also", vietnamese: "không những...mà còn", category: "ĐIỀU HỢP", fileName: "Mặc định"),
        .init(english: "either...or", vietnamese: "hoặc...hoặc", category: "ĐIỀU HỢP", fileName: "Mặc định"),
        .init(english: "neither...nor", vietnamese: "không...cũng không", category: "ĐIỀU HỢP", fileName: "Mặc định"),
        .init(english: "not...but", vietnamese: "không...mà là", category: "ĐIỀU HỢP", fileName: "Mặc định"),
        .init(english: "as well as", vietnamese: "cũng như, và", category: "ĐIỀU HỢP", fileName: "Mặc định"),
        .init(english: "but", vietnamese: "nhưng", category: "ĐỐI LẬP", fileName: "Mặc định"),
        .init(english: "however", vietnamese: "tuy nhiên", category: "ĐỐI LẬP", fileName: "Mặc định"),
        .init(english: "yet", vietnamese: "tuy nhiên, nhưng", category: "ĐỐI LẬP", fileName: "Mặc định"),
        .init(english: "still", vietnamese: "vẫn, tuy vậy", category: "ĐỐI LẬP", fileName: "Mặc định"),
        .init(english: "in contrast", vietnamese: "trái lại", category: "ĐỐI LẬP", fileName: "Mặc định"),
        .init(english: "on the contrary", vietnamese: "trái ngược lại", category: "ĐỐI LẬP", fileName: "Mặc định"),
        .init(english: "if", vietnamese: "nếu", category: "ĐIỀU KIỆN", fileName: "Mặc định"),
        .init(english: "unless", vietnamese: "trừ khi, nếu không", category: "ĐIỀU KIỆN", fileName: "Mặc định"),
        .init(english: "as long as", vietnamese: "miễn là, chừng nào", category: "ĐIỀU KIỆN", fileName: "Mặc định"),
        .init(english: "provided that", vietnamese: "với điều kiện là", category: "ĐIỀU KIỆN", fileName: "Mặc định"),
        .init(english: "providing that", vietnamese: "với điều kiện là", category: "ĐIỀU KIỆN", fileName: "Mặc định"),
        .init(english: "in case", vietnamese: "trong trường hợp, phòng khi", category: "ĐIỀU KIỆN", fileName: "Mặc định"),
        .init(english: "in case of", vietnamese: "trong trường hợp (trước danh từ)", category: "ĐIỀU KIỆN", fileName: "Mặc định"),
        .init(english: "suppose", vietnamese: "giả sử", category: "ĐIỀU KIỆN", fileName: "Mặc định"),
        .init(english: "on condition that", vietnamese: "với điều kiện là", category: "ĐIỀU KIỆN", fileName: "Mặc định"),
        .init(english: "when", vietnamese: "khi", category: "THỜI GIAN", fileName: "Mặc định"),
        .init(english: "while", vietnamese: "trong khi", category: "THỜI GIAN", fileName: "Mặc định"),
        .init(english: "before", vietnamese: "trước khi", category: "THỜI GIAN", fileName: "Mặc định"),
        .init(english: "after", vietnamese: "sau khi", category: "THỜI GIAN", fileName: "Mặc định"),
        .init(english: "since", vietnamese: "từ khi", category: "THỜI GIAN", fileName: "Mặc định"),
        .init(english: "until", vietnamese: "cho đến khi", category: "THỜI GIAN", fileName: "Mặc định"),
        .init(english: "till", vietnamese: "cho đến khi", category: "THỜI GIAN", fileName: "Mặc định"),
        .init(english: "as soon as", vietnamese: "ngay khi", category: "THỜI GIAN", fileName: "Mặc định"),
        .init(english: "by the time", vietnamese: "vào lúc", category: "THỜI GIAN", fileName: "Mặc định"),
        .init(english: "once", vietnamese: "ngay khi, một khi", category: "THỜI GIAN", fileName: "Mặc định"),
        .init(english: "the moment", vietnamese: "ngay khi", category: "THỜI GIAN", fileName: "Mặc định"),
        .init(english: "so as to", vietnamese: "để", category: "MỤC ĐÍCH", fileName: "Mặc định"),
        .init(english: "in order to", vietnamese: "để", category: "MỤC ĐÍCH", fileName: "Mặc định"),
        .init(english: "so that", vietnamese: "để mà", category: "MỤC ĐÍCH", fileName: "Mặc định"),
        .init(english: "in order that", vietnamese: "để mà", category: "MỤC ĐÍCH", fileName: "Mặc định"),
        .init(english: "for fear that", vietnamese: "sợ rằng, e rằng", category: "MỤC ĐÍCH", fileName: "Mặc định"),
        .init(english: "lest", vietnamese: "sợ rằng, e rằng", category: "MỤC ĐÍCH", fileName: "Mặc định"),
        .init(english: "as...as", vietnamese: "như, bằng (so sánh bằng)", category: "SO SÁNH", fileName: "Mặc định"),
        .init(english: "than", vietnamese: "hơn (so sánh hơn)", category: "SO SÁNH", fileName: "Mặc định"),
        .init(english: "the more...the more", vietnamese: "càng...càng", category: "SO SÁNH", fileName: "Mặc định"),
        .init(english: "compared to", vietnamese: "so với", category: "SO SÁNH", fileName: "Mặc định"),
        .init(english: "so...that", vietnamese: "quá...đến nỗi", category: "MỨC ĐỘ", fileName: "Mặc định"),
        .init(english: "such...that", vietnamese: "quá...đến nỗi", category: "MỨC ĐỘ", fileName: "Mặc định"),
        .init(english: "too...to", vietnamese: "quá...không thể", category: "MỨC ĐỘ", fileName: "Mặc định"),
        .init(english: "enough...to", vietnamese: "đủ...để", category: "MỨC ĐỘ", fileName: "Mặc định"),
        .init(english: "besides", vietnamese: "ngoài ra, hơn nữa", category: "BỔ SUNG", fileName: "Mặc định"),
        .init(english: "moreover", vietnamese: "hơn nữa, ngoài ra", category: "BỔ SUNG", fileName: "Mặc định"),
        .init(english: "furthermore", vietnamese: "hơn nữa, ngoài ra", category: "BỔ SUNG", fileName: "Mặc định"),
        .init(english: "in addition", vietnamese: "thêm vào đó, ngoài ra", category: "BỔ SUNG", fileName: "Mặc định"),
        .init(english: "in addition to", vietnamese: "ngoài ra (trước danh từ)", category: "BỔ SUNG", fileName: "Mặc định"),
        .init(english: "additionally", vietnamese: "thêm vào đó", category: "BỔ SUNG", fileName: "Mặc định"),
        .init(english: "for example", vietnamese: "ví dụ", category: "VÍ DỤ", fileName: "Mặc định"),
        .init(english: "for instance", vietnamese: "ví dụ", category: "VÍ DỤ", fileName: "Mặc định"),
        .init(english: "such as", vietnamese: "như là", category: "VÍ DỤ", fileName: "Mặc định"),
        .init(english: "namely", vietnamese: "cụ thể là", category: "VÍ DỤ", fileName: "Mặc định"),
        .init(english: "in short", vietnamese: "tóm lại", category: "TÓM TẮT", fileName: "Mặc định"),
        .init(english: "in brief", vietnamese: "nói tóm lại", category: "TÓM TẮT", fileName: "Mặc định"),
        .init(english: "in conclusion", vietnamese: "kết luận lại", category: "TÓM TẮT", fileName: "Mặc định"),
        .init(english: "to sum up", vietnamese: "tóm lại", category: "TÓM TẮT", fileName: "Mặc định"),
        .init(english: "all in all", vietnamese: "tất cả nói lên", category: "TÓM TẮT", fileName: "Mặc định"),
        .init(english: "or", vietnamese: "hoặc, hay là", category: "LỰA CHỌN", fileName: "Mặc định"),
        .init(english: "otherwise", vietnamese: "nếu không thì", category: "LỰA CHỌN", fileName: "Mặc định"),
        .init(english: "or else", vietnamese: "hoặc là, nếu không thì", category: "LỰA CHỌN", fileName: "Mặc định"),
        .init(english: "similarly", vietnamese: "tương tự, cũng vậy", category: "TƯƠNG TỰ", fileName: "Mặc định"),
        .init(english: "likewise", vietnamese: "cũng vậy, tương tự", category: "TƯƠNG TỰ", fileName: "Mặc định"),
        .init(english: "in the same way", vietnamese: "cùng cách như vậy", category: "TƯƠNG TỰ", fileName: "Mặc định"),
        .init(english: "firstly", vietnamese: "đầu tiên", category: "LIỆT KÊ", fileName: "Mặc định"),
        .init(english: "secondly", vietnamese: "thứ hai", category: "LIỆT KÊ", fileName: "Mặc định"),
        .init(english: "thirdly", vietnamese: "thứ ba", category: "LIỆT KÊ", fileName: "Mặc định"),
        .init(english: "finally", vietnamese: "cuối cùng", category: "LIỆT KÊ", fileName: "Mặc định"),
        .init(english: "lastly", vietnamese: "cuối cùng", category: "LIỆT KÊ", fileName: "Mặc định"),
        .init(english: "next", vietnamese: "tiếp theo", category: "LIỆT KÊ", fileName: "Mặc định"),
        .init(english: "then", vietnamese: "sau đó, rồi", category: "LIỆT KÊ", fileName: "Mặc định")
    ]
}

// MARK: - Main UI
struct ContentView: View {
    @StateObject private var viewModel = VocabularyViewModel()
    @StateObject private var speech = SpeechManager()
    @State private var showFilePicker = false
    @State private var showConjunctionsView = false
    @State private var searchText = ""        // ô tìm trong buổi đang mở
    @State private var globalSearch = ""      // ô tìm ngoài sidebar, tìm toàn bộ file
    @State private var selectedFilter: FilterItem? = nil
    @State private var expandedFiles: Set<String> = []
    @State private var hideAllMeanings = false
    // Chụp ảnh lấy từ mới
    @State private var showCamera = false
    @State private var showPhotoLibrary = false
    @State private var showAISettings = false
    @State private var photoItem: PhotosPickerItem? = nil
    @State private var pendingPhoto: CapturedPhoto? = nil
    @State private var capturedPhoto: CapturedPhoto? = nil
    let boardColor = Color(red: 0.05, green: 0.2, blue: 0.1)
    let allFilter = FilterItem(fileName: "ALL_FILES", session: "ALL")

    var fileStructure: [String: [String]] {
        var d = [String: Set<String>]()
        for w in viewModel.words { d[w.fileName, default: []].insert(w.session) }
        return d.mapValues { Array($0).sorted() }
    }
    func handlePhotoAction(_ action: PhotoAction) {
        switch action {
        case .camera:
            if UIImagePickerController.isSourceTypeAvailable(.camera) { showCamera = true }
            else { showPhotoLibrary = true }   // simulator không có camera
        case .library: showPhotoLibrary = true
        case .settings: showAISettings = true
        }
    }
    // Lọc theo buổi đang chọn trước, rồi mới tìm trong phạm vi đó
    var filteredWords: [VocabularyWord] {
        var r = viewModel.words
        if let f = selectedFilter, f != allFilter {
            r = r.filter { $0.fileName == f.fileName && $0.session == f.session }
        }
        return searchText.isEmpty ? r : r.filter { $0.matches(searchText) }
    }
    var detailSearchPrompt: String {
        if let f = selectedFilter, f != allFilter { return "Tìm trong \(f.session)..." }
        return "Tìm trong tất cả..."
    }
    var body: some View {
        NavigationSplitView {
            SidebarView(viewModel: viewModel, selectedFilter: $selectedFilter,
                        expandedFiles: $expandedFiles, fileStructure: fileStructure,
                        speech: speech, showFilePicker: $showFilePicker,
                        showConjunctionsView: $showConjunctionsView, allFilter: allFilter,
                        globalSearch: $globalSearch, onPhotoAction: handlePhotoAction)
        } detail: {
            DetailView(viewModel: viewModel, speech: speech, showFilePicker: $showFilePicker,
                        searchText: $searchText, selectedFilter: $selectedFilter,
                        hideAllMeanings: $hideAllMeanings, filteredWords: filteredWords,
                        searchPrompt: detailSearchPrompt)
        }
        .onChange(of: selectedFilter) { _, _ in searchText = "" }
        // Camera: đợi màn camera đóng hẳn rồi mới mở màn xem trước, tránh 2 màn tranh nhau
        .fullScreenCover(isPresented: $showCamera, onDismiss: {
            if let p = pendingPhoto { capturedPhoto = p; pendingPhoto = nil }
        }) {
            CameraPicker { img in pendingPhoto = CapturedPhoto(image: img) }.ignoresSafeArea()
        }
        .photosPicker(isPresented: $showPhotoLibrary, selection: $photoItem, matching: .images)
        .onChange(of: photoItem) { _, item in
            guard let item else { return }
            Task {
                if let data = try? await item.loadTransferable(type: Data.self), let img = UIImage(data: data) {
                    capturedPhoto = CapturedPhoto(image: img)
                } else {
                    viewModel.errorMessage = "Không đọc được ảnh đã chọn."
                }
                photoItem = nil
            }
        }
        .sheet(item: $capturedPhoto) { photo in
            PhotoVocabSheet(image: photo.image, viewModel: viewModel, speech: speech) { filter in
                expandedFiles.insert(filter.fileName)
                selectedFilter = filter
            }
        }
        .sheet(isPresented: $showAISettings) { AISettingsView() }
        .preferredColorScheme(.dark)
        .sheet(isPresented: $showConjunctionsView) { ConjunctionsView(viewModel: viewModel, speech: speech) }
        .sheet(isPresented: $viewModel.showImportPreview) { ImportPreviewView(viewModel: viewModel, speech: speech) }
        .fileImporter(isPresented: $showFilePicker, allowedContentTypes: [.spreadsheet], allowsMultipleSelection: true) { result in
            switch result {
            case .success(let files):
                var loaded: [(data: Data, fileName: String)] = []
                for url in files {
                    let ok = url.startAccessingSecurityScopedResource()
                    defer { if ok { url.stopAccessingSecurityScopedResource() } }
                    if let d = try? Data(contentsOf: url) {
                        loaded.append((d, url.deletingPathExtension().lastPathComponent))
                    } else {
                        viewModel.errorMessage = "Không đọc được file \(url.lastPathComponent)."
                    }
                }
                viewModel.enqueueExcelImports(loaded)
            case .failure(let e): viewModel.errorMessage = e.localizedDescription
            }
        }
    }
}

// MARK: - Import Preview
struct ImportPreviewView: View {
    @ObservedObject var viewModel: VocabularyViewModel
    @ObservedObject var speech: SpeechManager
    @Environment(\.dismiss) var dismiss
    @State private var showOnlyNew = false
    let boardColor = Color(red: 0.05, green: 0.2, blue: 0.1)
    var newWords: [VocabularyWord] { viewModel.pendingWords.filter { !viewModel.isDuplicateWord($0) } }
    var dupWords: [VocabularyWord] { viewModel.pendingWords.filter { viewModel.isDuplicateWord($0) } }
    var displayWords: [VocabularyWord] { showOnlyNew ? newWords : viewModel.pendingWords }
    var body: some View {
        NavigationStack {
            ZStack {
                boardColor.ignoresSafeArea()
                if viewModel.pendingWords.isEmpty {
                    ProgressView("Đang quét file...").foregroundColor(.white).tint(.yellow).scaleEffect(1.2)
                } else {
                    VStack(spacing: 0) {
                        HStack(spacing: 12) {
                            VStack(spacing: 4) {
                                Text("\(viewModel.pendingWords.count)").font(.system(size: 24, weight: .bold))
                                Text("Tổng").font(.caption2)
                            }.frame(maxWidth: .infinity).padding(.vertical, 10)
                            .background(Color.white.opacity(0.1)).cornerRadius(10)
                            VStack(spacing: 4) {
                                Text("\(newWords.count)").font(.system(size: 24, weight: .bold)).foregroundColor(.green)
                                Text("Mới").font(.caption2)
                            }.frame(maxWidth: .infinity).padding(.vertical, 10)
                            .background(Color.green.opacity(0.15)).cornerRadius(10)
                            VStack(spacing: 4) {
                                Text("\(dupWords.count)").font(.system(size: 24, weight: .bold)).foregroundColor(.red)
                                Text("Cũ").font(.caption2)
                            }.frame(maxWidth: .infinity).padding(.vertical, 10)
                            .background(Color.red.opacity(0.15)).cornerRadius(10)
                        }.padding(.horizontal, 12).padding(.top, 8)
                        HStack {
                            Text("📋 \(viewModel.pendingFileName)").font(.caption.bold()).foregroundColor(.yellow)
                            Spacer()
                            Toggle(isOn: $showOnlyNew) { Text("Chỉ từ mới").font(.caption.bold()) }
                                .toggleStyle(.button).tint(.green)
                        }.padding(.horizontal, 12).padding(.vertical, 4)
                        List {
                            ForEach(displayWords, id: \.id) { w in
                                let isDup = viewModel.isDuplicateWord(w)
                                HStack(spacing: 10) {
                                    Text(isDup ? "CŨ" : "MỚI").font(.system(size: 10, weight: .heavy))
                                        .foregroundColor(.white).padding(.horizontal, 6).padding(.vertical, 3)
                                        .background(isDup ? Color.red : Color.green).cornerRadius(4)
                                    VStack(alignment: .leading, spacing: 2) {
                                        Text(w.word.capitalized).font(.system(size: 16, weight: .bold, design: .rounded)).foregroundColor(.white)
                                            .lineLimit(w.word.contains(" ") ? nil : 1).minimumScaleFactor(0.6)
                                            .fixedSize(horizontal: false, vertical: true)
                                        Text(w.pronunciation).font(.system(size: 12, design: .monospaced)).foregroundColor(.gray)
                                        Text(w.meaning).font(.system(size: 13)).foregroundColor(Color(red: 1, green: 0.9, blue: 0.6)).lineLimit(1)
                                    }
                                    Spacer()
                                    Button(action: { speech.speak(w.word) }) {
                                        Image(systemName: "speaker.wave.2.fill").font(.system(size: 14)).foregroundColor(.blue)
                                    }.buttonStyle(.plain)
                                }.padding(.vertical, 4)
                                .listRowBackground(isDup ? Color.red.opacity(0.08) : Color.green.opacity(0.06))
                            }
                        }.scrollContentBackground(.hidden).background(boardColor)
                    }
                }
            }
            .navigationTitle("Quét từ vựng").navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Hủy") { viewModel.cancelImport(); dismiss() }.foregroundColor(.red)
                }
                ToolbarItem(placement: .primaryAction) {
                    Menu {
                        Button(action: { viewModel.confirmImport(importAll: true); dismiss() }) {
                            Label("Import tất cả (\(viewModel.pendingWords.count))", systemImage: "tray.and.arrow.down.fill")
                        }
                        Button(action: { viewModel.confirmImport(importAll: false); dismiss() }) {
                            Label("Chỉ từ mới (\(newWords.count))", systemImage: "plus.circle.fill")
                        }.disabled(newWords.isEmpty)
                    } label: { Text("Import").font(.headline.bold()).foregroundColor(.yellow) }
                }
            }
        }
    }
}

// MARK: - Sidebar
struct SidebarView: View {
    @ObservedObject var viewModel: VocabularyViewModel
    @Binding var selectedFilter: FilterItem?
    @Binding var expandedFiles: Set<String>
    let fileStructure: [String: [String]]
    @ObservedObject var speech: SpeechManager
    @Binding var showFilePicker: Bool
    @Binding var showConjunctionsView: Bool
    let allFilter: FilterItem
    @Binding var globalSearch: String
    let onPhotoAction: (PhotoAction) -> Void
    @State private var showClearConfirm = false
    let boardColor = Color(red: 0.05, green: 0.2, blue: 0.1)
    var globalResults: [VocabularyWord] { viewModel.words.filter { $0.matches(globalSearch) } }
    var body: some View {
        List(selection: $selectedFilter) {
            if !globalSearch.isEmpty {
                Section("KẾT QUẢ TRONG TẤT CẢ FILE (\(globalResults.count))") {
                    if globalResults.isEmpty {
                        Text("Không tìm thấy từ nào").foregroundColor(.gray)
                    }
                    ForEach(globalResults) { w in
                        // Bấm vào kết quả để mở đúng buổi chứa từ đó
                        NavigationLink(value: FilterItem(fileName: w.fileName, session: w.session)) {
                            HStack(spacing: 10) {
                                VStack(alignment: .leading, spacing: 3) {
                                    Text(w.word.capitalized).font(.system(size: 17, weight: .bold, design: .rounded))
                                        .foregroundColor(.white).fixedSize(horizontal: false, vertical: true)
                                    Text(w.meaning).font(.system(size: 14))
                                        .foregroundColor(Color(red: 1, green: 0.9, blue: 0.6)).lineLimit(2)
                                    Text("\(w.fileName) › \(w.session)").font(.caption).foregroundColor(.green)
                                }
                                Spacer()
                                Button(action: { speech.speak(w.word) }) {
                                    Image(systemName: "speaker.wave.2.fill").foregroundColor(.blue)
                                }.buttonStyle(.plain)
                            }
                        }
                    }
                }
            } else {
            Section("TỔNG HỢP") {
                NavigationLink(value: allFilter) {
                    HStack { Text("Tất cả các file").fontWeight(.bold); Spacer(); Text("\(viewModel.words.count)").foregroundColor(.yellow) }
                }
                Button(action: { showConjunctionsView = true }) {
                    HStack {
                        Image(systemName: "link.circle.fill").foregroundColor(.orange)
                        Text("Liên từ (Conjunctions)").fontWeight(.semibold)
                        Spacer()
                        if !viewModel.conjunctions.isEmpty { Text("\(viewModel.conjunctions.count)").foregroundColor(.orange) }
                    }
                }.buttonStyle(.plain)
            }
            Section("THEO FILE EXCEL") {
                ForEach(fileStructure.keys.sorted(), id: \.self) { fn in
                    DisclosureGroup(isExpanded: Binding(
                        get: { expandedFiles.contains(fn) },
                        set: { v in if v { expandedFiles.insert(fn) } else { expandedFiles.remove(fn) } }
                    )) {
                        ForEach(fileStructure[fn] ?? [], id: \.self) { s in
                            NavigationLink(value: FilterItem(fileName: fn, session: s)) {
                                HStack { Text(s); Spacer(); Text("\(viewModel.words.filter { $0.fileName == fn && $0.session == s }.count)").foregroundColor(.gray) }
                            }
                        }
                    } label: { Text(fn).fontWeight(.semibold).foregroundColor(.green) }
                }
            }
            }
        }.scrollContentBackground(.hidden).background(boardColor)
        .navigationTitle("SỔ TỪ VỰNG")
        .searchable(text: $globalSearch, placement: .navigationBarDrawer(displayMode: .always),
                    prompt: "Tìm trong tất cả file Excel")
        .confirmationDialog("Xoá toàn bộ \(viewModel.words.count) từ vựng?", isPresented: $showClearConfirm,
                            titleVisibility: .visible) {
            Button("Xoá tất cả", role: .destructive) {
                speech.stopAll(); viewModel.clearAll(); selectedFilter = nil; expandedFiles.removeAll()
            }
            Button("Hủy", role: .cancel) {}
        } message: { Text("Thao tác này không hoàn tác được.") }
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                HStack {
                    Menu {
                        Button { onPhotoAction(.camera) } label: { Label("Chụp ảnh trang sách", systemImage: "camera.fill") }
                        Button { onPhotoAction(.library) } label: { Label("Chọn ảnh có sẵn", systemImage: "photo.on.rectangle") }
                        Divider()
                        Button { onPhotoAction(.settings) } label: { Label("Cài đặt AI", systemImage: "gearshape") }
                    } label: {
                        Image(systemName: "camera.viewfinder").foregroundColor(.yellow)
                    }.accessibilityLabel("Lấy từ mới từ ảnh")
                    Button(action: { showFilePicker = true }) { Image(systemName: "plus.circle.fill").foregroundColor(.yellow) }
                    Button(action: { showClearConfirm = true }) {
                        Image(systemName: "trash").foregroundColor(.red)
                    }.disabled(viewModel.words.isEmpty).accessibilityLabel("Xoá tất cả từ vựng")
                }
            }
        }
    }
}

// MARK: - Detail View
struct DetailView: View {
    @ObservedObject var viewModel: VocabularyViewModel
    @ObservedObject var speech: SpeechManager
    @Binding var showFilePicker: Bool
    @Binding var searchText: String
    @Binding var selectedFilter: FilterItem?
    @Binding var hideAllMeanings: Bool
    let filteredWords: [VocabularyWord]
    let searchPrompt: String
    let boardColor = Color(red: 0.05, green: 0.2, blue: 0.1)
    var body: some View {
        ZStack {
            boardColor.ignoresSafeArea()
            if viewModel.isLoading {
                ProgressView("Đang quét file...").foregroundColor(.white).tint(.yellow).scaleEffect(1.2)
            } else if viewModel.words.isEmpty {
                EmptyStateView(showFilePicker: $showFilePicker, errorMessage: viewModel.errorMessage)
            } else {
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 12) {
                        if filteredWords.isEmpty {
                            Text("Không tìm thấy từ nào 😔").foregroundColor(.gray).frame(maxWidth: .infinity).padding(.top, 50)
                        } else {
                            ForEach(filteredWords, id: \.id) { w in
                                WordCardView(word: w, speech: speech, onDelete: { viewModel.deleteWord(word: w) }, hideAllMeanings: $hideAllMeanings)
                            }
                        }
                    }.padding(12)
                }
            }
        }
        .searchable(text: $searchText, prompt: searchPrompt)
        .toolbar {
            ToolbarItemGroup(placement: .automatic) {
                Button(action: { hideAllMeanings.toggle() }) {
                    Image(systemName: hideAllMeanings ? "eye.slash.fill" : "eye.fill")
                        .foregroundColor(hideAllMeanings ? .red : .yellow).font(.title2)
                }
                Button(action: { speech.isSpeakingAll ? speech.stopAll() : speech.speakAll(words: filteredWords.map { $0.word }) }) {
                    Image(systemName: speech.isSpeakingAll ? "stop.circle.fill" : "play.circle.fill")
                        .foregroundColor(speech.isSpeakingAll ? .red : .yellow).font(.title2)
                }
                Button(action: { showFilePicker = true }) {
                    Label("Thêm file", systemImage: "plus.circle.fill").foregroundColor(.yellow)
                }
            }
        }
    }
}

// MARK: - Empty State
struct EmptyStateView: View {
    @Binding var showFilePicker: Bool
    let errorMessage: String
    var body: some View {
        VStack(spacing: 25) {
            Image(systemName: "book.closed").font(.system(size: 70)).foregroundColor(.gray.opacity(0.5))
            Text("Chưa có dữ liệu").font(.title2).foregroundColor(.white)
            Button(action: { showFilePicker = true }) {
                Label("Thêm file Excel", systemImage: "tablecells")
                    .padding(.horizontal, 30).padding(.vertical, 15)
                    .background(Color.yellow).foregroundColor(.black).cornerRadius(10).font(.headline)
            }
            if !errorMessage.isEmpty { Text(errorMessage).foregroundColor(.red).padding() }
        }
    }
}

// MARK: - Word Card
struct WordCardView: View {
    let word: VocabularyWord
    @ObservedObject var speech: SpeechManager
    let onDelete: () -> Void
    @Binding var hideAllMeanings: Bool
    @State private var isCardHidden = false
    var shouldHideMeaning: Bool { hideAllMeanings ? !isCardHidden : isCardHidden }
    let cardColor = Color.white.opacity(0.08)
    // Cụm nhiều từ: xuống dòng theo khoảng trắng. Từ đơn: giữ 1 dòng, thu nhỏ chữ thay vì bẻ giữa từ
    private var wordText: some View {
        Text(word.word.capitalized)
            .font(.system(size: 22, weight: .bold, design: .rounded)).foregroundColor(.white)
            .lineLimit(word.word.contains(" ") ? nil : 1)
            .minimumScaleFactor(0.6)
            .fixedSize(horizontal: false, vertical: true)
    }
    private var pronunciationText: some View {
        Text(word.pronunciation)
            .font(.system(size: 15, weight: .medium, design: .monospaced)).foregroundColor(Color(white: 0.85))
            .lineLimit(word.pronunciation.contains(" ") ? nil : 1)
            .minimumScaleFactor(0.7)
            .fixedSize(horizontal: false, vertical: true)
    }
    var body: some View {
        HStack(alignment: .center, spacing: 12) {
            VStack(alignment: .leading, spacing: 6) {
                // Vừa 1 dòng thì để từ + phiên âm cạnh nhau, không vừa thì phiên âm xuống dòng nguyên khối
                ViewThatFits(in: .horizontal) {
                    HStack(alignment: .firstTextBaseline, spacing: 10) { wordText; pronunciationText }
                    VStack(alignment: .leading, spacing: 2) { wordText; pronunciationText }
                }
                if shouldHideMeaning {
                    Text("(Đã ẩn nghĩa)").font(.system(size: 17)).foregroundColor(.gray.opacity(0.4)).lineLimit(1)
                } else {
                    HStack(spacing: 6) {
                        Text(word.meaning).font(.system(size: 19)).foregroundColor(Color(red: 1, green: 0.9, blue: 0.6)).lineLimit(1)
                        if word.meaningSource == "ai" {
                            Text("AI").font(.system(size: 9, weight: .heavy)).foregroundColor(.white)
                                .padding(.horizontal, 4).padding(.vertical, 1).background(Color.purple.opacity(0.8)).cornerRadius(3)
                                .accessibilityLabel("Nghĩa do AI điền")
                        }
                    }
                }
            }
            Spacer()
            HStack(spacing: 8) {
                Button(action: { isCardHidden.toggle() }) {
                    Image(systemName: shouldHideMeaning ? "eye.slash.fill" : "eye.fill").font(.system(size: 15)).foregroundColor(.yellow)
                        .frame(width: 36, height: 36).background(Color.white.opacity(0.2)).clipShape(Circle())
                }.buttonStyle(PlainButtonStyle())
                Button(action: { speech.speak(word.word) }) {
                    Image(systemName: "speaker.wave.2.fill").font(.system(size: 18)).foregroundColor(.white)
                        .frame(width: 36, height: 36).background(Color.blue).clipShape(Circle())
                }.buttonStyle(PlainButtonStyle())
                Button(action: { onDelete() }) {
                    Image(systemName: "trash.fill").font(.system(size: 15)).foregroundColor(.red)
                        .frame(width: 36, height: 36).background(Color.white.opacity(0.2)).clipShape(Circle())
                }.buttonStyle(PlainButtonStyle())
            }
        }.padding(14).background(cardColor).cornerRadius(12)
        .overlay(RoundedRectangle(cornerRadius: 12).stroke(Color.white.opacity(0.1), lineWidth: 1))
    }
}

// MARK: - Conjunctions View
struct ConjunctionsView: View {
    @ObservedObject var viewModel: VocabularyViewModel
    @ObservedObject var speech: SpeechManager
    @Environment(\.dismiss) var dismiss
    @State private var showPicker = false
    @State private var searchText = ""
    @State private var selectedConjunction: Conjunction? = nil
    @State private var categoryExpanded: Set<String> = []
    @State private var viewMode: ConjunctionViewMode = .table
    @State private var showClearConfirm = false
    @State private var showReferenceImage = false
    let boardColor = Color(red: 0.05, green: 0.2, blue: 0.1)
    enum ConjunctionViewMode: String, CaseIterable {
        case table = "Bảng tổng quan"
        case detail = "Chi tiết"
    }
    var grouped: [(String, [Conjunction])] {
        let d = Dictionary(grouping: viewModel.conjunctions, by: { $0.category })
        return d.map { ($0.key, $0.value) }.sorted { $0.0 < $1.0 }
    }
    var filtered: [(String, [Conjunction])] {
        if searchText.isEmpty { return grouped }
        let f = viewModel.conjunctions.filter {
            $0.english.lowercased().contains(searchText.lowercased()) ||
            $0.vietnamese.lowercased().contains(searchText.lowercased())
        }
        let d = Dictionary(grouping: f, by: { $0.category })
        return d.map { ($0.key, $0.value) }.sorted { $0.0 < $1.0 }
    }
    var body: some View {
        NavigationStack {
            ZStack {
                boardColor.ignoresSafeArea()
                if viewModel.isParsingConjunctions {
                    ProgressView("Đang bóc tách...").foregroundColor(.white).tint(.orange).scaleEffect(1.2)
                } else if viewModel.conjunctions.isEmpty {
                    VStack(spacing: 20) {
                        Image(systemName: "link.badge.plus").font(.system(size: 60)).foregroundColor(.orange.opacity(0.6))
                        Text("Chưa có dữ liệu").foregroundColor(.white).font(.title3)
                        Button(action: { showPicker = true }) {
                            Label("Import", systemImage: "plus.circle.fill")
                                .padding(.horizontal, 30).padding(.vertical, 14)
                                .background(Color.orange).foregroundColor(.black).cornerRadius(10).font(.headline)
                        }
                    }
                } else {
                    VStack(spacing: 0) {
                        Picker("", selection: $viewMode) {
                            ForEach(ConjunctionViewMode.allCases, id: \.self) { Text($0.rawValue).tag($0) }
                        }.pickerStyle(.segmented).padding(.horizontal, 12).padding(.top, 8).padding(.bottom, 4)
                        if viewMode == .table {
                            ConjunctionTableView(conjunctions: filtered, speech: speech, onTap: { selectedConjunction = $0 })
                        } else {
                            List {
                                ForEach(filtered, id: \.0) { cat, items in
                                    DisclosureGroup(isExpanded: Binding(
                                        get: { categoryExpanded.contains(cat) || !searchText.isEmpty },
                                        set: { v in if v { categoryExpanded.insert(cat) } else { categoryExpanded.remove(cat) } }
                                    )) {
                                        ForEach(items) { c in
                                            ConjunctionRow(conjunction: c, speech: speech)
                                                .contentShape(Rectangle()).onTapGesture { selectedConjunction = c }
                                                .swipeActions(edge: .trailing) {
                                                    Button(role: .destructive) { viewModel.deleteConjunction(c) } label: { Label("Xóa", systemImage: "trash") }
                                                }
                                        }
                                    } label: {
                                        HStack {
                                            Image(systemName: "link.circle.fill").foregroundColor(.orange)
                                            Text(cat).fontWeight(.bold).foregroundColor(.white)
                                            Spacer(); Text("\(items.count)").foregroundColor(.gray)
                                        }
                                    }
                                }
                            }.scrollContentBackground(.hidden).background(boardColor)
                        }
                    }
                }
            }
            .navigationTitle("🔗 LIÊN TỪ").navigationBarTitleDisplayMode(.inline)
            .searchable(text: $searchText, prompt: "Tìm liên từ...")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Đóng") { dismiss() }.foregroundColor(.yellow) }
                ToolbarItem(placement: .primaryAction) {
                    HStack {
                        Button(action: { showReferenceImage = true }) { Image(systemName: "photo.fill").foregroundColor(.cyan) }
                            .accessibilityLabel("Ảnh tham khảo")
                        Button(action: { showPicker = true }) { Image(systemName: "plus.circle.fill").foregroundColor(.orange) }
                    }
                }
                if !viewModel.conjunctions.isEmpty {
                    ToolbarItem(placement: .bottomBar) {
                        Button(role: .destructive) { showClearConfirm = true } label: { Label("Xóa tất cả", systemImage: "trash") }
                    }
                }
            }
            .fileImporter(isPresented: $showPicker, allowedContentTypes: [.spreadsheet], allowsMultipleSelection: false) { result in
                switch result {
                case .success(let files):
                    for url in files {
                        if url.startAccessingSecurityScopedResource() {
                            if let d = try? Data(contentsOf: url) {
                                let fn = url.deletingPathExtension().lastPathComponent
                                url.stopAccessingSecurityScopedResource()
                                viewModel.parseExcel(data: d, fileName: fn, isConjunctionFile: true)
                            } else { url.stopAccessingSecurityScopedResource() }
                        }
                    }
                case .failure(let e): viewModel.errorMessage = e.localizedDescription
                }
            }
            .sheet(item: $selectedConjunction) { ConjunctionDetailView(conjunction: $0, speech: speech) }
            .sheet(isPresented: $showReferenceImage) { ReferenceImageView() }
            .confirmationDialog("Xoá toàn bộ \(viewModel.conjunctions.count) liên từ?", isPresented: $showClearConfirm,
                                titleVisibility: .visible) {
                Button("Xoá tất cả", role: .destructive) { viewModel.clearConjunctions() }
                Button("Hủy", role: .cancel) {}
            } message: { Text("Thao tác này không hoàn tác được.") }
        }
    }
}

// MARK: - Conjunction Table
struct ConjunctionTableView: View {
    let conjunctions: [(String, [Conjunction])]
    @ObservedObject var speech: SpeechManager
    let onTap: (Conjunction) -> Void
    let boardColor = Color(red: 0.05, green: 0.2, blue: 0.1)
    var body: some View {
        ScrollView {
            VStack(spacing: 16) {
                ForEach(conjunctions, id: \.0) { cat, items in
                    VStack(alignment: .leading, spacing: 0) {
                        HStack {
                            Text(cat).font(.system(size: 16, weight: .heavy, design: .rounded)).foregroundColor(.black)
                            Spacer(); Text("\(items.count)").font(.system(size: 13, weight: .bold)).foregroundColor(.black.opacity(0.7))
                        }.padding(.horizontal, 12).padding(.vertical, 8).background(Color.orange).cornerRadius(8)
                        HStack(spacing: 0) {
                            Text("ENGLISH").frame(maxWidth: .infinity, alignment: .leading).font(.system(size: 11, weight: .bold)).foregroundColor(.gray)
                            Text("VIỆT NAM").frame(maxWidth: .infinity, alignment: .leading).font(.system(size: 11, weight: .bold)).foregroundColor(.gray)
                        }.padding(.horizontal, 12).padding(.vertical, 4).background(Color.white.opacity(0.05))
                        ForEach(items) { c in
                            HStack(spacing: 0) {
                                Button(action: { speech.speak(c.english) }) {
                                    HStack(spacing: 4) {
                                        Image(systemName: "volume.circle.fill").font(.system(size: 11)).foregroundColor(.blue)
                                        Text(c.english).font(.system(size: 13, weight: .semibold, design: .rounded)).foregroundColor(.white)
                                            .fixedSize(horizontal: false, vertical: true)
                                    }.frame(maxWidth: .infinity, alignment: .leading)
                                }.buttonStyle(.plain)
                                Rectangle().fill(Color.white.opacity(0.1)).frame(width: 1).padding(.vertical, 2)
                                Button(action: { onTap(c) }) {
                                    Text(c.vietnamese).font(.system(size: 12)).foregroundColor(Color(red: 1, green: 0.9, blue: 0.6))
                                        .frame(maxWidth: .infinity, alignment: .leading).multilineTextAlignment(.leading)
                                }.buttonStyle(.plain)
                            }.padding(.horizontal, 12).padding(.vertical, 6).background(Color.white.opacity(0.03))
                            .contentShape(Rectangle()).onTapGesture { onTap(c) }
                        }
                    }.background(Color.white.opacity(0.06)).cornerRadius(10)
                    .overlay(RoundedRectangle(cornerRadius: 10).stroke(Color.white.opacity(0.08), lineWidth: 1))
                }
            }.padding(12)
        }.background(boardColor)
    }
}

// MARK: - Conjunction Row
struct ConjunctionRow: View {
    let conjunction: Conjunction
    @ObservedObject var speech: SpeechManager
    var body: some View {
        HStack(spacing: 12) {
            Button(action: { speech.speak(conjunction.english) }) {
                HStack(spacing: 6) {
                    Image(systemName: "speaker.wave.2.fill").font(.system(size: 14)).foregroundColor(.blue)
                    Text(conjunction.english).font(.system(size: 16, weight: .semibold, design: .rounded)).foregroundColor(.white).multilineTextAlignment(.leading)
                }
            }.buttonStyle(.plain).frame(maxWidth: .infinity, alignment: .leading)
            Rectangle().fill(Color.white.opacity(0.15)).frame(width: 1, height: 30)
            Text(conjunction.vietnamese).font(.system(size: 15)).foregroundColor(Color(red: 1, green: 0.9, blue: 0.6))
                .frame(maxWidth: .infinity, alignment: .leading).multilineTextAlignment(.leading)
        }.padding(.vertical, 6).listRowBackground(Color.white.opacity(0.05))
    }
}

// MARK: - Reference Image (zoom + kéo, ảnh do người dùng tự chụp/chọn)

/// Ảnh tham khảo lưu trong thư mục Documents của app trên máy người dùng,
/// không đóng gói vào app nên không phát tán tài liệu có bản quyền.
enum ReferenceImageStore {
    private static var url: URL {
        FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("reference_conjunctions.jpg")
    }
    static func load() -> UIImage? {
        guard let d = try? Data(contentsOf: url) else { return nil }
        return UIImage(data: d)
    }
    static func save(_ image: UIImage) {
        let resized = image.resizedForUpload(maxSide: 3000)   // đủ nét để zoom, không quá nặng
        if let d = resized.jpegData(compressionQuality: 0.9) { try? d.write(to: url) }
    }
    static func delete() { try? FileManager.default.removeItem(at: url) }
    static var hasUserImage: Bool { FileManager.default.fileExists(atPath: url.path) }
}

struct ReferenceImageView: View {
    @Environment(\.dismiss) var dismiss
    let boardColor = Color(red: 0.05, green: 0.2, blue: 0.1)
    /// Ưu tiên ảnh người dùng chọn; nếu có ảnh "LienTuTongQuan" trong Assets thì dùng làm mặc định
    @State private var image: UIImage? = ReferenceImageStore.load() ?? UIImage(named: "LienTuTongQuan")
    @State private var hasUserImage = ReferenceImageStore.hasUserImage
    @State private var showCamera = false
    @State private var showLibrary = false
    @State private var photoItem: PhotosPickerItem? = nil
    @State private var pendingImage: UIImage? = nil
    @State private var baseScale: CGFloat = 1.0
    @State private var currentScale: CGFloat = 1.0
    @State private var offset: CGSize = .zero
    @State private var lastOffset: CGSize = .zero

    var body: some View {
        NavigationStack {
            ZStack {
                boardColor.ignoresSafeArea()
                if let img = image {
                    Image(uiImage: img)
                        .resizable()
                        .scaledToFit()
                        .scaleEffect(currentScale)
                        .offset(offset)
                        .gesture(
                            SimultaneousGesture(
                                MagnificationGesture()
                                    .onChanged { value in
                                        currentScale = max(1.0, min(baseScale * value, 8.0))
                                    }
                                    .onEnded { _ in
                                        baseScale = currentScale
                                        if currentScale <= 1.0 { withAnimation { resetZoom() } }
                                    },
                                DragGesture()
                                    .onChanged { value in
                                        if currentScale > 1.0 {
                                            offset = CGSize(
                                                width: lastOffset.width + value.translation.width,
                                                height: lastOffset.height + value.translation.height
                                            )
                                        }
                                    }
                                    .onEnded { _ in lastOffset = offset }
                            )
                        )
                        .onTapGesture(count: 2) {
                            withAnimation {
                                if currentScale > 1.0 { resetZoom() } else { currentScale = 3.0; baseScale = 3.0 }
                            }
                        }
                        .padding(.horizontal, 4)
                } else {
                    VStack(spacing: 16) {
                        Image(systemName: "photo.badge.plus").font(.system(size: 60)).foregroundColor(.cyan.opacity(0.7))
                        Text("Chưa có ảnh tham khảo").font(.title2).foregroundColor(.white)
                        Text("Chụp hoặc chọn ảnh bảng liên từ của bạn. Ảnh chỉ lưu trên máy này.")
                            .font(.subheadline).foregroundColor(.gray).multilineTextAlignment(.center).padding(.horizontal, 30)
                        HStack(spacing: 12) {
                            Button { openCamera() } label: {
                                Label("Chụp ảnh", systemImage: "camera.fill").padding(.horizontal, 18).padding(.vertical, 12)
                                    .background(Color.cyan).foregroundColor(.black).cornerRadius(10)
                            }
                            Button { showLibrary = true } label: {
                                Label("Chọn ảnh", systemImage: "photo.on.rectangle").padding(.horizontal, 18).padding(.vertical, 12)
                                    .background(Color.white.opacity(0.15)).foregroundColor(.white).cornerRadius(10)
                            }
                        }
                        Text("Hoặc dùng chế độ \"Bảng tổng quan\" để xem nhanh.").font(.caption).foregroundColor(.cyan)
                    }.padding(.top, 20)
                }
            }
            .navigationTitle("Ảnh tham khảo").navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Đóng") { dismiss() }.foregroundColor(.yellow)
                }
                if image != nil {
                    ToolbarItem(placement: .primaryAction) {
                        Menu {
                            Button { openCamera() } label: { Label("Chụp ảnh mới", systemImage: "camera.fill") }
                            Button { showLibrary = true } label: { Label("Chọn ảnh khác", systemImage: "photo.on.rectangle") }
                            if hasUserImage {
                                Button(role: .destructive) {
                                    ReferenceImageStore.delete(); hasUserImage = false
                                    image = UIImage(named: "LienTuTongQuan"); resetZoom()
                                } label: { Label("Xoá ảnh", systemImage: "trash") }
                            }
                        } label: { Image(systemName: "ellipsis.circle").foregroundColor(.yellow) }
                            .accessibilityLabel("Đổi ảnh tham khảo")
                    }
                }
            }
            .fullScreenCover(isPresented: $showCamera, onDismiss: {
                if let img = pendingImage { setImage(img); pendingImage = nil }
            }) {
                CameraPicker { img in pendingImage = img }.ignoresSafeArea()
            }
            .photosPicker(isPresented: $showLibrary, selection: $photoItem, matching: .images)
            .onChange(of: photoItem) { _, item in
                guard let item else { return }
                Task {
                    if let data = try? await item.loadTransferable(type: Data.self), let img = UIImage(data: data) {
                        setImage(img)
                    }
                    photoItem = nil
                }
            }
        }
    }

    private func openCamera() {
        if UIImagePickerController.isSourceTypeAvailable(.camera) { showCamera = true } else { showLibrary = true }
    }

    private func setImage(_ img: UIImage) {
        ReferenceImageStore.save(img)
        image = ReferenceImageStore.load() ?? img
        hasUserImage = true
        resetZoom()
    }

    private func resetZoom() {
        currentScale = 1.0; baseScale = 1.0; offset = .zero; lastOffset = .zero
    }
}

// MARK: - Conjunction Detail
struct ConjunctionDetailView: View {
    let conjunction: Conjunction
    @ObservedObject var speech: SpeechManager
    @Environment(\.dismiss) var dismiss
    let boardColor = Color(red: 0.05, green: 0.2, blue: 0.1)
    var body: some View {
        NavigationStack {
            ZStack {
                boardColor.ignoresSafeArea()
                VStack(spacing: 30) {
                    Image(systemName: "link.circle.fill").font(.system(size: 60)).foregroundColor(.orange)
                    VStack(spacing: 16) {
                        Text(conjunction.english).font(.system(size: 32, weight: .bold, design: .rounded)).foregroundColor(.white).multilineTextAlignment(.center)
                        Text(conjunction.vietnamese).font(.system(size: 22)).foregroundColor(Color(red: 1, green: 0.9, blue: 0.6)).multilineTextAlignment(.center)
                        Text(conjunction.category).font(.caption).foregroundColor(.gray)
                            .padding(.horizontal, 12).padding(.vertical, 6).background(Color.white.opacity(0.1)).cornerRadius(8)
                    }.padding(.horizontal, 30)
                    HStack(spacing: 20) {
                        Button(action: { speech.speak(conjunction.english) }) {
                            VStack { Image(systemName: "speaker.wave.2.fill").font(.system(size: 28)); Text("Phát âm").font(.caption) }
                            .foregroundColor(.blue).frame(width: 100, height: 80).background(Color.white.opacity(0.1)).cornerRadius(12)
                        }
                        Button(action: { speech.speak(conjunction.english, rate: 0.25) }) {
                            VStack { Image(systemName: "tortoise.fill").font(.system(size: 28)); Text("Chậm").font(.caption) }
                            .foregroundColor(.orange).frame(width: 100, height: 80).background(Color.white.opacity(0.1)).cornerRadius(12)
                        }
                    }
                    Spacer()
                }.padding(.top, 60)
            }.navigationTitle("Chi tiết").navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Đóng") { dismiss() }.foregroundColor(.yellow)
                }
            }
        }
    }
}
