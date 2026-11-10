# Android setup

Requirements for local builds: JDK 17, Android SDK 34, Gradle 8.7 (or open `android/` in Android Studio Koala+, which supplies Gradle).
Copy `android/drivelink.properties.example` to `android/drivelink.properties` (git-ignored) or provide the same names as environment variables.

| Key | Purpose |
|---|---|
| `SUPABASE_URL`, `SUPABASE_ANON_KEY` | Default to the included project; public values only |
| `MAP_STYLE_LIGHT_URL`, `MAP_STYLE_DARK_URL`, `ROUTING_BASE_URL`, `GEOCODER_BASE_URL` | Optional. Default to OpenFreeMap / OSRM demo / Nominatim; no keys. See `MAPS_AND_ROUTING.md` |
| `CRED_PUBLIC_KEY_B64` | Verifies emergency credentials |

**Maps:** no Google key is needed any more (MapLibre Native + OpenFreeMap + OSRM). Read `docs/MAPS_AND_ROUTING.md` before shipping to many users: the default public servers have usage limits.

**Emergency credentials:** run `scripts/gen-credential-keys.sh`; set the private output as Supabase secret `CRED_SIGNING_KEY_PKCS8_B64` (Project → Edge Functions → Secrets) and the public output as GitHub secret `CRED_PUBLIC_KEY_B64`.

**Release signing:** create a keystore (`keytool -genkeypair -v -keystore release.keystore -alias drivelink -keyalg RSA -keysize 2048 -validity 10000`), then add secrets `ANDROID_KEYSTORE_BASE64` (`base64 -w0 release.keystore`), `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD` and run **Android signed release (optional)**.

**Permissions:** location (while using), Bluetooth/Nearby devices, notifications. Two phones are needed to test communication; both must have Bluetooth and Wi-Fi on.
