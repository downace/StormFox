package com.github.downace.stormfox

import com.github.downace.stormfox.settings.MagoSettingsValidationService
import com.intellij.openapi.progress.ProgressManager
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.SwingUtilities

class MagoSettingsValidationTest : BasePlatformTestCase() {
    fun testSuccessfulValidationDeliversResultOnEdt() {
        val parent = SupervisorJob()
        val session = MagoSettingsValidationService(CoroutineScope(Dispatchers.Default + parent)).createSession()
        var delivered: String? = null
        var deliveredOnEdt = false
        try {
            val job = session.validate(check = { "done" }, update = {
                delivered = it
                deliveredOnEdt = SwingUtilities.isEventDispatchThread()
            })
            PlatformTestUtil.waitWithEventsDispatching("Validation completion", { job.isCompleted }, 10)
            assertEquals("done", delivered)
            assertTrue(deliveredOnEdt)
        } finally {
            session.dispose()
            parent.cancel()
        }
    }

    fun testDisposalCancelsRunningValidation() = assertRunningValidationCancelled(false)

    fun testServiceScopeCancellationCancelsRunningValidation() = assertRunningValidationCancelled(true)

    private fun assertRunningValidationCancelled(cancelParent: Boolean) {
        val parent = SupervisorJob()
        val session = MagoSettingsValidationService(CoroutineScope(Dispatchers.Default + parent)).createSession()
        val started = CountDownLatch(1)
        val stopped = CountDownLatch(1)
        val updated = AtomicBoolean()
        try {
            session.validate(check = {
                val indicator = ProgressManager.getInstance().progressIndicator!!
                started.countDown()
                try {
                    while (!indicator.isCanceled) Thread.yield()
                    indicator.checkCanceled()
                } finally {
                    stopped.countDown()
                }
            }, update = { updated.set(true) })
            UIUtil.dispatchAllInvocationEvents()
            assertTrue(started.await(10, TimeUnit.SECONDS))
            if (cancelParent) parent.cancel() else session.dispose()
            assertTrue(stopped.await(10, TimeUnit.SECONDS))
            UIUtil.dispatchAllInvocationEvents()
            assertFalse(updated.get())
        } finally {
            session.dispose()
            parent.cancel()
        }
    }

    fun testDisposalSuppressesLateCompletion() = assertCompletionSuppressed(true)

    fun testDisposalSuppressesCompletionBeforeEdtDelivery() = assertCompletionSuppressed(false)

    private fun assertCompletionSuppressed(disposeBeforeCompletion: Boolean) {
        val parent = SupervisorJob()
        val session = MagoSettingsValidationService(CoroutineScope(Dispatchers.Default + parent)).createSession()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val completed = CountDownLatch(1)
        val updated = AtomicBoolean()
        try {
            session.validate(check = {
                started.countDown()
                check(release.await(10, TimeUnit.SECONDS))
                completed.countDown()
                "done"
            }, update = { updated.set(true) })
            UIUtil.dispatchAllInvocationEvents()
            assertTrue(started.await(10, TimeUnit.SECONDS))
            if (disposeBeforeCompletion) session.dispose()
            release.countDown()
            assertTrue(completed.await(10, TimeUnit.SECONDS))
            session.dispose()
            UIUtil.dispatchAllInvocationEvents()
            assertFalse(updated.get())
        } finally {
            release.countDown()
            session.dispose()
            parent.cancel()
        }
    }
}
