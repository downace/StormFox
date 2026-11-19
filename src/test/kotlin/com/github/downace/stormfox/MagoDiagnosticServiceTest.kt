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
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import com.intellij.openapi.util.TextRange
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent
import com.intellij.codeInsight.daemon.impl.AnnotationHolderImpl
import com.intellij.lang.annotation.AnnotationSession
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

class MagoDiagnosticServiceTest : BasePlatformTestCase() {
    fun testSettingsChangesImmediatelyInvalidateCachedSnapshots() {
        val file = myFixture.configureByText("SettingsTarget.php", "<?php echo 1;").virtualFile
        val service = project.getService(MagoDiagnosticService::class.java)
        val settings = MagoSettings.getInstance(project).state
        val original = settings.configPath
        try {
            val snapshot = MagoSnapshot(1, "<?php echo 1;", file.path, File(project.basePath!!),
                settings.magoPath, settings.configPath, emptyMap(), service.epoch)
            service.state.request(file, MagoTool.ANALYZE, snapshot)
            assertTrue(service.state.isCurrent(file, MagoTool.ANALYZE, snapshot))
            settings.configPath = original + ".changed"
            assertTrue(service.epoch > snapshot.generation)
            assertFalse(service.state.isCurrent(file, MagoTool.ANALYZE, snapshot))
            val epoch = service.epoch
            settings.configPath = settings.configPath
            settings.showStatusWidget = !settings.showStatusWidget
            assertEquals(epoch, service.epoch)
            settings.showStatusWidget = !settings.showStatusWidget
        } finally {
            settings.configPath = original
        }
    }
    fun testGuardWarningsAnnotateEmptyPhpAndOfferAllActions() {
        myFixture.configureByText("Empty.php", "")
        myFixture.enableInspections(MagoLintInspection(), MagoAnalyzeInspection(), MagoGuardInspection())
        val snapshot = MagoDiagnosticAnnotator.Snapshot(
            myFixture.file.text, myFixture.file.virtualFile.path, File("."), "mago", "",
        )
        val report = MagoDiagnosticAnnotator.Report(
            snapshot, MagoDiagnosticRunner.Result.Success(emptyList(), listOf("Guard checks skipped: missing configuration.")),
        )
        val holder = annotated(MagoGuardAnnotator(), report)
        val warning = holder.single { it.message?.contains("Guard checks skipped") == true }
        assertEquals(com.intellij.lang.annotation.HighlightSeverity.WARNING, warning.severity)
        assertTrue(warning.isFileLevelAnnotation)
        val fixes = warning.quickFixes!!.map { it.quickFix.text }
        assertEquals(
            listOf("Open Mago config", "Open inspection settings", "Disable inspection"),
            fixes,
        )
        val actions = warning.quickFixes!!.map { it.quickFix }
        actions.forEach {
            assertTrue(it.isAvailable(project, myFixture.editor, myFixture.file))
            assertFalse(it.startInWriteAction())
            assertEquals(com.intellij.codeInsight.intention.preview.IntentionPreviewInfo.EMPTY,
                it.generatePreview(project, myFixture.editor, myFixture.file))
        }
        actions.last().invoke(project, myFixture.editor, myFixture.file)
        assertNull(MagoDiagnosticAnnotator.inspection(myFixture.file, MagoTool.GUARD))
        assertNotNull(MagoDiagnosticAnnotator.inspection(myFixture.file, MagoTool.LINT))
        assertNotNull(MagoDiagnosticAnnotator.inspection(myFixture.file, MagoTool.ANALYZE))
    }

    private data class Worker(val tool: MagoTool, val snapshot: MagoSnapshot,
                              val gate: CompletableDeferred<MagoDiagnosticRunner.Result>)

    private fun workerSnapshot(snapshot: MagoDiagnosticAnnotator.Snapshot) = MagoSnapshot(
        snapshot.revision, snapshot.text, snapshot.logicalPath, snapshot.workingDirectory, snapshot.binary,
        snapshot.configPath, snapshot.environment, snapshot.generation)

