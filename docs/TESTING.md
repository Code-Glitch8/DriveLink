# Testing

**Automated (JVM, run in CI as a non-blocking step):** `cd android && gradle testDebugUnitTest` — risk engine scenarios (crossing = HIGH_RISK,
parallel/diverging/stationary = NORMAL, poor accuracy/stale = no warning), protocol validation, serializer, geo maths, authentication
(unauthenticated ignored, tampering, replay, session spoofing, emergency credential verified/unverified).

**Automated additions (95 tests total in CI):** heading maths/fusion/camera gate, speed filter, hazard parsing/geometry/route-corridor boxes, search parsing/ranking/cache,
route labelling/duplicates/hazard advice, merge recipient selection, protocol `act` validation. SQL behaviour tests for the hazard migration:
`supabase/tests/hazards_test.sql` (run on a scratch Postgres/Supabase branch — never production).

**Needs a real device / on-road test (NOT proven by the automated tests):**
- Heading-Up: compass drift/calibration, phone in a mount vs flat vs in hand, smoothness while turning, behaviour with sensors disabled, GPS-course switch at ~10 km/h, battery with screen on.
- Speed: GPS noise at a stop, tunnels/urban canyons, comparison with the vehicle speedometer.
- Photon search: real response shapes and latency, ambiguous names, no-network error, rate limiting.
- OSRM alternatives: how many routes come back for real trips; map line distinguishability outdoors in sunlight.
- Hazards: apply the migration, then report from phone A, see on phone B, confirm, clear with two accounts, watch expiry; poor-network reporting.
- Merge: two or more phones in cars/on foot at lane-width offsets; verify left/right with different headings, cancel, 8 s expiry, no alert for the wrong side, behaviour at low GPS accuracy.
- UI: dark/light on real displays, glanceability while driving (as a passenger), landscape, large fonts.

**Website:** open the Simulator tab, run each scenario; open DevTools console for errors. Risk logic matches the Android engine.

**Supabase checks (SQL editor as different users):** a user cannot select/update another user's vehicles; cannot change `drive_link_vehicle_id`,
`owner_id`, `verification_status`; cannot insert into `emergency_accounts`; `select public.generate_drivelink_vehicle_id('IND')` as a normal user fails.

**Device test (2 Android phones):**
1. Sign in on both, register a vehicle, set it active. 2. Start a journey on both (grant permissions). 3. Within ~1 minute the status line shows
"Connected to 1 nearby vehicle(s)" and a dot appears on the radar. 4. Turn on airplane mode, then Bluetooth/Wi-Fi back on — communication continues.
5. Tap Merge left on one phone — the other shows "requests to merge left — not permission". 6. Report a hazard. 7. Walk apart > 300 m / switch one off — it
disappears within ~10 s. 8. End journey: two confirmations; Emergency exit ends immediately.
9. Emergency: admin verifies an emergency vehicle (`admin_set_emergency_status`), owner starts a journey (needs internet once for the credential), "Send emergency
alert" appears; the other phone (with `CRED_PUBLIC_KEY_B64` configured) shows a verified emergency alert.

**Field testing caution:** test as a passenger or while stationary/at walking speed. Do not rely on this for safety.
