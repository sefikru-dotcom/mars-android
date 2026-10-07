package ru.mars.network.model

import java.util.concurrent.atomic.AtomicBoolean

private val STRING_FIELD = Regex("\\\"([a-z_]+)\\\"\\s*:\\s*\\\"([^\\\"]*)\\\"")
private val NUMBER_FIELD = Regex("\\\"([a-z_]+)\\\"\\s*:\\s*(\\d+)")
private val SCOPES_FIELD = Regex("\\\"scopes\\\"\\s*:\\s*\\[(.*?)]")
private val SCOPE_ITEM = Regex("\\\"([^\\\"]*)\\\"")


data class PairingResponse(
    val pairId: String,
    val code: String,
    val pollSecret: String,
    val expiresIn: Int,
    val status: PairingStatus
)

data class PairingStatusResponse(
    val pairId: String,
    val status: PairingStatus,
    val deviceToken: String? = null,
    val deviceId: String? = null,
    val scopes: List<String> = emptyList(),
    val expiresAt: String? = null
) {
    fun oneTimeCredentialOrNull(): OneTimeDeviceCredential? =
        if (status == PairingStatus.APPROVED && deviceToken != null && deviceId != null) {
            OneTimeDeviceCredential(deviceToken, deviceId, scopes, expiresAt)
        } else null
}

enum class PairingStatus { PENDING, APPROVED }

data class AuthResponse(val accessToken: String?, val refreshToken: String?)

class OneTimeDeviceCredential internal constructor(
    deviceToken: String,
    val deviceId: String,
    val scopes: List<String>,
    val expiresAt: String?
) {
    private val token = OneTimeValue(deviceToken)

    fun consume(): ConsumedDeviceCredential? = token.consume()?.let {
        ConsumedDeviceCredential(it, deviceId, scopes, expiresAt)
    }

    override fun toString(): String = "OneTimeDeviceCredential(deviceToken=[REDACTED], deviceId=$deviceId, scopes=$scopes, expiresAt=$expiresAt)"
}

data class ConsumedDeviceCredential(
    val deviceToken: String,
    val deviceId: String,
    val scopes: List<String>,
    val expiresAt: String?
)

private class OneTimeValue<T>(private val value: T) {
    private val consumed = AtomicBoolean(false)

    fun consume(): T? = if (consumed.compareAndSet(false, true)) value else null
}

object PairingResponseParser {
    fun parse(json: String): PairingResponse {
        val strings = json.stringFields()
        val numbers = json.numberFields()
        return PairingResponse(
            pairId = strings.required("pair_id"),
            code = strings.required("code"),
            pollSecret = strings.required("poll_secret"),
            expiresIn = numbers.required("expires_in"),
            status = PairingStatusParser.parse(strings.required("status"))
        )
    }
}

object PairingStatusResponseParser {
    fun parse(json: String): PairingStatusResponse {
        val strings = json.stringFields()
        return PairingStatusResponse(
            pairId = strings.required("pair_id"),
            status = PairingStatusParser.parse(strings.required("status")),
            deviceToken = strings["device_token"],
            deviceId = strings["device_id"],
            scopes = json.scopesField(),
            expiresAt = strings["expires_at"]
        )
    }
}

private object PairingStatusParser {
    fun parse(raw: String): PairingStatus = when (raw) {
        "pending" -> PairingStatus.PENDING
        "approved" -> PairingStatus.APPROVED
        else -> error("Unsupported pairing status: $raw")
    }
}

private fun String.stringFields(): Map<String, String> = STRING_FIELD.findAll(this)
    .associate { it.groupValues[1] to it.groupValues[2] }

private fun String.numberFields(): Map<String, Int> = NUMBER_FIELD.findAll(this)
    .associate { it.groupValues[1] to it.groupValues[2].toInt() }

private fun String.scopesField(): List<String> = SCOPES_FIELD.find(this)?.groupValues?.get(1)
    ?.let { SCOPE_ITEM.findAll(it).map { match -> match.groupValues[1] }.toList() }
    ?: emptyList()

private fun Map<String, String>.required(name: String): String = this[name]?.takeIf { it.isNotEmpty() }
    ?: error("Missing required field: $name")

private fun Map<String, Int>.required(name: String): Int = this[name]
    ?: error("Missing required field: $name")
