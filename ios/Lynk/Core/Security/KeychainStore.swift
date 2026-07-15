import Foundation
import Security

struct KeychainStore: Sendable {
    let service: String

    init(service: String = "app.lynk.credentials") {
        self.service = service
    }

    func string(for account: String) -> String {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne
        ]
        var item: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &item) == errSecSuccess,
              let data = item as? Data,
              let value = String(data: data, encoding: .utf8) else { return "" }
        return value
    }

    func set(_ value: String, for account: String) {
        let identity: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account
        ]
        if value.isEmpty {
            SecItemDelete(identity as CFDictionary)
            return
        }
        let attributes: [String: Any] = [
            kSecValueData as String: Data(value.utf8),
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        ]
        if SecItemUpdate(identity as CFDictionary, attributes as CFDictionary) == errSecItemNotFound {
            SecItemAdd(identity.merging(attributes) { _, new in new } as CFDictionary, nil)
        }
    }
}

