package com.github.downace.stormfox.lint

enum class MagoTool(
    val command: String,
    val shortName: String,
    val displayName: String,
    val timeoutMillis: Int,
    val documentationUrl: String,
    val documentationLabel: String,
    val documentationCodeAnchor: Boolean = false,
) {
    LINT("lint", "MagoLint", "Mago Linter validation", 30_000,
        "https://mago.carthage.software/latest/en/tools/linter/rules/#", "Rule documentation", true),
    ANALYZE("analyze", "MagoAnalyze", "Mago Analyzer validation", 300_000,
        "https://mago.carthage.software/latest/en/tools/analyzer/overview/", "Analyzer documentation"),
    GUARD("guard", "MagoGuard", "Mago Guard validation", 300_000,
        "https://mago.carthage.software/latest/en/tools/guard/overview/", "Guard documentation"),
}
