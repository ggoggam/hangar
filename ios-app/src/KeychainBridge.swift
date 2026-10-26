import Foundation
import KotlinModules
import Security

/// Generic-password items for Kotlin's `IosSecureStore`, readable after the first unlock (so a
/// background refresh can use the token) and never synced or restored to another device.
final class KeychainBridge: NSObject, IosSecureStore {
    private let service = "dev.ggoggam.hangar"

    func read(key: String) -> String? {
        var query = base(key)
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var item: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &item) == errSecSuccess, let data = item as? Data else {
            return nil
        }
        return String(data: data, encoding: .utf8)
    }

    func write(key: String, value: String) {
        let data = Data(value.utf8)
        let update = [kSecValueData as String: data]
        if SecItemUpdate(base(key) as CFDictionary, update as CFDictionary) == errSecItemNotFound {
            var add = base(key)
            add[kSecValueData as String] = data
            add[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
            SecItemAdd(add as CFDictionary, nil)
        }
    }

    func delete(key: String) {
        SecItemDelete(base(key) as CFDictionary)
    }

    private func base(_ key: String) -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: key,
        ]
    }
}
