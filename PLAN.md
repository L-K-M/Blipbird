# Blipbird — Design & Implementation Plan

> **Blipbird** is an open-source Android flight tracker. Enter one or more flight numbers
> (`CA861`, `CCA861`, or a saved name like *"Mom's flight home"*) and optionally a date;
> Blipbird shows a beautiful, glanceable list of your flights, a rich detail view with a
> live-updating map and a **flight ribbon** visualizing daylight/darkness and weather along
> the whole route, and sends notifications for the moments that matter — boarding,
> departure, delays, gate changes, landing.
>
> This plan is the output of a structured research phase (July 2026): seven research
> tracks (flight-status APIs, live-position APIs, Android stack, aviation metadata/assets,
> competitor UX, en-route weather sources, day/night solar math), each followed by an
> adversarial fact-check of its load-bearing claims against current official sources.
> Verified facts below are cited inline.

---

## Table of contents

1. [Product vision](#1-product-vision)
2. [Market context](#2-market-context)
3. [Tech stack](#3-tech-stack)
4. [Data sources & API strategy](#4-data-sources--api-strategy)
5. [Flight identity resolution](#5-flight-identity-resolution)
6. [Architecture](#6-architecture)
7. [Data model](#7-data-model)
8. [Refresh engine & quota budget](#8-refresh-engine--quota-budget)
9. [UX design](#9-ux-design)
10. [Theming system](#10-theming-system)
11. [Live map](#11-live-map)
12. [Notifications](#12-notifications)
13. [Platform surfaces: Live Updates & widgets](#13-platform-surfaces-live-updates--widgets)
14. [Feature roadmap & milestones](#14-feature-roadmap--milestones)
15. [Testing strategy](#15-testing-strategy)
16. [Licensing, attribution & privacy](#16-licensing-attribution--privacy)
17. [Risks & mitigations](#17-risks--mitigations)
18. [Appendix A: API quick reference](#appendix-a-api-quick-reference)
19. [Appendix B: research provenance](#appendix-b-research-provenance)

---

## 1. Product vision

**The calm, beautiful flight day companion for Android.**

Blipbird is not a radar toy for watching all of aviation (Flightradar24 owns that). It is a
*my flights* app: the airport departure board, distilled to the flights you care about, that
tells you what happens next and warns you early when plans change.

Design principles:

1. **Departure-board clarity.** One line per flight; the single most relevant fact for the
   current phase of flight is the biggest thing on screen. (Airports have had 50 years to
   figure out what's important — we borrow that.)
2. **Calm by default, loud when it matters.** No ads, no feed, no engagement mechanics.
   Semantic colors and notifications escalate only for genuine disruption — and a schedule
   change is *not* a cancellation (a documented failure mode of the best-in-class iOS app
   we explicitly design against).
3. **Honest about data.** Free aviation data is imperfect: gates are best-effort, oceanic
   position coverage has gaps. Blipbird always shows *when* data was last updated, renders
   missing fields gracefully ("Gate —"), and never fakes precision.
4. **Private and open.** All user data (tracked flights, aliases, stats) stays on device.
   No accounts, no analytics. Permissively licensed open-source (Apache-2.0 recommended;
   license gate in §16) with reproducible builds and an F-Droid-friendly dependency set
   (no Google Play Services required for core features).
5. **Personal, themable, delightful.** Themes go beyond dark/light — a retro split-flap
   board, a cockpit night mode, pastel skies — with micro-interactions (flip animations,
   phase-aware haptics) that make checking your flight a small pleasure.

---

## 2. Market context

Research on the competitive landscape (July 2026) found a genuine opening:

- **Flighty** (iOS) is the universally praised design benchmark — one-line airport-board
  rows, a scheduled/estimated/actual event timeline, the "Where's my plane coming from?"
  inbound-aircraft view, explained delay predictions. It is **Apple-only**, and its official
  Android waitlist page says there are no immediate plans for an Android version.
- **App in the Air**, the main design-focused cross-platform alternative, **shut down
  permanently in October 2024**, orphaning its Android users.
- **Flightradar24 / FlightAware** are enthusiast radar apps — great for watching the sky,
  weak for the "my flight day" workflow; FR24's free tier shows aggressive ads inside the map.
- New Flighty-alikes for Android (e.g. *Aviate*, announced June 2026) are emerging, which
  validates demand. (Research confirmed these entrants exist but did not audit their
  licensing, privacy posture, or theming — Blipbird's wedge is chosen because no
  *established* player occupies it, not because the newcomers were proven to lack it.)

**Positioning:** the open-source, ad-free, design-obsessed flight-day app for Android.
Material You dynamic color ships at launch; the other Android-native superpowers iOS apps
can't copy there — Android 16 Live Updates and Glance widgets — are explicit roadmap items
(v2, with a first "next flight" widget targeted for M4).

---

## 3. Tech stack

Decisions verified against current versions and 2026 Play policy (all fact-checked):

| Concern | Choice | Version (Jul 2026) | Why |
|---|---|---|---|
| Language | Kotlin (K2) | 2.4.x | Standard; K2 default since 2.0 |
| Build | AGP + Gradle version catalogs, Kotlin DSL | AGP 9.2.x | Current stable line |
| UI | Jetpack Compose (BOM) | BOM 2026.06.01 | The 2026 default for new Android UI |
| Design system | Material 3 | material3 1.4.x | Stable line; **plan a hop to 1.5.0 "Expressive" when it goes stable** (currently 1.5.0-alpha; most Expressive APIs already graduated from experimental within the alpha line) |
| SDK levels | targetSdk/compileSdk **36**, minSdk **26** | — | Play requires API 36 for new apps/updates from Aug 31 2026 (verified); minSdk 26 reaches ~96% of devices and gives java.time + notification channels natively, no desugaring |
| Navigation | Jetpack Navigation 3 | 1.0.x | Stable since Nov 2025; built for Compose, type-safe serializable routes, adaptive list-detail scaffold |
| Architecture | MVVM + unidirectional data flow, single immutable `UiState` per screen | — | Google's guidance; MVI-lite pragmatism for an app this size |
| DI | Hilt (+ androidx.hilt `hilt-work`, `hilt-navigation-compose`) | 2.60.x / 1.4.0 | Compile-time safety; first-party WorkManager worker injection |
| Networking | Retrofit + OkHttp | 3.0.0 / 4.12+ | Boring and battle-tested; OkHttp interceptors for per-provider API keys, HTTP cache + ETag conditional GETs to stretch quotas |
| JSON | kotlinx.serialization | 1.11.x | Compile-time serializers, K2-native |
| Persistence | Room (KSP) + Preferences DataStore | 2.8.x / 1.2.x | Room = flight snapshots + bundled reference data; DataStore = theme, settings, per-flight notification prefs. (Room 3 is alpha — do not adopt yet) |
| Background | WorkManager | 2.11.x | Periodic refresh (≥15-min floor) + one-time delayed jobs for notification lead-ups |
| Images | Coil (`coil-compose`, `coil-network-okhttp`, `coil-svg`) | 3.5.x | Airline logos with shared OkHttp cache |
| Pull-to-refresh | material3 `PullToRefreshBox` | in material3 | The settled M3 API (still `@ExperimentalMaterial3Api`-annotated but stable in practice) |
| Solar math | commons-suncalc (`org.shredzone.commons:commons-suncalc`) | 3.11 | Apache-2.0, zero runtime deps, API 26+ (verified) — offline solar elevation/twilight/azimuth for the flight ribbon & map terminator (§9.4); Kastro is the Kotlin-multiplatform alternative |
| Map | **MapLibre Native** via `org.maplibre.compose:maplibre-compose` | 0.13.x / native 11.x | Open-source, **no API key, no billing**, F-Droid-friendly. Compose wrapper is pre-1.0 → pin the version; classic `MapView` in `AndroidView` is the proven fallback |
| Map tiles | **OpenFreeMap** (primary); failover: project-hosted Protomaps PMTiles, then BYO-key MapTiler | — | OpenFreeMap: no key, no registration, no request limits, commercial use allowed (verified on openfreemap.org); donation-funded/no SLA → tile-source URL remotely configurable. Fallbacks carry real constraints (verified): MapTiler free tier = per-user API key, 100k tiles/mo, non-commercial/evaluation, hard-stops when exceeded; Protomaps hosted free tier = non-commercial ≤1M tiles/mo. The keyless failover is therefore a PMTiles archive self-hosted by the project on static hosting — never a shared key |
| Widgets | Jetpack Glance | current | Home-screen "next flight" + "in-flight" widgets |

**Modularization:** start as a light split — `:app`, `:core:model`, `:core:data`,
`:core:database`, `:core:network`, `:core:designsystem`, `:feature:flightlist`,
`:feature:flightdetail`, `:feature:settings`. Google's guidance warns against
over-modularizing small apps; this split is just enough to keep provider plug-ins and the
design system honest. (Reference: Now in Android.)

**Why not KMP/Flutter:** Android-only scope for now. The chosen stack (Room, DataStore,
Coil, maplibre-compose, kotlinx.serialization) is largely KMP-ready if iOS ever happens;
Retrofit→Ktor would be the main swap.

---

## 4. Data sources & API strategy

The defining constraint: **there is no single free API that does everything.** Blipbird
composes four independent data planes, each behind a pluggable provider interface with
graceful degradation. All claims below were verified against official sources in July 2026.

### 4.1 Flight status (schedule, times, gates, delays) — `FlightStatusProvider`

| Provider | Free allowance | Gates/terminals | Lookup | Window | Notes |
|---|---|---|---|---|---|
| **AeroDataBox** (PRIMARY, via RapidAPI or API.Market) | **600 units/mo free** ≈ 300 status lookups (Tier-2 endpoint = 2 units) | ✅ terminal, gate, check-in desk, baggage belt (best-effort) | One endpoint accepts **IATA or ICAO** flight number, any case | **±365 days** even on free | Also: scheduled/revised/runway times, aircraft model + registration, `codeshareStatus`. Cheap upgrade: ~$5/mo → 6,000 units |
| **FlightAware AeroAPI** (FALLBACK, "Personal" tier) | **$5/mo usage free** ≈ 1,000 `/flights/{ident}` calls ($0.005/result-set) | ✅ `gate_origin/destination`, `terminal_*` | IATA or ICAO (ICAO recommended); `/flights/{ident}/canonical` resolves ambiguity | −10 d … +2 d | Best data quality; `scheduled/estimated/actual` × `out/off/on/in`. **License: personal/academic use only → each user brings their own free key** |
| aviationstack (optional tertiary) | 100 req/mo (too small; no historical/schedules on free) | ✅ | separate `flight_iata`/`flight_icao` params | real-time only on free | Only as an optional user-configured provider |
| ~~Amadeus Self-Service~~ | — | — | — | — | **Eliminated: portal decommissioned, keys disabled July 17 2026 (verified)** |
| ~~FR24 official API~~ | no free tier ($9/mo) | ❌ no gates/schedules/delay status | — | — | It's a positions product, not a status product |

**Key architectural consequence — BYO keys (an explicit requirements deviation):** an
open-source repo must not ship embedded API keys, and AeroAPI's Personal tier is licensed
per-person anyway. This deviates from the ideal "enter a flight number and everything just
works": **without a user-supplied key there is no schedule, countdown, gate, or
delay/boarding notification data.** We mitigate rather than hide this:

- Onboarding treats key setup as the **guided happy path**, not an optional extra: a
  step-by-step flow (~3 minutes) with deep links to the RapidAPI/FlightAware signup pages,
  paste-and-validate (an immediate test call confirms the key works), and a clear
  free-tier explanation. Skipping is possible but explicitly labeled as limited mode.
- Keys are stored via a custom DataStore `Serializer` that AES-GCM-encrypts the payload
  with a key held in AndroidKeyStore (~100 LOC + tests — there is no first-party encrypted
  DataStore, and `androidx.security-crypto` is deprecated; budgeted in M1). If the
  Keystore key is invalidated, the recovery path is a re-prompt for API keys.
- **Zero-key requirements coverage** (what works with no key at all):

| Requirement | Zero-key behavior |
|---|---|
| Time to departure | ⚠️ Only if schedule known from adsbdb route + user-entered date/time; otherwise "connect a source" CTA |
| Gate numbers | ❌ Needs a status key — honest CTA shown in place |
| Airline info | ✅ Bundled datasets + adsbdb |
| Live map | ✅ Free ADS-B plane (with the callsign caveat in §4.5) |
| Airport info, weather | ✅ Bundled data + aviationweather.gov |
| Notifications (status-driven) | ❌ Needs a status key; position-driven events (takeoff/landing detected from ADS-B) still work for airborne flights |
| Themes, pull-to-refresh, list/detail UI | ✅ Fully functional |

### 4.2 Live aircraft positions — `PositionProvider`

The "readsb v2" aggregator family all expose the **identical ADSBExchange-v2 JSON schema**
(`{"ac":[{hex, flight, r, t, lat, lon, alt_baro, gs, track, seen_pos, …}]}`), so one DTO
covers three interchangeable providers with failover — verified by live tests (same
aircraft returned by all three with sub-second data freshness). Caveat: the *response
schema* is identical but the *URL paths* are not — adsb.fi uses `/v2/registration/{reg}`
(not `/v2/reg/`) and `/v3/lat/{lat}/lon/{lon}/dist/{d}` (not `/v2/point/…`) — so the
provider abstraction carries a per-provider path map:

| Provider | Auth | Query by callsign | Rate limit | License |
|---|---|---|---|---|
| **adsb.lol** (PRIMARY) | none today ("in future" keys via feeding; contact for production use) | ✅ `/v2/callsign/{cs}` (also `/v2/icao`, `/v2/reg`, `/v2/point`) | dynamic | **ODbL — the only openly licensed source; ideal for an open-source app** |
| **airplanes.live** (fallback 1) | none | ✅ same endpoints | 1 req/s | non-commercial |
| **adsb.fi** (fallback 2) | none | ✅ same endpoints | 1 req/s | personal non-commercial, attribution |
| **OpenSky Network** (track polyline + tertiary) | OAuth2 client-credentials or anonymous | ❌ icao24 hex only | credit-based: 400/day anon, 4,000/day registered | research-oriented |
| ~~ADSBExchange~~ | — | — | — | free tier gone ($10/mo) — skip |
| ~~ADSB One~~ | — | — | — | repo archived Apr 2026 — avoid |

**OpenSky's special trick** (live-verified, works anonymously): `GET
/tracks/all?icao24={hex}&time=0` returns the **full waypoint path of the current flight** —
the cheapest way to draw the already-flown polyline without accumulating points client-side.
OpenSky is *not* free-for-unlimited though: anonymous access is a **400-credit/day budget**
at 1 credit/call. So: refresh the track at most **every 5 min, only while the map is
foregrounded**, count OpenSky calls in the quota ledger (§8), rely on client-side point
accumulation between refreshes, and offer optional OpenSky OAuth2 client credentials in the
BYO-keys flow (registered tier: 4,000 credits/day). (Endpoint marked "experimental" by
OpenSky → wrap in try/fallback to pure client-side accumulation.)

**Polling policy:** 8–10 s while the live map is foregrounded (positions are ~1 s fresh
upstream; marker interpolation makes 10 s look smooth), 30–60 s for list view, zero when
backgrounded. Jitter + exponential backoff on 429/4xx, distinct User-Agent
(`Blipbird/<version> (+repo URL)`), and we contact the adsb.lol maintainer before launch as
their ToS requests.

**Coverage honesty:** all free sources are terrestrial community ADS-B — excellent over
Europe/NA/Japan/Australia, gaps over oceans, Siberia, parts of Africa, thin inside mainland
China. The UI shows "last seen X min ago" via `seen_pos` staleness and optionally
dead-reckons along heading at ground speed with a clearly "estimated" ghost marker.

### 4.3 Reference data & enrichment — bundled + `MetadataProvider`

**Bundled in APK** (regenerated by a CI job each release; pre-processed into a Room-shipped
SQLite asset, ~1 MB total):

| Dataset | Source | License | Contents |
|---|---|---|---|
| Airports | OurAirports `airports.csv` (nightly-updated; verified public domain) filtered to IATA-coded/scheduled-service rows | Public Domain | name, city, country, IATA/ICAO, lat/lon |
| Airport timezones | mwgg/Airports `airports.json` (verified MIT, actively maintained, per-airport **IANA `tz` ID**) | MIT | joined by ICAO — OurAirports has **no** timezone field (verified) |
| Airlines | OpenTravelData `optd_airlines.csv` (verified CC-BY, updated July 2026) filtered to active carriers | CC-BY | name, IATA↔ICAO code, alliance |
| Aircraft types | Wikipedia type-designator list → small JSON | CC BY-SA 4.0 | ICAO type code → family name ("A359" → "Airbus A350-900") |

**Runtime enrichment (cached, never re-exported):**

- **adsbdb.com** (free, no key, actively maintained): `/v0/callsign/{cs}` returns both
  callsign forms (ICAO + IATA), the airline (incl. radio callsign), and full
  origin/destination airport objects — live-verified with `CCA861` → Air China,
  Beijing ZBAA → Geneva LSGG. Also `/v0/aircraft/{hex|reg}` for aircraft details + photo URL.
  **License trap (verified): its route data may not be copied into other databases —
  query live, cache transiently, never bundle.**
- **hexdb.io** (fallback; free, 1,000 req/5 min): route, hex↔reg, aircraft, airport lookups.

**Airline logos — the licensing reality (researched):** there is **no fully-licensed free
logo source**; logos are trademarks and the free CDNs grant no license. Strategy:

1. Default: **generated monogram avatars** (two-letter IATA code on a deterministic
   per-airline color derived from the code hash) — offline, theme-aware, always works.
2. Optional runtime fetch from `pics.avs.io` (live-tested) with Kiwi/Daisycon CDN fallback,
   cached on device, behind a swappable `AirlineLogoProvider`.
3. **Never** bundle scraped logo packs in the repo. About screen carries a "logos are
   trademarks of their respective owners, used for identification only" notice.

### 4.4 Weather — `WeatherProvider` (airports) + `RouteWeatherProvider` (en route)

**Airport weather — aviationweather.gov data API** (NOAA): free, **no API key**, worldwide
METAR + TAF as JSON — verified, incl. rate limits (100 req/min max, ~1 req/min per endpoint
sustained, custom User-Agent expected). Blipbird decodes METAR into plain language ("Broken
clouds at 2,500 ft, wind 12 kt gusting 22") with the raw string one tap away for avgeeks,
and shows arrival-airport TAF as "expected weather at landing."

**En-route weather — Open-Meteo (PRIMARY, verified July 2026):** free tier needs **no API
key** (non-commercial use — matches Blipbird exactly), data licensed **CC-BY 4.0**
("Weather data by Open-Meteo.com" on the attribution screen). The killer feature: **one
HTTP request carries comma-separated coordinate lists (up to 1,000 points)** — so a whole
route's 10–20 sample points, each evaluated at its *overflight hour*, is a single request.
Hourly fields per point: total/low/mid/high **cloud cover**, precipitation +
`precipitation_probability`, WMO `weather_code` (maps cleanly to condition icons),
visibility (best-effort), temperature, and **wind/temperature/cloud at 19 pressure levels**
— 250 hPa ≈ cruise altitude, so en-route headwinds and cloud at FL340 are directly
available. 16-day forecast horizon covers any sensibly tracked flight. Quota: 10,000
*weighted* calls/day (a multi-point request counts ≈ one weighted call per point ×
variable/day factors) — a route fetch costs ~15–25 weighted calls, utterly comfortable.
Escape hatch if terms ever change: the server is AGPL open-source and self-hostable.

**Fallback — MET Norway Locationforecast 2.0:** global, no key, CC-BY 4.0, ~10-day horizon
(hourly → 6-hourly). Constraints (verified): **mandatory identifying User-Agent** (default
okhttp/Dalvik UAs are banned → 403 — Blipbird sets a custom UA everywhere anyway), one
point per request, 20 req/s cap *across all installs*, aggressive caching required
(honor `Expires` / send `If-Modified-Since`). Fallback-only for exactly these reasons.

**Hazard layer (optional, later):** aviationweather.gov `isigmet` is the only *globally*
scoped hazard product (GeoJSON polygons — turbulence/convective SIGMETs) and could ship as
an opt-in map layer; G-AIRMET/PIREP/windtemp are US-only — skipped.

**Radar tiles: consciously skipped.** RainViewer's API is in official wind-down (nowcast +
satellite killed Jan 2026; the surviving past-2h tiles are personal-use-only, zoom ≤ 7, no
SLA — verified), and **no free, no-key, global radar tile source exists in mid-2026**. If
ever revisited: US-only extra via Iowa State's keyless NEXRAD XYZ tiles (verified live), or
the community LibreWXR instance once it has a track record.

### 4.5 Degradation matrix

| Situation | Behavior |
|---|---|
| No status API key configured | Map-only mode: positions, route (adsbdb), airline, aircraft, weather. Status card shows a friendly "connect a data source" CTA |
| Status API has no gate | "Gate —" placeholder; notification for gate only fires when a gate exists |
| Flight not yet airborne | Countdown UI; position section shows scheduled route arc |
| Position lookup empty in-flight (oceanic gap) | "Last seen 24 min ago" + estimated ghost marker; map keeps flown track |
| Callsign ≠ flight number (e.g. BA545 flies as BAW5GU) | Fall back to registration (from status payload) → `/v2/reg/{reg}` (adsb.fi: `/v2/registration/`), then poll by hex (see §5) |
| Callsign ≠ flight number **in zero-key mode** (no status payload → no registration) | No silent empty map: show the route arc + an honest "live position for this airline needs a connected data source" CTA. Detect the case heuristically via a bundled flag on airlines known to fly alphanumeric ATC callsigns |
| Flight beyond a provider's window (AeroAPI = −10 d…+2 d) | Fall through to the provider that covers it (AeroDataBox: ±365 d). AeroAPI-only users adding a flight >2 days out see "full status available from 2 days before departure" plus bundled/adsbdb schedule skeleton — never a bare empty state |
| Quota nearly exhausted | Adaptive cadence backs off; banner explains reduced freshness |
| All providers down | Room cache renders last-known everything + "last updated" stamp |

---

## 5. Flight identity resolution

The gnarliest hidden problem in the whole app. One physical flight has up to four names:
IATA flight number (`CA861`), ICAO flight number (`CCA861`), ATC callsign (broadcast on
ADS-B; usually equals the ICAO number but **can differ entirely** — European carriers fly
alphanumeric callsigns like `BAW5GU` for BA545), and codeshare marketing numbers.

**Resolution pipeline** (each step cached in Room):

```
user input ("CA861", "CCA861", "Mom's flight" → alias table)
  │
  ├─ 1. Parse: ^([A-Z]{3})(\d{1,4})([A-Z]?)$ → ICAO form
  │         ^([A-Z0-9]{2})(\d{1,4})([A-Z]?)$ (≥1 letter) → IATA form
  │         3-char prefixes are ambiguous (IATA-with-digit vs ICAO) → try both
  │
  ├─ 2. Normalize via bundled airline table (IATA↔ICAO), validated at runtime
  │     by adsbdb /v0/callsign/{cs} (returns both forms + airline + route)
  │
  ├─ 3. Date resolution (the "perhaps a date" rule): an explicit date is used
  │     as-is (dateLocalRole=Departure, in the DEPARTURE airport's IANA zone).
  │     Dateless input resolves to the NEXT scheduled occurrence within 7 days,
  │     evaluated in the departure airport's zone. If an instance departed
  │     within the last ~6 h AND another is upcoming (or several same-day
  │     instances exist), show a disambiguation sheet instead of guessing.
  │
  ├─ 4. Status lookup: AeroDataBox accepts either form directly (verified);
  │     AeroAPI prefers ICAO → feed it the ICAO form.
  │     Multi-leg numbers (one number flying A→B→C) return several legs →
  │     leg-selection sheet; the chosen leg index is stored on the tracked
  │     flight (tracking both legs as a grouped trip is the v2 upgrade).
  │     Resolve codeshares to the OPERATING flight; display
  │     "CA861 · operated by …" when the user tracked a marketing number.
  │
  ├─ 5. Position lookup: try /v2/callsign/{ICAO form}.
  │     Empty? → get registration from status payload → /v2/reg/{reg}
  │     (adsb.fi: /v2/registration/). Found either way → cache the icao24
  │     hex; poll /v2/icao/{hex} thereafter (the stable key for the rest
  │     of the flight).
  │
  └─ 6. Identity confidence check: compare adsbdb/hexdb route for the callsign
        against the flight's origin/destination. Match → high confidence.
        Mismatch → a WARNING that triggers the registration→hex fallback,
        NOT a hard rejection: the community route DB is date-unaware and
        wrong for irregular ops (verified caveat), and the status API's own
        registration/route is always the higher-trust signal when available.
```

Aliases are first-class **saved flights**: a `SavedFlight` entity maps a name to a flight
designator plus a notification profile (see §7); MVP scope is single upcoming occurrence
per alias (re-resolved each time via the date rule above), and *recurring* date rules
("every Friday") are v2. No competitor does named recurring flights well.

---

## 6. Architecture

```
┌────────────────────────── UI (Compose) ──────────────────────────┐
│  FlightListScreen   FlightDetailScreen   Settings/Themes  Widgets │
│        └─ ViewModel (StateFlow<UiState>, UDF, Nav3 routes)        │
├──────────────────────────── Domain ──────────────────────────────┤
│  TrackFlightUseCase · RefreshFlightUseCase · ResolveIdentity      │
│  NotificationPlanner (pure: FlightSnapshot → planned alerts)      │
│  FlightPhaseMachine (pure: snapshot → Phase + next event)         │
│  DaylightEngine (pure: route × schedule → solar elevation,        │
│                  light bands, sunrise/sunset events, terminator)  │
├───────────────────────────── Data ───────────────────────────────┤
│  FlightRepository (single source of truth = Room; network→db)     │
│  ├─ FlightStatusProvider    ←  AeroDataBoxProvider | AeroApiProv. │
│  ├─ PositionProvider        ←  AdsbLolProvider | AirplanesLive…   │
│  ├─ TrackProvider           ←  OpenSkyTracks | ClientAccumulated  │
│  ├─ MetadataProvider        ←  bundled Room asset + adsbdb/hexdb  │
│  ├─ WeatherProvider         ←  AviationWeatherGov (airport wx)    │
│  ├─ RouteWeatherProvider    ←  OpenMeteo | MetNo (en-route wx)    │
│  └─ AirlineLogoProvider     ←  Monogram | AvsIo | Kiwi            │
├─────────────────────────── Platform ─────────────────────────────┤
│  WorkManager workers (refresh, notification lead-ups)             │
│  NotificationEmitter (channels, ProgressStyle Live Updates)       │
│  Glance widgets · DataStore (settings, keys via Keystore)         │
└───────────────────────────────────────────────────────────────────┘
```

Principles:

- **Room is the single source of truth.** UI never renders a network response directly;
  refresh writes snapshots to Room, UI collects Flows. Pull-to-refresh, background workers,
  and the live-map poller all funnel through the same repository write path → offline
  rendering and "last updated" stamps come for free.
- **Every external service sits behind an interface** with an ordered failover chain and
  per-provider circuit breakers. Free APIs die; Blipbird outlives them by construction.
- **Pure decision cores.** `FlightPhaseMachine` (phase: Scheduled → CheckIn → Boarding →
  Departed → EnRoute → Approaching → Landed → ArrivedGate, plus Delayed/Cancelled/Diverted
  overlays) and `NotificationPlanner` are pure functions over snapshots — trivially unit
  testable, no Android deps.
- **All times are `Instant` + airport IANA `ZoneId`,** converted only at the display edge.
  Countdown math on `Instant` only. Never dataset UTC offsets (verified stale in older
  datasets). Device tzdata self-updates via the Time Zone Data Mainline module on
  Android 10+ (verified); unknown-zone fallback renders fixed-offset with an "approx." flag.

---

## 7. Data model

Core Room entities (abridged):

```kotlin
TrackedFlight(id, designatorIata, designatorIcao, date?, legIndex?,
              savedFlightId?, notificationProfileId, createdAt, archived)

SavedFlight(id, name,                         // "Mom's flight home" — first-class alias
            designatorIata, designatorIcao,
            recurrenceRule?,                  // v2; null in MVP
            notificationProfileId)

FlightSnapshot(trackedFlightId, fetchedAt, provider,
               status,                    // enum incl. Unknown
               depAirportIcao, arrAirportIcao,   // PLAIN code columns — resolved via
                                                 // lookup at read time, NO enforced FK
                                                 // into the read-only bundled table
               schedDep/estDep/actDep, schedArr/estArr/actArr,   // Instants, out/off/on/in where available
               depTerminal?, depGate?, depCheckInDesk?,
               arrTerminal?, arrGate?, baggageBelt?,
               aircraftType?, registration?, icao24Hex?,
               operatingDesignator?, codeshareOf?,
               rawProviderPayload)        // for debugging/forward-compat

PositionFix(trackedFlightId, at, lat, lon, altitudeFt?, groundSpeedKt?,
            trackDeg?, verticalRateFpm?, seenPosAgeSec, source)

TrackPolyline(trackedFlightId, points: encoded, source, refreshedAt)

RoutePoint(trackedFlightId, seq, overflightAt, lat, lon,   // great-circle samples
           solarElevationDeg, lightBand,     // computed offline by DaylightEngine
           cloudPct?, precipMmH?, precipProbPct?, weatherCode?,
           cruiseWindKt?, cruiseWindDirDeg?, fetchedAt?)   // Open-Meteo enrichment

// Bundled reference tables use a synthetic PK with UNIQUE indexes on icao and
// iata separately (airports with IATA but no ICAO exist); provider-returned
// airports missing from the bundle are upserted into RuntimeAirport from
// adsbdb payloads so unknown airports can never fail a snapshot write.
Airport(id, icao?, iata?, name, city, country, lat, lon, tz)    // bundled
RuntimeAirport(id, icao?, iata?, name?, city?, lat?, lon?, tz?) // runtime upserts
Airline(id, icao?, iata?, name, alliance?, callsign?)           // bundled + enriched
AircraftTypeName(icaoType, marketingName)                       // bundled

NotificationProfile(id, perEventToggles: Map<EventType, Bool>, quietHours?)
EmittedEvent(trackedFlightId, eventType, dedupKey, emittedAt)   // notification dedup
                                                                // ledger — persisted so
                                                                // dedup survives process death
QuotaLedgerEntry(provider, periodKey, unitsUsed)   // periodKey = provider billing cycle
                                                   // (RapidAPI cycles run from subscription
                                                   // date, NOT calendar months)
FlightLogEntry(...)                                             // v2: passport/stats
```

`FlightSnapshot` history is retained per flight (pruned after landing + N days) — this
both powers the timeline's "estimate superseded" strikethroughs and, long-term, an
on-device route punctuality stat (Flighty's "Arrival Forecast" analog, purely local).

---

## 8. Refresh engine & quota budget

Free tiers are tight: **AeroDataBox ≈ 300 status lookups/month; AeroAPI ≈ 1,000/month**
(both verified). ADS-B positions are effectively unmetered at polite rates (OpenSky is the
exception — credit-budgeted, see §4.2). So: **poll positions generously, poll status
stingily, on an adaptive schedule** driven by time-to-departure.

Cadence tiers are **non-overlapping, boundaries explicit, most-specific-window-wins**;
tier transitions cancel-and-re-enqueue the periodic WorkManager request:

| Window (non-overlapping) | Status fetch cadence | Position cadence |
|---|---|---|
| > 48 h out | on app open / manual only | — |
| 48 h → 24 h | every 6 h, **only on days the app was opened** | — |
| 24 h → 3 h | every 3 h | — |
| 3 h → T−75 min | every 30 min | — |
| **Gate-critical: T−75 min → T+30 min** | every 15 min (WorkManager floor; tighter with exact-alarm grant, see §12.2) | 30–60 s if map open |
| T+30 min → (landing − 45 min) | every 2 h (mid-cruise ETA drift is slow; ADS-B already yields position-derived ETA) | 8–10 s foreground map · 30–60 s list · 0 background |
| Approach (landing − 45 min) → arrival gate + baggage resolved | every 15 min; **stop as soon as arrival gate + belt are known** (baggage comes from that same fetch) | same as above until landed |

Rules that keep the budget honest:

- **Anchor workers reuse snapshots:** a notification anchor (§12.2) only fetches if the
  latest snapshot is older than ~10 min; otherwise it diffs against what refresh already
  brought in. Anchor fetches and pull-to-refresh calls **are counted in the ledger**.
- Pull-to-refresh bypasses HTTP cache but is debounced (min 30 s per flight).
- ETag/If-Modified-Since via OkHttp cache wherever providers support it.

**Budget math (recomputed from the table above):** tracked from 48 h out — 48–24 h ≈ 4
(if the app is opened both days), 24–3 h ≈ 7, 3 h→T−75 ≈ 3–4, gate-critical ≈ 7, en-route
0 (short-haul) to ~5 (12-h long-haul), approach ≈ 2–4 → **≈ 23–31 lookups per flight,
plus a handful of manual refreshes → ~25–35 total**. The free AeroDataBox tier (~300)
therefore covers **~8–10 tracked flights/month** on its own; adding a free AeroAPI
Personal key (~1,000 lookups) raises the combined ceiling to ~35–40 flights/month with
failover headroom. The quota ledger (per provider, per *billing cycle* — RapidAPI cycles
run from subscription date, not calendar months) drives adaptive backoff and surfaces
usage in Settings, so the "reduced freshness" banner never surprises anyone.

En-route weather rides the same tiers at zero marginal status-quota cost: one batched
Open-Meteo request per flight at the 24 h, 3 h, and gate-critical boundaries (plus on any
schedule change ≥ 30 min) — ~15–25 weighted Open-Meteo calls each against its separate
10,000/day allowance. Daylight bands are pure math (§9.4) and recompute freely on-device.

---

## 9. UX design

### 9.1 List view — the departure board

One airport-board line per flight (Flighty's proven formula, translated to Material 3):

```
┌────────────────────────────────────────────────────────┐
│ ◉CA  CA861 · Mom's flight home            ON TIME ●    │
│ PEK ✈ GVA          Boards ~14:10 · Gate C27            │
│ ▓▓▓▓▓▓▓▓░░░░░░░░░░░░░░  Departs in 2 h 14 m            │
└────────────────────────────────────────────────────────┘
```

- Airline monogram/logo · flight number + alias · **status word with semantic color**
  (On Time green / Delayed amber / Boarding pulse / En Route sky / Landed neutral /
  Cancelled red). Status is always **word + color**, never color alone (accessibility).
- Route as IATA codes with a plane glyph; **the one phase-relevant time**, auto-switching:
  "Departs in 2 h 14 m" → "Lands in 1 h 12 m" → "Landed 14:32 · Bag belt 5".
- Countdown granularity adapts: days → `h m` → `m s` inside the last hour.
- Thin phase progress bar; gate/terminal chip once known.
- **Tap opens the detail view.** Swipe to archive; long-press to edit alias/notifications.
- Sorted by next-event time.
- Add-flight flow accepts **batch input**: paste "CA861, LX1612" (comma/space/newline
  separated) and each number resolves through the §5 pipeline into its own row.
- `PullToRefreshBox` with a themed indicator (see §10); persistent "Updated 3 m ago" stamp.
- Empty state: friendly onboarding — add a flight, pick sample flight to demo the UI.

### 9.2 Detail view — the flight dossier

Ordered by usefulness (Flighty-verified order, plus our additions):

1. **Hero**: big countdown/ETA, status banner, origin→destination with local times +
   timezone labels ("14:10 CST · your time 08:10"), live map snippet (expands full-screen).
2. **Key facts grid**: dep terminal/gate/check-in desk, arr terminal/gate/baggage belt,
   aircraft type + registration (tap → aircraft details/photo), duration, distance.
3. **Event timeline** — the centerpiece. Rows: check-in opens → boarding → doors → pushback
   → takeoff → cruise → descent → landing → gate arrival → baggage. Three-column
   scheduled/estimated/actual presentation; superseded estimates get struck through, not
   overwritten (trust through transparency). Live row pulses.
   **Derived rows are labeled as estimates** (`~` prefix + "est." chip) because no status
   API supplies them (verified): *boarding* = scheduled departure − N min (N from a small
   airline/aircraft-size table, default 40); *check-in opens* = airline policy table or
   T−3 h heuristic; *doors/pushback* = the `out` timestamp when the provider sends it;
   *cruise/descent* = inferred from ADS-B altitude + vertical rate (data the
   PositionProvider already returns). Consistent with §1's "never fakes precision."
4. **Inbound aircraft** *(v2, Flighty's most-praised anxiety-killer)*: "Your plane is
   arriving from Shanghai as CA1858, lands 12:40" — derived from the registration's previous
   leg; late-inbound warning feeds the delay heads-up.
5. **Flight ribbon** — daylight & weather along the route (§9.4).
6. **Airport weather**: decoded METAR now at both airports; arrival TAF as "expected at
   landing."
7. **Airport health chip** *(v2)*: "PEK: departures averaging +40 min right now."
8. **About the airline**: name, alliance, radio callsign ("AIR CHINA"), contact links.
9. **Share / Pickup mode**: read-only big-type card (ETA · terminal · progress) exportable
   as image/link for whoever's picking you up.

### 9.3 Disruption semantics (designed against Flighty's documented failure)

| Event | Treatment |
|---|---|
| Delay | Amber; old time struck through + new time + delta ("+45 min"); reason when inferable (late inbound) |
| Gate change | "Gate C27 → **D14**" notification + field highlighted in UI |
| Cancellation | Red full banner — **only** when the provider status is truly *cancelled* |
| Schedule change / renumbering | Calm blue informational treatment — explicitly never the red banner |
| Diversion | Banner names the new airport; map re-centers; timeline re-anchors |

### 9.4 The flight ribbon — daylight & weather along the route

A signature Blipbird visualization no mainstream tracker offers: a horizontal strip
representing the whole flight (x = flight time), answering at a glance *"how much of my
red-eye is actually dark?"* and *"what will it look like out the window over Greenland?"*

```
 PEK ─────────────────────────────────────────────── GVA
 │ ☀ daylight  │▒ dusk ▒│   ★ night   │▒ dawn ▒│  ☀   │
 │  ☁ 80%   🌧 showers  │  ✦ clear    │ ☁ 40%  │  ⛅   │
 14:10        17:52 🌇                 05:41 🌅   06:30
```

**Daylight band (pure math, fully offline — `DaylightEngine`):**

- Sample the great-circle route (Veness slerp intermediate-point formula, MIT) at
  **1-minute flight-time steps** between estimated wheels-up and wheels-down (live ADS-B
  track positions replace the great-circle for the flown portion). At most ~1,000 samples;
  the whole computation is <10 ms — recompute freely on any schedule change.
- Compute solar elevation at each `(lat, lon, overflight Instant)` with
  **commons-suncalc 3.11** (Apache-2.0, zero dependencies, API 26+ — verified) or its
  Kotlin-native port Kastro; classify by the standard USNO angles: day (≥ −0.833°), civil
  (−6°), nautical (−12°), astronomical (−18°) twilight, night — rendered as a continuous
  gradient (warm daylight → orange dusk → deep blue → near-black), not hard bands.
- **Cruise-altitude correction (verified):** at ~11 km the horizon dips ≈ 3.1°, so
  passengers see the sun until ≈ −4° elevation — sunsets seen from the cabin run
  **~12–20 min later** than on the ground below. Sunrise/sunset *event markers* (🌅/🌇
  with times, refined to the second by bisection between samples) use the
  altitude-corrected threshold; the twilight band boundaries stay at their standard
  geometric angles, per convention. Westbound terminator-chasing flights (double sunsets,
  reversed sunrises) fall out of per-sample classification naturally.
- Delighter callout derived from the same data: *"Sunrise over Greenland at 05:41 UTC —
  left side of the aircraft"* (commons-suncalc also gives azimuth → which window to book).

**Weather band (one Open-Meteo request, §4.4):** under the daylight gradient, weather
glyphs + cloud-cover % at ~10–20 sampled waypoints, each evaluated at its *overflight
hour* — WMO `weather_code` icons, precipitation probability, and cruise-level wind
(head/tailwind arrow computed against the route bearing). Tapping a ribbon segment shows
the sample detail (position, local time, conditions, cruise wind). Airport endpoints show
METAR-now (actual) rather than forecast. All cells honor the "never fake precision" rule:
beyond the 16-day forecast horizon or on fetch failure the weather band renders as
"forecast available closer to departure" while the daylight band (pure math) always
renders.

The ribbon appears in the detail view (item 5 in §9.2) and — condensed to just the
daylight gradient — as the progress-bar background in list rows and widgets. The same
per-segment light bands color the map route polyline (§11).

---

## 10. Theming system

Themes are Blipbird's signature. Implementation (standard, verified pattern):

- `enum class AppTheme` persisted in DataStore, exposed as `Flow`, collected in
  `MainActivity` (splash `keepOnScreenCondition` until first value → no theme flicker).
- One `BlipbirdTheme(theme, darkMode)` wrapper maps each theme to `ColorScheme` pairs
  (Material Theme Builder tonal palettes so all M3 roles work) + an `ExtendedColors`
  `staticCompositionLocalOf` for app-specific roles (status colors, map polyline, timeline
  accents). Switching = instant recomposition, no Activity recreate.
- **Each theme pairs with a matching MapLibre style URL** so the map re-themes too, and
  with its own pull-to-refresh flourish and typography accents.

Theme roster (launch = first three, per §14 scoping; Solari/Skyfade follow in v2):

| Theme | Ships | Vibe |
|---|---|---|
| **Daylight** (default) | Launch | Clean Material 3; **Dynamic** variant uses Material You wallpaper color (Android 12+) |
| **Cockpit** | Launch | Near-black AMOLED, avionics green/amber accents, EFIS-style progress arc |
| **High Contrast** | Launch | WCAG-AAA, large type, maximal legibility |
| **Solari** | v2 | Retro split-flap departure board: yellow-on-black mono type, values flip with a split-flap animation on change |
| **Skyfade** | v2 | Pastel dawn/dusk gradients that shift with local time at the departure airport |

Micro-interactions (theme-aware): split-flap flip on any status/gate value change;
pull-to-refresh as a plane accelerating down a runway; distinct haptic patterns per event
class; granularity-shifting countdown; subtle contrail confetti on "Landed on time."

---

## 11. Live map

- **Renderer:** MapLibre via `maplibre-compose` (declarative sources/layers), classic
  `MapView`-in-`AndroidView` as the escape hatch if the pre-1.0 wrapper bites.
- **Layers:**
  1. Vector base style per theme (OpenFreeMap Liberty/Dark/Positron…).
  2. **Flown track**: solid `LineLayer` (rounded caps) from OpenSky `/tracks/all` +
     appended live fixes.
  3. **Remaining route**: dashed great-circle arc to destination.
  4. **Plane marker**: `SymbolLayer` icon rotated to heading; animated interpolation
     between fixes (positions every 8–10 s, 60 fps marker tween between them); tinted by
     theme; "ghost" style + "last seen X min ago" callout when `seen_pos` goes stale;
     optional dead-reckoning along track at ground speed.
  5. Origin/destination pins with gate labels.
  6. **Day/night on the map** (core, powered by `DaylightEngine` §9.4): the route
     polyline is segment-colored by light band at overflight time, sun icons mark the
     altitude-corrected sunrise/sunset points, and nested **terminator/twilight polygons**
     (iso-elevation curves at 0/−6/−12/−18°, the Leaflet.Terminator math generalized —
     `φ = atan(−cos H / tan δ)` at e=0 — emitted as GeoJSON with graded opacity) shade the
     night side. Antimeridian splitting and polar-day/night longitudes handled explicitly.
  7. *(Optional layer, later)* worldwide SIGMET hazard polygons from aviationweather.gov
     `isigmet` (§4.4).
- **Camera:** auto-follow with manual override; "recenter" FAB; detail-hero snippet is a
  non-interactive lite view into the same composable.
- Altitude/speed/track readout strip under the map (avgeek candy, hidden until data exists).

---

## 12. Notifications

### 12.1 Taxonomy (each independently toggleable, per-flight profiles)

Modeled on the canonical Flighty set, adapted:

MVP: **check-in open · delay (and further slips) · gate change · boarding call ·
departure (pushback/takeoff) · landing soon (~45 min out) · landed · arrival gate +
baggage belt · cancellation · diversion**.
v2: aircraft/registration change · inbound-plane-late early warning.
(Check-in and boarding are *derived* times per §9.2 — their notifications say "~".)

One **notification channel per event class** so Android-level muting works naturally;
quiet-hours setting suppresses non-critical classes (never cancellation/gate-change).

### 12.2 Scheduling mechanics (2026 policy-verified)

- **Baseline (no special permissions):** WorkManager one-time delayed workers anchored to
  the next known event (e.g. ~T−45 min before estimated boarding: reuse the latest
  snapshot if fresher than ~10 min, else fetch → diff → post → schedule the next anchor).
  Periodic refresh rides the §8 cadence.
  **Honest latency bound:** WorkManager guarantees no exactness — periodic work has flex
  windows and Doze/App-Standby batching defers work on exactly the stationary-phone flight
  day. Real-world delivery of *detected* disruptions (gate change, delay, cancellation) in
  baseline mode is **best-effort ~15–45 min**, and the UI's notification settings say so.
- **Precision upgrade (opt-in, recommended in onboarding):** `SCHEDULE_EXACT_ALARM` is
  denied by default on Android 14+ but **may be requested by any app via the
  special-access screen** — our fact-check confirmed Play policy itself directs
  non-alarm/calendar apps to it (it is `USE_EXACT_ALARM` that's restricted to
  alarm/calendar apps and would fail review). Settings offers "Precise alerts"
  (`ACTION_REQUEST_SCHEDULE_EXACT_ALARM`, checked via `canScheduleExactAlarms()`). When
  granted, exact alarms do two jobs: (a) fire scheduled alerts (boarding call,
  landing-soon) on time, and (b) **drive the entire gate-critical refresh loop** —
  `setExactAndAllowWhileIdle` (its ~1-per-9-min idle throttle accommodates a 15-min
  cadence) triggers an expedited `OneTimeWorkRequest` fetch-and-diff during
  T−75 → T+30, shrinking disruption-detection latency to ~15 min even in Doze.
  Degrades silently to baseline when not granted. (A user-visible opt-in "flight day"
  foreground service for the gate-critical window is a possible later addition if field
  telemetry—i.e. bug reports—shows the alarm path insufficient.)
- **Offline branch (defined behavior):** if an anchor worker's fetch fails (airplane mode,
  dead zone), it posts from the last cached snapshot with a "projected" label rather than
  skipping silently — the M3-scoped subset of the v2 offline mode (§14).
- **`POST_NOTIFICATIONS`** runtime permission (API 33+) requested in context — at the
  moment the user tracks their first flight, with a rationale screen.
- Event *detection* (gate change, delay) happens in refresh workers by diffing the new
  `FlightSnapshot` against the previous one; `NotificationPlanner` decides emissions —
  pure and unit-tested. Dedup rides the **persisted `EmittedEvent` ledger** (§7), so
  duplicate suppression survives process death.

---

## 13. Platform surfaces: Live Updates & widgets

The Android-native answer to iOS Live Activities (verified current):

- **Android 16 `Notification.ProgressStyle` Live Update**: flight-phase progress bar with
  colored **segments** (boarding / taxi / cruise / descent) and milestone **points**
  (takeoff, landing), `setProgressTrackerIcon(plane)` as the moving tracker. The
  chip/lock-screen/AOD surfaces all arrived in Android 16 **QPR1**, not the first stable
  release (verified) — pre-QPR1 devices get only the in-drawer ProgressStyle notification;
  OEM eligibility caveats apply. Google Wallet already uses this pattern for flights —
  users know it.
- **Pre-API-36 fallback:** ongoing notification with standard progress + big-text state.
- **Glance widgets:** "Next flight" (countdown · gate · status) and "In flight"
  (progress · ETA), matching lock-screen-glance value.
- **What advances progress between refreshes:** background position polling is zero (§4.2)
  and WorkManager floors at 15 min, so the widget/Live Update progress advances on
  **projected time from the cached flight record** (the same pre-computed schedule that
  powers offline mode), truth-ed up whenever a periodic/anchor worker lands a fresh
  snapshot. Respect Live Update posting-frequency limits; no foreground service needed.
- **Offline in-flight mode** (Flighty's deliberate trick, verified): before scheduled
  takeoff, pre-compute and cache the full expected flight record (times, phases, projected
  progress) so the app, widget, and Live Update keep working with zero connectivity,
  clearly labeled "projected."

---

## 14. Feature roadmap & milestones

### M0 — Skeleton (week 1–2)
Project scaffolding: modules, version catalog, CI (build + unit tests + ktlint/detekt),
Hilt graph, Nav3 shell, theme engine with Daylight + Cockpit, bundled reference-data
pipeline (CSV → SQLite asset in CI), empty list + add-flight flow with parser/normalizer.
**Exit:** add `CA861`, see it resolved (airline, route, airports) from bundled data + adsbdb.

### M1 — Status MVP (week 3–5)
`FlightStatusProvider` with **AeroDataBox only** (the provider interface isolates adding
AeroAPI later), BYO-key onboarding incl. the encrypted-DataStore key store (§4.1 — budgeted
here), Room snapshot pipeline, **alias/SavedFlight entry in the add-flight flow + display
in list/detail**, list view with real status/countdown/progress, detail view hero +
key-facts grid + event timeline (incl. derived-time rules), pull-to-refresh, adaptive
refresh engine v1, "last updated" stamps, disruption semantics.
**Exit:** track a real flight end-to-end on status alone; unit tests for phase machine,
parser, planner, derived times.

### M2 — Live map + second provider (week 6–8)
Position provider chain (adsb.lol → airplanes.live → adsb.fi, with per-provider path map),
identity→hex resolution, OpenSky track polyline, MapLibre screen (flown/remaining path,
interpolated rotated marker, staleness ghost), detail-hero map snippet, foreground polling
loop with lifecycle-aware start/stop, OpenFreeMap themed styles. **`DaylightEngine` + the
daylight half of the flight ribbon** (pure math, no new API): great-circle sampling, light
bands, sunrise/sunset markers, map terminator polygons, light-band-colored route polyline.
**AeroAPI as second status provider + failover fault-injection tests.** Start the F-Droid
inclusion RFP now (their review queue takes weeks and is outside our control).
**Exit:** watch a live flight cross the map smoothly with the terminator band visible;
ribbon shows correct dark/light segments for a known red-eye; airplane-mode replay renders
cached track; status failover proven with fault injection.

### M3 — Notifications (week 9–10)
Channels, `POST_NOTIFICATIONS` flow, anchor workers (snapshot-reuse rule), snapshot-diff
event detection, notification planner + persisted `EmittedEvent` ledger, per-flight
profiles, exact-alarm upgrade path incl. the alarm-driven gate-critical loop, the
minimal offline projection branch (anchor posts from cached snapshot labeled "projected"),
METAR/TAF weather cards, **`RouteWeatherProvider` (Open-Meteo primary / met.no fallback) +
the weather half of the flight ribbon**.
**Exit:** full notification lifecycle observed on a real tracked flight (boarding →
landed) from a *ground observer's* phone, including one induced offline interval handled
via the projection branch. (Full offline in-flight mode remains v2.)

### M4 — Polish & release (week 11–13)
Launch themes locked to **three** (Daylight incl. Dynamic, Cockpit, High Contrast), haptics
+ themed pull-to-refresh, **"Next flight" Glance widget** (cheap once the state layer
exists), accessibility audit (TalkBack, contrast, touch targets), quota ledger UI,
About/attribution screen, Play listing (F-Droid RFP already in flight since M2),
baseline profiles.
**Exit:** public beta.

### v2 (post-launch)
**Solari split-flap theme + flip-animation engine · Skyfade theme** · Android 16
ProgressStyle Live Updates + "In flight" Glance widget · inbound-aircraft section + late
warning · full offline pre-computed in-flight mode · recurring alias date rules · flight
log + Passport stats (flights, km, hours, airports, airlines, aircraft-type badges,
shareable year card — all on-device) · airport-health chip · pickup/share mode ·
multi-flight trip grouping (absorbs multi-leg tracking).

### Delighters (ongoing)
"Sunrise on the left at 05:41" window-seat callouts (azimuth already computed by
`DaylightEngine`) · landing confetti · route on-time forecast from
locally accumulated snapshots · AR "point at the sky" long-shot.

---

## 15. Testing strategy

- **Unit (JVM, the bulk):** designator parser (property-based: round-trip IATA↔ICAO,
  ambiguity cases), `FlightPhaseMachine` transition table, `NotificationPlanner`
  (given-snapshot-diff-expect-events, dedup ledger), quota ledger, adaptive scheduler,
  METAR decoder, `DaylightEngine` (property-based: band edges at exact USNO angles, polar
  day/night longitudes, antimeridian routes, westbound double-sunset flights, known
  reference flights vs SunFlight/FlightVsLight outputs), terminator polygon generation,
  Open-Meteo multi-point response mapping.
- **Provider contract tests:** recorded JSON fixtures per provider (happy path, missing
  gates, cancelled, diverted, codeshare, empty `ac[]`, 429) run against the DTO mappers;
  a nightly *live* smoke workflow (opt-in CI job) pings each free API so provider drift is
  caught before users see it.
- **Repository/integration:** Room in-memory + fake providers; failover chains under
  injected faults; snapshot-diff event emission.
- **UI:** Compose testing for list/detail states (loading, degraded, disrupted); screenshot
  tests per theme × light/dark via Paparazzi (JVM, fast, no emulator).
- **E2E happy path:** Maestro flow on CI emulator — add flight (fixture-backed via
  test-only `FakeProviders` build flavor), see list, open detail, pull-to-refresh.
- **Manual flight-day protocol:** a checklist run against a real tracked flight before each
  release (the only honest test of a flight tracker).

---

## 16. Licensing, attribution & privacy

- **App license — recommendation: Apache-2.0** (explicit patent grant; compatible with
  every dependency chosen). ⚠️ The repo's current `LICENSE` is **the Unlicense**
  (public-domain dedication, verified). If that was a deliberate choice it can stand —
  but the decision must be confirmed as a **hard M0-week-1 gate, before any external PR
  is accepted**: once outside contributions land, relicensing needs every contributor's
  consent. Either way, a `NOTICE` file will state that upstream *data-source terms*
  (non-commercial ADS-B feeds, adsbdb no-rebundling, AeroAPI personal-use) bind
  deployments regardless of how permissive the code license is.
- **Attribution screen (required by our data diet):** OurAirports (PD, courtesy credit) ·
  mwgg/Airports (MIT) · OpenTravelData (CC-BY — credit + license link + **indicate
  changes**, since we filter to active carriers) · Wikipedia aircraft types (CC BY-SA) ·
  adsb.lol (ODbL) · airplanes.live / adsb.fi (courtesy + their non-commercial terms) ·
  OpenSky Network · adsbdb (+ its credited route-data owners) · hexdb.io ·
  **airport-data.com (aircraft photo thumbnails served via adsbdb)** · aviationweather.gov
  · **"Weather data by Open-Meteo.com" (CC-BY 4.0)** · **MET Norway (CC-BY 4.0)** ·
  OpenFreeMap "© OpenMapTiles © OpenStreetMap" (MapLibre renders map attribution
  automatically) · trademark notice for airline logos. In-code attribution comments for
  the MIT-licensed Veness great-circle formulas and Leaflet.Terminator-derived math.
- **License red lines (verified in research):** never bundle or re-export adsbdb/hexdb route
  databases; never ship scraped logo packs; respect AeroAPI Personal = personal use → BYO
  key by design; contact adsb.lol before production launch.
- **Privacy:** no accounts, no analytics, no server. API keys in the Keystore-backed
  encrypted DataStore (§4.1). Flight history stays on device; export/erase built in from
  day one. Network calls go only to the data providers listed on the attribution screen
  **plus one disclosed exception: the signed provider-config JSON fetched from the
  project's repo** (§17) — listed in the same screen, integrity-checked (signature
  verified against a key baked into the app), and **opt-out** (F-Droid builds default to
  opt-in) so it cannot function as a silent kill-switch.

---

## 17. Risks & mitigations

| Risk | Likelihood | Mitigation |
|---|---|---|
| A free API dies or adds auth (see: Amadeus portal shutdown, ADSBExchange free tier, ADSB One archive — all happened recently) | **High, eventually** | Everything behind provider interfaces with failover chains; **signed, disclosed, opt-out** provider-config JSON (fetched from the repo, see §16 privacy note) can reorder/disable providers without an app update; nightly live smoke tests |
| Free-tier quota exhaustion for heavy users | Medium | Adaptive cadence + quota ledger + visible usage + cheap upgrade path (user's own $5 tiers) |
| Gate data missing/wrong for many airports | Certain, sometimes | Best-effort UI ("Gate —"), never fabricate; notifications conditional on data existing |
| Callsign ≠ flight number breaks position lookup | Medium (mostly EU carriers) | Registration→hex fallback chain (§5); verified route sanity check |
| maplibre-compose pre-1.0 API churn | Medium | Pin version; `AndroidView` fallback is mature and feature-equivalent |
| OpenFreeMap outage (no SLA) | Low/Medium | Keyless failover to a project-hosted Protomaps PMTiles archive on static hosting; MapTiler only as a per-user BYO-key option (its free tier is 100k tiles/mo, non-commercial, hard-stops — a shared key would violate both its terms and our no-embedded-keys rule); map degrades to route-line-on-blank gracefully |
| Play policy drift (target SDK, alarms, notifications) | Annual certainty | Targets verified for 2026 (SDK 36 by Aug 31); alarms strategy already the policy-preferred one; revisit each Play policy cycle |
| Flighty-alikes land on Android first (Aviate etc.) | Medium | Ship the calm-open-source-themable wedge; their existence proves the market |
| ToS ambiguity on aggregator "non-commercial" terms for an open-source app | Low | App is free/no-ads; contact maintainers pre-launch (adsb.lol asks for exactly this); document permissions received |

---

## Appendix A: API quick reference

| Purpose | Endpoint | Auth | Limit |
|---|---|---|---|
| Status by number+date | `GET aerodatabox.p.rapidapi.com/flights/number/{num}/{date}` | RapidAPI key (user's) | 600 units/mo free, 1 req/s |
| Status fallback | `GET aeroapi.flightaware.com/aeroapi/flights/{ident}` | `x-apikey` (user's) | $5/mo credit ≈ 1,000 calls, 10 res/min |
| Ident disambiguation | `GET …/flights/{ident}/canonical` | same | $0.001/call |
| Live position | `GET api.adsb.lol/v2/callsign/{cs}` · `/v2/reg/{reg}` · `/v2/icao/{hex}` | none | dynamic; be polite |
| Position fallback | `GET api.airplanes.live/v2/…` · `GET opendata.adsb.fi/api/v2/…` (adsb.fi paths differ: `/v2/registration/`, `/v3/lat/…/lon/…/dist/`) | none | 1 req/s each |
| Flown track | `GET opensky-network.org/api/tracks/all?icao24={hex}&time=0` | anon (400 credits/day) or OAuth2 | 1 credit/call |
| Callsign→route/airline | `GET api.adsbdb.com/v0/callsign/{cs}` | none | unpublished; cache |
| Hex/route fallback | `GET hexdb.io/api/v1/route/icao/{cs}` | none | 1,000 / 5 min |
| METAR/TAF | `GET aviationweather.gov/api/data/metar?ids={icao}&format=json` | none (custom UA) | 100/min hard, ~1/min sustained |
| En-route weather (multi-point) | `GET api.open-meteo.com/v1/forecast?latitude={lat1},{lat2},…&longitude={lon1},…&hourly=cloud_cover,weather_code,precipitation_probability,wind_speed_250hPa,…&timeformat=unixtime` | none (non-commercial) | 10,000 weighted calls/day; ≤1,000 points/request |
| En-route weather fallback | `GET api.met.no/weatherapi/locationforecast/2.0/compact?lat={lat}&lon={lon}` | none (**mandatory identifying UA**) | 20 req/s app-wide; cache per `Expires` |
| Map tiles | `https://tiles.openfreemap.org/styles/{liberty|dark|positron}` | none | unlimited (no SLA) |
| Airline logo (optional) | `https://pics.avs.io/{w}/{h}/{IATA}.png` | none | unofficial; monogram fallback |

## Appendix B: research provenance

This plan was synthesized from seven research reports produced by parallel research agents
(July 22, 2026), each report's key claims adversarially fact-checked by an independent
verifier against official sources; the full plan was then itself adversarially reviewed by
three independent reviewers (requirements coverage, fact consistency, engineering
feasibility — 29 findings, all addressed). Of 28 fact-checked claims, 26 were confirmed
and 2 refuted with the plan corrected accordingly: (1) exact-alarm policy —
`SCHEDULE_EXACT_ALARM` is user-grantable for flight trackers; only `USE_EXACT_ALARM` is
restricted to alarm/calendar apps; (2) the ±0.01° accuracy figure often quoted for NOAA's
short solar-position form actually belongs to a different algorithm (Michalsky) — moot
since Blipbird uses commons-suncalc, whose ~1-minute event accuracy is verified.
Load-bearing verified facts: AeroDataBox free tier & endpoint semantics; AeroAPI Personal
pricing/window/license; Amadeus Self-Service shutdown (July 17, 2026); FR24 API scope;
adsb.lol ODbL & endpoints; airplanes.live/adsb.fi limits; OpenSky OAuth2 + credits +
anonymous `/tracks/all`; ADSBExchange free-tier removal; ADSB One archival; Play target-SDK
36 deadline; OpenFreeMap terms; material3 1.4/1.5-alpha status; OurAirports PD + no-tz;
mwgg/Airports MIT + IANA tz; OPTD CC-BY; adsbdb route-data restriction; hexdb limits;
aviationweather.gov API terms; Android 16 ProgressStyle; Flighty/App-in-the-Air market
facts; Open-Meteo free tier, multi-point batching + pressure-level fields; RainViewer API
wind-down; met.no Locationforecast terms (mandatory UA, 20 req/s app-wide); ISIGMET global
vs G-AIRMET/PIREP/windtemp US-only scope; commons-suncalc 3.11 license/API/Android support;
USNO twilight angles; Leaflet.Terminator math (MIT) and its iso-elevation generalization;
cruise-altitude horizon dip ≈3.1° at 11 km → ~12–20 min sunset shift.
