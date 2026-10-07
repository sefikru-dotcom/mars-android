package ru.mars.network.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonReader
import okio.Buffer
import java.util.concurrent.atomic.AtomicBoolean

data class PairingResponse(val pairId: String, val code: String, val pollSecret: String, val expiresIn: Int, val status: PairingStatus)

data class PairingStatusResponse(val pairId: String, val status: PairingStatus)

data class PairingExchangeRequest(@Json(name = "poll_secret") val pollSecret: String)
data class PairingExchangeResponse(
    @Json(name = "access_token") val accessToken: String,
    @Json(name = "token_type") val tokenType: String,
    @Json(name = "expires_in") val expiresIn: Int,
    @Json(name = "device_id") val deviceId: String,
    val scopes: List<String>
) {
    fun oneTimeCredential(): OneTimeDeviceCredential =
        OneTimeDeviceCredential(accessToken, tokenType, deviceId, scopes, expiresIn)
}

enum class PairingStatus { PENDING, APPROVED, CLAIMED }
data class AuthResponse(val accessToken: String?, val refreshToken: String?)

class OneTimeDeviceCredential internal constructor(
    accessToken: String,
    val tokenType: String,
    val deviceId: String,
    val scopes: List<String>,
    val expiresIn: Int
) {
    private val token = OneTimeValue(accessToken)
    fun consume(): ConsumedDeviceCredential? = token.consume()?.let { ConsumedDeviceCredential(it, tokenType, deviceId, scopes, expiresIn) }
    override fun toString() = "OneTimeDeviceCredential(accessToken=[REDACTED], tokenType=$tokenType, deviceId=$deviceId, scopes=$scopes, expiresIn=$expiresIn)"
}

data class ConsumedDeviceCredential(val accessToken: String, val tokenType: String, val deviceId: String, val scopes: List<String>, val expiresIn: Int)
private class OneTimeValue<T>(private val value: T) {
    private val consumed = AtomicBoolean(false)
    fun consume(): T? = if (consumed.compareAndSet(false, true)) value else null
}

object PairingResponseParser {
    fun parse(json: String): PairingResponse {
        val fields = JsonFields.parse(json)
        return PairingResponse(fields.stringRequired("pair_id"), fields.stringRequired("code"), fields.stringRequired("poll_secret"), fields.intRequired("expires_in"), PairingStatusParser.parse(fields.stringRequired("status")))
    }
}

object PairingStatusResponseParser {
    fun parse(json: String): PairingStatusResponse {
        val fields = JsonFields.parse(json)
        return PairingStatusResponse(fields.stringRequired("pair_id"), PairingStatusParser.parse(fields.stringRequired("status")))
    }
}

object PairingExchangeResponseParser {
    fun parse(json: String): PairingExchangeResponse {
        val fields = JsonFields.parse(json)
        return PairingExchangeResponse(
            fields.stringRequired("access_token"),
            fields.stringRequired("token_type"),
            fields.intRequired("expires_in"),
            fields.stringRequired("device_id"),
            fields.scopesRequired()
        )
    }
}

private object PairingStatusParser {
    fun parse(raw: String) = when (raw) {
        "pending" -> PairingStatus.PENDING
        "approved" -> PairingStatus.APPROVED
        "claimed" -> PairingStatus.CLAIMED
        else -> error("Unsupported pairing status: $raw")
    }
}

private class JsonFields(private val values: Map<String, Any?>) {
    fun string(name: String): String? = values[name] as? String
    fun stringRequired(name: String) = string(name)?.takeIf(String::isNotEmpty) ?: error("Missing required field: $name")
    fun int(name: String) = (values[name] as? Number)?.toInt()
    fun intRequired(name: String) = int(name) ?: error("Missing required field: $name")
    fun scopesRequired() = (values["scopes"] as? List<*>)?.map { it as? String ?: error("Invalid scopes field") }
        ?: error("Missing required field: scopes")
    companion object {
        fun parse(json: String): JsonFields {
            val reader = JsonReader.of(Buffer().writeUtf8(json))
            val values = linkedMapOf<String, Any?>()
            reader.beginObject()
            while (reader.hasNext()) {
                val name = reader.nextName()
                if (name == "scopes") {
                    val items = mutableListOf<String>(); reader.beginArray(); while (reader.hasNext()) items += reader.nextString(); reader.endArray(); values[name] = items
                } else when (reader.peek()) {
                    JsonReader.Token.STRING -> values[name] = reader.nextString()
                    JsonReader.Token.NUMBER -> values[name] = reader.nextLong()
                    else -> reader.skipValue()
                }
            }
            reader.endObject()
            return JsonFields(values)
        }
    }
}