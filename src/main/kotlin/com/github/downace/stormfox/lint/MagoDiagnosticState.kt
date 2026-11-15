package com.github.downace.stormfox.lint

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import com.intellij.openapi.util.TextRange
import java.util.concurrent.atomic.AtomicLong

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
    private val trace: (String) -> Unit = {},
) {
    internal data class View(val result: MagoDiagnosticRunner.Result, val retained: Boolean, val slotId: Long)
    private class Slot(val snapshot: MagoSnapshot, val id: Long) {
        var job: Job? = null
        var view: View? = null
    }
    private val files = LinkedHashMap<K, MutableMap<MagoTool, Slot>>(16, 0.75f, true)
    private var disposed = false
    private val ids = AtomicLong()
    private fun slot(snapshot: MagoSnapshot) = Slot(snapshot, ids.incrementAndGet())
    private fun details(slot: Slot, tool: MagoTool) =
        "file=${slot.snapshot.logicalPath} tool=$tool revision=${slot.snapshot.revision} generation=${slot.snapshot.generation} slot=${slot.id}"
    private fun status(result: MagoDiagnosticRunner.Result) = when (result) {
        is MagoDiagnosticRunner.Result.Success -> "success count=${result.diagnostics.size}"
        is MagoDiagnosticRunner.Result.Failure -> "failure"
    }

    @Synchronized
    fun request(key: K, tool: MagoTool, snapshot: MagoSnapshot): MagoDiagnosticRunner.Result? =
        requestView(key, tool, snapshot)?.result

    @Synchronized
    fun requestView(key: K, tool: MagoTool, snapshot: MagoSnapshot, verifiedCurrent: Boolean = false): View? {
        if (disposed) return null
        val slots = files.getOrPut(key) { mutableMapOf() }
        val previous = slots[tool]
        // A delayed annotator pass must not replace a slot already advanced by an edit.
        if (previous != null && (previous.snapshot.generation > snapshot.generation ||
                !verifiedCurrent && previous.snapshot.isNewerThan(snapshot))) {
            trace("request-drop reason=older file=${snapshot.logicalPath} tool=$tool revision=${snapshot.revision} generation=${snapshot.generation} currentSlot=${previous.id}")
            return null
        }
        val slot = if (previous?.snapshot == snapshot) previous else {
            if (previous?.job?.isActive == true) trace("cancel reason=request-replaced ${details(previous, tool)}")
            previous?.job?.cancel(null)
            slot(snapshot).also { next ->
                val success = previous?.view?.result as? MagoDiagnosticRunner.Result.Success
                if (verifiedCurrent && previous != null && success != null &&
                    previous.snapshot.copy(revision = snapshot.revision) == snapshot) {
                    next.view = View(success, true, next.id)
                    trace("request-rebase reason=current-revision ${details(next, tool)} previousSlot=${previous.id} count=${success.diagnostics.size}")
                }
                slots[tool] = next
            }
        }
        if (slot.job != null || slot.view?.retained == false) return slot.view
        while (files.size > capacity) {
            val iterator = files.entries.iterator()
            iterator.next().value.forEach { (evictedTool, evicted) ->
                trace("cancel reason=capacity ${details(evicted, evictedTool)}")
                evicted.job?.cancel(null)
            }
            iterator.remove()
        }
        trace("request-start ${details(slot, tool)} view=${if (slot.view == null) "none" else "retained"}")
        slot.job = scope.launch(start = CoroutineStart.LAZY) {
            delay(debounceMillis)
            trace("compute-start ${details(slot, tool)}")
            val result = compute(tool, snapshot)
            val publish = synchronized(this@MagoDiagnosticState) {
                if (disposed || files[key]?.get(tool) !== slot) false
                else { slot.view = View(result, false, slot.id); true }
            }
            trace("compute-${if (publish) "complete" else "drop"} ${details(slot, tool)} ${status(result)}")
            if (publish) completed(key, tool, snapshot)
        }
        val job = slot.job!!
        job.invokeOnCompletion { cause ->
            if (cause != null) {
                synchronized(this@MagoDiagnosticState) {
                    if (slot.job === job) slot.job = null
                }
                trace("compute-ended ${details(slot, tool)} reason=${if (cause is kotlinx.coroutines.CancellationException) "cancelled" else "exception"} type=${cause.javaClass.simpleName}")
            }
        }
        job.start()
        return slot.view
    }

    private fun MagoSnapshot.isNewerThan(other: MagoSnapshot): Boolean =
        generation > other.generation || generation == other.generation && revision > other.revision

    @Synchronized
    fun edited(key: K, offset: Int, oldLength: Int, newLength: Int, text: String, revision: Long) {
        val slots = files[key] ?: return
        for ((tool, old) in slots.toMap()) {
            trace("edit ${details(old, tool)} newRevision=$revision offset=$offset oldLength=$oldLength newLength=$newLength")
            old.job?.cancel(null)
            val before = old.snapshot.text
            if (offset < 0 || oldLength < 0 || newLength < 0 ||
                offset + oldLength > before.length || offset + newLength > text.length ||
                before.length - oldLength + newLength != text.length ||
                before.substring(0, offset) != text.substring(0, offset) ||
                before.substring(offset + oldLength) != text.substring(offset + newLength)) {
                slots.remove(tool)
                trace("edit-drop reason=snapshot-mismatch ${details(old, tool)}")
                continue
            }
            val slot = slot(old.snapshot.copy(revision = revision, text = text))
            val success = old.view?.result as? MagoDiagnosticRunner.Result.Success
            if (success != null) {
                val diagnostics = success.diagnostics.mapNotNull { diagnostic ->
                    val range = diagnostic.range
                    val start = range.startOffset
                    val end = range.endOffset
                    val editEnd = offset + oldLength
                    val intersects = if (oldLength == 0) offset in start..end
                        else if (start == end) start >= offset && start < editEnd
                        else offset < end && editEnd > start
                    if (intersects) null else {
                        val shift = if (editEnd <= start && (oldLength != 0 || offset < start)) newLength - oldLength else 0
                        diagnostic.copy(range = TextRange(start + shift, end + shift))
                    }
                }
                slot.view = View(MagoDiagnosticRunner.Result.Success(diagnostics), true, slot.id)
                trace("edit-retain ${details(slot, tool)} previousSlot=${old.id} count=${diagnostics.size}")
            }
            slots[tool] = slot
        }
    }

    @Synchronized
    fun isCurrentView(key: K, tool: MagoTool, snapshot: MagoSnapshot, view: View): Boolean =
        !disposed && files[key]?.get(tool)?.let { it.snapshot == snapshot && it.view === view } == true

    @Synchronized
    fun isCurrent(key: K, tool: MagoTool, snapshot: MagoSnapshot): Boolean =
        !disposed && files[key]?.get(tool)?.snapshot == snapshot

    @Synchronized
    fun invalidate(key: K) {
        files.remove(key)?.forEach { (tool, slot) -> trace("invalidate ${details(slot, tool)}"); slot.job?.cancel(null) }
    }

    @Synchronized
    fun invalidateAll() {
        files.values.forEach { slots -> slots.forEach { (tool, slot) ->
            trace("invalidate-all ${details(slot, tool)}")
            slot.job?.cancel(null)
        } }
        files.clear()
    }

    @Synchronized
    fun invalidateForSave(key: K, text: String, revision: Long, oldGeneration: Long, newGeneration: Long,
                          binary: String, configPath: String, environment: Map<String, String>) {
        val oldSlots = files[key]
        val retained = mutableMapOf<MagoTool, Slot>()
        oldSlots?.forEach { (tool, old) ->
            val snapshot = old.snapshot
            val result = old.view?.result as? MagoDiagnosticRunner.Result.Success
            if (result != null && snapshot.text == text && snapshot.revision == revision &&
                snapshot.generation == oldGeneration && snapshot.binary == binary && snapshot.configPath == configPath &&
                snapshot.environment == environment) {
                retained[tool] = slot(snapshot.copy(generation = newGeneration)).also {
                    it.view = View(result, true, it.id)
                    trace("save-retain ${details(it, tool)} previousSlot=${old.id} count=${result.diagnostics.size}")
                }
            } else {
                trace("save-drop ${details(old, tool)} reason=${if (result == null) "no-success" else "snapshot-mismatch"}")
            }
        }
        invalidateAll()
        if (retained.isNotEmpty()) files[key] = retained
    }

    @Synchronized
    fun keys(): List<K> = files.keys.toList()

    @Synchronized
    fun dispose() { disposed = true; invalidateAll() }
}
