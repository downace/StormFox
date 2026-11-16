package com.github.downace.stormfox

import com.intellij.execution.process.CapturingProcessHandler

/** Terminates the local CLI process tree, not independently managed remote processes. */
internal fun terminateMagoProcess(handler: CapturingProcessHandler) {
    val process = handler.process
    if (process.isAlive) {
        process.descendants().use { children -> children.forEach { it.destroyForcibly() } }
        process.destroyForcibly()
    }
    handler.destroyProcess()
}
