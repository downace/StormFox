package com.github.downace.stormfox

import com.github.downace.stormfox.lint.MagoApplyLintFixAction
import com.github.downace.stormfox.lint.MagoLintAnnotator
import com.github.downace.stormfox.lint.MagoLintInspection
import com.github.downace.stormfox.lint.MagoLintReport
import com.github.downace.stormfox.lint.MagoLintRunner
import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInsight.intention.IntentionActionWithOptions
import com.intellij.codeInsight.intention.PriorityAction
import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo
import com.intellij.lang.annotation.AnnotationBuilder
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.modcommand.ActionContext
import com.intellij.modcommand.ModCommand
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File
import java.lang.reflect.Proxy

class MagoLintBatchFixActionTest : BasePlatformTestCase() {
    private val source = "<?php echo 123;"

    private fun actions(safeties: List<String>, codes: List<String> = List(safeties.size) { "example" }): List<List<MagoApplyLintFixAction>> {
        myFixture.configureByText("Sample.php", source)
        myFixture.enableInspections(MagoLintInspection())
        val path = myFixture.file.virtualFile.path
        val issues = safeties.mapIndexed { index, safety ->
            val start = 11 + index
            """{"code":"${codes[index]}","message":"Issue","level":"Warning","edits":[[{"name":"$path"},[{"range":{"start":$start,"end":${start + 1}},"new_text":[${52 + index}],"safety":"$safety"}]]],"annotations":[{"kind":"Primary","span":{"file_id":{"name":"$path"},"start":{"offset":$start},"end":{"offset":${start + 1}}}}]}"""
        }
        val diagnostics = MagoLintReport.parse("""{"issues":[${issues.joinToString()}]}""", source, path, null)
        val captured = mutableListOf<MutableList<MagoApplyLintFixAction>>()
        val holder = Proxy.newProxyInstance(AnnotationHolder::class.java.classLoader, arrayOf(AnnotationHolder::class.java)) { _, method, _ ->
            if (method.name != "newAnnotation") null else {
                val fixes = mutableListOf<MagoApplyLintFixAction>()
                captured.add(fixes)
                Proxy.newProxyInstance(AnnotationBuilder::class.java.classLoader, arrayOf(AnnotationBuilder::class.java)) { proxy, builderMethod, args ->
                    if (builderMethod.name == "newFix") {
                        val action = args!![0] as MagoApplyLintFixAction
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
                    if (builderMethod.returnType == AnnotationBuilder::class.java) proxy else null
                }
            }
        } as AnnotationHolder
        MagoLintAnnotator().apply(myFixture.file, MagoLintAnnotator.Report(
            MagoLintAnnotator.Snapshot(source, path, File("."), "mago", ""),
            MagoLintRunner.Result.Success(diagnostics)), holder)
        return captured
    }

    private fun options(action: IntentionAction) = (action as IntentionActionWithOptions).options

    fun testCapturedTreeHasFlatSameRuleVariantsAndGlobalThresholds() {
        val captured = actions(listOf("safe", "potentiallyunsafe", "unsafe"))
        val current = captured.first().first().asIntention()
        assertEquals(listOf("Fix all example problems in file (unsafe)",
            "Fix all example problems in file (potentially unsafe)",
            "Fix all example problems in file"), options(current).map { it.text })
        assertTrue(options(current).all { options(it).isEmpty() })
        val global = captured.first().last().asIntention()
        assertEquals("Mago Linter: Fix all problems in file (safe only)", global.text)
        assertEquals(listOf("Fix all problems in file (including potentially unsafe)",
            "Fix all problems in file (including unsafe)"), options(global).map { it.text })
        assertEquals(PriorityAction.Priority.HIGH, (current as PriorityAction).priority)
        assertEquals(PriorityAction.Priority.NORMAL, (global as PriorityAction).priority)
        assertTrue(options(current).all { (it as PriorityAction).priority == PriorityAction.Priority.NORMAL })
        assertTrue(options(global).all { (it as PriorityAction).priority == PriorityAction.Priority.NORMAL })
        assertTrue(options(current).all { it.generatePreview(project, myFixture.editor, myFixture.file) == IntentionPreviewInfo.EMPTY })
        assertTrue(options(global).all { it.generatePreview(project, myFixture.editor, myFixture.file) == IntentionPreviewInfo.EMPTY })
        assertEquals(IntentionPreviewInfo.EMPTY, global.generatePreview(project, myFixture.editor, myFixture.file))
        assertNotSame(IntentionPreviewInfo.EMPTY, current.generatePreview(project, myFixture.editor, myFixture.file))
    }

    fun testNoSafeEditsStillExposeHigherActionsAndRuleOptions() {
        val captured = actions(listOf("potentiallyunsafe", "unsafe"), listOf("a", "b"))
        assertEquals(listOf("Fix all a problems in file (potentially unsafe)"),
            options(captured.first().first().asIntention()).map { it.text })
        val global = captured.first().last().asIntention()
        assertEquals("Mago Linter: Fix all problems in file (including potentially unsafe)", global.text)
        assertEquals("Fix all problems in file (including unsafe)", options(global).single().text)
        myFixture.launchAction(options(global).single())
        assertEquals("<?php echo 453;", myFixture.editor.document.text)
    }

    fun testSingleIssueStillOffersSameRuleFixAllChild() {
        val captured = actions(listOf("safe"))
        val current = captured.single().first().asIntention()
        assertEquals("Mago Linter: Fix example", current.text)
        assertEquals(listOf("Fix all example problems in file"), options(current).map { it.text })
        assertEquals(PriorityAction.Priority.HIGH, (current as PriorityAction).priority)
        assertEquals(PriorityAction.Priority.NORMAL, (options(current).single() as PriorityAction).priority)
        assertEquals(IntentionPreviewInfo.EMPTY,
            options(current).single().generatePreview(project, myFixture.editor, myFixture.file))
        assertNull(myFixture.getIntentionPreviewText(options(current).single()))
        assertEquals(com.intellij.icons.AllIcons.Actions.IntentionBulb,
            (current as com.intellij.openapi.util.Iconable).getIcon(0))
        assertEquals(com.intellij.icons.AllIcons.Actions.IntentionBulb,
            (options(current).single() as com.intellij.openapi.util.Iconable).getIcon(0))
    }

    fun testBatchPreviewDoesNotMutateAndOneUndoRestoresWholeFile() {
        val batch = actions(listOf("safe", "safe")).first().last()
        val intention = batch.asIntention()
        assertNull(myFixture.getIntentionPreviewText(intention))
        assertEquals(source, myFixture.editor.document.text)
        myFixture.launchAction(intention)
        assertEquals("<?php echo 453;", myFixture.editor.document.text)
        val editor = FileEditorManager.getInstance(project).selectedEditor
        val undo = UndoManager.getInstance(project)
        assertTrue(undo.isUndoAvailable(editor))
        undo.undo(editor)
        assertEquals(source, myFixture.editor.document.text)
    }

    fun testBatchTopLevelAndChildrenRejectUncommittedStaleAndWrongFile() {
        val captured = actions(listOf("safe", "potentiallyunsafe"))
        val top = captured.first().last()
        val children = options(top.asIntention()) + options(captured.first().first().asIntention())
        WriteCommandAction.runWriteCommandAction(project) { myFixture.editor.document.insertString(0, " ") }
        val context = ActionContext.from(myFixture.editor, myFixture.file)
        assertNull(top.getPresentation(context))
        assertEquals(ModCommand.nop(), top.perform(context))
        assertTrue(children.all { !it.isAvailable(project, myFixture.editor, myFixture.file) })
        myFixture.configureByText("Other.php", source)
        assertNull(top.getPresentation(ActionContext.from(myFixture.editor, myFixture.file)))
        assertTrue(children.all { !it.isAvailable(project, myFixture.editor, myFixture.file) })
    }
}
