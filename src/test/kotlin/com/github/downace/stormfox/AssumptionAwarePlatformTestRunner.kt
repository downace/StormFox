package com.github.downace.stormfox

import org.junit.internal.runners.JUnit38ClassRunner
import org.junit.internal.AssumptionViolatedException
import org.junit.runner.Description
import org.junit.runner.notification.Failure
import org.junit.runner.notification.RunListener
import org.junit.runner.notification.RunNotifier

// Platform fixtures use JUnit 3; its adapter otherwise reports assumptions as failures.
class AssumptionAwarePlatformTestRunner(testClass: Class<*>) : JUnit38ClassRunner(testClass) {
    override fun run(notifier: RunNotifier) {
        val adapter = RunNotifier()
        adapter.addListener(object : RunListener() {
            override fun testStarted(description: Description) = notifier.fireTestStarted(description)
            override fun testFinished(description: Description) = notifier.fireTestFinished(description)
            override fun testIgnored(description: Description) = notifier.fireTestIgnored(description)
            override fun testFailure(failure: Failure) {
                if (failure.exception is AssumptionViolatedException) notifier.fireTestAssumptionFailed(failure)
                else notifier.fireTestFailure(failure)
            }
        })
        super.run(adapter)
    }
}
