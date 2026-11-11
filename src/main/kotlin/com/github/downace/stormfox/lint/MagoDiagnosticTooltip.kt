package com.github.downace.stormfox.lint

import java.nio.charset.StandardCharsets
import java.util.Base64

internal object MagoDiagnosticTooltip {
    const val PREFIX = "stormfox-mago-details:"

    fun compact(diagnostic: MagoDiagnosticReport.Diagnostic): String {
        val message = diagnostic.summary
        val code = diagnostic.code?.takeIf { it.isNotBlank() }?.let { " <code>${renderText(it)}</code>" }.orEmpty()
        val extra = expanded(diagnostic)
        val details = if (extra == null) "" else " &nbsp; ${detailsLink(extra)}"
        return "<html><b>Mago</b>: ${renderText(message)}$code$details</html>"
    }

    fun expanded(diagnostic: MagoDiagnosticReport.Diagnostic): String? = buildString {
        var hasContent = false
        fun section(content: String) {
            append(content)
            hasContent = true
        }
        diagnostic.message.takeIf {
            it.isNotBlank() && it.trim() != diagnostic.summary.trim()
        }?.let {
            section("<p>${renderText(it)}</p>")
        }
        val help = diagnostic.help?.takeIf { it.isNotBlank() }?.let(::renderText)
        val notes = diagnostic.notes.filter { it.isNotBlank() }.takeIf { it.isNotEmpty() }
            ?.joinToString("<br>") { renderText(it) }
        help?.let {
            section("<p><icon src=\"com.intellij.icons.AllIcons.Actions.IntentionBulb\" alt=\"Help\"/>&nbsp;&nbsp;$it</p>")
        }
        notes?.let {
            section("<p><icon src=\"com.intellij.icons.AllIcons.General.Information\" alt=\"Notes\"/>&nbsp;&nbsp;$it</p>")
        }
        diagnostic.code?.takeIf { it.isNotBlank() }?.let {
            val source = diagnostic.source
            val url = source.documentationUrl + if (source.documentationCodeAnchor) encodeFragment(it) else ""
            section("<p><a href=\"$url\">${source.documentationLabel}</a></p>")
        }
        if (!hasContent) return null
    }.let { "<html><body>$it</body></html>" }

    fun decode(refSuffix: String): String? {
        return runCatching {
            String(Base64.getUrlDecoder().decode(refSuffix), StandardCharsets.UTF_8)
        }.getOrNull()
    }

    fun detailsUrl(html: String): String = PREFIX + Base64.getUrlEncoder().withoutPadding()
        .encodeToString(html.toByteArray(StandardCharsets.UTF_8))

    private fun detailsLink(html: String): String =
        "<a href=\"${detailsUrl(html)}\">Details</a>"

    private fun renderText(value: String): String {
        val escaped = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&#39;")
        return Regex("`([^`]+)`").replace(escaped) { "<code>${it.groupValues[1]}</code>" }
            .replace("\r\n", "\n").replace("\r", "\n").replace("\n", "<br>")
    }

    private fun encodeFragment(value: String): String = value.toByteArray(StandardCharsets.UTF_8).joinToString("") {
        val byte = it.toInt() and 0xff
        if ((byte in 'a'.code..'z'.code) || (byte in 'A'.code..'Z'.code) || (byte in '0'.code..'9'.code) ||
            byte == '-'.code || byte == '_'.code || byte == '.'.code || byte == '~'.code
        ) byte.toChar().toString() else "%%%02X".format(byte)
    }
}
