package ru.mars.network.api

import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import ru.mars.network.model.*

interface MarsApi {
    @POST("api/auth/device-pairings") suspend fun pair(@Body request: PairingRequest): Response<PairingResponse>
    @GET("api/auth/device-pairings") suspend fun pairings(): Response<List<DevicePairing>>
}
