package com.drivelink.config

import com.drivelink.BuildConfig

object AppConfig {
    val supabaseUrl: String get() = BuildConfig.SUPABASE_URL
    val supabaseAnonKey: String get() = BuildConfig.SUPABASE_ANON_KEY
    val mapsKey: String get() = BuildConfig.MAPS_API_KEY
    val credentialPublicKey: String get() = BuildConfig.CRED_PUBLIC_KEY_B64
    val mapsConfigured: Boolean get() = BuildConfig.MAPS_API_KEY.isNotBlank()
}
