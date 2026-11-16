package com.github.downace.stormfox.config

import java.io.File

internal object MagoConfigNavigation {
    fun discover(
        workingDirectory: File,
        configPath: String,
        environment: Map<String, String> = System.getenv(),
        includeGlobal: Boolean = true,
    ): File? {
        if (configPath.isNotBlank()) {
            val path = File(configPath)
            return (if (path.isAbsolute) path else File(workingDirectory, configPath)).takeIf { it.isFile }
        }
        val directories = mutableListOf(workingDirectory to true)
        environment["XDG_CONFIG_HOME"]?.takeIf { it.isNotEmpty() }?.let { directories.add(File(it) to false) }
        val homeKey = if (File.separatorChar == '\\') "USERPROFILE" else "HOME"
        (environment[homeKey]?.takeIf { it.isNotEmpty() } ?: System.getProperty("user.home")?.takeIf { it.isNotEmpty() })?.let {
            directories.add(File(it, ".config") to false)
            directories.add(File(it) to false)
        }
        for ((directory, workspace) in directories) {
            if (!workspace && !includeGlobal) break
            for (base in if (workspace) listOf("mago", "mago.dist") else listOf("mago")) {
                for (extension in listOf("toml", "yaml", "yml", "json")) {
                    val candidate = File(directory, "$base.$extension")
                    if (candidate.isFile) return candidate
                }
            }
        }
        return null
    }

    fun ruleOffset(text: String, fileName: String, tool: String, code: String?): Int? {
        if (!tool.equals("lint", ignoreCase = true) || code.isNullOrBlank()) return null
        return when (fileName.substringAfterLast('.', "").lowercase()) {
            "toml" -> TomlScanner(text, code).find()
            "json" -> JsonScanner(text, code).find()
            "yaml", "yml" -> YamlScanner(text, code).find()
            else -> null
        }
    }

    private data class Key(val value: String, val offset: Int)

    /** A deliberately narrow TOML reader: tracks table/key paths, skipping all scalar values. */
    private class TomlScanner(private val text: String, private val rule: String) {
        private var pos = 0
        private var table = emptyList<Key>()
        private val target = listOf("linter", "rules", rule)

        fun find(): Int? {
            while (pos < text.length) {
                whitespace()
                if (pos >= text.length) break
                if (text[pos] == '[') {
                    pos++
                    if (peek('[')) {
                        // Array elements are not ordinary config tables; never inherit the prior table.
                        table = listOf(Key("<array>", pos))
                        skipLine()
                        continue
                    }
                    val keys = keys()
                    horizontal()
                    if (keys != null && peek(']')) {
                        pos++
                        table = keys
                        match(keys)?.let { return it }
                    } else skipLine()
                } else {
                    val keys = keys()
                    horizontal()
                    if (keys != null && peek('=')) {
                        pos++
                        val path = table + keys
                        match(path)?.let { return it }
                        value(path)?.let { return it }
                    } else skipLine()
                }
            }
            return null
        }

        private fun match(path: List<Key>): Int? =
            if (path.size >= 3 && path.take(3).map { it.value } == target) path[2].offset else null

        private fun keys(): List<Key>? {
            val result = mutableListOf<Key>()
            while (true) {
                horizontal()
                val start = pos
                val name = when {
                    pos >= text.length -> return null
                    text[pos] == '"' || text[pos] == '\'' -> string() ?: return null
                    else -> {
                        while (pos < text.length && (text[pos].isLetterOrDigit() || text[pos] == '_' || text[pos] == '-')) pos++
                        if (start == pos) return null
                        text.substring(start, pos)
                    }
                }
                result.add(Key(name, start))
                horizontal()
                if (!peek('.')) return result
                pos++
            }
        }

