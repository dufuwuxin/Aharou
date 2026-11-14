package com.aharou.core.config

import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class BuiltinSkillEditCapabilityTest {
    @After fun reset() { BuiltinSkillEditCapability.setEnabled(false) }
    @Test fun permissionCanBeGrantedAndRevoked() {
        BuiltinSkillEditCapability.setEnabled(false)
        assertFalse(BuiltinSkillEditCapability.enabled.value)
        BuiltinSkillEditCapability.setEnabled(true)
        assertTrue(BuiltinSkillEditCapability.enabled.value)
        BuiltinSkillEditCapability.setEnabled(false)
        assertFalse(BuiltinSkillEditCapability.enabled.value)
    }
    @Test fun revocationCannotReturnWhileAnAuthorizedMutationIsStillWriting() {
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val revoking = java.util.concurrent.CountDownLatch(1)
        val revoked = java.util.concurrent.CountDownLatch(1)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
        BuiltinSkillEditCapability.setEnabled(true)
        try {
            val mutation = pool.submit {
                synchronized(BuiltinSkillEditCapability) {
                    entered.countDown()
                    check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                    check(BuiltinSkillEditCapability.enabled.value)
                }
            }
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            val revocation = pool.submit {
                revoking.countDown()
                BuiltinSkillEditCapability.setEnabled(false)
                revoked.countDown()
            }
            assertTrue(revoking.await(5, java.util.concurrent.TimeUnit.SECONDS))
            assertFalse(revoked.await(100, java.util.concurrent.TimeUnit.MILLISECONDS))
            release.countDown()
            mutation.get(5, java.util.concurrent.TimeUnit.SECONDS)
            revocation.get(5, java.util.concurrent.TimeUnit.SECONDS)
            assertFalse(BuiltinSkillEditCapability.enabled.value)
        } finally { release.countDown(); pool.shutdownNow() }
    }

}
