package ru.mars.network.model

import com.squareup.moshi.JsonReader
import okio.Buffer
import java.util.concurrent.atomic.AtomicBoolean

data class PairingResponse(val pairId: String, val code: String, val pollSecret: String, val expiresIn: Int, val status: PairingStatus)

data class PairingStatusResponse(
    val pairId: String,
    val status: PairingStatus,
    val deviceToken: String? = null,
    val deviceId: String? = null,
    val scopes: List<String> = emptyList(),
    val expiresIn: Int? = null
) {
    fun oneTimeCredentialOrNull(): OneTimeDeviceCredential? =
        if (status == PairingStatus.APPROVED && deviceToken != null && deviceId != null && expiresIn != null) {
            OneTimeDeviceCredential(deviceToken, deviceId, scopes, expiresIn)
        } else null
}

enum class PairingStatus { PENDING, APPROVED }
data class AuthResponse(val accessToken: String?, val refreshToken: String?)

class OneTimeDeviceCredential internal constructor(
    deviceToken: String,
    val deviceId: String,
    val scopes: List<String>,
    val expiresIn: Int
) {
    private val token = OneTimeValue(deviceToken)
    fun consume(): ConsumedDeviceCredential? = token.consume()?.let { ConsumedDeviceCredential(it, deviceId, scopes, expiresIn) }
    override fun toString() = "OneTimeDeviceCredential(deviceToken=[REDACTED], deviceId=$deviceId, scopes=$scopes, expiresIn=$expiresIn)"
}

data class ConsumedDeviceCredential(val deviceToken: String, val deviceId: String, val scopes: List<String>, val expiresIn: Int)
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
        val status = PairingStatusParser.parse(fields.stringRequired("status"))
        val response = PairingStatusResponse(fields.stringRequired("pair_id"), status, fields.string("device_token"), fields.string("device_id"), fields.scopes(), fields.int("expires_in"))
        if (status == PairingStatus.APPROVED) response.oneTimeCredentialOrNull() ?: error("Approved pairing is missing native credential fields")
        return response
    }
}

private object PairingStatusParser {
    fun parse(raw: String) = when (raw) {
        "pending" -> PairingStatus.PENDING
        "approved" -> PairingStatus.APPROVED
        else -> error("Unsupported pairing status: $raw")
    }
}

private class JsonFields(private val values: Map<String, Any?>) {
    fun string(name: String): String? = values[name] as? String
    fun stringRequired(name: String) = string(name)?.takeIf(String::isNotEmpty) ?: error("Missing required field: $name")
    fun int(name: String) = (values[name] as? Number)?.toInt()
    fun intRequired(name: String) = int(name) ?: error("Missing required field: $name")
    fun scopes() = (values["scopes"] as? List<*>).orEmpty().map { it as? String ?: error("Invalid scopes field") }
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
