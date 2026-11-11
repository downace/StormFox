package com.github.downace.stormfox

import com.github.downace.stormfox.lint.*
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.github.downace.stormfox.settings.MagoSettings
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File
import java.nio.file.Files

class MagoDiagnosticServiceTest : BasePlatformTestCase() {
    fun testDefaultConfigurationAndBaselinePaths() {
        val root = File(System.getProperty("java.io.tmpdir"), "stormfox-paths")
        val paths = MagoDependencyPaths(root, "", "mago")
        for (name in listOf("mago.toml", "mago.yaml", "mago.yml", "mago.json", "mago.dist.toml",
            "mago.dist.yaml", "mago.dist.yml", "mago.dist.json", "baseline.json", "composer.lock")) {
            assertTrue(name, paths.affectsDependencies(File(root, name).path))
        }
        assertFalse(paths.affectsDependencies(File(root, "README.md").path))
        assertFalse(paths.affectsDependencies(File(root, "Source.php").path))
        assertTrue(paths.affectsDependencies(File(root, "Source.php").path, includePhp = true))
    }

    fun testExplicitRelativeConfigurationAndBinaryPathsAreNormalized() {
        val root = File(System.getProperty("java.io.tmpdir"), "stormfox-paths")
        val paths = MagoDependencyPaths(root, "config/../config.custom", "tools/mago.exe")
        assertTrue(paths.affectsDependencies(File(root, "config.custom").path))
        assertTrue(paths.affectsDependencies(File(root, "tools/mago.exe").path))
        assertTrue(paths.affectsDependencies(File(root, "config.custom").path.replace('/', '\\')))
        assertFalse(paths.affectsDependencies(File(root, "unrelated.custom").path))
    }

    fun testYamlJsonAndBaselineSavedChangesInvalidateSnapshots() {
        for (name in listOf("mago.yaml", "mago.dist.yml", "mago.json", "mago.dist.json", "baseline.json")) {
            withDependency(name) { file ->
                WriteAction.run<RuntimeException> { file.setBinaryContent("changed".toByteArray()) }
            }
        }
    }

    fun testConfigurationDocumentEditsUseTheSameDependencyPredicate() {
        for (name in listOf("mago.yaml", "mago.json", "baseline.json")) {
            withDependency(name) { file ->
                val document = FileDocumentManager.getInstance().getDocument(file)!!
                WriteCommandAction.runWriteCommandAction(project) { document.insertString(0, "changed") }
            }
        }
    }

    fun testRelativeArbitraryExtensionConfigEditsRenamesAndDeletionInvalidateSnapshots() {
        withDependency("config.custom", explicitConfig = true) { file ->
            val document = FileDocumentManager.getInstance().getDocument(file)!!
            WriteCommandAction.runWriteCommandAction(project) { document.insertString(0, "changed") }
        }
        withDependency("config.custom", explicitConfig = true) { file ->
            WriteAction.run<RuntimeException> { file.setBinaryContent("changed".toByteArray()) }
        }
        withDependency("config.custom", explicitConfig = true) { file ->
            WriteAction.run<RuntimeException> { file.rename(this, "unrelated.custom") }
        }
        withDependency("config.custom", explicitConfig = true) { file ->
            WriteAction.run<RuntimeException> { file.delete(this) }
        }
    }

    fun testConfiguredBinaryChangeInvalidatesSnapshots() {
        withDependency("custom-binary.exe", binary = true) { file ->
            WriteAction.run<RuntimeException> { file.setBinaryContent("changed".toByteArray()) }
        }
    }

    private fun withDependency(name: String, explicitConfig: Boolean = false, binary: Boolean = false,
                               change: (VirtualFile) -> Unit) {
        val directory = Files.createTempDirectory("stormfox-dependency-config-")
        val targetPath = directory.resolve("Target.php")
        val dependencyPath = directory.resolve(name)
        val settings = MagoSettings.getInstance().state
        val oldConfig = settings.configPath
        val oldBinary = settings.magoPath
        try {
            Files.writeString(targetPath, "<?php echo 1;")
            Files.writeString(dependencyPath, "original")
            val fileSystem = LocalFileSystem.getInstance()
            val target = fileSystem.refreshAndFindFileByNioFile(targetPath)!!
            val dependency = fileSystem.refreshAndFindFileByNioFile(dependencyPath)!!
            settings.configPath = if (explicitConfig) File(project.basePath!!).toPath().relativize(dependencyPath).toString() else ""
            settings.magoPath = if (binary) dependencyPath.toString() else "must-not-start-before-debounce"
            val service = project.getService(MagoDiagnosticService::class.java)
            val snapshot = MagoSnapshot(1, "<?php echo 1;", target.path, File(project.basePath!!),
                settings.magoPath, settings.configPath, emptyMap(), service.epoch)
            service.state.request(target, MagoTool.ANALYZE, snapshot)
            assertTrue(service.state.isCurrent(target, MagoTool.ANALYZE, snapshot))
            change(dependency)
            assertTrue(name, service.epoch > snapshot.generation)
            assertFalse(name, service.state.isCurrent(target, MagoTool.ANALYZE, snapshot))
        } finally {
            settings.configPath = oldConfig
            settings.magoPath = oldBinary
            Files.walk(directory).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }

    fun testOtherPhpFileVfsChangeInvalidatesUnchangedEditorSnapshot() {
        val directory = Files.createTempDirectory("stormfox-dependency-")
        val targetPath = directory.resolve("Target.php")
        val dependencyPath = directory.resolve("Dependency.php")
        try {
            Files.writeString(targetPath, "<?php echo 1;")
            Files.writeString(dependencyPath, "<?php class Dependency {}")
            val fileSystem = LocalFileSystem.getInstance()
            val target = fileSystem.refreshAndFindFileByNioFile(targetPath)!!
            val dependency = fileSystem.refreshAndFindFileByNioFile(dependencyPath)!!
            val service = project.getService(MagoDiagnosticService::class.java)
            val snapshot = MagoSnapshot(1, "<?php echo 1;", target.path, File(project.basePath!!),
                "must-not-start-before-debounce", "", emptyMap(), service.epoch)
            service.state.request(target, MagoTool.ANALYZE, snapshot)
            assertTrue(service.state.isCurrent(target, MagoTool.ANALYZE, snapshot))
            WriteAction.run<RuntimeException> { dependency.setBinaryContent("<?php class Changed {}".toByteArray()) }
            assertTrue(service.epoch > snapshot.generation)
            assertFalse(service.state.isCurrent(target, MagoTool.ANALYZE, snapshot))
        } finally {
            Files.deleteIfExists(targetPath)
            Files.deleteIfExists(dependencyPath)
            Files.deleteIfExists(directory)
        }
    }
}
