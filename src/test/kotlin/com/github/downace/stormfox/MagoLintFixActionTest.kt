package com.github.downace.stormfox

import com.github.downace.stormfox.lint.MagoApplyLintFixAction
import com.github.downace.stormfox.lint.MagoLintReport
import com.github.downace.stormfox.lint.MagoRelatedNavigation
import com.github.downace.stormfox.lint.MagoLintAnnotator
import com.github.downace.stormfox.lint.MagoLintInspection
import com.github.downace.stormfox.lint.MagoLintRunner
import com.intellij.lang.annotation.AnnotationBuilder
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.modcommand.ActionContext
import com.intellij.modcommand.ModCommand
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File
import java.lang.reflect.Proxy

class MagoLintFixActionTest : BasePlatformTestCase() {
    private fun action(safety: MagoLintReport.Safety = MagoLintReport.Safety.SAFE) = MagoApplyLintFixAction(
        MagoLintReport.Fix("<?php echo 2;", safety), MagoRelatedNavigation.snapshotHash(myFixture.editor.document.text),
        myFixture.file.virtualFile, "example", "Issue",
    )

    private fun context() = ActionContext.from(myFixture.editor, myFixture.file)

    fun testNativeCommandPreviewAndApplication() {
        myFixture.configureByText("Sample.php", "<?php echo 1;")
        val action = action()
        assertNotNull(action.getPresentation(context()))
        val command = action.perform(context())
        assertFalse(command == ModCommand.nop())
        assertEquals("<?php echo 1;", myFixture.editor.document.text)
        myFixture.launchAction(action.asIntention())
        assertEquals("<?php echo 2;", myFixture.editor.document.text)
    }

    fun testUncommittedChangesAndWrongFileRejectFix() {
        myFixture.configureByText("Sample.php", "<?php echo 1;")
        val action = action()
        WriteCommandAction.runWriteCommandAction(project) { myFixture.editor.document.insertString(0, " ") }
        assertFalse(PsiDocumentManager.getInstance(project).isCommitted(myFixture.editor.document))
        assertNull(action.getPresentation(context()))
        assertEquals(ModCommand.nop(), action.perform(context()))
        myFixture.configureByText("Other.php", "<?php echo 1;")
        assertNull(action.getPresentation(context()))
        assertEquals(ModCommand.nop(), action.perform(context()))
    }

    fun testRiskLabels() {
        myFixture.configureByText("Sample.php", "<?php echo 1;")
        assertEquals("Mago Linter: Fix example (unsafe)", action(MagoLintReport.Safety.UNSAFE).getPresentation(context())!!.name())
        assertEquals("Mago Linter: Fix example (potentially unsafe)", action(MagoLintReport.Safety.POTENTIALLY_UNSAFE).getPresentation(context())!!.name())
    }

    fun testAnnotationRegistersNativeFix() {
        val text = "<?php echo 1;"
        myFixture.configureByText("Sample.php", text)
        myFixture.enableInspections(MagoLintInspection())
        val path = myFixture.file.virtualFile.path
        val diagnostic = MagoLintReport.parse(
            """{"issues":[{"message":"Issue","level":"Warning","edits":[[{"name":"$path"},[{"range":{"start":11,"end":12},"new_text":[50],"safety":"safe"}]]],"annotations":[{"kind":"Primary","span":{"file_id":{"name":"$path"},"start":{"offset":11},"end":{"offset":12}}}]}]}""",
            text, path, null,
        ).single()
        val fixes = mutableListOf<Any?>()
        val builder = Proxy.newProxyInstance(AnnotationBuilder::class.java.classLoader, arrayOf(AnnotationBuilder::class.java)) { proxy, method, args ->
            if (method.name == "newFix") {
                val action = args!![0]
                return@newProxyInstance Proxy.newProxyInstance(AnnotationBuilder.FixBuilder::class.java.classLoader,
                    arrayOf(AnnotationBuilder.FixBuilder::class.java)) { fixProxy, fixMethod, fixArgs ->
                    when (fixMethod.name) {
                        "key" -> {
                            assertSame(com.intellij.codeInsight.daemon.HighlightDisplayKey.find(MagoLintInspection.SHORT_NAME), fixArgs!![0])
                            fixProxy
                        }
                        "registerFix" -> { fixes.add(action); proxy }
                        else -> null
                    }
                }
            }
            if (method.returnType == AnnotationBuilder::class.java) proxy else null
        }
        val holder = Proxy.newProxyInstance(AnnotationHolder::class.java.classLoader, arrayOf(AnnotationHolder::class.java)) { _, method, _ ->
            if (method.name == "newAnnotation") builder else null
        } as AnnotationHolder
        val snapshot = MagoLintAnnotator.Snapshot(text, path, File("."), "mago", "")
        MagoLintAnnotator().apply(myFixture.file,
            MagoLintAnnotator.Report(snapshot, MagoLintRunner.Result.Success(listOf(diagnostic))), holder)
        assertEquals(2, fixes.size)
        assertTrue(fixes.all { it is MagoApplyLintFixAction })
        val nativeHolder = com.intellij.codeInsight.daemon.impl.AnnotationHolderImpl(
            com.intellij.lang.annotation.AnnotationSession(myFixture.file), true)
        nativeHolder.runAnnotatorWithContext(myFixture.file) { _, target ->
            MagoLintAnnotator().apply(myFixture.file,
                MagoLintAnnotator.Report(snapshot, MagoLintRunner.Result.Success(listOf(diagnostic))), target)
        }
        nativeHolder.assertAllAnnotationsCreated()
        val nativeFixes = nativeHolder.single().quickFixes!!
        assertEquals(2, nativeFixes.size)
        nativeFixes.forEach {
            assertSame(com.intellij.codeInsight.daemon.HighlightDisplayKey.find(MagoLintInspection.SHORT_NAME), it.key)
        }
    }
}
