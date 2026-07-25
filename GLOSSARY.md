# Blipbird — Glossary

A short glossary of aviation and technical terms used in `PLAN.md`, `ANALYSIS.md`,
and the codebase. Aviation data is imperfect and full of overlapping names; this
exists to lower the contribution barrier, not to be authoritative.

## Flight identity

- **IATA code** — two-character airline designator, at least one letter (e.g.
  `CA` = Air China, `BA` = British Airways). IATA also issues 2-letter airport
  codes (`PEK`, `GVA`).
- **ICAO code** — three-letter airline designator (e.g. `CCA` = Air China,
  `BAW` = British Airways). ICAO also issues 4-letter airport codes (`ZBAA`,
  `LSGG`).
- **Flight number / designator** — airline code + 1–4 digits + optional suffix,
  e.g. `CA861`, `CCA861`. The IATA form (`CA861`) and ICAO form (`CCA861`) refer
  to the same flight.
- **Callsign (ATC)** — what air traffic control and ADS-B broadcast; usually
  equals the ICAO number but **can differ entirely** (European carriers fly
  alphanumeric callsigns like `BAW5GU` for `BA545`). Reused daily; not a stable
  identity.
- **Codeshare** — one operating flight marketed under multiple flight numbers.
  Resolved to the operating flight while displaying "operated by …".
- **Registration (tail number)** — the aircraft's unique ID, e.g. `B-2032`. The
  `icao24` **hex** (`780abc`) is the ADS-B transponder address derived from it.

## Flight phases & milestones

- **OUT** — pushback / gate departure (wheels moving at the gate).
- **OFF** — wheels-off / takeoff.
- **ON** — wheels-on / landing.
- **IN** — gate arrival (parking brake set).
- These four are distinct because six generic departure/arrival timestamps
  cannot represent scheduled/estimated/actual × OUT/OFF/ON/IN.
- **FL (flight level)** — pressure altitude in hundreds of feet, e.g. FL340 ≈
  34,000 ft on the standard-pressure (1013.25 hPa) datum. Not the same as
  geometric MSL or ellipsoid altitude — never compare them directly.

## Weather

- **METAR** — hourly-ish *observation* at an airport (current conditions).
- **TAF** — *forecast* at an airport, with prevailing conditions plus `FM`/
  `BECMG`/`TEMPO`/`PROB` change groups. Blipbird resolves the group valid at
  ETA.
- **SIGMET / AIRMET** — significant / airmen's meteorological advisories
  (convective, icing, turbulence, etc.). Worldwide international SIGMETs come
  from `/isigmet`; CONUS domestic from `/airsigmet`.
- **AWC** — NOAA's Aviation Weather Center (`aviationweather.gov`), the free,
  no-key source Blipbird uses for METAR/TAF/SIGMET.

## Data sources & map

- **ADS-B** — Automatic Dependent Surveillance–Broadcast; how community
  feeders (`adsb.lol`, `airplanes.live`, `adsb.fi`) see live aircraft positions.
  Terrestrial; gaps over oceans, Siberia, parts of Africa; thin in mainland
  China.
- **OpenFreeMap** — free, no-API-key, no-billing map tiles (OpenStreetMap-
  derived). No SLA.
- **MapLibre** — open-source map renderer; `maplibre-compose` is the
  declarative Compose wrapper (pre-1.0).
- **ODbL** — Open Data Commons Open Database License (used by OSM/adsb.lol);
  carries attribution and share-alike obligations on the *database*.

## Platform / Android

- **Live Updates** — Android 16 QPR2 promoted ongoing notifications
  (`setRequestPromotedOngoing(true)`); the native analog of iOS Live
  Activities. Promotion is never guaranteed.
- **Glance** — Jetpack library for home-screen widgets.
- **WorkManager** — the background-work primitive; ≥15-min floor, best-effort
  under Doze. Blipbird never claims a guaranteed notification latency.
- **Baseline Profile** — generated profile that skips interpretation/JIT warmup
  for hot code paths; measured perf is meaningless without one.

## Blipbird internals

- **Itinerary** — a user-owned, explicitly ordered collection of at least two
  tracked flight occurrences. One tracked flight belongs to at most one
  itinerary in the first version.
- **Leg** — one itinerary membership pointing to an existing tracked flight.
- **Transition** — the neutral directed edge between adjacent itinerary legs.
  The user classifies it as a direct connection, destination stay, surface
  transfer, or unknown; adjacency alone never implies a connection.
- **Destination stay** — a break before a later flight that is not treated as a
  transfer.
- **Surface transfer** — a user-confirmed move between different airports.
  Blipbird does not infer its route or travel time.
- **Connection window** — gate arrival (`IN`) to onward gate departure (`OUT`).
  It is factual arithmetic, not a guarantee that a connection is feasible.
- **MCT** — a filed minimum connection time matched from a licensed current
  rules source. The SSIM manual describes exchange semantics but is not itself
  the rules dataset.
- **Self-transfer** — flights whose traveler may need separate check-in,
  baggage collection/recheck, or other arrival/departure procedures. Blipbird
  asks about booking and baggage rather than inferring protection.
- **Ribbon** — the §9.4 horizontal strip showing daylight/weather along the
  whole projected flight.
- **DaylightEngine** — the pure offline component that computes solar
  elevation, light bands, and cabin-visible crossings for the ribbon/map.
- **ProjectedRouteProfile** — the time/altitude projection built before
  rendering the ribbon or fetching en-route weather.
- **JetlagEngine** — the pure offline component behind the §9.5 body-clock
  card: signed time-zone shift (exact) plus a hedged adaptation rule of thumb.
- **Phase advance / phase delay** — moving the body clock *earlier* (advance,
  the harder direction, ~1 h/day) or *later* (delay, ~1.5 h/day). Eastward
  travel usually needs an advance, westward a delay — but past about nine
  hours east, delaying the other way round the 24-hour clock is quicker.
- **CBTmin** — core-body-temperature minimum, roughly two hours before
  habitual wake. Bright light after it advances the clock, before it delays;
  it is the anchor for the card's seek/avoid light windows.
- **Heartbeat** — the single shared `Flow<Instant>` that drives all countdowns
  (`PLAN.md` §6).
- **CrashRouter** — the local, redacted uncaught-exception sink (`PLAN.md` §17).
