package com.omega.keyboard

import org.junit.Assert.assertTrue
import org.junit.Test

/** Runs the plain-Kotlin suite under Gradle (`gradle testDebugUnitTest`). */
class CoreSuiteTest {
    @Test
    fun coreLogic() {
        val failures = CoreTests.runAll()
        assertTrue("\n" + failures.joinToString("\n"), failures.isEmpty())
    }
}
