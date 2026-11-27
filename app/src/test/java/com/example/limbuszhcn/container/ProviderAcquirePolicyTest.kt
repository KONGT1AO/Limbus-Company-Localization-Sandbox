package com.example.limbuszhcn.container

import com.lody.virtual.helper.compat.ProviderAcquirePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderAcquirePolicyTest {
    @Test
    fun detectsSameVirtualProcessIncludingDefaultProcessName() {
        assertTrue(
            ProviderAcquirePolicy.isSameVirtualProcess(
                "com.google.android.gms",
                "com.google.android.gms",
                "com.google.android.gms",
                "com.google.android.gms"
            )
        )
        assertTrue(
            ProviderAcquirePolicy.isSameVirtualProcess(
                "com.google.android.gms",
                null,
                "com.google.android.gms",
                "com.google.android.gms"
            )
        )
        assertFalse(
            ProviderAcquirePolicy.isSameVirtualProcess(
                "com.google.android.gms",
                "com.google.android.gms:ui",
                "com.google.android.gms",
                "com.google.android.gms"
            )
        )
        assertFalse(
            ProviderAcquirePolicy.isSameVirtualProcess(
                "com.ProjectMoon.LimbusCompany",
                "com.ProjectMoon.LimbusCompany",
                "com.google.android.gms",
                "com.google.android.gms"
            )
        )
    }

    @Test
    fun splitsSemicolonDelimitedAuthoritiesSafely() {
        assertEquals(
            setOf("com.google.android.gms.chimera", "com.google.android.gms.microg.settings"),
            ProviderAcquirePolicy.splitAuthorities(
                " com.google.android.gms.chimera ; ; com.google.android.gms.microg.settings "
            )
        )
        assertTrue(ProviderAcquirePolicy.splitAuthorities(null).isEmpty())
        assertTrue(ProviderAcquirePolicy.splitAuthorities("").isEmpty())
    }

    @Test
    fun backsOffAppSealingScanIntervalAfterInitialBurst() {
        assertEquals(25L, ProviderAcquirePolicy.computeAppSealingScanSleepMs(0))
        assertEquals(25L, ProviderAcquirePolicy.computeAppSealingScanSleepMs(9))
        assertEquals(100L, ProviderAcquirePolicy.computeAppSealingScanSleepMs(10))
        assertEquals(100L, ProviderAcquirePolicy.computeAppSealingScanSleepMs(50))
    }

    @Test
    fun definesFiniteTimeoutsForBindInstallAndProcessInit() {
        assertTrue(ProviderAcquirePolicy.BIND_APPLICATION_TIMEOUT_MS in 5_000L..30_000L)
        assertTrue(ProviderAcquirePolicy.PROCESS_INIT_TIMEOUT_MS in 5_000L..30_000L)
        assertTrue(ProviderAcquirePolicy.LOCAL_PROVIDER_INSTALL_TIMEOUT_MS in 1_000L..15_000L)
    }
}
