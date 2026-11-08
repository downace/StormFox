package com.github.downace.stormfox.lint

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ex.ExternalAnnotatorBatchInspection
import com.intellij.openapi.application.ApplicationManager

class MagoLintInspection : LocalInspectionTool(), ExternalAnnotatorBatchInspection {
    init {
        ApplicationManager.getApplication().getService(MagoInspectionResultsLifetime::class.java)
    }

    override fun getShortName(): String = SHORT_NAME
    override fun getDisplayName(): String = "Mago validation"
    override fun getGroupDisplayName(): String = "Quality tools"
    override fun getGroupPath(): Array<String> = arrayOf("PHP", groupDisplayName)
    override fun isEnabledByDefault(): Boolean = false

    companion object {
        const val SHORT_NAME = "MagoLint"
    }
}
