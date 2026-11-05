package de.millione.schulranzen.core.notebook

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Writing that cannot leave half a file behind: temp file, flushed to the
 * disk, then an atomic rename over the old one. If anything fails the old
 * file is untouched; there is no moment without a readable version.
 */
object SafeFile {
    fun write(file: File, block: (OutputStream) -> Unit) {
        val dir = file.absoluteFile.parentFile
        dir?.mkdirs()
        // Per thread, so two writers of one file never scribble into the same temp file.
        val tmp = File(dir, "${file.name}.${Thread.currentThread().id}.tmp")
        try {
            FileOutputStream(tmp).use { fos ->
                val out = BufferedOutputStream(fos)
                block(out)
                out.flush()
                // Without this a power cut after the rename can leave an empty page.
                fos.fd.sync()
            }
            Files.move(tmp.toPath(), file.absoluteFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (t: Throwable) {
            tmp.delete()
            throw t
        }
    }

    /**
     * Keeps a copy of an unreadable file next to it (`<name>.damaged`,
     * `.damaged2`, …) before anything can overwrite it. The same content is
     * not copied twice, so opening a damaged notebook again adds nothing.
     */
    fun keepCopy(file: File): File? {
        if (!file.isFile) return null
        var n = 1
        while (n < 20) {
            val dst = File(file.parentFile, file.name + ".damaged" + if (n == 1) "" else "$n")
            if (dst.exists()) {
                if (dst.length() == file.length()) return dst
            } else {
                return runCatching { file.copyTo(dst) }.getOrNull()
            }
            n++
        }
        return null
    }
}
