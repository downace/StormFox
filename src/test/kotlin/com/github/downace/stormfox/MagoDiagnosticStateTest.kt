package com.github.downace.stormfox

import com.github.downace.stormfox.lint.*
import com.github.downace.stormfox.lint.MagoDiagnosticRunner as MagoLintRunner
import junit.framework.TestCase
import kotlinx.coroutines.*
import java.io.File
import com.intellij.openapi.util.TextRange

class MagoDiagnosticStateTest : TestCase() {
    fun testVerifiedCurrentRevisionCanMoveBackwardsWithoutDiscardingRetainedView() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val gates = mutableListOf<CompletableDeferred<MagoLintRunner.Result>>()
        val state = MagoDiagnosticState<String>(scope, { _, _ ->
            CompletableDeferred<MagoLintRunner.Result>().also { gates += it }.await()
        }, { _, _, _ -> }, debounceMillis = 0)
        try {
            val original = snapshot(10).copy(text = "abcdef")
            state.requestView("f", MagoTool.LINT, original)
            val diagnostic = MagoDiagnosticReport.Diagnostic(TextRange(2, 4), "problem", null, "Warning")
            gates.last().complete(MagoLintRunner.Result.Success(listOf(diagnostic)))
            state.edited("f", 0, 0, 1, "xabcdef", 20)
            state.requestView("f", MagoTool.LINT, original.copy(text = "xabcdef", revision = 20))
            gates.last().complete(MagoLintRunner.Result.Success(listOf(diagnostic.copy(range = TextRange(3, 5)))))
            state.edited("f", 0, 1, 0, "abcdef", 30)
            val view = state.requestView("f", MagoTool.LINT, original, verifiedCurrent = true)!!
            assertTrue(view.retained)
            assertEquals(TextRange(2, 4), (view.result as MagoLintRunner.Result.Success).diagnostics.single().range)
            val worker = gates.last()
            assertNull(state.requestView("f", MagoTool.LINT, original.copy(text = "stale!", revision = 9)))
            assertFalse(worker.isCompleted)
            worker.complete(MagoLintRunner.Result.Success(listOf(diagnostic)))
            assertFalse(state.requestView("f", MagoTool.LINT, original, verifiedCurrent = true)!!.retained)
        } finally { state.dispose(); scope.cancel() }
    }
    fun testCancelledAndFailedWorkersCanRetrySameSnapshot() = runBlocking {
        val failures = mutableListOf<Throwable>()
        val handler = CoroutineExceptionHandler { _, error -> failures += error }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined + handler)
        var attempts = 0
        val publications = mutableListOf<MagoSnapshot>()
        val state = MagoDiagnosticState<String>(scope, { _, _ ->
            when (++attempts) {
                1 -> throw CancellationException("worker cancelled")
                2 -> throw IllegalStateException("worker failed")
                else -> MagoLintRunner.Result.Success(emptyList())
            }
        }, { _, _, snapshot -> publications += snapshot }, debounceMillis = 0)
        try {
            val snapshot = snapshot()
            assertNull(state.requestView("file", MagoTool.LINT, snapshot))
            assertNull(state.requestView("file", MagoTool.LINT, snapshot))
            val result = state.requestView("file", MagoTool.LINT, snapshot)
            assertEquals(3, attempts)
            assertEquals(MagoLintRunner.Result.Success(emptyList()), result?.result)
            assertEquals(listOf(snapshot), publications)
            assertEquals(1, failures.size)
        } finally { state.dispose(); scope.cancel() }
    }
    fun testSaveRetainsOnlyMatchingSuccessAndInvalidatesDependents() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val state = MagoDiagnosticState<String>(scope, { tool, _ ->
            if (tool == MagoTool.GUARD) MagoLintRunner.Result.Failure("failure")
            else MagoLintRunner.Result.Success(listOf(MagoDiagnosticReport.Diagnostic(TextRange(0, 1), "problem", null, "Warning")))
        }, { _, _, _ -> }, debounceMillis = 0)
        try {
            val original = snapshot()
            MagoTool.entries.forEach { state.requestView("saved", it, original) }
            state.requestView("other", MagoTool.LINT, original)
            state.invalidateForSave("saved", "A", 1, 0, 1, "mago", "", emptyMap())
            assertFalse(state.isCurrent("other", MagoTool.LINT, original))
            val rebased = original.copy(generation = 1)
            assertTrue(state.isCurrent("saved", MagoTool.LINT, rebased))
            assertTrue(state.isCurrent("saved", MagoTool.ANALYZE, rebased))
            assertFalse(state.isCurrent("saved", MagoTool.GUARD, rebased))
            // No fallback survives mismatched document, settings or generation.
            state.invalidateForSave("saved", "wrong", 1, 1, 2, "mago", "", emptyMap())
            assertFalse(state.isCurrent("saved", MagoTool.LINT, rebased))
        } finally { state.dispose(); scope.cancel() }
    }
    fun testPointDeletionAndReplacementHalfOpenBoundaries() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var first = true
        val state = MagoDiagnosticState<String>(scope, { _, _ ->
            if (!first) CompletableDeferred<Unit>().await()
            first = false
            MagoLintRunner.Result.Success(listOf(MagoDiagnosticReport.Diagnostic(TextRange(3, 3), "point", null, "Warning")))
        }, { _, _, _ -> }, debounceMillis = 0)
        try {
            for ((offset, length, replacement, expected) in listOf(
                listOf(2, 2, "", null), listOf(3, 1, "X", null),
                listOf(1, 2, "", 1), listOf(4, 1, "X", 3))) {
                state.invalidateAll()
                first = true
                val before = snapshot().copy(text = "abcdef")
                state.requestView("f", MagoTool.LINT, before)
                val start = offset as Int
                val count = length as Int
                val inserted = replacement as String
                val text = before.text.replaceRange(start, start + count, inserted)
                state.edited("f", start, count, inserted.length, text, 2)
                val view = state.requestView("f", MagoTool.LINT, before.copy(text = text, revision = 2))!!
                val ranges = (view.result as MagoLintRunner.Result.Success).diagnostics.map { it.range.startOffset }
                assertEquals(if (expected == null) emptyList<Int>() else listOf(expected), ranges)
            }
        } finally { state.dispose(); scope.cancel() }
    }

    fun testUtf16CrlfAdjacentDeletionUndoAndConfigMismatch() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val gates = mutableListOf<CompletableDeferred<MagoLintRunner.Result>>()
        val state = MagoDiagnosticState<String>(scope, { _, _ ->
            CompletableDeferred<MagoLintRunner.Result>().also { gates += it }.await()
        }, { _, _, _ -> }, debounceMillis = 0)
        try {
            val before = snapshot().copy(text = "😀\r\nabc!")
            state.requestView("f", MagoTool.LINT, before)
            gates.last().complete(MagoLintRunner.Result.Success(listOf(
                MagoDiagnosticReport.Diagnostic(TextRange(4, 7), "abc", null, "Warning"))))
            state.edited("f", 0, 2, 0, "\r\nabc!", 2)
            var current = before.copy(text = "\r\nabc!", revision = 2)
            assertEquals(TextRange(2, 5), (state.requestView("f", MagoTool.LINT, current)!!.result as MagoLintRunner.Result.Success).diagnostics.single().range)
            state.edited("f", 5, 1, 1, "\r\nabc?", 3)
            current = current.copy(text = "\r\nabc?", revision = 3)
            assertEquals(TextRange(2, 5), (state.requestView("f", MagoTool.LINT, current)!!.result as MagoLintRunner.Result.Success).diagnostics.single().range)
            state.edited("f", 0, 0, 2, "😀\r\nabc?", 4)
            current = current.copy(text = "😀\r\nabc?", revision = 4)
            assertEquals(TextRange(4, 7), (state.requestView("f", MagoTool.LINT, current)!!.result as MagoLintRunner.Result.Success).diagnostics.single().range)
            assertNull(state.requestView("f", MagoTool.LINT, current.copy(configPath = "different")))
            assertNull(state.requestView("f", MagoTool.LINT, current.copy(configPath = "different", generation = 1)))
        } finally { state.dispose(); scope.cancel() }
    }
    fun testIndependentToolsZeroLengthAndLateCompletion() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val gates = mutableMapOf<MagoTool, MutableList<CompletableDeferred<MagoLintRunner.Result>>>()
        val published = mutableListOf<MagoTool>()
        val state = MagoDiagnosticState<String>(scope, { tool, _ ->
            val gate = CompletableDeferred<MagoLintRunner.Result>()
            gates.getOrPut(tool) { mutableListOf() }.add(gate)
            withContext(NonCancellable) { gate.await() }
        }, { _, tool, _ -> published += tool }, debounceMillis = 0)
        try {
            val before = snapshot().copy(text = "abcdef")
            MagoTool.entries.forEach { state.requestView("f", it, before) }
            val diagnostic = MagoDiagnosticReport.Diagnostic(TextRange(3, 3), "point", null, "Warning")
            gates.getValue(MagoTool.LINT).single().complete(MagoLintRunner.Result.Success(listOf(diagnostic)))
            gates.getValue(MagoTool.ANALYZE).single().complete(MagoLintRunner.Result.Success(listOf(diagnostic)))
            val text = "abcXdef"
            state.edited("f", 3, 0, 1, text, 2)
            val after = before.copy(text = text, revision = 2)
            for (tool in listOf(MagoTool.LINT, MagoTool.ANALYZE)) {
                val view = state.requestView("f", tool, after)!!
                assertTrue(view.retained)
                assertTrue((view.result as MagoLintRunner.Result.Success).diagnostics.isEmpty())
            }
            assertNull(state.requestView("f", MagoTool.GUARD, after))
            assertNull(state.requestView("f", MagoTool.LINT, before))
            gates.getValue(MagoTool.GUARD).first().complete(MagoLintRunner.Result.Success(listOf(diagnostic)))
            assertEquals(2, published.size)
            state.invalidateAll()
            gates.values.flatten().forEach { it.complete(MagoLintRunner.Result.Success(emptyList())) }
            assertEquals(2, published.size)
        } finally { state.dispose(); scope.cancel() }
    }
    fun testEditsRetainOnlyUnaffectedDiagnosticsAndFreshResultsReplaceThem() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val gates = mutableListOf<CompletableDeferred<MagoLintRunner.Result>>()
        val state = MagoDiagnosticState<String>(scope, { _, _ ->
            CompletableDeferred<MagoLintRunner.Result>().also { gates += it }.await()
        }, { _, _, _ -> }, debounceMillis = 0)
        fun snap(text: String, revision: Long) = snapshot(revision).copy(text = text)
        fun diag(start: Int, end: Int) = MagoDiagnosticReport.Diagnostic(TextRange(start, end), "problem", "rule", "Warning")
        try {
            val original = snap("0123456789", 1)
            state.requestView("f", MagoTool.LINT, original)
            gates[0].complete(MagoLintRunner.Result.Success(listOf(diag(2, 4), diag(6, 9))))
            state.edited("f", 0, 0, 1, "x0123456789", 2)
            val second = snap("x0123456789", 2)
            val retained = state.requestView("f", MagoTool.LINT, second)!!
            assertTrue(retained.retained)
            assertEquals(listOf(TextRange(3, 5), TextRange(7, 10)),
                (retained.result as MagoLintRunner.Result.Success).diagnostics.map { it.range })
            state.edited("f", 5, 0, 1, "x0123y456789", 3)
            val third = snap("x0123y456789", 3)
            val next = state.requestView("f", MagoTool.LINT, third)!!
            assertEquals(listOf(TextRange(8, 11)), (next.result as MagoLintRunner.Result.Success).diagnostics.map { it.range })
            assertFalse(state.isCurrentView("f", MagoTool.LINT, second, retained))
            gates.last().complete(MagoLintRunner.Result.Success(emptyList()))
            val fresh = state.requestView("f", MagoTool.LINT, third)!!
            assertFalse(fresh.retained)
            assertTrue((fresh.result as MagoLintRunner.Result.Success).diagnostics.isEmpty())
        } finally { state.dispose(); scope.cancel() }
    }

    fun testBoundaryIntersectionAndFailureAreNotRetained() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val gates = mutableListOf<CompletableDeferred<MagoLintRunner.Result>>()
        val state = MagoDiagnosticState<String>(scope, { _, _ ->
            CompletableDeferred<MagoLintRunner.Result>().also { gates += it }.await()
        }, { _, _, _ -> }, debounceMillis = 0)
        try {
            for ((offset, oldLength, replacement) in listOf(Triple(2, 0, "x"), Triple(5, 0, "x"),
                    Triple(3, 1, ""), Triple(2, 2, "z"))) {
                state.invalidateAll()
                val before = snapshot().copy(text = "01\n345678")
                state.requestView("f", MagoTool.LINT, before)
                gates.last().complete(MagoLintRunner.Result.Success(listOf(
                    MagoDiagnosticReport.Diagnostic(TextRange(2, 5), "multiline", null, "Warning"))))
                val text = before.text.replaceRange(offset, offset + oldLength, replacement)
                state.edited("f", offset, oldLength, replacement.length, text, 2)
                val view = state.requestView("f", MagoTool.LINT, before.copy(text = text, revision = 2))!!
                assertTrue((view.result as MagoLintRunner.Result.Success).diagnostics.isEmpty())
                gates.last().complete(MagoLintRunner.Result.Failure("failed"))
                assertFalse(state.requestView("f", MagoTool.LINT, before.copy(text = text, revision = 2))!!.retained)
                state.edited("f", 0, 0, 1, "x$text", 3)
                assertNull(state.requestView("f", MagoTool.LINT, before.copy(text = "x$text", revision = 3)))
            }
        } finally { state.dispose(); scope.cancel() }
    }
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
