package com.github.downace.stormfox.lint

import com.intellij.codeInsight.highlighting.TooltipLinkHandler
import com.intellij.openapi.editor.Editor

class MagoDiagnosticTooltipLinkHandler : TooltipLinkHandler() {
    override fun getDescription(refSuffix: String, editor: Editor): String? = MagoDiagnosticTooltip.decode(refSuffix)

    override fun getDescriptionTitle(refSuffix: String, editor: Editor): String = "Details"
}
