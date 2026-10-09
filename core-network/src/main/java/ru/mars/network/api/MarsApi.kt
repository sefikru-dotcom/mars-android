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
    companion object {
        /** Default backend base URL shared by all Mars clients. */
        const val BASE_URL: String = "https://marscore.ru"
    }

    @POST("api/v1/auth/device-pairings")
    suspend fun createPairing(): Response<PairingResponse>

    /**
     * `GET /api/v1/auth/device-pairings/status` — identified by `pair_id` ONLY.
     *
     * The deployed server (api/routes.py) answers 400 `poll_secret is accepted
     * only by exchange` when this request carries the `X-Hermes-Poll-Secret`
     * header or a `poll_secret` query parameter, so the interface must not
     * declare either. The poll secret belongs exclusively to
     * [exchangePairing]'s JSON body ([PairingExchangeRequest.pollSecret]).
     */
    @GET("api/v1/auth/device-pairings/status")
    suspend fun pollPairingStatus(
        @Query("pair_id") pairId: String
    ): Response<PairingStatusResponse>

    @POST("api/v1/auth/device-pairings/{pair_id}/exchange")
    suspend fun exchangePairing(
        @Path("pair_id") pairId: String,
        @Body request: PairingExchangeRequest
    ): Response<PairingExchangeResponse>

    @POST("api/v1/auth/devices/{device_id}/revoke")
    suspend fun revokeCredential(
        @Path("device_id") deviceId: String,
        @Header("Authorization") authorization: String
    ): Response<Unit>
}
