package com.github.downace.stormfox

import com.github.downace.stormfox.lint.MagoInspectionResultsLifetime
import com.github.downace.stormfox.lint.MagoLintInspection
import com.intellij.analysis.AnalysisScope
import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ex.GlobalInspectionContextImpl
import com.intellij.codeInspection.ex.InspectionProfileImpl
import com.intellij.codeInspection.ex.InspectionRVContentProviderImpl
import com.intellij.codeInspection.ex.InspectionManagerEx
import com.intellij.codeInspection.ex.LocalInspectionToolWrapper
import com.intellij.codeInspection.ui.InspectionResultsView
import com.intellij.ide.plugins.DynamicPlugins
import com.intellij.platform.testFramework.loadDescriptorInTest
import com.intellij.openapi.application.Application
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.NotNullLazyValue
import com.intellij.openapi.wm.ToolWindowAnchor
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.ToolWindow
import com.intellij.testFramework.replaceService
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.toolWindow.ToolWindowHeadlessManagerImpl
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import org.jetbrains.org.objectweb.asm.ClassReader
import org.jetbrains.org.objectweb.asm.ClassVisitor
import org.jetbrains.org.objectweb.asm.MethodVisitor
import org.jetbrains.org.objectweb.asm.Opcodes
import org.jetbrains.org.objectweb.asm.ClassWriter
import org.jetbrains.org.objectweb.asm.commons.ClassRemapper
import org.jetbrains.org.objectweb.asm.commons.SimpleRemapper