        private fun value(path: List<Key>): Int? {
            horizontal()
            if (peek('{')) {
                pos++
                while (pos < text.length) {
                    whitespace()
                    if (peek('}')) { pos++; return null }
                    val keys = keys() ?: run { skipValue(); return null }
                    horizontal()
                    if (!peek('=')) { skipValue(); return null }
                    pos++
                    val child = path + keys
                    match(child)?.let { return it }
                    value(child)?.let { return it }
                    whitespace()
                    if (peek(',')) pos++ else if (!peek('}')) return null
                }
            } else skipValue()
            return null
        }

        private fun skipValue() {
            var depth = 0
            while (pos < text.length) {
                when (text[pos]) {
                    '"', '\'' -> string()
                    '#' -> { skipLine(); if (depth == 0) return }
                    '[', '{' -> { depth++; pos++ }
                    ']', '}' -> if (depth == 0) return else { depth--; pos++ }
                    ',', '\n', '\r' -> if (depth == 0) return else pos++
                    else -> pos++
                }
            }
        }

        private fun string(): String? {
            val quote = text[pos++]
            val multiline = pos + 1 < text.length && text[pos] == quote && text[pos + 1] == quote
            if (multiline) pos += 2
            val result = StringBuilder()
            while (pos < text.length) {
                val c = text[pos++]
                if (c == quote) {
                    if (!multiline) return result.toString()
                    if (pos + 1 < text.length && text[pos] == quote && text[pos + 1] == quote) {
                        pos += 2
                        return result.toString()
                    }
                }
                if (c == '\\' && quote == '"' && pos < text.length) {
                    val escaped = text[pos++]
                    when (escaped) {
                        'u', 'U' -> {
                            val count = if (escaped == 'u') 4 else 8
                            if (pos + count > text.length) return null
                            val code = text.substring(pos, pos + count).toLongOrNull(16) ?: return null
                            if (code > 0x10ffff || code in 0xd800..0xdfff) return null
                            result.appendCodePoint(code.toInt())
                            pos += count
                        }
                        '"' -> result.append('"')
                        '\\' -> result.append('\\')
                        'n' -> result.append('\n')
                        'r' -> result.append('\r')
                        't' -> result.append('\t')
                        else -> result.append(escaped)
                    }
                } else result.append(c)
            }
            return null
        }

        private fun horizontal() { while (pos < text.length && text[pos] in " \t") pos++ }
        private fun whitespace() {
            while (pos < text.length) {
                when {
                    text[pos].isWhitespace() -> pos++
                    text[pos] == '#' -> skipLine()
                    else -> return
                }
            }
        }
        private fun skipLine() { while (pos < text.length && text[pos] != '\n') pos++ ; if (pos < text.length) pos++ }
        private fun peek(c: Char) = pos < text.length && text[pos] == c
    }

    /** Reads block mappings only. Scalar/flow values form opaque subtrees, including block scalars. */
    private class YamlScanner(private val text: String, private val rule: String) {
        private data class Entry(val indent: Int, val key: String, val mapping: Boolean)

        fun find(): Int? {
            val stack = mutableListOf<Entry>()
            var offset = 0
            for (line in text.split('\n')) {
                val indent = line.takeWhile { it == ' ' }.length
                val content = line.substring(indent).trimEnd('\r')
                if (content.isBlank() || content.startsWith('#')) {
                    offset += line.length + 1
                    continue
                }
                while (stack.isNotEmpty() && stack.last().indent >= indent) stack.removeAt(stack.lastIndex)
                if (stack.lastOrNull()?.mapping == false) {
                    offset += line.length + 1
                    continue
                }
                val parsed = key(content)
                if (parsed == null) {
                    // Unknown syntax (including sequences) cannot introduce a mapping path.
                    stack.add(Entry(indent, "", false))
                } else {
                    val (name, end) = parsed
                    val path = stack.map { it.key } + name
                    if (path == listOf("linter", "rules", rule)) return offset + indent
                    val rest = content.substring(end).trimStart()
                    stack.add(Entry(indent, name, rest.isEmpty() || rest.startsWith('#')))
                }
                offset += line.length + 1
            }
            return null
        }

