package com.drivelink.config

import com.drivelink.BuildConfig

object AppConfig {
    val supabaseUrl: String get() = BuildConfig.SUPABASE_URL
    val supabaseAnonKey: String get() = BuildConfig.SUPABASE_ANON_KEY
    val credentialPublicKey: String get() = BuildConfig.CRED_PUBLIC_KEY_B64

    // Map and routing providers (no API keys). See docs/MAPS_AND_ROUTING.md for usage-policy notes.
    val mapStyleLightUrl: String get() = BuildConfig.MAP_STYLE_LIGHT_URL
    val mapStyleDarkUrl: String get() = BuildConfig.MAP_STYLE_DARK_URL
    val routingBaseUrl: String get() = BuildConfig.ROUTING_BASE_URL.trimEnd('/')
    val photonBaseUrl: String get() = BuildConfig.PHOTON_BASE_URL.trimEnd('/')
    val geocoderBaseUrl: String get() = BuildConfig.GEOCODER_BASE_URL.trimEnd('/')
}
