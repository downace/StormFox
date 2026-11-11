package com.github.downace.stormfox.lint

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

internal data class MagoSnapshot(
    val revision: Long,
    val text: String,
    val logicalPath: String,
    val workingDirectory: File,
    val binary: String,
    val configPath: String,
    val environment: Map<String, String>,
    val generation: Long,
)

internal class MagoDiagnosticState<K>(
    private val scope: CoroutineScope,
    private val compute: suspend (MagoTool, MagoSnapshot) -> MagoDiagnosticRunner.Result,
    private val completed: (K, MagoTool, MagoSnapshot) -> Unit,
    private val capacity: Int = 64,
    private val debounceMillis: Long = 300,
) {
    private class Slot(val snapshot: MagoSnapshot) {
        var job: Job? = null
        var result: MagoDiagnosticRunner.Result? = null
    }
    private val files = LinkedHashMap<K, MutableMap<MagoTool, Slot>>(16, 0.75f, true)
    private var disposed = false

    @Synchronized
    fun request(key: K, tool: MagoTool, snapshot: MagoSnapshot): MagoDiagnosticRunner.Result? {
        if (disposed) return null
        val slots = files.getOrPut(key) { mutableMapOf() }
        val previous = slots[tool]
        if (previous?.snapshot == snapshot) return previous.result
        previous?.job?.cancel(null)
        val slot = Slot(snapshot)
        slots[tool] = slot
        while (files.size > capacity) {
            val iterator = files.entries.iterator()
            iterator.next().value.values.forEach { it.job?.cancel(null) }
            iterator.remove()
        }
        slot.job = scope.launch(start = CoroutineStart.LAZY) {
            delay(debounceMillis)
            val result = compute(tool, snapshot)
            val publish = synchronized(this@MagoDiagnosticState) {
                if (disposed || files[key]?.get(tool) !== slot) false
                else { slot.result = result; true }
            }
            if (publish) completed(key, tool, snapshot)
        }
        slot.job!!.start()
        return null
    }

    @Synchronized
    fun isCurrent(key: K, tool: MagoTool, snapshot: MagoSnapshot): Boolean =
        !disposed && files[key]?.get(tool)?.snapshot == snapshot

    @Synchronized
    fun invalidate(key: K) {
        files.remove(key)?.values?.forEach { it.job?.cancel(null) }
    }

    @Synchronized
    fun invalidateAll() {
        files.values.flatMap { it.values }.forEach { it.job?.cancel(null) }
        files.clear()
    }

    @Synchronized
    fun keys(): List<K> = files.keys.toList()

    @Synchronized
    fun dispose() { disposed = true; invalidateAll() }
}
