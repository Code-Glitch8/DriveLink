# Communication protocol (v1)

Service id `com.drivelink.v1`; endpoint name `DL1-<8 hex>` (random per journey). Strategy `P2P_CLUSTER`. Payload type: bytes (≤ 4096).
The phone whose name sorts lower initiates the connection; the other falls back after 8 s. Max 6 peers.

Envelope (JSON):
```json
{"protocol":"DRIVELINK","version":1,"type":"VEHICLE_STATUS","sid":"DL1-1a2b3c4d","seq":42,"ts":1793960000,
 "body":"{\"lat\":28.6139,\"lon\":77.209,\"spd\":35.0,\"hdg\":120.0,\"acc\":6.0}","sig":"<base64 DER ECDSA P-256/SHA-256>"}
```
Signature covers `DRIVELINK|1|TYPE|sid|seq|ts|body`. `ts` is Unix seconds.

| type | body |
|---|---|
| `HELLO` | `{pub, vid, vtype, cp?, cs?}` — public key (X.509 b64), DriveLink ID, category, optional emergency credential (payload, P1363 signature) |
| `VEHICLE_STATUS` | `{lat, lon, spd (km/h), hdg (deg), acc (m)}` — sent ~1 Hz |
| `LANE_CHANGE_REQUEST` | `{dir: LEFT\|RIGHT}` — a request, never permission |
| `EMERGENCY_ALERT` | `{etype: ambulance\|police\|fire_service}` — "verified" only if the sender's HELLO carried a valid credential for that type |
| `HAZARD_ALERT` | `{kind: ACCIDENT\|OBSTRUCTION\|BROKEN_DOWN\|ROAD_DAMAGE\|ROAD_CLOSURE\|SUDDEN_BRAKING\|OTHER, lat, lon}` |

Receiver rules: drop if oversized/malformed/wrong version, clock skew > 30 s, rate > 10 msg/s, `seq` not increasing, no valid HELLO, signature
failure, or body validation failure. Peers silent for 10 s are removed from the map. HELLO is re-sent every 60 s; identities expire after 5 min.

Emergency credential: `{"v":1,"vid":"DL-IND-…","etype":"ambulance","exp":<unix s>}` signed by the `issue-credential` function (ECDSA P-256, raw r‖s).
