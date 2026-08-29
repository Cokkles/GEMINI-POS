package com.cokkles.gpos.data.remote

import java.time.Instant

data class ContractEnvelope<T>(
    val contractVersion: String,
    val generatedAt: Instant,
    val requestId: String? = null,
    val payloadVersion: String? = null,
    val etag: String? = null,
    val payload: T,
)

data class ClientCompatibility(
    val clientType: String = CLIENT_TYPE,
    val clientVersion: String,
    val supportedContractVersions: Set<String>,
) {
    fun supports(contractVersion: String): Boolean = contractVersion in supportedContractVersions

    companion object {
        const val CLIENT_TYPE = "GPOS_ANDROID"
    }
}

sealed interface ContractCompatibilityResult {
    data class Compatible(val negotiatedVersion: String) : ContractCompatibilityResult
    data class Incompatible(
        val receivedVersion: String,
        val supportedVersions: Set<String>,
    ) : ContractCompatibilityResult
}

object ContractCompatibility {
    fun evaluate(
        receivedVersion: String,
        client: ClientCompatibility,
    ): ContractCompatibilityResult {
        return if (client.supports(receivedVersion)) {
            ContractCompatibilityResult.Compatible(receivedVersion)
        } else {
            ContractCompatibilityResult.Incompatible(
                receivedVersion = receivedVersion,
                supportedVersions = client.supportedContractVersions,
            )
        }
    }
}
