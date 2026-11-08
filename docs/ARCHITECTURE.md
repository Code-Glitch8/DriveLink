# Architecture

```
 Android phone A  <—— Nearby Connections (BLE/Wi-Fi, no internet) ——>  Android phone B
      │  Supabase (HTTPS): sign-in, vehicle registry, emergency credential │
      └──────────────────────────── Supabase ──────────────────────────────┘
 Website (static)  ——— Supabase JS (anon key + RLS) ———>  Supabase (Auth, Postgres, Edge Functions)
```

**Local-first:** Supabase is only the identity/vehicle registry. Once peers are connected, status, merge requests,
hazards and emergency alerts flow phone-to-phone with no internet. Live positions are never uploaded.

## Android modules (`com.drivelink.*`)
- `communication` — `NearbyConnectionManager` (transport, P2P_CLUSTER, strategy swappable), `DeviceDiscoveryManager`
  (who connects to whom, reconnect, peer cap), `VehicleAuthenticationManager`, `CommunicationProtocol`, `MessageSerializer`,
  `VehicleDataManager` (sign/validate/de-dup), `PeerConnectionRepository` (per-peer state, rate limit).
- `location` — `LocationProvider` (adaptive Fused Location), `VehicleTracker` (latest valid peer state, stale pruning), `Geo`.
- `safety` — `RiskEngine` (experimental time-of-closest-approach reasoning, GPS-accuracy aware).
- `driving` — `JourneyManager` orchestrates a journey; `notifications` — `JourneyService` (foreground), `AlertSpeaker` (TTS, priorities, cooldowns).
- `navigation` — destination search (Geocoder), route preview + step list (Directions API), hand-off to Google Maps for turn-by-turn.
- `authentication` — `SupabaseApi` (GoTrue, PostgREST, Edge Functions via OkHttp), `SessionStore`.
- `ui` — Compose screens: sign-in, home map, journey confirmation, driving dashboard (radar), vehicles, settings.

## Trust model
- Each journey creates an ephemeral ECDSA P-256 key. HELLO announces its public half, self-signed; every later message is signed and
  bound to the session id. Messages are rejected if unauthenticated, badly signed, malformed, stale (>30 s skew), replayed (seq), or
  over the rate limit (10/s). Identities live only for the current connection and expire after 5 minutes unless HELLO is refreshed — nothing
  is trusted because of a previous connection.
- **Emergency privileges:** only a server-signed credential (Edge Function `issue-credential`, issued only for admin-verified emergency vehicles,
  24 h expiry) makes a peer's emergency alert "verified". The app verifies it offline with `CRED_PUBLIC_KEY_B64`. Without it, emergency claims are
  shown as "unverified" and never spoken.
- Peers exchange a random session id; the official registration number never leaves the owner's account. Note: the DriveLink Vehicle ID a peer
  announces is self-asserted for ordinary vehicles (only emergency status is server-attested).

## Known limitations
- Not compiled/run on a device yet. Room and DataStore are not used: SharedPreferences hold the session and preferences, and nothing needs a
  local database yet (so `database/` is empty). The session is stored in private app storage, not encrypted storage.
- Google Navigation SDK is **not** integrated (it needs separate Google Cloud approval/licensing). Route preview uses the Directions API and
  turn-by-turn opens the Google Maps app.
- Blind-turn detection is the same trajectory logic without road-geometry data; no lane-level positioning.
- Sudden braking uses GPS speed drop only (experimental); no accelerometer fusion.
- The cached-credential/revocation system is replaced by never trusting previous connections; emergency credentials expire after 24 h.
- The Android app cannot request an emergency account; use the website. Admin verification is via SQL (`admin_set_emergency_status`).