    private fun waitForFresh(service: MagoDiagnosticService, snapshot: MagoDiagnosticAnnotator.Snapshot, tool: MagoTool) {
        repeat(100) {
            val view = service.state.requestView(snapshot.file!!, tool, workerSnapshot(snapshot))
            if (view != null && !view.retained) return
            Thread.sleep(10)
        }
        fail("Fresh result did not arrive for $tool")
    }

    private fun annotated(annotator: MagoDiagnosticAnnotator, report: MagoDiagnosticAnnotator.Report): AnnotationHolderImpl {
        val holder = AnnotationHolderImpl(AnnotationSession(myFixture.file), true)
        holder.runAnnotatorWithContext(myFixture.file) { _, target -> annotator.apply(myFixture.file, report, target) }
        holder.assertAllAnnotationsCreated()
        return holder
    }

    fun testOldCollectedInputsAfterRealSaveStartBothToolsWithoutNewCollect() {
        Thread.sleep(1100) // Let the shared service's settings watcher settle after other tests.
        val service = project.getService(MagoDiagnosticService::class.java)
        val pending = LinkedBlockingQueue<Worker>()
        service.state.invalidateAll()
        service.computeForTest = { tool, snapshot ->
            CompletableDeferred<MagoDiagnosticRunner.Result>().let { pending.add(Worker(tool, snapshot, it)); it.await() }
        }
        try {
            myFixture.configureByText("Sample.php", "<?php echo 1;")
            myFixture.enableInspections(MagoLintInspection(), MagoAnalyzeInspection())
            val annotators = listOf(MagoLintAnnotator(), MagoAnalyzeAnnotator())
            val tools = listOf(MagoTool.LINT, MagoTool.ANALYZE)
            val original = annotators.map { it.collectInformation(myFixture.file)!! }
            annotators.forEachIndexed { i, annotator -> assertNull(annotator.doAnnotate(original[i])) }
            repeat(2) {
                val worker = pending.poll(5, TimeUnit.SECONDS)!!
                worker.gate.complete(MagoDiagnosticRunner.Result.Success(listOf(
                    MagoDiagnosticReport.Diagnostic(TextRange(6, 10), "retained", null, "Warning", source = worker.tool))))
            }
            tools.forEachIndexed { i, tool -> waitForFresh(service, original[i], tool) }
            val document = myFixture.editor.document
            WriteCommandAction.runWriteCommandAction(project) { document.insertString(document.textLength, " ") }
            val collected = annotators.map { it.collectInformation(myFixture.file)!! }
            val generation = service.epoch
            FileDocumentManager.getInstance().saveDocument(document)
            assertTrue(service.epoch > generation)
            annotators.forEachIndexed { i, annotator ->
                val report = annotator.doAnnotate(collected[i])!!
                assertTrue(report.retained)
                assertEquals(service.epoch, report.snapshot.generation)
                assertEquals(document.modificationStamp, report.snapshot.revision)
                assertEquals(1, (report.result as MagoDiagnosticRunner.Result.Success).diagnostics.size)
            }
            val workers = List(2) { pending.poll(5, TimeUnit.SECONDS)!! }
            assertEquals(tools.toSet(), workers.map { it.tool }.toSet())
            assertTrue(workers.all { it.snapshot.generation == service.epoch && !it.gate.isCompleted })
        } finally { service.computeForTest = null; service.state.invalidateAll() }
    }

