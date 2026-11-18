package com.github.downace.stormfox.format

import com.github.downace.stormfox.settings.MagoSettings
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import com.intellij.psi.codeStyle.CodeStyleSettings
import com.intellij.psi.codeStyle.modifier.CodeStyleSettingsModifier
import com.intellij.psi.codeStyle.modifier.CodeStyleStatusBarUIContributor
import com.intellij.psi.codeStyle.modifier.TransientCodeStyleSettings
import java.util.function.Consumer

class MagoCodeStyleSettingsModifier : CodeStyleSettingsModifier {
    override fun modifySettings(settings: TransientCodeStyleSettings, file: PsiFile): Boolean = false

    override fun getName(): String = "Mago"

    override fun getStatusBarUiContributor(
        transientSettings: TransientCodeStyleSettings,
    ): CodeStyleStatusBarUIContributor? = null

    override fun mayOverrideSettingsOf(project: Project): Boolean {
        val state = MagoSettings.getInstance(project).state
        return state.enabled || (state.twigEnabled ?: state.enabled)
    }

    override fun getDisablingFunction(project: Project): Consumer<CodeStyleSettings> = Consumer {
        val state = MagoSettings.getInstance(project).state
        state.enabled = false
        state.twigEnabled = false
    }
}
