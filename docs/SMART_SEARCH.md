# Smart destination search

**Provider:** [Photon](https://photon.komoot.io) — an OpenStreetMap geocoder designed for search-as-you-type, with location bias. No API key.
Fallback: **Nominatim** (OSM) for a one-shot query. Both are OSM-data based; nothing here needs Google services or a paid key.

## Behaviour
- Suggestions appear while typing: **debounced 350 ms**, **minimum 3 characters**, in-flight request **cancelled** when the user keeps typing.
- Results are biased to the user's position (`lat`/`lon`), so "Central Mall" resolves to the one nearby.
- Each suggestion shows a **title**, a **locality subtitle** (street, district, city, state), distance, and a **category** (landmark, address,
  city/area, food, fuel, health, transport, shop, hotel, park, education).
- **Dedupe**: same normalised title within 150 m is one result (Photon often returns a building and its node).
- **Ambiguity**: when several results share a name but are far apart, all are flagged and the locality is highlighted so the user can tell them apart.
- **Ranking**: exact title > prefix > word-prefix > contains > matched elsewhere (address/locality), then place importance, then distance.
- **Cache**: 40 entries / 5 min TTL, keyed by normalised query + position rounded to ~11 km.
- **States**: idle, "type at least 3 characters", loading, results, empty ("No places found…"), error with Retry, and a note when only basic fallback results are shown.
- Choosing a result sets the destination, centres the map, and requests routes immediately.
- No text input exists on the driving dashboard.

## Usage policy
The public Photon server is fair-use. DriveLink's debounce, 3-char minimum, cancellation, cache and `User-Agent: DriveLink/<version> (…github URL)`
keep traffic low; Nominatim is only hit as a fallback (its policy: max 1 req/s, no autocomplete). For production volumes **self-host Photon**
and set `PHOTON_BASE_URL` (see `android/drivelink.properties.example`). Do not point a large user base at the public servers.

## Verification status
Parsing, URL building, ranking, dedupe, cache and category mapping are unit-tested with sample Photon payloads.
**Live calls to photon.komoot.io could not be made from the build sandbox** (network allowlist), so real-world response shapes and rate behaviour
must be confirmed on a device. The parser skips malformed features instead of failing, which limits the blast radius of any shape difference.
