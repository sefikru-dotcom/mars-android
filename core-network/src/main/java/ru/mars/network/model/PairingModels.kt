package ru.mars.network.model

private val STRING_FIELD = Regex("\\\"([a-z_]+)\\\"\\s*:\\s*\\\"([^\\\"]*)\\\"")
private val NUMBER_FIELD = Regex("\\\"([a-z_]+)\\\"\\s*:\\s*(\\d+)")


data class PairingResponse(
    val pairId: String,
    val code: String,
    val pollSecret: String,
    val expiresIn: Int,
    val status: PairingStatus
)

data class PairingStatusResponse(val pairId: String, val status: PairingStatus, val expiresAt: String?)

enum class PairingStatus { PENDING, APPROVED }

data class AuthResponse(val accessToken: String?, val refreshToken: String?)

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

private fun Map<String, String>.required(name: String): String = this[name]?.takeIf { it.isNotEmpty() }
    ?: error("Missing required field: $name")

private fun Map<String, Int>.required(name: String): Int = this[name]
    ?: error("Missing required field: $name")
