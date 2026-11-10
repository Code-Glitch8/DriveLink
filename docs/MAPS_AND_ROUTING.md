# Maps and routing

DriveLink renders maps with **MapLibre Native for Android** (`org.maplibre.gl:android-sdk:11.12.0`, OpenGL ES build) using
**OpenFreeMap** vector styles built from **OpenStreetMap** data. Routing is a **separate** service (OSRM). Nothing needs an API key.

## Providers and configuration
| Purpose | Default | Override (drivelink.properties / env) |
|---|---|---|
| Light style | `https://tiles.openfreemap.org/styles/liberty` | `MAP_STYLE_LIGHT_URL` |
| Dark style | `https://tiles.openfreemap.org/styles/dark` | `MAP_STYLE_DARK_URL` |
| Routing | `https://router.project-osrm.org` | `ROUTING_BASE_URL` |
| Place search fallback | `https://nominatim.openstreetmap.org` | `GEOCODER_BASE_URL` |

Style fallback chain: dark style → light style → plain offline background. Vehicles, routes and your location are separate GeoJSON
layers, so they still draw when base tiles cannot load. A banner with **Retry** explains which fallback is active.

## Map orientation and speed
- **Heading source** (`orientation/HeadingFusion.kt`): compass (TYPE_ROTATION_VECTOR, falling back to accelerometer+magnetometer, converted to true north with
  `GeomagneticField`) when stationary/slow; **GPS course** when moving (≥ 3 m/s, accuracy ≤ 30 m, fix has a bearing). Between 1.5 and 3 m/s the previous source is kept
  (hysteresis). If the preferred source is missing/stale the other is used; if neither is trustworthy no heading is invented and the map stays North-Up.
- **Smoothing**: circular low-pass (correct across 359°→1°), 1.5° dead-band, snap (no long swing) when switching source. `CameraUpdateGate` only moves the camera for ≥ 2° turns
  or ≥ 1.5 m moves and at most every 200 ms.
- **Phone angle**: the pointing axis is chosen from the gravity vector (flat → top edge, upright → camera axis); near the boundary the reading is flagged unreliable.
- **Lifecycle**: `HeadingTracker` is ref-counted: the driving screen (started/stopped with the lifecycle) and a running journey each acquire/release it; sensors stop when nobody needs them.
  Devices without orientation sensors degrade to GPS course / North-Up.
- **Controls**: ▲/N toggles Heading-Up/North-Up (remembered for the app session), ◎ Recenter (highlighted after you pan away). Marker, vehicles, hazards and routes are always
  placed by true coordinates; only the camera bearing changes.
- **Speed** (`location/SpeedFilter.kt`): EMA (faster catch-up on hard acceleration), rejects fixes without speed / accuracy > 50 m / speed accuracy > 3 m/s / older than 3 s /
  non-advancing timestamps; snaps to 0 below ~2 km/h; after 6 s without an accepted fix the widget shows "—  GPS speed unavailable" instead of a stale number.

## Route alternatives and hazard-aware advice
OSRM is asked for `alternatives=3`; `RouteUtil` drops near-identical routes, labels **Fastest / Shortest / Alternative**, and extracts the major named roads
(≥ 12 % of the distance, in driving order). Each option has time, distance and roads; the selected route is solid and the alternatives are dashed in distinct colours.
`RouteAdvisor` counts active hazards within 50 m of each route. It warns only for severity ≥ 2 hazards ("Reported accident on this route in 980 m (unconfirmed)") and
suggests another option only if it removes enough weighted impact, showing the time and distance delta. It never says a road is "blocked" or "closed" as fact.
The public OSRM server returns fewer alternatives than requested depending on geography; one route is a normal outcome.

## Usage limits: read before production
- **OpenFreeMap** is a free public service. Check <https://openfreemap.org> for current terms; consider self-hosting for heavy use. Attribution
  ("© OpenStreetMap contributors · OpenFreeMap · © OpenMapTiles") is always shown on the map.
- **OSRM public demo server**: reasonable, non-commercial use only, about 1 request/second, identifying User-Agent, no uptime guarantee,
  car profile only. The app spaces requests ≥1.1 s apart and sends a User-Agent. For real traffic, **self-host OSRM** or use a paid router and set `ROUTING_BASE_URL`.
- **Photon** (autocomplete, `PHOTON_BASE_URL`) is fair-use; see [SMART_SEARCH](SMART_SEARCH.md). Self-host for real traffic.
- **Nominatim public server**: max 1 request/second, identifying User-Agent, no search-as-you-type. Used only as the fallback when Photon fails.
- The dark style URL above follows OpenFreeMap's naming but was **not** verified against a live request during the migration; if it fails the app falls back to light.

## Layer tree (`com.drivelink.map`)
```
DRIVELINK MAP
1. Base map      Background · Land · Water · Parks · Buildings      (classified from the style's layers)
2. Road network  Motorways · Primary · Secondary · Residential/other · Road labels
3. Navigation    dl-nav-route-alt · dl-nav-route-casing · dl-nav-route-main · dl-nav-destination · dl-nav-me(-halo)
4. Vehicles      dl-veh-emergency-halo · dl-veh-approaching · dl-veh-merge · dl-veh-direction
5. UI            dl-ui-selected-vehicle · dl-ui-selected-destination
```
Groups 1-2 come from the OpenMapTiles-schema style and are classified by layer id / source-layer (heuristic, used for the debug panel and group toggles).
Groups 3-5 are owned by `MapLayerManager` (add / remove / update / visibility / reorder / update-source). Navigation layers are inserted beneath the
style's first label layer so labels stay readable. IDs live in `MapIds`. Data updates only call `GeoJsonSource.setGeoJson`; the style is not rebuilt.

## Vehicles on the map
Drawn only from authenticated peers in `JourneyManager` (Nearby Connections); there is no simulated data. Heading arrow, approaching ring (risk ≥ caution),
red conflict arrow, amber ring for a recent lane-merge request (8 s), red halo for credential-verified emergency vehicles, blue ring for the selected vehicle.
Peers silent for more than `MapConfig.DEFAULT_VEHICLE_STALE_TIMEOUT_MS` (10 s) are not drawn; older than 5 s are dimmed.
Vehicles appear on the **journey dashboard** (Map view, with Radar view toggle). The home screen shows none because peers only exist during a journey.

## Debug panel
Debug builds only (`BuildConfig.DEBUG`): the wrench button on the map opens the live layer tree (ids, types, sources, order, visibility toggles, source update
counters). Release builds contain no entry point to it.

## Upgrading MapLibre
13.x needs Kotlin 2.2 (`kotlin-stdlib 2.2.10`) and newer AndroidX. To move to it, upgrade Kotlin (+ Compose compiler plugin), AGP and compileSdk first.
