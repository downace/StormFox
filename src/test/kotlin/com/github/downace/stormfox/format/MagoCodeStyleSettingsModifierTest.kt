package com.github.downace.stormfox.format

import com.github.downace.stormfox.settings.MagoSettings
import com.intellij.psi.codeStyle.CodeStyleSettings
import com.intellij.psi.codeStyle.modifier.CodeStyleSettingsModifier
import com.intellij.psi.codeStyle.modifier.TransientCodeStyleSettings
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Test

class MagoCodeStyleSettingsModifierTest {
    @Test fun modifierContract() {
        object : Fixture() {}.apply { name = "testContract" }.runBare()
    }

    abstract class Fixture : BasePlatformTestCase() {
        fun testContract() {
            val state = MagoSettings.getInstance(project).state
            val enabled = state.enabled
            val twigEnabled = state.twigEnabled
            try {
                val modifier = CodeStyleSettingsModifier.EP_NAME.extensionList
                    .filterIsInstance<MagoCodeStyleSettingsModifier>().single()
                assertEquals("Mago", modifier.name)
                for (php in listOf(false, true)) {
                    for (twig in listOf(null, false, true)) {
                        state.enabled = php
                        state.twigEnabled = twig
                        assertEquals(php || (twig ?: php), modifier.mayOverrideSettingsOf(project))
                    }
                }

                val file = myFixture.configureByText("test.php", "<?php echo 1;")
                val settings = TransientCodeStyleSettings(file.virtualFile, project, CodeStyleSettings())
                val before = settings.clone()
                val dependencies = settings.dependencies.toList()
                assertFalse(modifier.modifySettings(settings, file))
                assertEquals(before, settings)
                assertEquals(dependencies, settings.dependencies)
                assertNull(settings.modifier)
                assertNull(modifier.getStatusBarUiContributor(settings))

                val path = state.magoPath
                val config = state.configPath
                val root = state.executionRoot
                val widget = state.showStatusWidget
                state.enabled = true
                state.twigEnabled = null
                val disable = modifier.getDisablingFunction(project)
                repeat(2) {
                    disable.accept(settings)
                    assertFalse(state.enabled)
                    assertEquals(false, state.twigEnabled)
                    assertFalse(modifier.mayOverrideSettingsOf(project))
                    assertEquals(path, state.magoPath)
                    assertEquals(config, state.configPath)
                    assertEquals(root, state.executionRoot)
                    assertEquals(widget, state.showStatusWidget)
                }
            } finally {
                state.enabled = enabled
                state.twigEnabled = twigEnabled
            }
        }
    }
}
