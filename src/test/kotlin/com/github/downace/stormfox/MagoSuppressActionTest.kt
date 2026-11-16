package com.github.downace.stormfox

import com.github.downace.stormfox.lint.MagoDiagnosticReport
import com.github.downace.stormfox.lint.MagoRelatedNavigation
import com.github.downace.stormfox.lint.MagoSuppressAction
import com.github.downace.stormfox.lint.MagoTool
import com.github.downace.stormfox.lint.MagoLintAnnotator
import com.github.downace.stormfox.lint.MagoLintInspection
import com.github.downace.stormfox.lint.MagoDiagnosticAnnotator
import com.github.downace.stormfox.lint.MagoDiagnosticRunner
import com.intellij.codeInsight.intention.IntentionActionWithOptions
import com.intellij.lang.annotation.AnnotationBuilder
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.modcommand.ActionContext
import com.intellij.modcommand.ModCommand
import com.intellij.modcommand.ModCommandAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File
import java.lang.reflect.Proxy

class MagoSuppressActionTest : BasePlatformTestCase() {
    private fun action(text: String, source: MagoTool = MagoTool.LINT, code: String? = "some-rule",
                       mode: MagoSuppressAction.Mode = MagoSuppressAction.Mode.EXPECT, marker: String = "echo") =
        MagoSuppressAction(
            MagoDiagnosticReport.Diagnostic(TextRange(text.indexOf(marker), text.indexOf(marker) + marker.length),
                "issue", code, "warning", snapshotHash = MagoRelatedNavigation.snapshotHash(text), source = source),
            myFixture.file.virtualFile, mode,
        ).asIntention()

    fun testPreviewAndApplyForEachTool() {
        for ((tool, category) in listOf(MagoTool.LINT to "lint", MagoTool.ANALYZE to "analysis", MagoTool.GUARD to "guard")) {
            val text = "<?php\n    echo 'ü';\n"
            myFixture.configureByText("Sample.php", text)
            val intention = action(text, tool)
            val expected = "<?php\n    // @mago-expect $category:some-rule(1)\n    echo 'ü';\n"
            assertEquals(expected, myFixture.getIntentionPreviewText(intention))
            myFixture.launchAction(intention)
            assertEquals(expected, myFixture.editor.document.text)
        }
    }

    fun testInlineOpeningTagAndStaleGuard() {
        val text = "<?php echo 1;"
        myFixture.configureByText("Sample.php", text)
        val intention = action(text)
        assertTrue(intention.isAvailable(project, myFixture.editor, myFixture.file))
        myFixture.launchAction(intention)
        assertEquals("<?php\n// @mago-expect lint:some-rule(1)\n echo 1;", myFixture.editor.document.text)
        assertFalse(intention.isAvailable(project, myFixture.editor, myFixture.file))
    }

    fun testRejectsInvalidCode() {
        val text = "<?php\n// @mago-ignore lint:other\n echo 1;"
        myFixture.configureByText("Sample.php", text)
        assertTrue(action(text).isAvailable(project, myFixture.editor, myFixture.file))
        assertFalse(action(text, code = "all").isAvailable(project, myFixture.editor, myFixture.file))
    }

    fun testIgnorePreviewApplicationAndUnicodeBeforeDiagnostic() {
        val text = "<?php\n// café 😀\n\techo 1;"
        myFixture.configureByText("Sample.php", text)
        val intention = action(text, mode = MagoSuppressAction.Mode.IGNORE)
        val expected = "<?php\n// café 😀\n\t// @mago-ignore lint:some-rule(1)\n\techo 1;"
        assertEquals(expected, myFixture.getIntentionPreviewText(intention))
        myFixture.launchAction(intention)
        assertEquals(expected, myFixture.editor.document.text)
    }

    fun testTabAfterOpeningTag() {
        val text = "<?php\techo 1;"
        myFixture.configureByText("Sample.php", text)
        val intention = action(text)
        assertTrue(intention.isAvailable(project, myFixture.editor, myFixture.file))
        myFixture.launchAction(intention)
        assertEquals("<?php\n// @mago-expect lint:some-rule(1)\n\techo 1;", myFixture.editor.document.text)
    }

    fun testPlannerPreservesCrLf() {
        val text = "<?php\r\n  echo 1;\r\n"
        val offset = text.indexOf("echo")
        val diagnostic = MagoDiagnosticReport.Diagnostic(TextRange(offset, offset + 4), "issue", "some-rule", "warning")
        val (at, insertion) = MagoSuppressAction.plan(text, diagnostic, MagoSuppressAction.Mode.EXPECT) { true }!!
        assertEquals("<?php\r\n  // @mago-expect lint:some-rule(1)\r\n  echo 1;\r\n",
            text.substring(0, at) + insertion + text.substring(at))
    }

