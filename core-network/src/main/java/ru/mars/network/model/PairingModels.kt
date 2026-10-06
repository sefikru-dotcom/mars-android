package ru.mars.network.model

data class PairingRequest(val code: String, val deviceName: String, val platform: String = "android-tv")
data class PairingResponse(val deviceId: String?, val accessToken: String?, val status: String?, val expiresAt: String?)
data class AuthResponse(val accessToken: String?, val refreshToken: String?)
data class DevicePairing(val id: String, val name: String?, val status: String?, val createdAt: String?)
