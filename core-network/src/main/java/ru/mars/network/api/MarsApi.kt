package ru.mars.network.api

import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query
import ru.mars.network.model.PairingResponse
import ru.mars.network.model.PairingStatusResponse

interface MarsApi {
    @POST("api/auth/device-pairings")
    suspend fun createPairing(): Response<PairingResponse>

    @GET("api/auth/device-pairings/status")
    suspend fun pollPairingStatus(
        @Query("pair_id") pairId: String,
        @Query("poll_secret") pollSecret: String
    ): Response<PairingStatusResponse>
}
