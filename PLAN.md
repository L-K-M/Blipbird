# Blipbird — Design & Implementation Plan

> **Blipbird** is an open-source Android flight tracker. Enter one or more flight numbers
> (`CA861`, `CCA861`, or a saved name like *"Mom's flight home"*) and optionally a date;
> Blipbird shows a beautiful, glanceable list of your flights, a rich detail view with a
> live-updating map, and sends notifications for the moments that matter — boarding,
> departure, delays, gate changes, landing.
>
> This plan is the output of a structured research phase (July 2026): five parallel research
> tracks (flight-status APIs, live-position APIs, Android stack, aviation metadata/assets,
> competitor UX), each followed by an adversarial fact-check of its load-bearing claims
> against current official sources. Verified facts below are cited inline.

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
   No accounts, no analytics. AGPL-compatible open-source with reproducible builds and an
   F-Droid-friendly dependency set (no Google Play Services required for core features).
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
  validates demand — but none is open-source, privacy-first, and themable.

**Positioning:** the open-source, ad-free, design-obsessed flight-day app for Android, with
platform-native superpowers iOS apps can't copy there (Android 16 Live Updates, Glance
widgets, Material You dynamic color).

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
| Map | **MapLibre Native** via `org.maplibre.compose:maplibre-compose` | 0.13.x / native 11.x | Open-source, **no API key, no billing**, F-Droid-friendly. Compose wrapper is pre-1.0 → pin the version; classic `MapView` in `AndroidView` is the proven fallback |
| Map tiles | **OpenFreeMap** (primary), MapTiler/Protomaps (config fallback) | — | OpenFreeMap: no key, no registration, no request limits, commercial use allowed (verified on openfreemap.org); donation-funded/no SLA → keep the tile-source URL remotely configurable |
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

**Key architectural consequence — BYO keys:** an open-source repo must not ship embedded
API keys, and AeroAPI's Personal tier is licensed per-person anyway. Onboarding therefore
includes a friendly "connect a data source" flow where the user pastes their own free
RapidAPI (AeroDataBox) and/or FlightAware key, stored in Android Keystore-encrypted
DataStore. The app works without any status key in **map-only mode** (positions + routes
from the free ADS-B plane, no gates/schedule) so first-run isn't a brick wall.

### 4.2 Live aircraft positions — `PositionProvider`

The "readsb v2" aggregator family all expose the **identical ADSBExchange-v2 JSON schema**
(`{"ac":[{hex, flight, r, t, lat, lon, alt_baro, gs, track, seen_pos, …}]}`), so one DTO +
one client covers three interchangeable providers with failover — verified by live tests
(same aircraft returned by all three with sub-second data freshness):

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
We refresh it every 2–5 min and append our own polled points in between. (Marked
"experimental" by OpenSky → wrap in try/fallback to client-side accumulation.)

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

### 4.4 Airport weather — `WeatherProvider`

