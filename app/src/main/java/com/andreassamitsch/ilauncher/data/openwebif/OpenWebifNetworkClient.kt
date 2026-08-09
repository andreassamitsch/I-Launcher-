package com.andreassamitsch.ilauncher.data.openwebif

import okhttp3.Credentials
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

internal object OpenWebifNetworkClient {
    fun create(config: OpenWebifConfig): OpenWebifApi = Retrofit.Builder()
        .baseUrl(config.baseUrl)
        .client(createHttpClient(config))
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(OpenWebifApi::class.java)

    fun createHttpClient(config: OpenWebifConfig): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .writeTimeout(8, TimeUnit.SECONDS)
        .apply {
            if (config.username.isNotBlank()) {
                val authorization = Credentials.basic(config.username, config.password)
                addInterceptor { chain ->
                    chain.proceed(
                        chain.request().newBuilder()
                            .header("Authorization", authorization)
                            .build(),
                    )
                }
            }
        }
        .build()
}
