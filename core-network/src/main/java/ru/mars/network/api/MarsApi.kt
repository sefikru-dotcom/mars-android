package ru.mars.network.api

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Path
import retrofit2.http.POST
import retrofit2.http.Query
import ru.mars.network.model.PairingExchangeRequest
import ru.mars.network.model.PairingExchangeResponse
import ru.mars.network.model.PairingResponse
import ru.mars.network.model.PairingStatusResponse

interface MarsApi {
    @POST("api/auth/device-pairings")
    suspend fun createPairing(): Response<PairingResponse>

    @GET("api/v1/auth/device-pairings/status")
    suspend fun pollPairingStatus(
        @Query("pair_id") pairId: String,
        @Query("poll_secret") pollSecret: String
    ): Response<PairingStatusResponse>

    @POST("api/v1/auth/device-pairings/{pair_id}/exchange")
    suspend fun exchangePairing(
        @Path("pair_id") pairId: String,
        @Body request: PairingExchangeRequest
    ): Response<PairingExchangeResponse>

    @POST("api/v1/auth/device-pairings/revoke")
    suspend fun revokeCredential(@Header("Authorization") authorization: String): Response<Unit>
}
