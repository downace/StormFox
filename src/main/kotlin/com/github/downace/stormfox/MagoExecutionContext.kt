package com.github.downace.stormfox

import java.io.File

internal class MagoPathMappingException(message: String) : IllegalArgumentException(message)

/** A command prefix is argv, not a shell expression. */
internal data class MagoExecutionContext(
    val commandPrefix: String,
    val projectDirectory: File?,
    val executionRoot: String = "",
) {
    fun command(): List<String> {
        val input = commandPrefix.trim()
        require(input.isNotEmpty()) { "Mago command is empty" }
        // Preserve legacy executable paths containing unquoted spaces.
        val legacyFile = File(input).let { if (it.isAbsolute || projectDirectory == null) it else File(projectDirectory, input) }
        if (legacyFile.isFile || File("${legacyFile.path}.exe").isFile) return listOf(resolveMagoExecutable(legacyFile.path))
        val args = mutableListOf<String>()
        val token = StringBuilder()
        var quote: Char? = null
        var active = false
        var i = 0
        while (i < input.length) {
            val c = input[i]
            when {
                quote == null && c.isWhitespace() -> if (active) {
                    args.add(token.toString()); token.setLength(0); active = false
                }
                c == '\'' && quote != '"' -> { quote = if (quote == '\'') null else '\''; active = true }
                c == '"' && quote != '\'' -> { quote = if (quote == '"') null else '"'; active = true }
                c == '\\' && quote != '\'' && i + 1 < input.length &&
                    (input[i + 1] == '"' || input[i + 1] == '\'' || input[i + 1].isWhitespace()) -> {
                    token.append(input[++i]); active = true
                }
                else -> { token.append(c); active = true }
            }
            i++
        }
        require(quote == null) { "Unclosed quote in Mago command" }
        if (active) args.add(token.toString())
        require(args.isNotEmpty() && args[0].isNotEmpty()) { "Mago executable is empty" }
        return listOf(resolveMagoExecutable(args.first())) + args.drop(1)
    }

    private fun root(): String {
        require(executionRoot.startsWith('/')) { "Execution root must be an absolute POSIX path" }
        return normalizePosix(executionRoot)
    }

    fun toExecution(path: String): String {
        if (executionRoot.isBlank()) return path
        val hostRoot = requireNotNull(projectDirectory) { "Project directory is required for path mapping" }.toPath().toAbsolutePath().normalize()
        val host = hostRoot.resolve(path).normalize().toAbsolutePath()
        if (!host.startsWith(hostRoot)) throw MagoPathMappingException("Path is outside the mapped project: $path")
        val relative = hostRoot.relativize(host).toString().replace('\\', '/')
        val mappedRoot = root()
        return if (relative.isEmpty()) mappedRoot else mappedRoot.trimEnd('/') + "/$relative"
    }

    fun toHost(path: String, executionDirectory: String? = null): String {
        if (executionRoot.isBlank()) return path
        val mappedRoot = root()
        val absolute = normalizePosix(if (path.startsWith('/')) path else "${executionDirectory ?: mappedRoot}/$path")
        if (absolute != mappedRoot && !absolute.startsWith(mappedRoot.trimEnd('/') + "/"))
            throw MagoPathMappingException("Path is outside the mapped execution root: $path")
        val relative = absolute.removePrefix(mappedRoot).trimStart('/')
        if (relative.contains('\\') || relative.split('/').any { ':' in it })
            throw MagoPathMappingException("Path cannot be mapped safely to the host: $path")
        val hostRoot = requireNotNull(projectDirectory) { "Project directory is required for path mapping" }
            .toPath().toAbsolutePath().normalize()
        val host = hostRoot.resolve(relative).normalize()
        if (!host.startsWith(hostRoot)) throw MagoPathMappingException("Path is outside the mapped project: $path")
        return host.toString()
    }

    private fun normalizePosix(path: String): String {
        val parts = mutableListOf<String>()
        for (part in path.split('/')) when (part) {
            "", "." -> Unit
            ".." -> {
                if (parts.isEmpty()) throw MagoPathMappingException("Path escapes filesystem root: $path")
                parts.removeAt(parts.lastIndex)
            }
            else -> parts.add(part)
        }
        return "/" + parts.joinToString("/")
    }
}
