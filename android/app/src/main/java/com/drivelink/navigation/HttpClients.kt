package com.drivelink.navigation

import com.drivelink.BuildConfig
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** One shared client. Public OSM-family servers (Nominatim, OSRM demo) require an identifying User-Agent. */
object HttpClients {
    val userAgent: String = "DriveLink/${BuildConfig.VERSION_NAME} (Android; https://github.com/Code-Glitch8/DriveLink)"

    val shared: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("User-Agent", userAgent).build())
        }
        .build()
}
