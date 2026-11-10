# Shared hazard system

Hazards are shared through **Supabase** (not only Nearby Connections), so drivers who are not physically linked still see
what is ahead. Nearby Connections remains the *instant local* channel; Supabase is the *central, validated, expiring* record.

> **Deployment:** the schema lives in `supabase/migrations/20261109000001_shared_hazards.sql`. It has **not** been applied to your
> production project by this change. Until you apply it the app keeps working; hazard polling/reporting just shows a sync error.
> See [DATABASE_MIGRATIONS.md](DATABASE_MIGRATIONS.md).

## Categories (distinct icon + colour + shape)
| Key | Label | Default expiry | Max life | Dedupe radius | Relevance radius | Severity |
|---|---|---|---|---|---|---|
| `accident` | Accident | 60 min | 4 h | 75 m | 3 km | 3 |
| `road_blockage` | Road blockage | 90 min | 6 h | 75 m | 3 km | 3 |
| `construction` | Construction | 8 h | 24 h | 100 m | 3 km | 1 |
| `pothole` | Pothole | 12 h | 3 d | 25 m | 1.5 km | 1 |
| `vehicle_breakdown` | Vehicle breakdown | 45 min | 4 h | 40 m | 2 km | 2 |
| `traffic_congestion` | Traffic congestion | 30 min | 3 h | 300 m | 5 km | 1 |
| `road_closure` | Road closure | 4 h | 24 h | 100 m | 5 km | 3 |
| `other_danger` | Other danger | 60 min | 4 h | 50 m | 2 km | 2 |

Expiry is **configurable per category** in the table `hazard_category_config` (edit a row, no app release needed). Each confirmation
extends the expiry (`extend_on_confirm_minutes`) but never beyond `max_lifetime_minutes` from the original report.
Icons are drawn in code (`map/HazardIcons.kt`); shape *and* colour differ so they are distinguishable without colour vision.

## Lifecycle
1. **Report** (`report_hazard` RPC): category, position, optional description (≤280 chars), GPS accuracy. Timestamps are **server-side**
   (`now()`), never trusted from the phone. Rejected: unknown category, invalid/null-island coordinates, accuracy > 100 m, inactive accounts,
   more than 8 reports per user per 10 minutes.
2. **Dedupe**: same category, still active, within the category's radius ⇒ the report becomes a *confirmation* of the existing hazard.
   One user can only add one confirmation per hazard.
3. **Confirm / clear** (`vote_hazard` RPC): "Still there" or "It's gone". The voter must claim a position within 1.5 km of the hazard.
   - A hazard becomes **cleared** only on independent agreement: 2 clear votes, or the lone reporter retracting their own unconfirmed report,
     or clear votes outnumbering confirmations. A single clear vote on a confirmed hazard only shows "1 reported cleared".
4. **Expiry**: `expires_at <= now()` hides a hazard *immediately* (RLS and RPCs filter on it, and the client filters again each 10 s tick),
   even before cleanup runs. `purge_expired_hazards()` marks them `expired`, deletes expired/cleared rows after 7 days and audit rows after 30 days.
   It is scheduled every 15 minutes if `pg_cron` is enabled, otherwise call it from a Supabase scheduled function or the dashboard.
5. **Audit**: `road_hazard_audit` keeps `report / merged / confirm / clear_vote / cleared / expired` events for 30 days (no client access).

## Confidence — never "fact" from one report
`low` (1 report) → `medium` (2) → `high` (3+) → `verified` (set by an admin) / `disputed` (clear votes ≥ confirmations).
The UI says "Reported by 1 driver — unconfirmed"; low and disputed reports are drawn faded; route warnings say "reported", never "blocked".

## Security / privacy
- Direct `INSERT/UPDATE/DELETE` on `road_hazards` is revoked; every write goes through a validating `SECURITY DEFINER` function (`search_path = public`).
- The reporter id is **not readable** (column-level grant excludes `reporter_id`) and is never returned by the RPCs.
- RLS: signed-in users read only `status='active' and expires_at > now()`; votes readable only by their owner; audit not readable.
- Position claims cannot be proven server-side — the 1.5 km vote radius, rate limit, dedupe and multi-user clearing limit abuse but do not eliminate it.

## Which users receive what (relevance)
Not "everything to everyone". `hazards_in_bbox` returns active hazards inside a bounding box (server rejects boxes > 2° per side, max 500 rows).
The client requests: a 5 km box around the user, plus ≤ 6 corridor boxes along the **selected route** (built from the route geometry).
On the client, `HazardGeometry` then decides:
- **Nearby**: within the category's relevance radius and not clearly behind when moving (`nearbyRelevant`) → banner for severity ≥ 2.
- **Approaching / on route**: within 50 m of the route polyline (`hazardsOnRoute`), ordered by distance along the route.

Polling (Realtime is a possible upgrade): 12 s while moving, 30 s when parked, exponential back-off to 2 min on errors, stopped when signed out.
Hazard rows are small; a poll is one RPC call per box.

## Map and driving UX
- Distinct icon per category; a red ring marks severity-3 hazards. Tapping a hazard shows details **only while stopped** (< 5 km/h);
  while moving the app shows "Stop to view hazard details".
- Reporting is one tap on big buttons (no typing) at the current GPS position. The detail dialog (category, description, reported time,
  distance, confidence, expiry, Still there / It's gone) is only reachable when stopped or on the Home screen.
- A concise banner shows the nearest relevant severity ≥ 2 hazard ahead ("Accident ahead · 400 m · unconfirmed").

## Not covered / limitations
- Reports made offline are **not queued**; the user sees an error and can retry.
- Clock skew: expiry uses the device clock for display filtering; the server list is authoritative on each poll.
- No hazard photos, no per-user reputation score, no Realtime push yet.
