package com.omega.sight

import org.junit.Assert.assertTrue
import org.junit.Test

/** Runs the plain-Kotlin suites ([CoreTests], which calls the others) under Gradle's JUnit. Every failed check is listed in the failure. */
class CoreSuiteTest {
    @Test
    fun everythingPasses() {
        val failures = CoreTests.runAll()
        assertTrue("\n" + failures.joinToString("\n"), failures.isEmpty())
    }
}