    fun testWrongFileAndUncommittedEdits() {
        val text = "<?php\necho 1;"
        myFixture.configureByText("Sample.php", text)
        val intention = action(text)
        val command = (intention as IntentionActionWithOptions).asModCommandAction()!!
        WriteCommandAction.runWriteCommandAction(project) { myFixture.editor.document.insertString(0, " ") }
        assertFalse(PsiDocumentManager.getInstance(project).isCommitted(myFixture.editor.document))
        var context = ActionContext.from(myFixture.editor, myFixture.file)
        assertNull(command.getPresentation(context))
        assertEquals(ModCommand.nop(), command.perform(context))
        myFixture.configureByText("Other.php", text)
        context = ActionContext.from(myFixture.editor, myFixture.file)
        assertNull(command.getPresentation(context))
        assertEquals(ModCommand.nop(), command.perform(context))
    }

    fun testRejectsHtmlAndMultilineStrings() {
        for (text in listOf(
            "<html>echo 1;</html>",
            "<?php\n\$s = '\necho 1;';",
        )) {
            myFixture.configureByText("Sample.php", text)
            assertFalse("Unsafe: $text", action(text).isAvailable(project, myFixture.editor, myFixture.file))
        }
    }

    fun testDiagnosticOnSingleLineString() {
        val text = "<?php\n\$value = 'hello';"
        myFixture.configureByText("Sample.php", text)
        val intention = action(text, marker = "'hello'")
        assertTrue(intention.isAvailable(project, myFixture.editor, myFixture.file))
        myFixture.launchAction(intention)
        assertEquals("<?php\n// @mago-expect lint:some-rule(1)\n\$value = 'hello';", myFixture.editor.document.text)
    }

    fun testDocumentedFunctionKeepsDocblockAttached() {
        val text = "<?php\n    /**\n     * Documentation.\n     */\n    function example() {}"
        myFixture.configureByText("Sample.php", text)
        val intention = action(text, marker = "function")
        assertTrue(intention.isAvailable(project, myFixture.editor, myFixture.file))
        myFixture.launchAction(intention)
        assertEquals("<?php\n    // @mago-expect lint:some-rule(1)\n    /**\n     * Documentation.\n     */\n    function example() {}",
            myFixture.editor.document.text)
    }

    fun testRepeatedAndMixedPragmasAreStacked() {
        val text = "<?php\n// @mago-expect lint:some-rule(1)\necho 1;"
        myFixture.configureByText("Sample.php", text)
        val next = action(text, mode = MagoSuppressAction.Mode.IGNORE)
        assertTrue(next.isAvailable(project, myFixture.editor, myFixture.file))
        myFixture.launchAction(next)
        val updated = "<?php\n// @mago-expect lint:some-rule(1)\n// @mago-ignore lint:some-rule(1)\necho 1;"
        assertEquals(updated, myFixture.editor.document.text)
        val repeated = action(updated)
        assertTrue(repeated.isAvailable(project, myFixture.editor, myFixture.file))
        myFixture.launchAction(repeated)
        assertEquals(updated.replace("echo 1;", "// @mago-expect lint:some-rule(1)\necho 1;"),
            myFixture.editor.document.text)
    }

    fun testNoAutofixRegistrationAndAvailableChildren() {
        val text = "<?php\necho 1;"
        myFixture.configureByText("Sample.php", text)
        myFixture.enableInspections(MagoLintInspection())
        val path = myFixture.file.virtualFile.path
        fun registered(code: String?): List<ModCommandAction> {
            val offset = text.indexOf("echo")
            val diagnostic = MagoDiagnosticReport.Diagnostic(TextRange(offset, offset + 4), "issue", code, "warning",
                snapshotHash = MagoRelatedNavigation.snapshotHash(text))
            val actions = mutableListOf<ModCommandAction>()
            val builder = Proxy.newProxyInstance(AnnotationBuilder::class.java.classLoader, arrayOf(AnnotationBuilder::class.java)) { proxy, method, args ->
                if (method.name == "newFix") {
                    val action = args!![0] as ModCommandAction
                    return@newProxyInstance Proxy.newProxyInstance(AnnotationBuilder.FixBuilder::class.java.classLoader, arrayOf(AnnotationBuilder.FixBuilder::class.java)) { fixProxy, fixMethod, _ ->
                        when (fixMethod.name) {
                            "key" -> fixProxy
                            "registerFix" -> { actions.add(action); proxy }
                            else -> null
                        }
                    }
                }
                if (method.returnType == AnnotationBuilder::class.java) proxy else null
            }
            val holder = Proxy.newProxyInstance(AnnotationHolder::class.java.classLoader, arrayOf(AnnotationHolder::class.java)) { _, method, _ ->
                if (method.name == "newAnnotation") builder else null
            } as AnnotationHolder
            MagoLintAnnotator().apply(myFixture.file, MagoDiagnosticAnnotator.Report(
                MagoDiagnosticAnnotator.Snapshot(text, path, File("."), "mago", ""),
                MagoDiagnosticRunner.Result.Success(listOf(diagnostic))), holder)
            return actions
        }
        for (code in listOf(null, "", "all", "bad code", "lint:rule")) assertTrue(registered(code).isEmpty())
        val parent = registered("some-rule").single().asIntention()
        assertTrue(parent.isAvailable(project, myFixture.editor, myFixture.file))
        val options = (parent as IntentionActionWithOptions).options
        assertEquals(listOf("Suppress with @mago-expect", "Suppress with @mago-ignore"), options.map { it.text })
        assertTrue(options.all { it.isAvailable(project, myFixture.editor, myFixture.file) })
    }
}
