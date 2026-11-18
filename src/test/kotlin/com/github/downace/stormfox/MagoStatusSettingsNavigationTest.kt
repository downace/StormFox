package com.github.downace.stormfox

import com.github.downace.stormfox.lint.MagoTool
import com.github.downace.stormfox.settings.MagoConfigurable
import com.github.downace.stormfox.status.MagoStatusSettingsNavigation
import com.intellij.ide.actions.ShowSettingsUtilImpl
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.profile.codeInspection.InspectionProjectProfileManager
import com.intellij.profile.codeInspection.ui.ErrorsConfigurable
import com.intellij.testFramework.replaceService
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class MagoStatusSettingsNavigationTest : BasePlatformTestCase() {
    fun testNativeInspectionNavigationDefersSelectionUntilConfigurableIsInitialized() {
        var capturedProject: Project? = null
        var capturedConfigurable: Configurable? = null
        var capturedInitialization: Runnable? = null
        val settings = object : ShowSettingsUtilImpl() {
            override fun editConfigurable(
                project: Project?,
                configurable: Configurable,
                advancedInitialization: Runnable?,
            ): Boolean {
                capturedProject = project
                capturedConfigurable = configurable
                capturedInitialization = advancedInitialization
                return true
            }
        }
        ApplicationManager.getApplication().replaceService(ShowSettingsUtil::class.java, settings, testRootDisposable)

        // The native action must return without selecting an inspection on an uninitialized page.
        MagoStatusSettingsNavigation.openInspection(project, MagoTool.GUARD)
        assertSame(project, capturedProject)
        val configurable = capturedConfigurable as? ErrorsConfigurable
            ?: error("Native inspection action did not supply an ErrorsConfigurable")
        val initialization = capturedInitialization ?: error("Native inspection action did not defer initialization")
        try {
            assertNotNull(configurable.createComponent())
            configurable.reset()
            initialization.run()
        } finally {
            configurable.disposeUIResources()
        }
    }

    fun testPluginNavigationSelectsConfigurableClass() {
        var invoked = false
        MagoStatusSettingsNavigation.openPlugin(project) { actualProject, configurableClass ->
            invoked = true
            assertSame(project, actualProject)
            assertEquals(MagoConfigurable::class.java, configurableClass)
        }
        assertTrue(invoked)
    }

    fun testInspectionNavigationPassesCurrentProfileAndSelectedTool() {
        val profile = InspectionProjectProfileManager.getInstance(project).currentProfile
        val expectedNames = listOf("MagoLint", "MagoAnalyze", "MagoGuard", "MagoTwigLint")
        val calls = mutableListOf<String>()

        MagoTool.entries.forEach { tool ->
            MagoStatusSettingsNavigation.openInspection(project, tool) { actualProject, actualProfile, shortName ->
                assertSame(project, actualProject)
                assertSame(profile, actualProfile)
                calls.add(shortName)
                true
            }
        }

        assertEquals(expectedNames, calls)
    }
}