**aviationweather.gov data API** (NOAA): free, **no API key**, worldwide METAR + TAF as
JSON — verified, incl. rate limits (100 req/min max, ~1 req/min per endpoint sustained,
custom User-Agent expected). Blipbird decodes METAR into plain language ("Broken clouds at
2,500 ft, wind 12 kt gusting 22") with the raw string one tap away for avgeeks, and shows
arrival-airport TAF as "expected weather at landing."

### 4.5 Degradation matrix

| Situation | Behavior |
|---|---|
| No status API key configured | Map-only mode: positions, route (adsbdb), airline, aircraft, weather. Status card shows a friendly "connect a data source" CTA |
| Status API has no gate | "Gate —" placeholder; notification for gate only fires when a gate exists |
| Flight not yet airborne | Countdown UI; position section shows scheduled route arc |
| Position lookup empty in-flight (oceanic gap) | "Last seen 24 min ago" + estimated ghost marker; map keeps flown track |
| Callsign ≠ flight number (e.g. BA545 flies as BAW5GU) | Fall back to registration → `/v2/reg/{reg}`, then poll by hex (see §5) |
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
  ├─ 3. Status lookup: AeroDataBox accepts either form directly (verified);
  │     AeroAPI prefers ICAO → feed it the ICAO form.
  │     Resolve codeshares to the OPERATING flight; display
  │     "CA861 · operated by …" when the user tracked a marketing number.
  │
  ├─ 4. Position lookup: try /v2/callsign/{ICAO form}.
  │     Empty? → get registration from status payload → /v2/reg/{reg}.
  │     Found either way → cache the icao24 hex; poll /v2/icao/{hex}
  │     thereafter (the stable key for the rest of the flight).
  │
  └─ 5. Sanity check: adsbdb/hexdb route for the callsign must match the
        user's origin/destination, so we never track a same-callsign stranger.
```

Aliases are first-class: a saved name maps to (flight designator, optional recurring
date rule, notification profile). No competitor does named recurring flights well.

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
├───────────────────────────── Data ───────────────────────────────┤
│  FlightRepository (single source of truth = Room; network→db)     │
│  ├─ FlightStatusProvider    ←  AeroDataBoxProvider | AeroApiProv. │
│  ├─ PositionProvider        ←  AdsbLolProvider | AirplanesLive…   │
│  ├─ TrackProvider           ←  OpenSkyTracks | ClientAccumulated  │
│  ├─ MetadataProvider        ←  bundled Room asset + adsbdb/hexdb  │
│  ├─ WeatherProvider         ←  AviationWeatherGov                 │
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
TrackedFlight(id, designatorIata, designatorIcao, date?, alias?,
              notificationProfileId, createdAt, archived)

FlightSnapshot(trackedFlightId, fetchedAt, provider,
               status,                    // enum incl. Unknown
               depAirport, arrAirport,    // FK → Airport
               schedDep/estDep/actDep, schedArr/estArr/actArr,   // Instants, out/off/on/in where available
               depTerminal?, depGate?, depCheckInDesk?,
               arrTerminal?, arrGate?, baggageBelt?,
               aircraftType?, registration?, icao24Hex?,
               operatingDesignator?, codeshareOf?,
               rawProviderPayload)        // for debugging/forward-compat

PositionFix(trackedFlightId, at, lat, lon, altitudeFt?, groundSpeedKt?,
            trackDeg?, verticalRateFpm?, seenPosAgeSec, source)

TrackPolyline(trackedFlightId, points: encoded, source, refreshedAt)

Airport(icao, iata, name, city, country, lat, lon, tz)          // bundled
Airline(icao, iata, name, alliance?, callsign?)                 // bundled + runtime-enriched
AircraftTypeName(icaoType, marketingName)                       // bundled

NotificationProfile(id, perEventToggles: Map<EventType, Bool>, quietHours?)
FlightLogEntry(...)                                             // v2: passport/stats
```

`FlightSnapshot` history is retained per flight (pruned after landing + N days) — this
both powers the timeline's "estimate superseded" strikethroughs and, long-term, an
on-device route punctuality stat (Flighty's "Arrival Forecast" analog, purely local).

---

## 8. Refresh engine & quota budget

Free tiers are tight: **AeroDataBox ≈ 300 status lookups/month; AeroAPI ≈ 1,000/month**
(both verified). Positions are effectively unmetered at polite rates. So: **poll positions
generously, poll status stingily, on an adaptive schedule** driven by time-to-departure:

| Phase | Status fetch cadence | Position cadence |
|---|---|---|
| > 48 h out | on app open / manual only | — |
| 48 h → 24 h | every 6 h (WorkManager periodic) | — |
| 24 h → 3 h | every 2 h | — |
| 3 h → boarding | every 30 min; one-time delayed workers anchor check-in/boarding alerts | — |
| Gate-critical window (T−75 → T+30 min) | every 15 min (WorkManager floor) | 30–60 s if map open |
| En route | hourly (ETA drift) | 8–10 s foreground map · 30–60 s list · 0 background |
| Approach/landed | every 15 min until gate + baggage resolved, then stop | — |

Budget math: a typical tracked flight consumes ~15–25 status lookups end-to-end → the free
AeroDataBox tier alone comfortably covers ~10–15 tracked flights/month; users with both
keys get failover headroom. A local quota ledger tracks calls per provider per month,
drives the adaptive backoff, and surfaces usage in Settings so the "reduced freshness"
banner never surprises anyone.

ETag/If-Modified-Since via OkHttp cache wherever providers support it; pull-to-refresh
always bypasses cache but is debounced (min 30 s between forced status refreshes per flight).

---

## 9. UX design

### 9.1 List view — the departure board

One airport-board line per flight (Flighty's proven formula, translated to Material 3):

```
┌────────────────────────────────────────────────────────┐
│ ◉CA  CA861 · Mom's flight home            ON TIME ●    │
│ PEK ✈ GVA          Boards 14:10 · Gate C27             │
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
- Sorted by next-event time. Swipe to archive; long-press to edit alias/notifications.
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
4. **Inbound aircraft** *(v2, Flighty's most-praised anxiety-killer)*: "Your plane is
   arriving from Shanghai as CA1858, lands 12:40" — derived from the registration's previous
   leg; late-inbound warning feeds the delay heads-up.
5. **Weather**: decoded METAR now at both airports; arrival TAF as "expected at landing."
6. **Airport health chip** *(v2)*: "PEK: departures averaging +40 min right now."
7. **About the airline**: name, alliance, radio callsign ("AIR CHINA"), contact links.
8. **Share / Pickup mode**: read-only big-type card (ETA · terminal · progress) exportable
   as image/link for whoever's picking you up.

### 9.3 Disruption semantics (designed against Flighty's documented failure)

| Event | Treatment |
|---|---|
| Delay | Amber; old time struck through + new time + delta ("+45 min"); reason when inferable (late inbound) |
| Gate change | "Gate C27 → **D14**" notification + field highlighted in UI |
| Cancellation | Red full banner — **only** when the provider status is truly *cancelled* |
| Schedule change / renumbering | Calm blue informational treatment — explicitly never the red banner |
| Diversion | Banner names the new airport; map re-centers; timeline re-anchors |

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

Launch themes:

| Theme | Vibe |
|---|---|
| **Daylight** (default) | Clean Material 3; **Dynamic** variant uses Material You wallpaper color (Android 12+) |
| **Cockpit** | Near-black AMOLED, avionics green/amber accents, EFIS-style progress arc |
| **Solari** | Retro split-flap departure board: yellow-on-black mono type, values flip with a split-flap animation on change |
| **Skyfade** | Pastel dawn/dusk gradients that shift with local time at the departure airport |
| **High Contrast** | WCAG-AAA, large type, maximal legibility |

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
  6. *(Delighter)* **Day/night terminator** overlay (solar-position math ports trivially
     to Kotlin) + "sunrise over Greenland ~03:40 UTC" en-route callout.
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

One **notification channel per event class** so Android-level muting works naturally;
quiet-hours setting suppresses non-critical classes (never cancellation/gate-change).

### 12.2 Scheduling mechanics (2026 policy-verified)

- **Baseline (no special permissions):** WorkManager one-time delayed workers anchored to
  the next known event (e.g. T−45 min before boarding: fetch fresh status → post
  notification → schedule the next anchor). Periodic refresh rides the §8 cadence. Inexact
  timing (±minutes) is fine for check-in/boarding-window alerts.
- **Precision upgrade (opt-in):** `SCHEDULE_EXACT_ALARM` is denied by default on
  Android 14+ but **may be requested by any app via the special-access screen** — our
  fact-check confirmed Play policy itself directs non-alarm/calendar apps to it (it is
  `USE_EXACT_ALARM` that's restricted to alarm/calendar apps and would fail review).
  Settings offers "Precise alerts" which deep-links to the grant
  (`ACTION_REQUEST_SCHEDULE_EXACT_ALARM`, checked via `canScheduleExactAlarms()`), used
  only for time-critical alerts (boarding call, landing-soon). Degrades silently to
  WorkManager when not granted.
- **`POST_NOTIFICATIONS`** runtime permission (API 33+) requested in context — at the
  moment the user tracks their first flight, with a rationale screen.
- Event *detection* (gate change, delay) happens in refresh workers by diffing the new
  `FlightSnapshot` against the previous one; `NotificationPlanner` decides emissions —
  pure, unit-tested, and immune to duplicate-notification bugs via emitted-event ledger.

---

## 13. Platform surfaces: Live Updates & widgets

The Android-native answer to iOS Live Activities (verified current):

- **Android 16 `Notification.ProgressStyle` Live Update**: flight-phase progress bar with
  colored **segments** (boarding / taxi / cruise / descent) and milestone **points**
  (takeoff, landing), `setProgressTrackerIcon(plane)` as the moving tracker — surfacing as
  status-bar chip, lock screen, and AOD (chip/AOD surfaces arrived in QPR1; OEM eligibility
  caveats apply). Google Wallet already uses this pattern for flights — users know it.
- **Pre-API-36 fallback:** ongoing notification with standard progress + big-text state.
- **Glance widgets:** "Next flight" (countdown · gate · status) and "In flight"
  (progress · ETA), matching lock-screen-glance value.
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
`FlightStatusProvider` with AeroDataBox + AeroAPI implementations, BYO-key onboarding,
Room snapshot pipeline, list view with real status/countdown/progress, detail view hero +
key-facts grid + event timeline, pull-to-refresh, adaptive refresh engine v1, "last
updated" stamps, disruption semantics.
**Exit:** track a real flight end-to-end on status alone; unit tests for phase machine,
parser, planner; provider failover proven with fault injection.

### M2 — Live map (week 6–8)
Position provider chain (adsb.lol → airplanes.live → adsb.fi), identity→hex resolution,
OpenSky track polyline, MapLibre screen (flown/remaining path, interpolated rotated marker,
staleness ghost), detail-hero map snippet, foreground polling loop with lifecycle-aware
start/stop, OpenFreeMap themed styles.
**Exit:** watch a live flight cross the map smoothly; airplane-mode replay renders cached
track.

### M3 — Notifications (week 9–10)
Channels, `POST_NOTIFICATIONS` flow, WorkManager anchors, snapshot-diff event detection,
notification planner + ledger, per-flight profiles, optional exact-alarm upgrade path,
METAR/TAF weather cards.
**Exit:** full notification lifecycle observed on a real flight (boarding → landed) with
airplane-mode resilience.

### M4 — Polish & release (week 11–13)
Solari/Skyfade/High-Contrast themes + split-flap animation + haptics + themed
pull-to-refresh, accessibility audit (TalkBack, contrast, touch targets), quota ledger UI,
About/attribution screen, Play listing + F-Droid metadata, baseline profiles.
**Exit:** public beta.

### v2 (post-launch)
Android 16 ProgressStyle Live Updates + Glance widgets · inbound-aircraft section + late
warning · offline pre-computed in-flight mode · flight log + Passport stats (flights, km,
hours, airports, airlines, aircraft-type badges, shareable year card — all on-device) ·
airport-health chip · pickup/share mode · multi-flight trip grouping.

### Delighters (ongoing)
Day/night terminator + sunrise callouts · landing confetti · route on-time forecast from
locally accumulated snapshots · AR "point at the sky" long-shot.

---

## 15. Testing strategy

- **Unit (JVM, the bulk):** designator parser (property-based: round-trip IATA↔ICAO,
  ambiguity cases), `FlightPhaseMachine` transition table, `NotificationPlanner`
  (given-snapshot-diff-expect-events, dedup ledger), quota ledger, adaptive scheduler,
  METAR decoder, great-circle/terminator math.
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

- **App license:** Apache-2.0 or MIT for our code (final call at M0), compatible with every
  dependency chosen. Repo currently carries a LICENSE file — verify and align.
- **Attribution screen (required by our data diet):** OurAirports (PD, courtesy credit) ·
  mwgg/Airports (MIT) · OpenTravelData (CC-BY — credit + license link) · Wikipedia aircraft
  types (CC BY-SA) · adsb.lol (ODbL) · airplanes.live / adsb.fi (courtesy + their
  non-commercial terms) · OpenSky Network · adsbdb (+ its credited route-data owners) ·
  hexdb.io · aviationweather.gov · OpenFreeMap "© OpenMapTiles © OpenStreetMap" (MapLibre
  renders map attribution automatically) · trademark notice for airline logos.
- **License red lines (verified in research):** never bundle or re-export adsbdb/hexdb route
  databases; never ship scraped logo packs; respect AeroAPI Personal = personal use → BYO
  key by design; contact adsb.lol before production launch.
- **Privacy:** no accounts, no analytics, no server. API keys in Keystore-encrypted
  DataStore. Flight history stays on device; export/erase built in from day one. The only
  network calls are to the data providers the user can see listed.

---

## 17. Risks & mitigations

| Risk | Likelihood | Mitigation |
|---|---|---|
| A free API dies or adds auth (see: Amadeus portal shutdown, ADSBExchange free tier, ADSB One archive — all happened recently) | **High, eventually** | Everything behind provider interfaces with failover chains; remote-config JSON (fetched from the repo) can reorder/disable providers without an app update; nightly live smoke tests |
| Free-tier quota exhaustion for heavy users | Medium | Adaptive cadence + quota ledger + visible usage + cheap upgrade path (user's own $5 tiers) |
| Gate data missing/wrong for many airports | Certain, sometimes | Best-effort UI ("Gate —"), never fabricate; notifications conditional on data existing |
| Callsign ≠ flight number breaks position lookup | Medium (mostly EU carriers) | Registration→hex fallback chain (§5); verified route sanity check |
| maplibre-compose pre-1.0 API churn | Medium | Pin version; `AndroidView` fallback is mature and feature-equivalent |
| OpenFreeMap outage (no SLA) | Low/Medium | Tile-source failover to MapTiler free key / Protomaps; map degrades to route-line-on-blank gracefully |
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
| Position fallback | `GET api.airplanes.live/v2/…` · `GET opendata.adsb.fi/api/v2/…` | none | 1 req/s each |
| Flown track | `GET opensky-network.org/api/tracks/all?icao24={hex}&time=0` | anon (400 credits/day) or OAuth2 | 1 credit/call |
| Callsign→route/airline | `GET api.adsbdb.com/v0/callsign/{cs}` | none | unpublished; cache |
| Hex/route fallback | `GET hexdb.io/api/v1/route/icao/{cs}` | none | 1,000 / 5 min |
| METAR/TAF | `GET aviationweather.gov/api/data/metar?ids={icao}&format=json` | none (custom UA) | 100/min hard, ~1/min sustained |
| Map tiles | `https://tiles.openfreemap.org/styles/{liberty|dark|positron}` | none | unlimited (no SLA) |
| Airline logo (optional) | `https://pics.avs.io/{w}/{h}/{IATA}.png` | none | unofficial; monogram fallback |

## Appendix B: research provenance

This plan was synthesized from five research reports produced by parallel research agents
(July 22, 2026), each report's key claims adversarially fact-checked by an independent
verifier against official sources. 19 of 20 claims were confirmed; 1 was refuted and the
plan corrected accordingly (exact-alarm policy: `SCHEDULE_EXACT_ALARM` is user-grantable
for flight trackers; only `USE_EXACT_ALARM` is restricted to alarm/calendar apps).
Load-bearing verified facts: AeroDataBox free tier & endpoint semantics; AeroAPI Personal
pricing/window/license; Amadeus Self-Service shutdown (July 17, 2026); FR24 API scope;
adsb.lol ODbL & endpoints; airplanes.live/adsb.fi limits; OpenSky OAuth2 + credits +
anonymous `/tracks/all`; ADSBExchange free-tier removal; ADSB One archival; Play target-SDK
36 deadline; OpenFreeMap terms; material3 1.4/1.5-alpha status; OurAirports PD + no-tz;
mwgg/Airports MIT + IANA tz; OPTD CC-BY; adsbdb route-data restriction; hexdb limits;
aviationweather.gov API terms; Android 16 ProgressStyle; Flighty/App-in-the-Air market facts.
