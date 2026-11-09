package com.github.downace.stormfox.lint

import com.intellij.codeInsight.highlighting.TooltipLinkHandler
import com.intellij.openapi.editor.Editor

class MagoLintTooltipLinkHandler : TooltipLinkHandler() {
    override fun getDescription(refSuffix: String, editor: Editor): String? = MagoLintTooltip.decode(refSuffix)

    override fun getDescriptionTitle(refSuffix: String, editor: Editor): String = "Details"
}
