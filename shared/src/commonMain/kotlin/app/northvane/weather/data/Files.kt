package app.northvane.weather.data

import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath

/** Small text files under the app's private directory, on every platform. */
class TextFiles(root: String) {
    private val fs = FileSystem.SYSTEM
    val root: Path = root.toPath()

    fun path(vararg parts: String): Path = parts.fold(root) { p, s -> p / s }

    fun exists(path: Path): Boolean = fs.exists(path)

    fun read(path: Path): String? = runCatching { fs.read(path) { readUtf8() } }.getOrNull()

    /** Writes through a temporary file and a rename, so a crash never leaves half a file. */
    fun write(path: Path, text: String) {
        path.parent?.let { fs.createDirectories(it) }
        // A temp name of its own, so two writers of the same file never share one.
        val tmp = path.parent!! / (path.name + "." + app.northvane.core.util.randomUuid().take(8) + ".tmp")
        try {
            fs.write(tmp) { writeUtf8(text) }
            fs.atomicMove(tmp, path)
        } finally {
            runCatching { fs.delete(tmp) }
        }
    }

    fun delete(path: Path) = runCatching { fs.delete(path) }.let { }

    fun deleteRecursively(path: Path) = runCatching { fs.deleteRecursively(path) }.let { }
}
