package com.github.downace.stormfox

import com.github.downace.stormfox.settings.MagoSettings
import com.github.downace.stormfox.settings.LegacyMagoSettings
import com.github.downace.stormfox.settings.MagoSettingsListener
import com.github.downace.stormfox.settings.MagoSettingsSnapshot
import com.github.downace.stormfox.lint.MagoDiagnosticService
import com.intellij.openapi.components.RoamingType
import com.intellij.openapi.components.State
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.impl.stores.stateStore
import com.intellij.ide.impl.runUnderModalProgressIfIsEdt
import com.intellij.testFramework.PlatformTestUtil
import java.nio.file.Files
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.openapi.util.JDOMUtil
import com.intellij.util.xmlb.XmlSerializer
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MagoSettingsStorageTest {
    @Test
    fun localExecutableAndConfigPathsDoNotRoam() {
        val storage = LegacyMagoSettings::class.java.getAnnotation(State::class.java).storages.single()
        assertEquals("mago.xml", storage.value)
        assertEquals(RoamingType.DISABLED, storage.roamingType)
        assertEquals(StoragePathMacros.WORKSPACE_FILE, MagoSettings::class.java.getAnnotation(State::class.java).storages.single().value)
    }
}

class MagoSettingsProjectLifecycleTest : BasePlatformTestCase() {
    fun testWorkspaceMigrationSurvivesProjectReopenAndApplicationSave() {
        val app = ApplicationManager.getApplication()
        val legacy = app.getService(LegacyMagoSettings::class.java)
        val previous = legacy.state
        val options = java.nio.file.Path.of(PathManager.getOptionsPath()).resolve("mago.xml")
        val before = if (Files.exists(options)) Files.readAllBytes(options) else null
        val root = Files.createTempDirectory("mago-project-lifecycle-")
        val firstPath = Files.createDirectory(root.resolve("first"))
        val secondPath = Files.createDirectory(root.resolve("second"))
        Files.createDirectory(firstPath.resolve(".idea"))
        Files.createDirectory(secondPath.resolve(".idea"))
        val manager = ProjectManager.getInstance()
        val opened = mutableListOf<com.intellij.openapi.project.Project>()
        fun open(path: java.nio.file.Path): com.intellij.openapi.project.Project {
            val project = requireNotNull(manager.loadAndOpenProject(path.toString()))
            assertFalse(project.isDefault)
            opened.add(project)
            return project
        }
        fun close(project: com.intellij.openapi.project.Project) {
            PlatformTestUtil.saveProject(project, true)
            assertTrue(manager.closeAndDispose(project))
            opened.remove(project)
        }
        fun saveApplication() = runUnderModalProgressIfIsEdt {
            app.stateStore.save(forceSavingAllSettings = true)
        }
        try {
            Files.createDirectories(options.parent)
            val oldXml = """<application><component name="MagoSettings"><option name="magoPath" value="legacy-mago" /><option name="configPath" value="legacy.toml" /><option name="executionRoot" value="/legacy" /><option name="enabled" value="false" /><option name="showStatusWidget" value="false" /></component></application>"""
            Files.writeString(options, oldXml)
            legacy.loadState(MagoSettings.State().also {
                it.magoPath = "legacy-mago"
                it.configPath = "legacy.toml"
                it.executionRoot = "/legacy"
                it.enabled = false
                it.showStatusWidget = false
            })
            saveApplication()
            assertTrue(Files.exists(options))
            val savedLegacy = JDOMUtil.load(options).getChild("component")
            val legacyValues = savedLegacy.getChildren("option").associate { it.getAttributeValue("name") to it.getAttributeValue("value") }
            assertEquals("legacy-mago", legacyValues["magoPath"])
            assertEquals("legacy.toml", legacyValues["configPath"])
            assertEquals("/legacy", legacyValues["executionRoot"])
            assertEquals("false", legacyValues["enabled"])
            assertEquals("false", legacyValues["showStatusWidget"])
            println("Legacy XML rewritten by forced application save: ${Files.readString(options) != oldXml}")
            val first = open(firstPath)
            val second = open(secondPath)
            val one = MagoSettings.getInstance(first).state
            val two = MagoSettings.getInstance(second).state
            assertEquals("legacy-mago", one.magoPath)
            assertEquals("legacy-mago", two.magoPath)
            assertNull(one.twigEnabled)
            one.magoPath = ""
            one.configPath = ""
            one.executionRoot = ""
            one.enabled = true
            one.showStatusWidget = true
            two.magoPath = "second-only"
            close(first)
            close(second)
            assertTrue(Files.readString(firstPath.resolve(".idea/workspace.xml")).contains("migrationCompleted"))
            assertTrue(Files.readString(secondPath.resolve(".idea/workspace.xml")).contains("second-only"))
            legacy.loadState(MagoSettings.State().also { it.magoPath = "changed-legacy" })
            val reopenedFirst = open(firstPath)
            val reopenedSecond = open(secondPath)
            val restoredOne = MagoSettings.getInstance(reopenedFirst).state
            val restoredTwo = MagoSettings.getInstance(reopenedSecond).state
            assertEquals("", restoredOne.magoPath)
            assertEquals("", restoredOne.configPath)
            assertEquals("", restoredOne.executionRoot)
            assertTrue(restoredOne.enabled)
            assertTrue(restoredOne.showStatusWidget)
            assertNull(restoredOne.twigEnabled)
            assertTrue(restoredOne.migrationCompleted)
            assertEquals("second-only", restoredTwo.magoPath)
            assertEquals("legacy.toml", restoredTwo.configPath)
            assertTrue(restoredTwo.migrationCompleted)

            saveApplication()
            assertTrue("Legacy file was deleted during application save", Files.exists(options))
            assertTrue("Forced application save did not write legacy state", Files.readString(options).contains("changed-legacy"))
            val saved = Files.readString(options)
            saveApplication()
            assertTrue("Legacy file was deleted during repeated application save", Files.exists(options))
            assertEquals(saved, Files.readString(options))
            legacy.loadState(MagoSettings.State())
            saveApplication()
            println("Legacy XML exists after saving default application state: ${Files.exists(options)}")
        } finally {
            opened.toList().forEach { if (!it.isDisposed) manager.closeAndDispose(it) }
            legacy.loadState(previous)
            if (before == null) Files.deleteIfExists(options) else Files.write(options, before)
            root.toFile().deleteRecursively()
        }
    }
}

