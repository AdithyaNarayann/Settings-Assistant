package com.settingslens.app.data

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit

/**
 * Singleton Retrofit client for the Settings Lens backend.
 *
 * Default base URL points to the development machine on the local network.
 * For emulator use 10.0.2.2; for physical device use your laptop's LAN IP.
 */
object ApiClient {

    /**
     * Base URL for the backend server.
     * With physical device via USB: run `adb reverse tcp:8000 tcp:8000` to forward 127.0.0.1:8000 to your PC.
     * For emulator: "http://10.0.2.2:8000/"
     * For Wi-Fi: "http://<YOUR_PC_IP>:8000/"
     */
    @Volatile
    var baseUrl: String = "http://127.0.0.1:8000/"
        set(value) {
            field = if (value.endsWith("/")) value else "$value/"
            synchronized(this) {
                retrofitInstance = null
            }
        }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .addInterceptor(HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        })
        .build()

    @Volatile
    private var retrofitInstance: ApiService? = null

    val api: ApiService
        get() {
            return retrofitInstance ?: synchronized(this) {
                retrofitInstance ?: Retrofit.Builder()
                    .baseUrl(baseUrl)
                    .client(okHttpClient)
                    .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
                    .build()
                    .create(ApiService::class.java)
                    .also { retrofitInstance = it }
            }
        }
}
