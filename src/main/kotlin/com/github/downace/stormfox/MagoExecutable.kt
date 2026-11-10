package com.github.downace.stormfox

import com.intellij.openapi.util.SystemInfo
import java.io.File

internal fun resolveMagoExecutable(path: String): String {
    val file = File(path)
    if (file.isFile) {
        return file.absolutePath
    }
    if (SystemInfo.isWindows && !path.endsWith(".exe", ignoreCase = true)) {
        val executable = File("$path.exe")
        if (executable.isFile) {
            return executable.absolutePath
        }
    }
    return path
}