    fun testApplyResolvesSaveAndFreshCompletionWithoutAnotherCollect() {
        val service = project.getService(MagoDiagnosticService::class.java)
        val pending = LinkedBlockingQueue<Worker>()
        service.state.invalidateAll()
        service.computeForTest = { tool, snapshot ->
            CompletableDeferred<MagoDiagnosticRunner.Result>().let { pending.add(Worker(tool, snapshot, it)); it.await() }
        }
        try {
            myFixture.configureByText("Sample.php", "<?php echo 1;")
            myFixture.enableInspections(MagoLintInspection())
            val annotator = MagoLintAnnotator()
            val initial = annotator.collectInformation(myFixture.file)!!
            val diagnostic = MagoDiagnosticReport.Diagnostic(TextRange(6, 10), "problem", "rule", "Warning",
                fix = MagoDiagnosticReport.Fix(initial.text, MagoDiagnosticReport.Safety.SAFE))
            assertNull(annotator.doAnnotate(initial))
            pending.poll(5, TimeUnit.SECONDS)!!.gate.complete(MagoDiagnosticRunner.Result.Success(listOf(diagnostic)))
            waitForFresh(service, initial, MagoTool.LINT)
            val document = myFixture.editor.document
            WriteCommandAction.runWriteCommandAction(project) { document.insertString(document.textLength, " ") }
            val collected = annotator.collectInformation(myFixture.file)!!
            val beforeSave = annotator.doAnnotate(collected)!!
            assertTrue(beforeSave.retained)
            pending.poll(5, TimeUnit.SECONDS) // old generation, intentionally held
            val oldEpoch = service.epoch
            FileDocumentManager.getInstance().saveDocument(document)
            assertTrue(service.epoch > oldEpoch)
            val retainedHolder = annotated(annotator, beforeSave)
            assertEquals(1, retainedHolder.size)
            assertTrue(retainedHolder.single().quickFixes.isNullOrEmpty())
            val current = pending.poll(5, TimeUnit.SECONDS)!!
            assertEquals(service.epoch, current.snapshot.generation)
            current.gate.complete(MagoDiagnosticRunner.Result.Success(listOf(diagnostic)))
            val currentSnapshot = collected.copy(generation = service.epoch, revision = document.modificationStamp)
            waitForFresh(service, currentSnapshot, MagoTool.LINT)
            val freshHolder = annotated(annotator, beforeSave)
            assertEquals(1, freshHolder.size)
            assertFalse(freshHolder.single().quickFixes.isNullOrEmpty())
        } finally { service.computeForTest = null; service.state.invalidateAll() }
    }

