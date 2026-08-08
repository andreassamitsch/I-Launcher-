package com.andreassamitsch.ilauncher.data.tmdb

import com.andreassamitsch.ilauncher.BuildConfig
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

internal class TmdbNetworkClient(
    private val readAccessToken: String,
) {
    val isConfigured: Boolean
        get() = readAccessToken.isNotBlank()

    val api: TmdbApi by lazy {
        val httpClient = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val requestBuilder = chain.request()
                    .newBuilder()
                    .header("Accept", "application/json")
                    .header("User-Agent", "I-Launcher/${BuildConfig.VERSION_NAME}")

                if (readAccessToken.isNotBlank()) {
                    requestBuilder.header("Authorization", "Bearer $readAccessToken")
                }

                chain.proceed(requestBuilder.build())
            }
            .build()

        Retrofit.Builder()
            .baseUrl("https://api.themoviedb.org/")
            .client(httpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(TmdbApi::class.java)
    }
}
