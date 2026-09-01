package com.andreassamitsch.servusprovider.api

import com.google.gson.Gson
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object ServusNetwork {
    const val WEB_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; WOW64; rv:153.0) Gecko/20100101 Firefox/153.0"
    const val API_BASE_URL = "https://tv-api.redbull.com/"
    const val DMS_BASE_URL = "https://dms.redbull.tv/v5/"
    const val ARTWORK_BASE_URL = "https://resources.redbull.tv/"

    val gson: Gson = Gson()

    val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val request = chain.request().newBuilder()
                .header("User-Agent", WEB_USER_AGENT)
                .header("Accept", "application/json, text/plain, */*")
                .header("Accept-Language", "de-DE,de;q=0.9,en;q=0.8")
                .header("DNT", "1")
                .header("Origin", "https://www.servustv.com")
                .header("Referer", "https://www.servustv.com/")
                .build()
            chain.proceed(request)
        }
        .build()

    private val rawApi: ServusApi = Retrofit.Builder()
        .baseUrl(API_BASE_URL)
        .client(httpClient)
        .addConverterFactory(GsonConverterFactory.create(gson))
        .build()
        .create(ServusApi::class.java)

    val api: ServusApi = ServusResilientApi(rawApi)
}