class MagoSettingsSerializationTest : BasePlatformTestCase() {
    fun testSettingsEventsTrackChangesAndDetachReplacedState() {
        val component = MagoSettings(project)
        val events = mutableListOf<MagoSettingsSnapshot>()
        project.messageBus.connect(testRootDisposable).subscribe(MagoSettingsListener.TOPIC,
            MagoSettingsListener { events.add(it) })
        val old = component.state
        old.configPath = "custom"
        old.magoPath = "test-mago"
        old.executionRoot = "/app"
        assertEquals(listOf(
            MagoSettingsSnapshot("", "", "custom"),
            MagoSettingsSnapshot("test-mago", "", "custom"),
            MagoSettingsSnapshot("test-mago", "/app", "custom"),
        ), events)
        old.configPath = "custom"
        old.enabled = false
        assertEquals(3, events.size)
        val replacement = MagoSettings.State().also { it.configPath = "other" }
        component.loadState(replacement)
        assertEquals(MagoSettingsSnapshot("", "", "other"), events.last())
        val count = events.size
        old.configPath = "detached"
        component.loadState(replacement)
        assertEquals(count, events.size)
        replacement.configPath = "new"
        assertEquals(MagoSettingsSnapshot("", "", "new"), events.last())
    }
    fun testProjectServicesAreIsolated() {
        val other = ProjectManager.getInstance().defaultProject
        val first = MagoSettings.getInstance(project)
        val second = MagoSettings.getInstance(other)
        assertNotSame(first, second)
        assertNotSame(MagoVersionService.getInstance(project), MagoVersionService.getInstance(other))
        val originalFirst = first.state.magoPath
        val originalSecond = second.state.magoPath
        val firstEvents = mutableListOf<MagoSettingsSnapshot>()
        val secondEvents = mutableListOf<MagoSettingsSnapshot>()
        project.messageBus.connect(testRootDisposable).subscribe(MagoSettingsListener.TOPIC,
            MagoSettingsListener { firstEvents.add(it) })
        other.messageBus.connect(testRootDisposable).subscribe(MagoSettingsListener.TOPIC,
            MagoSettingsListener { secondEvents.add(it) })
        try {
            first.state.magoPath = "project-one"
            assertEquals(listOf(first.state.snapshot()), firstEvents)
            assertTrue(secondEvents.isEmpty())
            second.state.magoPath = "project-two"
            assertEquals(1, firstEvents.size)
            assertEquals(listOf(second.state.snapshot()), secondEvents)
            assertEquals("project-one", first.state.magoPath)
            assertEquals("project-two", second.state.magoPath)
        } finally {
            first.state.magoPath = originalFirst
            second.state.magoPath = originalSecond
        }
    }

    fun testMigrationCopiesLegacyValuesAndPersistedStateWins() {
        val legacy = ApplicationManager.getApplication().getService(LegacyMagoSettings::class.java)
        val original = legacy.state
        try {
            legacy.loadState(MagoSettings.State().also {
                it.magoPath = "/tools/mago"
                it.configPath = "/project/mago.toml"
                it.executionRoot = "/app"
                it.enabled = false
                it.twigEnabled = null
                it.showStatusWidget = false
            })
            val first = MagoSettings(project)
            val second = MagoSettings(project)
            first.noStateLoaded()
            second.noStateLoaded()
            assertEquals("/tools/mago", first.state.magoPath)
            assertEquals("/project/mago.toml", first.state.configPath)
            assertEquals("/app", first.state.executionRoot)
            assertFalse(first.state.enabled)
            assertNull(first.state.twigEnabled)
            assertFalse(first.state.showStatusWidget)
            assertTrue(first.state.migrationCompleted)
            first.state.magoPath = "project-one"
            assertEquals("/tools/mago", second.state.magoPath)
            val xml = XmlSerializer.serialize(first.state)
            val reloaded = MagoSettings(project)
            reloaded.loadState(XmlSerializer.deserialize(xml, MagoSettings.State::class.java))
            assertEquals("project-one", reloaded.state.magoPath)
            assertTrue(reloaded.state.migrationCompleted)

            legacy.loadState(MagoSettings.State())
            val defaults = MagoSettings(project)
            defaults.noStateLoaded()
            val defaultXml = XmlSerializer.serialize(defaults.state)
            assertTrue(JDOMUtil.writeElement(defaultXml).contains("migrationCompleted"))
            assertTrue(XmlSerializer.deserialize(defaultXml, MagoSettings.State::class.java).migrationCompleted)
        } finally {
            legacy.loadState(original)
        }
    }

