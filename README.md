# DriveLink — Connect. Communicate. Navigate.

Experimental smartphone vehicle-to-vehicle (V2V) awareness platform: a native Android app that talks directly to
nearby DriveLink phones over Google Nearby Connections, a static website for accounts/vehicles/simulation, and a
Supabase backend for identity and the vehicle registry.

> **Experimental driver-awareness tool. Not a certified ADAS or collision-avoidance system.** Consumer-phone GPS is
> not lane-accurate. Nothing here controls a vehicle. Never operate a phone while driving.

## What is in this repository
| Path | Contents |
|---|---|
| `index.html`, `website/` | Responsive static site (Supabase auth, vehicle registry, risk simulator, optional Google map) |
| `android/` | Kotlin + Jetpack Compose app (MVVM, Nearby Connections, Fused Location, Maps Compose, foreground service, TTS) |
| `supabase/migrations/` | Schema, triggers, RLS (**already applied to the "Drivelink" project**) |
| `supabase/functions/` | `delete-account`, `issue-credential` Edge Functions (**already deployed**) |
| `.github/workflows/` | `android-build.yml` (APK), `android-release.yml` (signed, optional), `deploy-website.yml` (Pages) |
| `docs/` | Architecture, database, protocol, setup, testing |

## Quick start
### 1. Build the APK on GitHub (no local Android Studio needed)
1. Create a GitHub repo and push this folder (`git init && git add . && git commit -m init && git push`).
2. **Actions → Build Android APK → Run workflow** (it also runs on every push to `main`).
3. Download **DriveLink-debug-apk** from the run's *Artifacts* and install `DriveLink-debug.apk` on an Android 8+ phone
   (allow "install unknown apps").

Optional repo secrets (Settings → Secrets and variables → Actions): `CRED_PUBLIC_KEY_B64` (needed to verify emergency
vehicles), `SUPABASE_URL` / `SUPABASE_ANON_KEY` (default to the included project). The map (MapLibre + OpenFreeMap) and routing
(OSRM) need **no API key or billing account**. See `docs/ANDROID_SETUP.md` and `docs/MAPS_AND_ROUTING.md`.

### 2. Publish the website
Settings → Pages → Source: **GitHub Actions**, then run **Deploy website** (or push a change to `index.html`/`website/`).
In Supabase → Authentication → URL Configuration set **Site URL** to your Pages URL so email-verification and password-reset links return to it.
Details: `docs/WEBSITE_SETUP.md`.

### 3. Backend
Already applied to the Supabase project `zfvbaicrqejktfiszefr`. To use another project, run the SQL files in
`supabase/migrations/` in order, deploy both functions, and change the URL/anon key. Make yourself admin with `supabase/seed.sql`.

## Honest status
Written and reasoned through carefully but **not yet compiled or run on a device** (the authoring environment had no Android
SDK or network). Expect to fix small compile errors on the first CI run — the build log will point at them. Known gaps are listed in
`docs/ARCHITECTURE.md#known-limitations`.
