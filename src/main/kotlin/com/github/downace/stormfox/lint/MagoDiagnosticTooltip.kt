package com.github.downace.stormfox.lint

import java.nio.charset.StandardCharsets
import java.util.Base64

internal object MagoDiagnosticTooltip {
    const val PREFIX = "stormfox-mago-details:"
    const val CONFIG_PREFIX = "stormfox-mago-config:"

    fun compact(diagnostic: MagoDiagnosticReport.Diagnostic): String {
        val message = diagnostic.summary
        val code = diagnostic.code?.takeIf { it.isNotBlank() }?.let { " <code>${renderText(it)}</code>" }.orEmpty()
        val extra = expanded(diagnostic)
        val links = buildList {
            if (extra != null) add(detailsLink(extra))
            else add(configLink(diagnostic.source.command, diagnostic.code?.takeIf { it.isNotBlank() }))
        }.joinToString(" &nbsp; ")
        val details = " &nbsp; $links"
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
        section("<p>${configLink(diagnostic.source.command, diagnostic.code?.takeIf { it.isNotBlank() })}</p>")
    }.let { "<html><body>$it</body></html>" }

    fun decode(refSuffix: String): String? {
        return runCatching {
            String(Base64.getUrlDecoder().decode(refSuffix), StandardCharsets.UTF_8)
        }.getOrNull()
    }

    fun detailsUrl(html: String): String = PREFIX + Base64.getUrlEncoder().withoutPadding()
        .encodeToString(html.toByteArray(StandardCharsets.UTF_8))

    fun configUrl(tool: String, code: String?): String {
        val payload = listOf(tool, code ?: "").joinToString("\u0000")
        return CONFIG_PREFIX + Base64.getUrlEncoder().withoutPadding()
            .encodeToString(payload.toByteArray(StandardCharsets.UTF_8))
    }

    fun decodeConfig(refSuffix: String): Pair<String, String?>? = runCatching {
        val decoded = String(Base64.getUrlDecoder().decode(refSuffix), StandardCharsets.UTF_8)
        val parts = decoded.split('\u0000')
        if (parts.size != 2 || parts[0] !in MagoTool.entries.map { it.command } ||
            parts[1].contains('\u0000')) return null
        parts[0] to parts[1].ifEmpty { null }
    }.getOrNull()

    private fun detailsLink(html: String): String =
        "<a href=\"${detailsUrl(html)}\">Details</a>"

    private fun configLink(tool: String, code: String?): String =
        "<a href=\"${configUrl(tool, code)}\">Open Mago config</a>"

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
