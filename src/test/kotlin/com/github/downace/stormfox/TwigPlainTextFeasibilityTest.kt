package com.github.downace.stormfox

import com.intellij.formatting.service.AsyncDocumentFormattingService
import com.intellij.formatting.service.AsyncFormattingRequest
import com.intellij.formatting.service.FormattingService
import com.intellij.formatting.FormattingContext
import com.intellij.lang.Language
import com.intellij.lang.LanguageExtensionPoint
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.ExternalAnnotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.extensions.PluginId
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.editor.Document
import com.intellij.psi.PsiFile
import com.intellij.psi.impl.source.PsiPlainTextFileImpl
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Disposable platform probe, deliberately independent of Mago and any Twig parser.
 * Requires Twig disabled and *.twig recognized as plaintext. When TextMate claims *.twig,
 * disable TextMate too. Otherwise the probe is skipped, not replaced with another file type.
 */
class TwigPlainTextFeasibilityTest {
    class ProbeAnnotator : ExternalAnnotator<String, String>() {
        override fun collectInformation(file: PsiFile): String? =
            if (file.name.endsWith(".twig")) file.text else null

        override fun doAnnotate(collectedInfo: String): String = collectedInfo

        override fun apply(file: PsiFile, annotationResult: String, holder: AnnotationHolder) {
            val start = annotationResult.indexOf("marker")
            if (start >= 0) holder.newAnnotation(HighlightSeverity.WARNING, "Twig probe")
                .range(TextRange(start, start + 6)).create()
        }
    }

    class ProbeFormatter : AsyncDocumentFormattingService() {
        override fun getFeatures(): Set<FormattingService.Feature> = emptySet()
        override fun canFormat(file: PsiFile): Boolean = file.name.endsWith(".twig")
        override fun getName(): String = "Twig probe"
        override fun getNotificationGroupId(): String = "Twig probe"
        override fun prepareForFormatting(document: Document, formattingContext: FormattingContext) = Unit
        override fun createFormattingTask(request: AsyncFormattingRequest): FormattingTask =
            object : FormattingTask {
                override fun run() = request.onTextReady(request.documentText.replace("{{  value  }}", "{{ value }}"))
                override fun cancel(): Boolean = true
            }
    }

    @Test fun plainTextDaemonAndEditorFormatting() {
        object : Fixture() {}.apply { name = "testProbe" }.runBare()
    }

    abstract class Fixture : BasePlatformTestCase() {
    fun testProbe() {
        assumeFalse("Twig plugin must be disabled for this probe",
            PluginManagerCore.isLoaded(PluginId.getId("com.jetbrains.twig")))
        ExtensionPointName<LanguageExtensionPoint<ExternalAnnotator<*, *>>>("com.intellij.externalAnnotator")
            .point.registerExtension(
                LanguageExtensionPoint(Language.ANY.id, ProbeAnnotator::class.java.name,
                    PluginManagerCore.getPlugin(PluginId.getId("com.github.downace.stormfox"))!!),
                testRootDisposable,
            )
        ExtensionPointName<FormattingService>("com.intellij.formattingService")
            .point.registerExtension(ProbeFormatter(), testRootDisposable)

        val original = "marker {{  value  }}"
        val virtualFile = myFixture.tempDirFixture.createFile("probe.twig", original)
        myFixture.configureFromExistingVirtualFile(virtualFile)
        assumeTrue("Requires plaintext *.twig; PSI=${myFixture.file.javaClass.name}, language=${myFixture.file.language.id}, type=${virtualFile.fileType.name}",
            myFixture.file is PsiPlainTextFileImpl)
        assertEquals("TEXT", myFixture.file.language.id)
        val highlights = myFixture.doHighlighting().filter { it.description == "Twig probe" }
        assertEquals(1, highlights.size)
        assertEquals(0, highlights.single().startOffset)
        assertEquals(6, highlights.single().endOffset)

        myFixture.performEditorAction("ReformatCode")
        for (attempt in 1..100) {
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            if (myFixture.editor.document.text == "marker {{ value }}") break
            Thread.sleep(20)
        }
        assertEquals("marker {{ value }}", myFixture.editor.document.text)
        assertEquals(original, String(virtualFile.contentsToByteArray(), Charsets.UTF_8))

        myFixture.configureByText("other.txt", original)
        assertTrue(myFixture.doHighlighting().none { it.description == "Twig probe" })
        myFixture.performEditorAction("ReformatCode")
        assertEquals(original, myFixture.editor.document.text)
    }
    }
}
