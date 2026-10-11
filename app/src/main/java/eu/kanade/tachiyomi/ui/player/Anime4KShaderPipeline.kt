package eu.kanade.tachiyomi.ui.player

import android.content.Context
import `is`.xyz.mpv.MPVLib
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Owns bundled assets and replaces only the app's entries in MPV's shader list. */
class Anime4KShaderPipeline internal constructor(
    private val shaderDir: File,
    private val assetReader: (String) -> InputStream,
    private val shaderListReader: () -> String?,
    private val commandSender: (Array<String>) -> Unit,
) {
    constructor(context: Context) : this(
        shaderDir = File(context.filesDir, "mpv/anime4k-shaders"),
        assetReader = { context.assets.open("anime4k/$it") },
        shaderListReader = { MPVLib.getPropertyString("glsl-shaders") },
        commandSender = { MPVLib.command(it) },
    )

    private val shaderNames = Anime4K.bundledShaderNames.toSet()
    private var assetsVerified = false

    /** Verify bytes once per player initialization, repairing interrupted or corrupt copies. */
    @Synchronized
    fun copyAssets(): Boolean {
        assetsVerified = false
        return try {
            if (!shaderDir.isDirectory && !shaderDir.mkdirs()) return false
            for (filename in shaderNames) {
                val expected = assetReader(filename).use { it.readBytes() }
                if (expected.isEmpty()) throw IOException("Empty bundled Anime4K shader: $filename")
                val outputFile = File(shaderDir, filename)
                val matches = outputFile.isFile &&
                    outputFile.length() == expected.size.toLong() &&
                    outputFile.readBytes().contentEquals(expected)
                if (!matches) {
                    val temporaryFile = File(shaderDir, "$filename.tmp")
                    try {
                        temporaryFile.writeBytes(expected)
                        try {
                            Files.move(
                                temporaryFile.toPath(),
                                outputFile.toPath(),
                                StandardCopyOption.REPLACE_EXISTING,
                                StandardCopyOption.ATOMIC_MOVE,
                            )
                        } catch (_: AtomicMoveNotSupportedException) {
                            Files.move(temporaryFile.toPath(), outputFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
                        }
                    } finally {
                        temporaryFile.delete()
                    }
                }
            }
            assetsVerified = true
            true
        } catch (error: IOException) {
            logcat(LogPriority.ERROR, error) { "Failed to install Anime4K shaders" }
            false
        }
    }

    @Synchronized
    fun apply(mode: Anime4KMode): Boolean {
        return try {
            if (mode != Anime4KMode.Off && !assetsVerified) return false
            if (mode.shaderFileNames.any { !File(shaderDir, it).isFile || File(shaderDir, it).length() == 0L }) {
                logcat(LogPriority.ERROR) { "Anime4K assets are incomplete; keeping the current shader pipeline" }
                return false
            }
            // Null means MPV could not read the property. Treating it as an empty list would
            // erase custom shaders on the next successful command.
            val shaderList = shaderListReader() ?: return false
            val current = shaderList.split(File.pathSeparatorChar).filter(String::isNotBlank)
            val target =
                current.filterNot(::isOwnedShader) + mode.shaderFileNames.map { File(shaderDir, it).absolutePath }
            if (current == target) return true
            commandSender(
                if (target.isEmpty()) {
                    arrayOf("change-list", "glsl-shaders", "clr", "")
                } else {
                    arrayOf("change-list", "glsl-shaders", "set", target.joinToString(File.pathSeparator))
                },
            )
            // MPVLib.command returns void and its JNI wrapper discards mpv_command errors.
            // A synchronous readback is the acknowledgement before updating the active profile.
            val applied = shaderListReader()?.split(File.pathSeparatorChar)?.filter(String::isNotBlank)
            applied == target
        } catch (error: Exception) {
            logcat(LogPriority.ERROR, error) { "Failed to apply Anime4K mode ${mode.name}" }
            false
        }
    }

    private fun isOwnedShader(path: String): Boolean {
        val file = File(path)
        return file.name in shaderNames && file.canonicalFile.parentFile == shaderDir.canonicalFile
    }
}
