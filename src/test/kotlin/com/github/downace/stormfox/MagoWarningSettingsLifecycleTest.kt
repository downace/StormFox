package com.github.downace.stormfox

import com.github.downace.stormfox.lint.MagoAnalyzeInspection
import com.github.downace.stormfox.lint.MagoGuardInspection
import com.github.downace.stormfox.lint.MagoLintInspection
import com.intellij.openapi.options.ex.ConfigurableExtensionPointUtil
import com.intellij.profile.codeInspection.InspectionProjectProfileManager
import com.intellij.profile.codeInspection.ui.ErrorsConfigurable
import com.intellij.profile.codeInspection.ui.ErrorsConfigurableProvider
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class MagoWarningSettingsLifecycleTest : BasePlatformTestCase() {
    fun testColdInspectionConfigurableCanSelectEachToolAfterInitialization() {
        myFixture.enableInspections(MagoLintInspection(), MagoAnalyzeInspection(), MagoGuardInspection())
        val configurable = ConfigurableExtensionPointUtil.createProjectConfigurableForProvider(
            project, ErrorsConfigurableProvider::class.java) as ErrorsConfigurable
        try {
            assertNotNull(configurable.createComponent())
            configurable.reset()
            val profile = InspectionProjectProfileManager.getInstance(project).currentProfile
            repeat(2) {
                configurable.selectProfile(profile)
                for (shortName in listOf("MagoGuard", "MagoAnalyze", "MagoLint")) {
                    configurable.selectInspectionTool(shortName)
                }
            }
            assertNotNull(configurable.selectedObject)
        } finally {
            configurable.disposeUIResources()
        }
    }
}
