package com.github.downace.stormfox

import com.github.downace.stormfox.lint.*
import com.github.downace.stormfox.lint.MagoDiagnosticRunner as MagoLintRunner
import junit.framework.TestCase
import kotlinx.coroutines.*
import java.io.File

class MagoDiagnosticStateTest : TestCase() {
    private fun snapshot(revision: Long = 1, generation: Long = 0) =
        MagoSnapshot(revision, "A", "Sample.php", File("."), "mago", "", emptyMap(), generation)

    fun testIndependentWorkersDeduplicateAndCacheEmptyAndFailureResults() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val gates = MagoTool.entries.associateWith { CompletableDeferred<MagoLintRunner.Result>() }
        var starts = 0
        val published = mutableListOf<MagoTool>()
        val state = MagoDiagnosticState<String>(scope, { tool, _ -> starts++; gates.getValue(tool).await() },
            { _, tool, _ -> published += tool }, debounceMillis = 0)
        try {
            repeat(3) { MagoTool.entries.forEach { assertNull(state.request("file", it, snapshot())) } }
            assertEquals(MagoTool.entries.size, starts)
            val empty = MagoLintRunner.Result.Success(emptyList())
            gates.getValue(MagoTool.LINT).complete(empty)
            assertEquals(listOf(MagoTool.LINT), published)
            assertEquals(empty, state.request("file", MagoTool.LINT, snapshot()))
            val failure = MagoLintRunner.Result.Failure("Cannot start Mago")
            gates.getValue(MagoTool.ANALYZE).complete(failure)
            assertEquals(failure, state.request("file", MagoTool.ANALYZE, snapshot()))
            gates.getValue(MagoTool.GUARD).complete(empty)
            assertEquals(empty, state.request("file", MagoTool.GUARD, snapshot()))
            assertEquals(MagoTool.entries.toSet(), published.toSet())
            assertEquals(MagoTool.entries.size, starts)
        } finally { state.dispose(); scope.coroutineContext[Job]!!.cancel(null) }
    }

    fun testRevisionAndDependencyGenerationRejectOldResultsAndCancelWorkers() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val gates = mutableListOf<CompletableDeferred<MagoLintRunner.Result>>()
        val publications = mutableListOf<MagoSnapshot>()
        var cancellations = 0
        val state = MagoDiagnosticState<String>(scope, { _, _ ->
            try { CompletableDeferred<MagoLintRunner.Result>().also { gates += it }.await() }
            finally { cancellations++ }
        }, { _, _, snapshot -> publications += snapshot }, debounceMillis = 0)
        try {
            state.request("file", MagoTool.ANALYZE, snapshot())
            state.request("file", MagoTool.ANALYZE, snapshot(3))
            assertEquals(1, cancellations)
            state.invalidateAll()
            assertEquals(2, cancellations)
            state.request("file", MagoTool.ANALYZE, snapshot(3, 1))
            gates.forEach { it.complete(MagoLintRunner.Result.Success(emptyList())) }
            assertEquals(listOf(snapshot(3, 1)), publications)
            assertFalse(state.isCurrent("file", MagoTool.ANALYZE, snapshot(3)))
            assertTrue(state.isCurrent("file", MagoTool.ANALYZE, snapshot(3, 1)))
        } finally { state.dispose(); scope.coroutineContext[Job]!!.cancel(null) }
    }

    fun testDisposedAndEvictedWorkersCannotPublish() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var cancelled = 0
        val state = MagoDiagnosticState<String>(scope, { _, _ ->
            try { CompletableDeferred<MagoLintRunner.Result>().await() } finally { cancelled++ }
        }, { _, _, _ -> fail("Cancelled worker published") }, capacity = 1, debounceMillis = 0)
        state.request("first", MagoTool.LINT, snapshot())
        state.request("second", MagoTool.ANALYZE, snapshot())
        assertEquals(1, cancelled)
        state.dispose()
        assertEquals(2, cancelled)
        assertNull(state.request("third", MagoTool.LINT, snapshot()))
        scope.coroutineContext[Job]!!.cancel(null)
    }

    fun testStaleCompletionIsRejectedEvenWhenWorkerIgnoresCancellation() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val gate = CompletableDeferred<MagoLintRunner.Result>()
        val publications = mutableListOf<MagoSnapshot>()
        val state = MagoDiagnosticState<String>(scope, { _, _ -> withContext(NonCancellable) { gate.await() } },
            { _, _, snapshot -> publications += snapshot }, debounceMillis = 0)
        try {
            state.request("file", MagoTool.ANALYZE, snapshot())
            state.invalidate("file")
            gate.complete(MagoLintRunner.Result.Success(emptyList()))
            assertTrue(publications.isEmpty())
        } finally { state.dispose(); scope.coroutineContext[Job]!!.cancel(null) }
    }
}
