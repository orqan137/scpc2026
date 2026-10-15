package org.scpc.r2.safeshare

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessRestartProtocolTest {
    @Test
    fun verifiesOnlyARealPositivePidChange() {
        assertTrue(ProcessRestartProtocol.isPidChangeVerified(1200, 1300))
        assertFalse(ProcessRestartProtocol.isPidChangeVerified(1200, 1200))
        assertFalse(ProcessRestartProtocol.isPidChangeVerified(-1, 1300))
        assertFalse(ProcessRestartProtocol.isPidChangeVerified(1200, 0))
    }
}
