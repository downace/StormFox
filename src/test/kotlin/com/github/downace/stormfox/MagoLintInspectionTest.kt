package com.github.downace.stormfox

import com.github.downace.stormfox.lint.MagoLintAnnotator
import com.github.downace.stormfox.lint.MagoLintInspection
import com.github.downace.stormfox.lint.MagoLintReport
import com.github.downace.stormfox.lint.MagoLintRunner
import com.github.downace.stormfox.settings.MagoSettings
import com.intellij.codeInsight.daemon.HighlightDisplayKey
import com.intellij.codeInsight.daemon.impl.AnnotationHolderImpl
import com.intellij.codeInspection.ProblemDescriptorUtil
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.lang.annotation.AnnotationSession
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.profile.codeInspection.InspectionProjectProfileManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File
import java.lang.reflect.Proxy
import java.nio.file.Files

class MagoLintInspectionTest : BasePlatformTestCase() {
    fun testRegisteredDisabled() {
        val inspection = MagoLintInspection()
        assertFalse(inspection.isEnabledByDefault)
        assertEquals(listOf("PHP", "Quality tools"), inspection.groupPath.toList())
        assertTrue(ExtensionPointName<com.intellij.codeInspection.LocalInspectionEP>("com.intellij.localInspection").extensionList.any {
            it.implementationClass == MagoLintInspection::class.java.name
        })
        assertTrue(ExtensionPointName<com.intellij.lang.LanguageExtensionPoint<Any>>("com.intellij.externalAnnotator").extensionList.any {
            it.implementationClass == MagoLintAnnotator::class.java.name
        })
    }

