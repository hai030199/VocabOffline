import Foundation
import Security

/// Lưu chuỗi bí mật (API key) vào Keychain thay vì UserDefaults.
enum KeychainStore {
    private static let service = "com.hai.VocabOffline"

    enum Account: String {
        case geminiAPIKey = "gemini_api_key"
    }

    private static func baseQuery(_ account: Account) -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account.rawValue
        ]
    }

    static func save(_ value: String, for account: Account) {
        let query = baseQuery(account)
        SecItemDelete(query as CFDictionary)
        let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        var add = query
        add[kSecValueData as String] = Data(trimmed.utf8)
        add[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlock
        SecItemAdd(add as CFDictionary, nil)
    }

    static func read(_ account: Account) -> String? {
        var query = baseQuery(account)
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var out: AnyObject?
        guard SecItemCopyMatching(query as CFDictionary, &out) == errSecSuccess,
              let data = out as? Data else { return nil }
        return String(data: data, encoding: .utf8)
    }
}

/// Cấu hình AI không bí mật + thống kê đã dùng, lưu ở UserDefaults.
enum AISettings {
    static let defaultModel = "gemini-3.8-flash"
    private static let modelKey = "ai_gemini_model"
    /// Model Google đã ngừng (đang tự chuyển hướng); gặp thì dùng model mặc định mới
    private static let retiredModels: Set<String> = ["gemini-3.5-flash", "gemini-3.7-flash", "gemini-2.5-flash", "gemini-2.0-flash"]

    static var model: String {
        get {
            let m = UserDefaults.standard.string(forKey: modelKey)?.trimmingCharacters(in: .whitespaces) ?? ""
            return (m.isEmpty || retiredModels.contains(m)) ? defaultModel : m
        }
        set { UserDefaults.standard.set(newValue.trimmingCharacters(in: .whitespaces), forKey: modelKey) }
    }

    /// Người dùng đã đồng ý cho gửi ảnh tới Google Gemini chưa (App Store guideline 5.1.2(i))
    private static let consentKey = "ai_gemini_consent"
    static var geminiConsent: Bool {
        get { UserDefaults.standard.bool(forKey: consentKey) }
        set { UserDefaults.standard.set(newValue, forKey: consentKey) }
    }
}

struct AIUsageStats: Codable {
    var requests = 0
    var inputTokens = 0
    var outputTokens = 0

    private static let key = "ai_usage_stats"

    static func load() -> AIUsageStats {
        guard let d = UserDefaults.standard.data(forKey: key),
              let s = try? JSONDecoder().decode(AIUsageStats.self, from: d) else { return AIUsageStats() }
        return s
    }

    static func record(input: Int, output: Int) {
        var s = load()
        s.requests += 1
        s.inputTokens += input
        s.outputTokens += output
        if let d = try? JSONEncoder().encode(s) { UserDefaults.standard.set(d, forKey: key) }
    }
}
