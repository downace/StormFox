package com.github.downace.stormfox

import com.github.downace.stormfox.lint.*
import junit.framework.TestCase
import com.intellij.openapi.util.Disposer
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File

class MagoDiagnosticActivityTest : TestCase() {
    fun testFailuresPersistUntilSuccessAndStaleCompletionIsIgnored() {
        val activity = MagoDiagnosticActivity()
        val tool = MagoTool.LINT
        activity.begin(tool, "a.php", 1)
        val queued = activity.snapshot()
        assertEquals(1, queued.queued.size)
        activity.running(tool, "a.php", 1)
        assertEquals(1, activity.snapshot().running.size)
        activity.finish(tool, "a.php", 1, MagoDiagnosticRunner.Result.Failure("failed"))
        activity.begin(tool, "a.php", 2)
        activity.finish(tool, "a.php", 1, MagoDiagnosticRunner.Result.Success(emptyList()))
        assertEquals("failed", activity.snapshot().failures.single().error)
        assertEquals(1, activity.snapshot().queued.size)
        activity.finish(tool, "a.php", 2, MagoDiagnosticRunner.Result.Success(emptyList()))
        assertTrue(activity.snapshot().failures.isEmpty())
        assertEquals(1, queued.queued.size)
    }

    fun testSemaphoreWaitAndEditCancellation() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val activity = MagoDiagnosticActivity()
        val semaphore = Semaphore(1, 1)
        val gate = CompletableDeferred<MagoDiagnosticRunner.Result>()
        val state = MagoDiagnosticState<String>(scope, { _, _ -> gate.await() }, { _, _, _ -> },
            debounceMillis = 0, activity = activity, execute = { tool, snapshot, id, run ->
                semaphore.withPermit {
                    activity.running(tool, snapshot.logicalPath, id)
                    run()
                }
            })
        val snapshot = MagoSnapshot(1, "", "a.php", File("."), "mago", "", emptyMap(), 0)
        try {
            state.request("a", MagoTool.LINT, snapshot)
            assertEquals(1, activity.snapshot().queued.size)
            semaphore.release()
            assertEquals(1, activity.snapshot().running.size)
            state.edited("a", 0, 0, 1, "x", 2)
            assertTrue(activity.snapshot().running.isEmpty())
            state.request("a", MagoTool.LINT, snapshot.copy(revision = 2, text = "x"))
            assertEquals(1, activity.snapshot().running.size)
            gate.complete(MagoDiagnosticRunner.Result.Failure("error"))
            assertEquals("error", activity.snapshot().failures.single().error)
            state.invalidateAll()
            assertTrue(activity.snapshot().failures.isEmpty())
        } finally { state.dispose(); scope.cancel() }
    }

    fun testCancellationDuringNonzeroDebounce() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val activity = MagoDiagnosticActivity()
        var computed = false
        val state = MagoDiagnosticState<String>(scope, { _, _ ->
            computed = true
            MagoDiagnosticRunner.Result.Success(emptyList())
        }, { _, _, _ -> }, debounceMillis = 60_000, activity = activity)
        try {
            state.request("a", MagoTool.LINT, snapshot("a.php"))
            assertEquals(1, activity.snapshot().queued.size)
            state.invalidate("a")
            assertTrue(activity.snapshot().queued.isEmpty())
            assertFalse(computed)
        } finally { state.dispose(); scope.cancel() }
    }

    fun testAlreadyCancelledScopeDoesNotLeaveQueuedRequest() = runBlocking {
        val scope = CoroutineScope(Job().also { it.cancel() } + Dispatchers.Unconfined)
        val activity = MagoDiagnosticActivity()
        val state = MagoDiagnosticState<String>(scope, { _, _ ->
            MagoDiagnosticRunner.Result.Success(emptyList())
        }, { _, _, _ -> }, debounceMillis = 60_000, activity = activity)
        try {
            state.request("a", MagoTool.LINT, snapshot("a.php"))
            assertTrue(activity.snapshot().queued.isEmpty())
            assertTrue(activity.snapshot().running.isEmpty())
        } finally { state.dispose() }
    }

    fun testParallelToolsAndFilesWithSameToolWaiting() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val activity = MagoDiagnosticActivity()
        val workers = MagoTool.entries.associateWith { Semaphore(1) }
        val gate = CompletableDeferred<MagoDiagnosticRunner.Result>()
        val state = MagoDiagnosticState<String>(scope, { _, _ -> gate.await() }, { _, _, _ -> },
            debounceMillis = 0, activity = activity, execute = { tool, snapshot, id, run ->
                workers.getValue(tool).withPermit {
                    activity.running(tool, snapshot.logicalPath, id)
                    run()
                }
            })
        try {
            state.request("a", MagoTool.LINT, snapshot("a.php"))
            state.request("b", MagoTool.LINT, snapshot("b.php"))
            state.request("b", MagoTool.ANALYZE, snapshot("b.php"))
            assertEquals(setOf(MagoTool.LINT to "a.php", MagoTool.ANALYZE to "b.php"),
                activity.snapshot().running.map { it.tool to it.logicalPath }.toSet())
            assertEquals("b.php", activity.snapshot().queued.single().logicalPath)
            gate.complete(MagoDiagnosticRunner.Result.Success(emptyList()))
            assertTrue(activity.snapshot().running.isEmpty())
            assertTrue(activity.snapshot().queued.isEmpty())
            assertTrue(activity.snapshot().failures.isEmpty())
        } finally { state.dispose(); scope.cancel() }
    }

    fun testListenerStopsAfterParentDisposal() {
        val parent = Disposer.newDisposable()
        val activity = MagoDiagnosticActivity()
        var calls = 0
        activity.addListener(parent) { calls++ }
        activity.begin(MagoTool.LINT, "a.php", 1)
        assertEquals(1, calls)
        Disposer.dispose(parent)
        activity.finish(MagoTool.LINT, "a.php", 1)
        assertEquals(1, calls)
    }

    fun testInvalidateFileAndDisposeClearActiveAndFailuresAndCachedSuccessIsIdle() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val activity = MagoDiagnosticActivity()
        val gate = CompletableDeferred<MagoDiagnosticRunner.Result>()
        val state = MagoDiagnosticState<String>(scope, { tool, _ ->
            when (tool) {
                MagoTool.LINT -> MagoDiagnosticRunner.Result.Failure("failed")
                MagoTool.ANALYZE -> MagoDiagnosticRunner.Result.Success(emptyList())
                else -> gate.await()
            }
        }, { _, _, _ -> }, debounceMillis = 0, activity = activity,
            execute = { tool, snapshot, id, run ->
                activity.running(tool, snapshot.logicalPath, id)
                run()
            })
        try {
            val a = snapshot("a.php")
            state.request("a", MagoTool.LINT, a)
            state.request("a", MagoTool.ANALYZE, a)
            state.request("a", MagoTool.GUARD, a)
            assertEquals(1, activity.snapshot().failures.size)
            assertEquals(1, activity.snapshot().running.size)
            state.request("a", MagoTool.ANALYZE, a)
            assertEquals(1, activity.snapshot().running.size)
            assertTrue(activity.snapshot().queued.isEmpty())
            state.invalidate("a")
            assertTrue(activity.snapshot().failures.isEmpty())
            assertTrue(activity.snapshot().running.isEmpty())
            state.request("b", MagoTool.LINT, snapshot("b.php"))
            state.request("b", MagoTool.GUARD, snapshot("b.php"))
            state.dispose()
            assertTrue(activity.snapshot().failures.isEmpty())
            assertTrue(activity.snapshot().running.isEmpty())
            assertTrue(activity.snapshot().queued.isEmpty())
        } finally { state.dispose(); scope.cancel() }
    }

    private fun snapshot(path: String) =
        MagoSnapshot(1, "", path, File("."), "mago", "", emptyMap(), 0)
}
