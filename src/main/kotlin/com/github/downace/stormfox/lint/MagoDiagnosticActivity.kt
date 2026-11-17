package com.github.downace.stormfox.lint

import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.util.Disposer
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CancellationException
import java.util.Collections

/** Project-wide activity of shared editor diagnostics. Listener callbacks may run on any thread. */
class MagoDiagnosticActivity {
    data class Entry(
        val tool: MagoTool,
        val logicalPath: String,
        /** Monotonic timestamp from System.nanoTime(), suitable for elapsed-time calculations. */
        val startedNanos: Long,
        val error: String? = null,
    )

    data class Snapshot(val queued: List<Entry>, val running: List<Entry>, val failures: List<Entry>)

    private data class Key(val path: String, val tool: MagoTool)
    private data class Active(val token: Long, val entry: Entry, val running: Boolean)
    private val active = mutableMapOf<Key, Active>()
    private val failures = mutableMapOf<Key, Entry>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val log = Logger.getInstance(MagoDiagnosticActivity::class.java)

    @Synchronized
    fun snapshot(): Snapshot = Snapshot(
        Collections.unmodifiableList(active.values.filterNot { it.running }.map { it.entry }),
        Collections.unmodifiableList(active.values.filter { it.running }.map { it.entry }),
        Collections.unmodifiableList(failures.values.toList()),
    )

    fun addListener(parentDisposable: Disposable, listener: () -> Unit) {
        listeners.add(listener)
        Disposer.register(parentDisposable, Disposable { listeners.remove(listener) })
    }

    private fun changed() {
        listeners.forEach { listener ->
            try {
                listener()
            } catch (e: ProcessCanceledException) {
                throw e
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("Mago diagnostic activity listener failed", e)
            }
        }
    }

    internal fun begin(tool: MagoTool, path: String, token: Long) {
        synchronized(this) { active[Key(path, tool)] = Active(token, Entry(tool, path, System.nanoTime()), false) }
        changed()
    }

    internal fun running(tool: MagoTool, path: String, token: Long) {
        val updated = synchronized(this) {
            val key = Key(path, tool)
            val current = active[key]
            if (current?.token != token) false else {
                active[key] = current.copy(entry = current.entry.copy(startedNanos = System.nanoTime()), running = true)
                true
            }
        }
        if (updated) changed()
    }

    internal fun finish(tool: MagoTool, path: String, token: Long, result: MagoDiagnosticRunner.Result? = null) {
        val updated = synchronized(this) {
            val key = Key(path, tool)
            val current = active[key]
            if (current?.token != token) false else {
                active.remove(key)
                when (result) {
                    is MagoDiagnosticRunner.Result.Success -> failures.remove(key)
                    is MagoDiagnosticRunner.Result.Failure -> failures[key] = current.entry.copy(error = result.message)
                    null -> Unit
                }
                true
            }
        }
        if (updated) changed()
    }

    internal fun clear() {
        synchronized(this) { active.clear(); failures.clear() }
        changed()
    }

    internal fun invalidate(tool: MagoTool, path: String) {
        synchronized(this) { val key = Key(path, tool); active.remove(key); failures.remove(key) }
        changed()
    }
}
