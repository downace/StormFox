package com.github.downace.stormfox.lint

import com.google.gson.JsonParser

internal data class MagoRuleMetadata(
    val code: String,
    val name: String = "",
    val category: String = "",
    val description: String = "",
    val level: String = "",
    val goodExample: String = "",
    val badExample: String = "",
)

internal object MagoRuleMetadataParser {
    fun parse(json: String): Map<String, MagoRuleMetadata> {
        val root = JsonParser.parseString(json)
        require(root.isJsonArray) { "Expected rule array" }
        val result = LinkedHashMap<String, MagoRuleMetadata>()
        for (element in root.asJsonArray) {
            require(element.isJsonObject) { "Expected rule object" }
            val obj = element.asJsonObject
            fun field(name: String): String {
                val value = obj.get(name) ?: return ""
                if (value.isJsonNull) return ""
                require(value.isJsonPrimitive && value.asJsonPrimitive.isString) { "Invalid rule field" }
                return value.asString
            }
            val code = field("code")
            require(code.isNotBlank() && code !in result) { "Missing or duplicate rule code" }
            result[code] = MagoRuleMetadata(code, field("name"), field("category"), field("description"),
                field("level"), field("good_example"), field("bad_example"))
        }
        require(result.isNotEmpty()) { "Empty rule catalogue" }
        return result
    }
}