    fun testPersistedColdLoadDoesNotLookUpSettingsWhileLoading() {
        // Ensure loadState takes the branch used when the version service already exists.
        val service = MagoVersionService.getInstance(project)
        val diagnostics = project.getService(MagoDiagnosticService::class.java)
        val epoch = diagnostics.epoch
        val original = MagoSettings.getInstance(project).state.snapshot()
        val xml = JDOMUtil.load("""<State><option name="magoPath" value="mago" /><option name="executionRoot" value="/app" /></State>""")
        val loaded = XmlSerializer.deserialize(xml, MagoSettings.State::class.java)
        val component = MagoSettings(project)
        val executor = Executors.newSingleThreadExecutor { action -> Thread(action, "mago-cold-load-test").apply { isDaemon = true } }
        try {
            MagoSettings.lookupGuard = {
                check(Thread.currentThread().name != "mago-cold-load-test") {
                    "Settings service lookup during persisted loadState"
                }
            }
            executor.submit { component.loadState(loaded) }.get(5, TimeUnit.SECONDS)
            assertTrue(diagnostics.epoch > epoch)
            assertEquals("mago", component.state.magoPath)
            assertEquals("/app", component.state.executionRoot)
        } finally {
            MagoSettings.lookupGuard = null
            executor.shutdownNow()
            service.refresh(original)
        }
    }

    fun testAttachedStateRoundTrip() {
        val state = MagoSettings.getInstance(project).getState()
        assertNotNull(state.onPathChanged)
        val xml = XmlSerializer.serialize(state)
        val text = JDOMUtil.writeElement(xml)
        val restored = try {
            XmlSerializer.deserialize(xml, MagoSettings.State::class.java)
        } catch (e: Exception) {
            throw AssertionError("Cannot deserialize attached settings: $text", e)
        }
        assertFalse("Callback must not be persisted: $text", text.contains("onPathChanged"))
        assertEquals(state.magoPath, restored.magoPath)
        assertEquals(state.configPath, restored.configPath)
        assertEquals(state.executionRoot, restored.executionRoot)
        assertEquals(state.enabled, restored.enabled)
        assertEquals(state.twigEnabled, restored.twigEnabled)
        assertEquals(state.showStatusWidget, restored.showStatusWidget)
    }

    fun testPreviouslySavedSettingsLoad() {
        assertPersistedSettingsLoad("")
    }

    fun testLegacyFormatterSettingAndExplicitTwigRoundTrip() {
        for (enabled in listOf(false, true)) {
            val xml = JDOMUtil.load("""<State><option name="enabled" value="$enabled" /></State>""")
            val legacy = XmlSerializer.deserialize(xml, MagoSettings.State::class.java)
            assertEquals(enabled, legacy.enabled)
            assertNull(legacy.twigEnabled)
            assertEquals(enabled, legacy.twigEnabled ?: legacy.enabled)

            for (twigEnabled in listOf(false, true)) {
                legacy.twigEnabled = twigEnabled
                val restored = XmlSerializer.deserialize(XmlSerializer.serialize(legacy), MagoSettings.State::class.java)
                assertEquals(enabled, restored.enabled)
                assertEquals(twigEnabled, restored.twigEnabled)
            }
        }
    }

    fun testPreviouslySavedCallbackIsIgnored() {
        assertPersistedSettingsLoad("""<option name="onPathChanged"><Function1 /></option>""")
    }

    private fun assertPersistedSettingsLoad(callback: String) {
        val xml = JDOMUtil.load(
            """
            <State>
              <option name="magoPath" value="/tools/mago" />
              <option name="configPath" value="/project/mago.toml" />
              <option name="executionRoot" value="/project" />
              <option name="enabled" value="false" />
              <option name="showStatusWidget" value="false" />
              $callback
            </State>
            """.trimIndent(),
        )
        val state = XmlSerializer.deserialize(xml, MagoSettings.State::class.java)
        assertEquals("/tools/mago", state.magoPath)
        assertEquals("/project/mago.toml", state.configPath)
        assertEquals("/project", state.executionRoot)
        assertFalse(state.enabled)
        assertFalse(state.showStatusWidget)
        assertNull(state.onPathChanged)
    }
}