class MagoInspectionResultsLifetimeTest : BasePlatformTestCase() {
    fun testDynamicPluginUnloadDisposesActivatedLightService() {
        val directory = Files.createTempDirectory("stormfox-lifetime-")
        Disposer.register(testRootDisposable) {
            Files.walk(directory).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
        val jar = directory.resolve("lifetime.jar")
        val sourcePackage = "com/github/downace/stormfox/lint/"
        val targetPackage = "stormfox/lifetime/fixture/"
        val names = listOf("MagoInspectionResultsLifetime", "MagoLintInspection", "MagoLintInspection\$Companion")
        val remapper = SimpleRemapper(names.associate { sourcePackage + it to targetPackage + it })
        JarOutputStream(Files.newOutputStream(jar)).use { output ->
            output.putNextEntry(JarEntry("META-INF/plugin.xml"))
            output.write("""<idea-plugin><id>stormfox.lifetime.fixture</id><name>Lifetime fixture</name><version>1</version><vendor>Test</vendor><depends>com.intellij.modules.lang</depends></idea-plugin>""".toByteArray())
            output.closeEntry()
            for (name in names) {
                val writer = ClassWriter(0)
                javaClass.classLoader.getResourceAsStream("$sourcePackage$name.class")!!.use {
                    ClassReader(it).accept(ClassRemapper(writer, remapper), 0)
                }
                output.putNextEntry(JarEntry("$targetPackage$name.class"))
                output.write(writer.toByteArray())
                output.closeEntry()
            }
        }
        val descriptor = loadDescriptorInTest(jar)
        val manager = InspectionManager.getInstance(project) as InspectionManagerEx
        val contexts = mutableListOf<GlobalInspectionContextImpl>()
        var loaded = false
        try {
            assertTrue(DynamicPlugins.loadPlugin(descriptor))
            loaded = true
            val application = ApplicationManager.getApplication()
            val loader = descriptor.pluginClassLoader!!
            val lifetimeClass = loader.loadClass("stormfox.lifetime.fixture.MagoInspectionResultsLifetime")
            assertNull(application.getServiceIfCreated(lifetimeClass))
            loader.loadClass("stormfox.lifetime.fixture.MagoLintInspection").getConstructor().newInstance()
            assertNotNull(application.getServiceIfCreated(lifetimeClass))
            val affected = createResults(manager, MagoLintInspection()).also(contexts::add)
            val unrelated = createResults(manager, UnrelatedInspection()).also(contexts::add)
            val secondAffected = createResults(manager, MagoLintInspection()).also(contexts::add)
            val views = listOf(affected.view!!, secondAffected.view!!)

            val unloaded = DynamicPlugins.unloadPlugin(descriptor)
            loaded = false
            assertTrue(unloaded)
            assertNull(application.getServiceIfCreated(lifetimeClass))
            assertTrue(views.all { it.isDisposed })
            assertFalse(manager.runningContexts.contains(affected))
            assertFalse(manager.runningContexts.contains(secondAffected))
            assertTrue(manager.runningContexts.contains(unrelated))
            assertFalse(unrelated.view!!.isDisposed)
        } finally {
            if (loaded) DynamicPlugins.unloadPlugin(descriptor)
            contexts.filter { manager.runningContexts.contains(it) }.forEach { it.close(true) }
        }
    }

    fun testCleanupDoesNotConsumeRerunState() {
        val listenerClass = MagoInspectionResultsLifetime::class.java
        listenerClass.getResourceAsStream("${listenerClass.simpleName}.class")!!.use { bytecode ->
            ClassReader(bytecode).accept(object : ClassVisitor(Opcodes.ASM9) {
                override fun visitMethod(
                    access: Int, name: String, descriptor: String, signature: String?, exceptions: Array<out String>?,
                ): MethodVisitor? {
                    return object : MethodVisitor(Opcodes.ASM9) {
                        override fun visitMethodInsn(opcode: Int, owner: String, name: String, descriptor: String, isInterface: Boolean) {
                            assertFalse("isRerun consumes state and must not be used as a guard", name == "isRerun")
                        }
                    }
                }
            }, ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
        }
    }

    fun testCleanupClosesOnlyInitializedMagoResultTabs() {
        val manager = InspectionManager.getInstance(project) as InspectionManagerEx
        val contexts = mutableListOf<GlobalInspectionContextImpl>()
        try {
            val affected = createResults(manager, MagoLintInspection()).also(contexts::add)
            val unrelated = createResults(manager, UnrelatedInspection()).also(contexts::add)
            val secondAffected = createResults(manager, MagoLintInspection()).also(contexts::add)
            val affectedView = affected.view!!
            val unrelatedView = unrelated.view!!
            val secondAffectedView = secondAffected.view!!
            val listener = MagoInspectionResultsLifetime(ApplicationManager.getApplication())

            assertTrue(listener.hasMagoResults(affectedView))
            assertFalse(listener.hasMagoResults(unrelatedView))
            assertTrue(listener.hasMagoResults(secondAffectedView))

            affected.currentProfile.disableAllTools(project)
            assertTrue(listener.hasMagoResults(affectedView))
            val windows = ToolWindowManager.getInstance(project)
            assertTrue(windows.toolWindowIds.contains("Unload test"))
            assertNotNull(windows.getToolWindow("Unload test")!!.contentManagerIfCreated)
            Disposer.dispose(listener)

            for (context in listOf(affected, secondAffected)) {
                assertFalse(manager.runningContexts.contains(context))
                assertNull(context.view)
            }
            assertTrue(affectedView.isDisposed)
            assertTrue(secondAffectedView.isDisposed)
            assertTrue(manager.runningContexts.contains(unrelated))
            assertSame(unrelatedView, unrelated.view)
            assertFalse(unrelatedView.isDisposed)
        } finally {
            contexts.filter { manager.runningContexts.contains(it) }.forEach { it.close(true) }
        }
    }

    fun testUpdatingAndStaleScopeResultsArePreserved() {
        val manager = InspectionManager.getInstance(project) as InspectionManagerEx
        val context = createResults(manager, MagoLintInspection())
        try {
            val view = context.view!!
            val listener = MagoInspectionResultsLifetime(ApplicationManager.getApplication())
            view.setUpdating(true)
            assertFalse(listener.hasMagoResults(view))
            listener.closeMagoResults(project)
            assertTrue(manager.runningContexts.contains(context))

            view.setUpdating(false)
            context.setCurrentScope(AnalysisScope(project))
            assertFalse(listener.hasMagoResults(view))
            listener.closeMagoResults(project)
            assertTrue(manager.runningContexts.contains(context))
            assertFalse(view.isDisposed)
        } finally {
            context.close(true)
        }
    }

    private fun createResults(manager: InspectionManagerEx, tool: LocalInspectionTool): GlobalInspectionContextImpl {
        val profile = InspectionProfileImpl("Unload test ${tool.shortName}")
        profile.disableAllTools(project)
        profile.addTool(project, LocalInspectionToolWrapper(tool), null)
        profile.enableTool(tool.shortName, project)
        val context = createMountedContext(manager)
        try {
            context.setExternalProfile(profile)
            context.setCurrentScope(AnalysisScope(project))
            context.initializeTools(mutableListOf(), mutableListOf(), mutableListOf())
            context.addView(InspectionResultsView(context, InspectionRVContentProviderImpl()), tool.shortName, true)
            return context
        } catch (error: Throwable) {
            context.close(true)
            throw error
        }
    }

    private fun createMountedContext(manager: InspectionManagerEx): GlobalInspectionContextImpl {
        var windows = ToolWindowManager.getInstance(project)
        if (!windows.toolWindowIds.contains("Unload test")) {
            // The headless manager neither enumerates registered windows nor exposes created content managers.
            val delegate = windows.registerToolWindow("Unload test", true, ToolWindowAnchor.BOTTOM, testRootDisposable)
            val contents = delegate.contentManager
            val window = Proxy.newProxyInstance(ToolWindow::class.java.classLoader, arrayOf(ToolWindow::class.java)) { _, method, args ->
                if (method.name in setOf("getContentManager", "getContentManagerIfCreated")) contents
                else method.invoke(delegate, *(args ?: emptyArray()))
            } as ToolWindow
            windows = object : ToolWindowHeadlessManagerImpl(project) {
                override val toolWindowIds = arrayOf("Unload test")
                override fun getToolWindow(id: String?): ToolWindow? = if (id == "Unload test") window else null
            }
            project.replaceService(ToolWindowManager::class.java, windows, testRootDisposable)
        }
        val window = windows.getToolWindow("Unload test")!!
        val context = GlobalInspectionContextImpl(project, NotNullLazyValue.createValue { window.contentManager })
        manager.runningContexts.add(context)
        return context
    }

    private class UnrelatedInspection : LocalInspectionTool() {
        override fun getShortName() = "StormFoxUnloadUnrelated"
        override fun getDisplayName() = "Unrelated inspection"
        override fun getGroupDisplayName() = "Test"
    }

    fun testCleanupDoesNotCreateToolWindowManager() {
        val before = project.getServiceIfCreated(ToolWindowManager::class.java)
        MagoInspectionResultsLifetime(ApplicationManager.getApplication()).closeMagoResults(project)
        assertSame(before, project.getServiceIfCreated(ToolWindowManager::class.java))
    }

    fun testUninitializedContextIsPreservedWithoutReadingTools() {
        val manager = InspectionManager.getInstance(project) as InspectionManagerEx
        val context = createMountedContext(manager)
        try {
            val listener = MagoInspectionResultsLifetime(ApplicationManager.getApplication())
            context.setCurrentScope(AnalysisScope(project))
            context.addView(InspectionResultsView(context, InspectionRVContentProviderImpl()), "Uninitialized", true)
            assertFalse(listener.hasMagoResults(context.view!!))
            listener.closeMagoResults(project)
            assertTrue(manager.runningContexts.contains(context))
        } finally {
            context.close(true)
        }
    }

    fun testInspectionActivatesLifetimeService() {
        val application = ApplicationManager.getApplication()
        val isolated = MagoInspectionResultsLifetime(application)
        application.replaceService(MagoInspectionResultsLifetime::class.java, isolated, testRootDisposable)
        MagoLintInspection()
        assertSame(isolated, application.getServiceIfCreated(MagoInspectionResultsLifetime::class.java))
    }

    fun testConstructorIsInertAndShutdownDoesNotAccessProjects() {
        var accesses = 0
        val application = Proxy.newProxyInstance(Application::class.java.classLoader, arrayOf(Application::class.java)) { _, method, _ ->
            accesses++
            check(method.name == "isDisposed") { "Unexpected application access: ${method.name}" }
            true
        } as Application
        val lifetime = MagoInspectionResultsLifetime(application)
        assertEquals(0, accesses)
        Disposer.dispose(lifetime)
        assertEquals(1, accesses)
    }

    fun testDisposalDoesNotCreateProjectManager() {
        var lookedUp = false
        val application = Proxy.newProxyInstance(Application::class.java.classLoader, arrayOf(Application::class.java)) { _, method, args ->
            when (method.name) {
                "isDisposed" -> false
                "assertIsDispatchThread" -> null
                "getServiceIfCreated" -> {
                    assertSame(ProjectManager::class.java, args!![0])
                    lookedUp = true
                    null
                }
                else -> error("Unexpected application access: ${method.name}")
            }
        } as Application
        Disposer.dispose(MagoInspectionResultsLifetime(application))
        assertTrue(lookedUp)
    }
}
