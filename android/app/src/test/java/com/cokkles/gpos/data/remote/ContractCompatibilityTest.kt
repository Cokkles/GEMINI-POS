package com.cokkles.gpos.data.remote

import org.junit.Assert.assertEquals
import org.junit.Test

class ContractCompatibilityTest {
    private val client = ClientCompatibility(
        clientVersion = "0.0.1-a0",
        supportedContractVersions = setOf("gpos-v1"),
    )

    @Test
    fun `supported contract negotiates explicitly`() {
        assertEquals(
            ContractCompatibilityResult.Compatible("gpos-v1"),
            ContractCompatibility.evaluate("gpos-v1", client),
        )
    }

    @Test
    fun `unsupported contract fails closed`() {
        assertEquals(
            ContractCompatibilityResult.Incompatible(
                receivedVersion = "gpos-v2",
                supportedVersions = setOf("gpos-v1"),
            ),
            ContractCompatibility.evaluate("gpos-v2", client),
        )
    }

    @Test
    fun `client type is diagnostic metadata`() {
        assertEquals("GPOS_ANDROID", client.clientType)
    }
}