        private fun key(content: String): Pair<String, Int>? {
            if (content.isEmpty()) return null
            var i = 0
            val result = StringBuilder()
            when (val quote = content[0]) {
                '\'', '"' -> {
                    i++
                    var closed = false
                    while (i < content.length) {
                        val c = content[i++]
                        if (c == quote) {
                            if (quote == '\'' && i < content.length && content[i] == '\'') {
                                result.append('\''); i++; continue
                            }
                            closed = true
                            break
                        }
                        if (quote == '"' && c == '\\') {
                            if (i >= content.length) return null
                            when (val escaped = content[i++]) {
                                '"', '\\', '/' -> result.append(escaped)
                                'n' -> result.append('\n')
                                't' -> result.append('\t')
                                'r' -> result.append('\r')
                                'u' -> {
                                    if (i + 4 > content.length) return null
                                    val code = content.substring(i, i + 4).toIntOrNull(16) ?: return null
                                    result.append(code.toChar()); i += 4
                                }
                                else -> return null
                            }
                        } else result.append(c)
                    }
                    if (!closed) return null
                    while (i < content.length && content[i] == ' ') i++
                }
                else -> {
                    while (i < content.length && content[i] != ':') {
                        if (content[i] in "[]{}#'\"\t") return null
                        i++
                    }
                    result.append(content.substring(0, i).trimEnd())
                }
            }
            if (result.isEmpty() || i >= content.length || content[i] != ':') return null
            i++
            if (i < content.length && !content[i].isWhitespace() && content[i] != '#') return null
            return result.toString() to i
        }
    }

    /** JSON object traversal; strings and arrays are skipped rather than searched as text. */
    private class JsonScanner(private val text: String, private val rule: String) {
        private var pos = 0
        private val target = listOf("linter", "rules", rule)
        fun find(): Int? = value(emptyList())

        private fun value(path: List<String>): Int? {
            space()
            if (pos >= text.length) return null
            when (text[pos]) {
                '{' -> {
                    pos++
                    while (true) {
                        space()
                        if (consume('}')) return null
                        val start = pos
                        val key = string() ?: return null
                        space()
                        if (!consume(':')) return null
                        val child = path + key
                        if (child == target) return start
                        value(child)?.let { return it }
                        space()
                        if (consume('}')) return null
                        if (!consume(',')) return null
                    }
                }
                '[' -> {
                    pos++
                    while (true) {
                        space()
                        if (consume(']')) return null
                        value(listOf("<array>")) // Array elements cannot define a configuration path.
                        space()
                        if (consume(']')) return null
                        if (!consume(',')) return null
                    }
                }
                '"' -> string()
                else -> while (pos < text.length && text[pos] !in ",]} \t\r\n") pos++
            }
            return null
        }

        private fun string(): String? {
            if (!consume('"')) return null
            val result = StringBuilder()
            while (pos < text.length) {
                val c = text[pos++]
                if (c == '"') return result.toString()
                if (c != '\\') { result.append(c); continue }
                if (pos >= text.length) return null
                when (val escaped = text[pos++]) {
                    'u' -> {
                        if (pos + 4 > text.length) return null
                        val code = text.substring(pos, pos + 4).toIntOrNull(16) ?: return null
                        result.append(code.toChar()); pos += 4
                    }
                    '"' , '\\', '/' -> result.append(escaped)
                    'b' -> result.append('\b')
                    'f' -> result.append('\u000c')
                    'n' -> result.append('\n')
                    'r' -> result.append('\r')
                    't' -> result.append('\t')
                    else -> return null
                }
            }
            return null
        }
        private fun space() { while (pos < text.length && text[pos].isWhitespace()) pos++ }
        private fun consume(c: Char): Boolean = if (pos < text.length && text[pos] == c) { pos++; true } else false
    }
}
