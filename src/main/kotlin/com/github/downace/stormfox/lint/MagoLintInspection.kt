package com.github.downace.stormfox.lint

class MagoLintInspection : MagoDiagnosticInspection(MagoTool.LINT) {
    companion object { const val SHORT_NAME = "MagoLint" }
}
