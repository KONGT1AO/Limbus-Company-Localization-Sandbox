package com.example.limbuszhcn.container

import com.lody.virtual.helper.compat.ServiceConnectionDispatchPolicy
import org.junit.Assert.assertEquals
import org.junit.Test

class ServiceConnectionDispatchPolicyTest {

    @Test
    fun android16ThreeArgumentCallbackUsesLegacyOverload() {
        assertEquals(
            3,
            ServiceConnectionDispatchPolicy.selectDeliveryArity(
                false,
                false,
                true,
                true,
            ),
        )
    }

    @Test
    fun android17FourArgumentCallbackUsesSessionOverload() {
        assertEquals(
            4,
            ServiceConnectionDispatchPolicy.selectDeliveryArity(
                true,
                true,
                true,
                true,
            ),
        )
    }

    @Test
    fun sessionOverloadIsNotUsedUnlessTheFrameworkDeliveredSession() {
        assertEquals(
            3,
            ServiceConnectionDispatchPolicy.selectDeliveryArity(
                false,
                true,
                true,
                true,
            ),
        )
    }

    @Test
    fun deprecatedCallbackUsesThreeArgumentOverloadWhenAvailable() {
        assertEquals(
            3,
            ServiceConnectionDispatchPolicy.selectDeliveryArity(
                false,
                true,
                true,
                true,
            ),
        )
    }

    @Test
    fun fallsBackToTwoArgumentsOnlyWhenNecessary() {
        assertEquals(
            2,
            ServiceConnectionDispatchPolicy.selectDeliveryArity(
                false,
                false,
                false,
                true,
            ),
        )
    }

}
