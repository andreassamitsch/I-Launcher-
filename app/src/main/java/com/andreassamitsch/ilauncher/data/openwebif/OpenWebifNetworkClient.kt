package com.andreassamitsch.ilauncher.data.openwebif

import okhttp3.Credentials
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

internal object OpenWebifNetworkClient {
    fun create(config: OpenWebifConfig): OpenWebifApi {
        val httpClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
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

        return Retrofit.Builder()
            .baseUrl(config.baseUrl)
            .client(httpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(OpenWebifApi::class.java)
    }
}
