package com.github.downace.stormfox

import junit.framework.TestCase
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MagoFailureLogTest : TestCase() {
    fun testRepeatCauseIsolationAndRecovery() {
        val events = mutableListOf<Pair<MagoFailureLog.Level, String>>()
        val log = MagoFailureLog(emit = { level, text -> events += level to text })
        val a = MagoFailureLog.context("project-a", "secret command")
        val b = MagoFailureLog.context("project-b", "secret command")
        log.failure(a, "lint", "process", "timeout")
        log.failure(a, "lint", "process", "timeout")
        log.failure(a, "lint", "process", "start")
        log.failure(a, "lint", "process", "timeout")
        log.failure(a, "guard", "process", "timeout")
        log.failure(b, "lint", "process", "timeout")
        log.success(a, "lint", "process")
        log.success(a, "lint", "process")
        log.failure(a, "lint", "process", "timeout")
        assertEquals(listOf("WARN", "DEBUG", "WARN", "WARN", "WARN", "WARN", "INFO", "WARN"),
            events.map { it.first.name })
        assertTrue(events.none { it.second.contains("secret") })
        assertEquals(3, log.size())
    }

    fun testBoundedAndConcurrent() {
        val events = Collections.synchronizedList(mutableListOf<MagoFailureLog.Level>())
        val log = MagoFailureLog(emit = { level, _ -> events.add(level) }, capacity = 2)
        val executor = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        try {
            val futures = (1..50).map {
                executor.submit { start.await(); log.failure("same", "lint", "process", "timeout") }
            }
            start.countDown()
            futures.forEach { it.get(10, TimeUnit.SECONDS) }
            assertEquals(1, events.count { it == MagoFailureLog.Level.WARN })
            assertEquals(49, events.count { it == MagoFailureLog.Level.DEBUG })
            log.failure("second", "lint", "process", "timeout")
            log.failure("third", "lint", "process", "timeout")
            assertEquals(2, log.size())
            log.failure("same", "lint", "process", "timeout")
            assertEquals(MagoFailureLog.Level.WARN, events.last())
        } finally {
            executor.shutdownNow()
        }
    }
}