    fun testOldInputAfterDependencyChangeUsesCurrentSettingsAndRejectsLateWorker() {
        Thread.sleep(1100)
        val service = project.getService(MagoDiagnosticService::class.java)
        val pending = LinkedBlockingQueue<Worker>()
        val settings = MagoSettings.getInstance(project).state
        val oldBinary = settings.magoPath
        val oldConfig = settings.configPath
        service.state.invalidateAll()
        service.computeForTest = { tool, snapshot ->
            CompletableDeferred<MagoDiagnosticRunner.Result>().let {
                pending.add(Worker(tool, snapshot, it))
                withContext(NonCancellable) { it.await() }
            }
        }
        try {
            val dependency = myFixture.tempDirFixture.createFile("mago.toml", "original")
            myFixture.configureByText("Sample.php", "<?php echo 1;")
            myFixture.enableInspections(MagoLintInspection())
            val annotator = MagoLintAnnotator()
            val original = annotator.collectInformation(myFixture.file)!!
            val diagnostic = MagoDiagnosticReport.Diagnostic(TextRange(6, 10), "old", null, "Warning")
            assertNull(annotator.doAnnotate(original))
            pending.poll(5, TimeUnit.SECONDS)!!.gate.complete(MagoDiagnosticRunner.Result.Success(listOf(diagnostic)))
            waitForFresh(service, original, MagoTool.LINT)
            val document = myFixture.editor.document
            WriteCommandAction.runWriteCommandAction(project) { document.insertString(document.textLength, " ") }
            val collected = annotator.collectInformation(myFixture.file)!!
            val staleReport = annotator.doAnnotate(collected)!!
            assertTrue(staleReport.retained)
            val oldWorker = pending.poll(5, TimeUnit.SECONDS)!!
            val beforeSettings = service.epoch
            settings.magoPath = "new-mago"
            settings.configPath = "new-config.toml"
            assertTrue(service.epoch > beforeSettings)
            val previousEpoch = service.epoch
            WriteAction.run<RuntimeException> { dependency.setBinaryContent("changed".toByteArray()) }
            assertTrue(service.epoch > previousEpoch)
            assertNull("Full invalidation must not relabel old success", annotator.doAnnotate(collected))
            val current = pending.poll(5, TimeUnit.SECONDS)!!
            assertEquals("new-mago", current.snapshot.binary)
            assertEquals("new-config.toml", current.snapshot.configPath)
            assertEquals(service.epoch, current.snapshot.generation)
            assertEquals(document.modificationStamp, current.snapshot.revision)
            assertTrue(annotated(annotator, staleReport).isEmpty())
            assertNull(annotator.doAnnotate(collected.copy(text = "different")))
            assertFalse(current.gate.isCompleted)
            oldWorker.gate.complete(MagoDiagnosticRunner.Result.Success(listOf(diagnostic)))
            Thread.sleep(50)
            assertNull(service.state.requestView(collected.file!!, MagoTool.LINT, current.snapshot))
            current.gate.complete(MagoDiagnosticRunner.Result.Success(emptyList()))
            waitForFresh(service, collected.copy(generation = current.snapshot.generation,
                binary = current.snapshot.binary, configPath = current.snapshot.configPath), MagoTool.LINT)
        } finally {
            settings.magoPath = oldBinary
            settings.configPath = oldConfig
            service.computeForTest = null
            service.state.invalidateAll()
            Thread.sleep(1100)
        }
    }
    fun testOldCollectedSnapshotIsNormalizedWhenTextReturns() {
        val service = project.getService(MagoDiagnosticService::class.java)
        val pending = LinkedBlockingQueue<CompletableDeferred<MagoDiagnosticRunner.Result>>()
        service.state.invalidateAll()
        service.computeForTest = { _, _ ->
            CompletableDeferred<MagoDiagnosticRunner.Result>().also { pending.add(it) }.await()
        }
        try {
            myFixture.configureByText("Sample.php", "<?php echo 1;")
            myFixture.enableInspections(MagoLintInspection())
            val annotator = MagoLintAnnotator()
            val original = annotator.collectInformation(myFixture.file)!!
            val diagnostic = MagoDiagnosticReport.Diagnostic(TextRange(6, 10), "fixture return", null, "Warning")
            fun completeNext() {
                val worker = pending.poll(5, TimeUnit.SECONDS)
                assertNotNull("Expected background computation", worker)
                worker!!.complete(MagoDiagnosticRunner.Result.Success(listOf(diagnostic)))
            }
            fun fresh(snapshot: MagoDiagnosticAnnotator.Snapshot): MagoDiagnosticAnnotator.Report {
                repeat(50) {
                    val report = annotator.doAnnotate(snapshot)
                    if (report != null && !report.retained) return report
                    Thread.sleep(20)
                }
                error("Fresh report did not arrive")
            }
            assertNull(annotator.doAnnotate(original))
            completeNext()
            fresh(original)
            val document = myFixture.editor.document
            WriteCommandAction.runWriteCommandAction(project) { document.insertString(document.textLength, " ") }
            val changed = annotator.collectInformation(myFixture.file)!!
            assertTrue(annotator.doAnnotate(changed)!!.retained)
            completeNext()
            fresh(changed)
            WriteCommandAction.runWriteCommandAction(project) { document.deleteString(document.textLength - 1, document.textLength) }
            assertEquals(original.text, document.text)
            assertTrue(original.revision != document.modificationStamp)
            val retained = annotator.doAnnotate(original)!!
            assertTrue(retained.retained)
            assertEquals(document.modificationStamp, retained.snapshot.revision)
            assertNull("Different-text old request must be rejected", annotator.doAnnotate(changed))
            val highlights = myFixture.doHighlighting().filter { it.description?.contains("Mago: fixture return") == true }
            assertEquals(1, highlights.size)
            completeNext()
            val fresh = fresh(original)
            assertEquals(document.modificationStamp, fresh.snapshot.revision)
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            assertEquals(1, myFixture.doHighlighting().count { it.description?.contains("Mago: fixture return") == true })
        } finally {
            service.computeForTest = null
            service.state.invalidateAll()
        }
    }
    fun testRegisteredAnnotatorReemitsRetainedHighlightWhileWorkerIsHeld() {
        Thread.sleep(1100)
        val service = project.getService(MagoDiagnosticService::class.java)
        val pending = LinkedBlockingQueue<Pair<MagoTool, CompletableDeferred<MagoDiagnosticRunner.Result>>>()
        service.state.invalidateAll()
        service.computeForTest = { tool, _ ->
            CompletableDeferred<MagoDiagnosticRunner.Result>().also { pending.add(tool to it) }.await()
        }
        try {
            myFixture.configureByText("Sample.php", "<?php echo 1;")
            val dependent = myFixture.tempDirFixture.createFile("Dependent.php", "<?php")
            myFixture.enableInspections(MagoLintInspection())
            fun highlights() = myFixture.doHighlighting().filter { it.description?.contains("Mago: fixture diagnostic") == true }
            fun waitForHighlight(expected: Boolean) {
                repeat(30) {
                    PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
                    if (highlights().isNotEmpty() == expected) return
                    Thread.sleep(50)
                }
                fail("Expected fixture diagnostic present=$expected")
            }
            highlights()
            val initial = pending.poll(5, TimeUnit.SECONDS)?.second
            assertNotNull("Registered annotator did not start background compute", initial)
            initial!!.complete(MagoDiagnosticRunner.Result.Success(listOf(
                MagoDiagnosticReport.Diagnostic(TextRange(6, 10), "fixture diagnostic", null, "Warning"))))
            waitForHighlight(true)
            WriteCommandAction.runWriteCommandAction(project) { myFixture.editor.document.insertString(0, " ") }
            val retained = highlights()
            assertEquals(1, retained.size)
            assertEquals(7, retained.single().startOffset)
            val rerun = pending.poll(5, TimeUnit.SECONDS)?.second
            assertNotNull("Edited document did not start background recomputation", rerun)
            assertFalse(rerun!!.isCompleted)
            val dependentSnapshot = MagoSnapshot(1, "<?php", dependent.path, File(project.basePath!!),
                MagoSettings.getInstance(project).state.magoPath.ifBlank { "mago" },
                MagoSettings.getInstance(project).state.configPath, System.getenv(), service.epoch)
            service.state.request(dependent, MagoTool.GUARD, dependentSnapshot)
            var sawSave = false
            project.messageBus.connect(testRootDisposable).subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
                override fun after(events: List<VFileEvent>) {
                    if (events.any { it is VFileContentChangeEvent && it.file == myFixture.file.virtualFile && it.isFromSave }) sawSave = true
                }
            })
            val oldEpoch = service.epoch
            FileDocumentManager.getInstance().saveDocument(myFixture.editor.document)
            assertTrue("Expected a real VFS document-save event", sawSave)
            assertTrue(service.epoch > oldEpoch)
            assertFalse(service.state.isCurrent(dependent, MagoTool.GUARD, dependentSnapshot))
            val saved = highlights()
            assertEquals(1, saved.size)
            assertEquals(7, saved.single().startOffset)
            // The save cancels the old worker and schedules a new generation.
            var next = pending.poll(5, TimeUnit.SECONDS)
            while (next != null && next.first != MagoTool.LINT) next = pending.poll(5, TimeUnit.SECONDS)
            val afterSave = next?.second
            assertNotNull("Saved document did not start background recomputation", afterSave)
            afterSave!!.complete(MagoDiagnosticRunner.Result.Success(emptyList()))
            waitForHighlight(false)
        } finally {
            service.computeForTest = null
            service.state.invalidateAll()
        }
    }
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
        val settings = MagoSettings.getInstance(project).state
        val oldConfig = settings.configPath
        val oldBinary = settings.magoPath
        // This fixture tests VFS invalidation, not executable validation; its binary is plain text.
        val onPathChanged = settings.onPathChanged
        settings.onPathChanged = null
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
            settings.onPathChanged = onPathChanged
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
