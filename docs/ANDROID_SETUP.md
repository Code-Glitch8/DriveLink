# Android setup

Requirements for local builds: JDK 17, Android SDK 34, Gradle 8.7 (or open `android/` in Android Studio Koala+, which supplies Gradle).
Copy `android/drivelink.properties.example` to `android/drivelink.properties` (git-ignored) or provide the same names as environment variables.

| Key | Purpose |
|---|---|
| `SUPABASE_URL`, `SUPABASE_ANON_KEY` | Default to the included project; public values only |
| `GOOGLE_MAPS_API_KEY` | Map, route preview. Enable **Maps SDK for Android** and **Directions API** |
| `CRED_PUBLIC_KEY_B64` | Verifies emergency credentials |

**Google Maps key:** Cloud Console → APIs & Services → Credentials → Create API key → restrict to *Android apps* (package `com.drivelink` + your signing-certificate SHA-1; for GitHub debug builds use the debug keystore SHA-1 shown by `keytool`) and to the two APIs above. The Directions call is made from the phone with the same key — Google recommends app restriction; if you prefer, use a second key with only Directions enabled. Use a **different** key for the website.

**Emergency credentials:** run `scripts/gen-credential-keys.sh`; set the private output as Supabase secret `CRED_SIGNING_KEY_PKCS8_B64` (Project → Edge Functions → Secrets) and the public output as GitHub secret `CRED_PUBLIC_KEY_B64`.

**Release signing:** create a keystore (`keytool -genkeypair -v -keystore release.keystore -alias drivelink -keyalg RSA -keysize 2048 -validity 10000`), then add secrets `ANDROID_KEYSTORE_BASE64` (`base64 -w0 release.keystore`), `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD` and run **Android signed release (optional)**.

**Permissions:** location (while using), Bluetooth/Nearby devices, notifications. Two phones are needed to test communication; both must have Bluetooth and Wi-Fi on.
