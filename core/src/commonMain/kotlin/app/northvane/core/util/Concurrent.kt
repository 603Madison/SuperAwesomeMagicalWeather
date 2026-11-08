package app.northvane.core.util

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/** A small thread-safe least-recently-used cache. */
class LruCache<K, V>(private val maxSize: Int) {
    private val lock = SynchronizedObject()
    private val map = LinkedHashMap<K, V>()

    operator fun get(key: K): V? = synchronized(lock) {
        val v = map.remove(key) ?: return@synchronized null
        map[key] = v
        v
    }

    operator fun set(key: K, value: V) = synchronized(lock) {
        map.remove(key)
        map[key] = value
        while (map.size > maxSize) map.remove(map.keys.first())
    }

    fun clear() = synchronized(lock) { map.clear() }
}

/** A thread-safe map for small lookup tables. */
class SyncMap<K, V> {
    private val lock = SynchronizedObject()
    private val map = HashMap<K, V>()

    operator fun get(key: K): V? = synchronized(lock) { map[key] }
    operator fun set(key: K, value: V) = synchronized(lock) { map[key] = value }
    fun remove(key: K): V? = synchronized(lock) { map.remove(key) }
    fun getOrPut(key: K, value: () -> V): V = synchronized(lock) { map.getOrPut(key, value) }
}

/** Runs [block] holding [lock]. */
inline fun <T> locked(lock: SynchronizedObject, block: () -> T): T = synchronized(lock, block)

/** application/x-www-form-urlencoded, as java.net.URLEncoder writes it. */
fun formEncode(s: String): String = buildString {
    for (b in s.encodeToByteArray()) {
        val c = (b.toInt() and 0xFF).toChar()
        when {
            c.isLetterOrDigit() && c.code < 0x80 || c in ".-*_" -> append(c)
            c == ' ' -> append('+')
            else -> append('%').append("0123456789ABCDEF"[c.code shr 4]).append("0123456789ABCDEF"[c.code and 0xF])
        }
    }
}

/** A random version 4 UUID string. */
fun randomUuid(): String {
    val r = kotlin.random.Random
    val b = ByteArray(16) { r.nextInt(256).toByte() }
    b[6] = ((b[6].toInt() and 0x0F) or 0x40).toByte()
    b[8] = ((b[8].toInt() and 0x3F) or 0x80).toByte()
    val h = b.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
    return "${h.substring(0, 8)}-${h.substring(8, 12)}-${h.substring(12, 16)}-${h.substring(16, 20)}-${h.substring(20)}"
}

/** [v] rounded half up to [decimals] places, without trailing zeros ("43.66", "-70", "0.5"). */
fun plainDecimal(v: Double, decimals: Int): String {
    val s = fixed(v, decimals)
    val t = if ('.' in s) s.trimEnd('0').trimEnd('.') else s
    return if (t == "-0") "0" else t
}
