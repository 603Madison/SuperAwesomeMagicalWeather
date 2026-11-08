@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package app.northvane.weather.ios

import app.northvane.weather.SecretStore
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDictionaryAddValue
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFMutableDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFTypeRef
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSData
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.dataUsingEncoding
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData

/**
 * API keys and passwords in the iOS Keychain: encrypted by the system, kept
 * on this device only (never in backups), readable after the first unlock so
 * background checks can use them.
 */
class KeychainSecretStore(private val service: String) : SecretStore {

    /** Adds a Kotlin/Foundation value to a CF dictionary, which keeps its own reference. */
    private fun put(dict: CFMutableDictionaryRef?, key: CFTypeRef?, value: Any) {
        val ref = CFBridgingRetain(value)
        CFDictionaryAddValue(dict, key, ref)
        CFRelease(ref)
    }

    private fun query(name: String, extra: (CFMutableDictionaryRef?) -> Unit = {}): CFMutableDictionaryRef? {
        val q = CFDictionaryCreateMutable(null, 0, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr)
        CFDictionaryAddValue(q, kSecClass, kSecClassGenericPassword)
        put(q, kSecAttrService, service)
        put(q, kSecAttrAccount, name)
        extra(q)
        return q
    }

    override fun get(name: String): String? = memScoped {
        val q = query(name) {
            CFDictionaryAddValue(it, kSecReturnData, kCFBooleanTrue)
            CFDictionaryAddValue(it, kSecMatchLimit, kSecMatchLimitOne)
        }
        val result = alloc<CFTypeRefVar>()
        val status = SecItemCopyMatching(q, result.ptr)
        CFRelease(q)
        if (status != errSecSuccess) return@memScoped null
        val data = CFBridgingRelease(result.value) as? NSData ?: return@memScoped null
        NSString.create(data = data, encoding = NSUTF8StringEncoding)?.toString()
    }

    override fun has(name: String): Boolean = get(name) != null

    override fun put(name: String, value: String?) {
        val q = query(name)
        SecItemDelete(q)
        CFRelease(q)
        if (value.isNullOrEmpty()) return
        val data = value.encodeToByteArray().toNSData()
        val add = query(name) {
            put(it, kSecValueData, data)
            CFDictionaryAddValue(it, kSecAttrAccessible, kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly)
        }
        SecItemAdd(add, null)
        CFRelease(add)
    }
}
