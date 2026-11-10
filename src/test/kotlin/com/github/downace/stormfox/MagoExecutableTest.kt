package com.github.downace.stormfox

import com.intellij.openapi.util.SystemInfo
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class MagoExecutableTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test fun resolvesExistingAbsolutePath() {
        val file = temporaryFolder.newFile("mago")
        assertEquals(file.absolutePath, resolveMagoExecutable(file.absolutePath))
    }

    @Test fun resolvesExistingRelativePath() {
        val file = temporaryFolder.newFile("mago")
        val relative = File("").absoluteFile.toPath().relativize(file.toPath()).toString()
        assertEquals(File(relative).absolutePath, resolveMagoExecutable(relative))
    }

    @Test fun preservesSpacesInExistingPath() {
        val file = File(temporaryFolder.newFolder("directory with spaces"), "mago binary").apply { createNewFile() }
        assertEquals(file.absolutePath, resolveMagoExecutable(file.absolutePath))
    }

    @Test fun leavesUnresolvedPathCommandUnchanged() {
        val command = "stormfox-unresolved-mago-command"
        assertEquals(command, resolveMagoExecutable(command))
    }

    @Test fun resolvesExeFallbackOnlyOnWindows() {
        val file = temporaryFolder.newFile("mago.exe")
        val path = file.absolutePath.removeSuffix(".exe")
        assertEquals(if (SystemInfo.isWindows) file.absolutePath else path, resolveMagoExecutable(path))
    }

    @Test fun prefersExistingFileOverExeFallback() {
        val file = temporaryFolder.newFile("mago")
        temporaryFolder.newFile("mago.exe")
        assertEquals(file.absolutePath, resolveMagoExecutable(file.absolutePath))
    }

    @Test fun doesNotAppendExeToExistingSuffixIgnoringCase() {
        assumeTrue(SystemInfo.isWindows)
        val path = File(temporaryFolder.root, "mago.EXE").absolutePath
        File("$path.exe").createNewFile()
        assertEquals(path, resolveMagoExecutable(path))
    }
}