    fun testCollectRequiresInspectionButNotFormatter() {
        val path = Files.createTempFile("stormfox-lint-", ".phtml")
        val settings = MagoSettings.getInstance().state
        val enabled = settings.enabled
        try {
            Files.writeString(path, "<?php echo 1;")
            val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)!!
            myFixture.configureFromExistingVirtualFile(virtualFile)
            val annotator = MagoLintAnnotator()
            assertNull(annotator.collectInformation(myFixture.file))
            myFixture.enableInspections(MagoLintInspection())
            settings.enabled = false
            assertNotNull(annotator.collectInformation(myFixture.file))
        } finally {
            settings.enabled = enabled
            Files.deleteIfExists(path)
        }
    }

    fun testSnapshotRejectsChangedText() {
        val snapshot = snapshot("<?php echo 1;")
        assertTrue(MagoLintAnnotator.isCurrent(snapshot, snapshot.text))
        assertFalse(MagoLintAnnotator.isCurrent(snapshot, "<?php echo 2;"))
    }

    fun testAnnotationsUseInspectionSeverityAndReportedRange() {
        val text = "<?php echo 1;"
        myFixture.configureByText("Sample.php", text)
        myFixture.enableInspections(MagoLintInspection())
        val profile = InspectionProjectProfileManager.getInstance(project).currentProfile
        profile.setErrorLevel(HighlightDisplayKey.find(MagoLintInspection.SHORT_NAME)!!,
            com.intellij.codeHighlighting.HighlightDisplayLevel.ERROR, project)
        val calls = mutableListOf<Pair<String, List<Any?>>>()
        val builderType = com.intellij.lang.annotation.AnnotationBuilder::class.java
        val builder = Proxy.newProxyInstance(builderType.classLoader, arrayOf(builderType)) { proxy, method, args ->
            calls += method.name to args.orEmpty().toList()
            if (method.returnType == builderType) proxy else null
        }
        val holder = Proxy.newProxyInstance(AnnotationHolder::class.java.classLoader, arrayOf(AnnotationHolder::class.java)) { _, method, args ->
            calls += method.name to args.orEmpty().toList()
            if (method.name == "newAnnotation") builder else null
        } as AnnotationHolder
        val range = TextRange(6, 10)
        val report = MagoLintAnnotator.Report(snapshot(text), MagoLintRunner.Result.Success(
            listOf(
                MagoLintReport.Diagnostic(range, "Avoid echo", "test-rule", "Warning"),
                MagoLintReport.Diagnostic(range, "Syntax error", null, "Error"),
            ),
        ))
        MagoLintAnnotator().apply(myFixture.file, report, holder)
        assertTrue(calls.contains("newAnnotation" to listOf(HighlightSeverity.ERROR, "Mago Linter: Avoid echo [test-rule]")))
        assertTrue(calls.contains("newAnnotation" to listOf(HighlightSeverity.ERROR, "Mago Linter: Syntax error")))
        assertTrue(calls.contains("range" to listOf(range)))
        assertTrue(calls.any { it.first == "create" })
        calls.clear()
        val staleReport = MagoLintAnnotator.Report(snapshot("<?php echo 2;"), report.result)
        MagoLintAnnotator().apply(myFixture.file, staleReport, holder)
        assertEmpty(calls)
        val failure = MagoLintAnnotator.Report(snapshot(text), MagoLintRunner.Result.Failure("Cannot start Mago"))
        MagoLintAnnotator().apply(myFixture.file, failure, holder)
        assertTrue(calls.contains("newAnnotation" to listOf(HighlightSeverity.ERROR, "Mago Linter: Cannot start Mago")))
        assertTrue(calls.any { it.first == "fileLevel" })
    }

    fun testArgumentsUseGlobalConfigAndWorkspaceRelativePath() {
        val root = File(System.getProperty("java.io.tmpdir"), "mago-lint-workspace")
        val path = File(root, "src/Sample.php").absolutePath
        val args = MagoLintRunner.arguments("mago", "custom.toml", path, root)
        assertEquals(listOf("mago", "--config", "custom.toml", "lint", "--stdin-input", "--reporting-format", "json", "--", "src/Sample.php"), args)
        assertFalse(args.contains("--fix"))
        val leadingDash = MagoLintRunner.arguments("mago", "", File(root, "-Sample.php").absolutePath, root)
        assertEquals(listOf("--", "-Sample.php"), leadingDash.takeLast(2))
    }

    fun testRealAnnotationsRespectProfileInstanceMappingsAndBatchConversion() {
        val text = "<?php echo 1;"
        myFixture.configureByText("Sample.php", text)
        myFixture.enableInspections(MagoLintInspection())
        val profile = InspectionProjectProfileManager.getInstance(project).currentProfile
        val inspection = profile.getInspectionTool(MagoLintInspection.SHORT_NAME, myFixture.file)!!.tool as MagoLintInspection
        profile.setErrorLevel(HighlightDisplayKey.find(MagoLintInspection.SHORT_NAME)!!,
            com.intellij.codeHighlighting.HighlightDisplayLevel.ERROR, project)
        inspection.noteSeverity = HighlightSeverity.INFORMATION.name
        inspection.helpSeverity = HighlightSeverity.WEAK_WARNING.name
        inspection.warningSeverity = HighlightSeverity.WARNING.name
        inspection.errorSeverity = HighlightSeverity.ERROR.name
        val range = TextRange(6, 10)
        val report = MagoLintAnnotator.Report(snapshot(text), MagoLintRunner.Result.Success(
            listOf("Note", "Help", "Warning", "Error").map {
                MagoLintReport.Diagnostic(range, it, null, it)
            },
        ))
        fun annotations(): AnnotationHolderImpl {
            val holder = AnnotationHolderImpl(AnnotationSession(myFixture.file), true)
            holder.runAnnotatorWithContext(myFixture.file) { _, target ->
                MagoLintAnnotator().apply(myFixture.file, report, target)
            }
            holder.assertAllAnnotationsCreated()
            return holder
        }
        assertEquals(List(4) { HighlightSeverity.ERROR }, annotations().map { it.severity })
        inspection.overrideSeverityLevels = true
        val mapped = annotations()
        assertEquals(listOf(HighlightSeverity.INFORMATION, HighlightSeverity.WEAK_WARNING,
            HighlightSeverity.WARNING, HighlightSeverity.ERROR), mapped.map { it.severity })
        assertTrue(mapped.all { it.startOffset == range.startOffset && it.endOffset == range.endOffset })
        assertEquals(listOf(ProblemHighlightType.WEAK_WARNING, ProblemHighlightType.WARNING, ProblemHighlightType.ERROR),
            ProblemDescriptorUtil.convertToProblemDescriptors(mapped, myFixture.file).map { it.highlightType })
        inspection.errorSeverity = HighlightSeverity.WEAK_WARNING.name
        val failureHolder = AnnotationHolderImpl(AnnotationSession(myFixture.file), true)
        failureHolder.runAnnotatorWithContext(myFixture.file) { _, target ->
            MagoLintAnnotator().apply(myFixture.file,
                MagoLintAnnotator.Report(snapshot(text), MagoLintRunner.Result.Failure("Cannot start Mago")), target)
        }
        assertEquals(HighlightSeverity.ERROR, failureHolder.single().severity)
        assertTrue(failureHolder.single().isFileLevelAnnotation)
        val custom = HighlightSeverity("StormFox runtime severity", 250)
        val registrar = com.intellij.codeInsight.daemon.impl.SeverityRegistrar.getSeverityRegistrar(project)
        registrar.registerSeverity(com.intellij.codeInsight.daemon.impl.SeverityRegistrar.SeverityBasedTextAttributes(
            com.intellij.openapi.editor.markup.TextAttributes(),
            com.intellij.codeInsight.daemon.impl.HighlightInfoType.HighlightInfoTypeImpl(custom,
                com.intellij.openapi.editor.colors.CodeInsightColors.WARNINGS_ATTRIBUTES)), null)
        try {
            inspection.warningSeverity = custom.name
            val customAnnotations = annotations()
            assertEquals(custom, customAnnotations[2].severity)
            assertEquals(ProblemHighlightType.INFORMATION,
                ProblemDescriptorUtil.convertToProblemDescriptors(customAnnotations, myFixture.file)[1].highlightType)
        } finally {
            registrar.unregisterSeverity(custom)
        }
        inspection.overrideSeverityLevels = false
        assertEquals(List(4) { HighlightSeverity.ERROR }, annotations().map { it.severity })
    }

    fun testPlatformBatchConverterPreservesBuiltinAndCustomSeverityBehavior() {
        myFixture.configureByText("Sample.php", "<?php echo 1;")
        val severities = listOf(HighlightSeverity.ERROR, HighlightSeverity.WARNING, HighlightSeverity.INFO,
            HighlightSeverity.WEAK_WARNING, HighlightSeverity("Runtime custom", 250), HighlightSeverity.INFORMATION)
        val annotations = severities.map {
            com.intellij.lang.annotation.Annotation(6, 10, it, it.name, null)
        }
        val descriptors = ProblemDescriptorUtil.convertToProblemDescriptors(annotations, myFixture.file)
        assertEquals(listOf(ProblemHighlightType.ERROR, ProblemHighlightType.WARNING, ProblemHighlightType.INFO,
            ProblemHighlightType.WEAK_WARNING, ProblemHighlightType.INFORMATION), descriptors.map { it.highlightType })
    }

    fun testCancellationPropagatesBeforeProcessStarts() {
        val indicator = com.intellij.openapi.progress.EmptyProgressIndicator()
        try {
            com.intellij.openapi.progress.ProgressManager.getInstance().runProcess(object : Runnable {
                override fun run() {
                    indicator.cancel()
                    MagoLintRunner.lint("must-not-be-started", File("."), "", "Sample.php", "<?php")
                }
            }, indicator)
            fail("Cancellation must propagate")
        } catch (_: com.intellij.openapi.progress.ProcessCanceledException) {
        }
    }

    fun testOptionalCliReadsSnapshotInsteadOfDisk() {
        val binary = System.getenv("STORMFOX_MAGO_TEST_BINARY") ?: return
        val directory = Files.createTempDirectory("stormfox-lint-cli-")
        val sourceDirectory = Files.createDirectory(directory.resolve("src"))
        val path = sourceDirectory.resolve("-Snapshot.php")
        val config = directory.resolve("custom.toml")
        val diskText = "<?php\n"
        try {
            Files.writeString(path, diskText)
            Files.writeString(config, "[source]\nworkspace = \"src\"\n")
            for (configPath in listOf("", config.toString())) {
                val result = MagoLintRunner.lint(binary, directory.toFile(), configPath, path.toString(), "<?php function broken( {")
                assertTrue("Expected a valid JSON report, got $result", result is MagoLintRunner.Result.Success)
                assertTrue((result as MagoLintRunner.Result.Success).diagnostics.isNotEmpty())
                assertEquals(diskText, Files.readString(path))
            }
        } finally {
            Files.deleteIfExists(path)
            Files.deleteIfExists(config)
            Files.deleteIfExists(sourceDirectory)
            Files.deleteIfExists(directory)
        }
    }

    private fun snapshot(text: String) = MagoLintAnnotator.Snapshot(text, "Sample.php", File("."), "mago", "")
}
