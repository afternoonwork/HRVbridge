import Foundation
import HRVCore
import Security

/// The Intervals.icu API key in the Keychain, this device only, readable after first unlock.
enum KeychainStore {
    private static let service = "HRVBridge"
    private static let account = "intervals-api-key"

    private static var baseQuery: [String: Any] {
        [kSecClass as String: kSecClassGenericPassword,
         kSecAttrService as String: service,
         kSecAttrAccount as String: account]
    }

    static func load() -> ApiKey? {
        var query = baseQuery
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var item: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &item) == errSecSuccess,
              let data = item as? Data, let text = String(data: data, encoding: .utf8) else { return nil }
        return ApiKey(userInput: text)
    }

    /// false if the Keychain refused; nothing is stored then.
    static func save(_ key: ApiKey) -> Bool {
        delete()
        var query = baseQuery
        query[kSecValueData as String] = Data(key.key.utf8)
        query[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        return SecItemAdd(query as CFDictionary, nil) == errSecSuccess
    }

    static func delete() {
        SecItemDelete(baseQuery as CFDictionary)
    }
}
