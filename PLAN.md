# Blipbird — Design & Implementation Plan

> **Blipbird** is an open-source Android flight tracker. Enter one or more flight numbers
> (`CA861`, `CCA861`, or a saved name like *"Mom's flight home"*) and optionally a date;
> Blipbird shows a beautiful, glanceable list of your flights, a rich detail view with a
> live-updating map, and sends notifications for the moments that matter — boarding,
> departure, delays, gate changes, landing.
>
> This plan reflects a point-in-time research review completed July 22, 2026. Provider
> pricing, quotas, schemas, terms, and Android policy are volatile: the official source
> register is in Appendix B, and every unresolved usage or retention right is an explicit
> release gate rather than an assumption. Revalidate the register before implementation and
> before each public release.

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
18. [Cross-cutting concerns](#18-cross-cutting-concerns)
19. [Appendix A: API quick reference](#appendix-a-api-quick-reference)
20. [Appendix B: source register & open decisions](#appendix-b-source-register--open-decisions)

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
   missing fields gracefully ("Gate —"), and never fakes precision. The same honesty
   applies to the big constraint surfaced in §4.1: the *status* layer (schedule, gates,
   delays, status-driven notifications) has **no fully keyless free source**, so the
   happy path is a guided ~3-minute BYO-key setup rather than "it just works" out of the
   box — and a genuine zero-key mode (positions, route, airline, weather) is designed as a
   real first-class mode, not a broken state.
4. **Private and open.** No account, analytics SDK, or Blipbird-operated data backend.
   Blipbird stores aliases/settings locally (with user-controlled OS backup/export) and
   keeps provider-derived operational data out of backup; flight identifiers and related
   lookup parameters necessarily go directly to enabled providers, which onboarding and
   the privacy screen disclose. Permissively licensed open-source (Apache-2.0
   recommended; license gate in §16) with reproducible builds and an F-Droid-friendly
   dependency set (no Google Play Services required for core features).
5. **Personal, themable, delightful.** Themes go beyond dark/light — a retro split-flap
   board, a cockpit night mode, pastel skies — with micro-interactions (flip animations,
   phase-aware haptics) that make checking your flight a small pleasure.

---

## 2. Market context

Research checked July 22, 2026 suggests a possible opening, but this is a positioning
judgment rather than measured market demand:

- **Flighty** supports Apple platforms, not Android, and won Apple's 2023 Design Award for
  Interaction. Its official materials document inbound-aircraft tracking and explained
  delay predictions; its Android waitlist says it has no immediate Android plans.
- **App in the Air** left the Apple, Google, and Samsung stores September 19, 2024. Its
  first-party shutdown notice allowed data access/export through October 19, 2024.
- **Flightradar24 and FlightAware** both provide maps, status, and alert features. Their
  Android listings are marked "Contains ads," and FR24 sells ad removal. Whether either is
  weaker for a single traveler's flight-day workflow is a usability hypothesis to test,
  not a fact.
- Android-native companions are emerging. **Aviate** was publicly reported open for Play
  pre-registration June 1, 2026 and still described itself as prerelease software July 22.
  It claims Material You/dynamic color, widgets, inbound-aircraft tracking, no ads, and no
  behavioral tracking; those vendor claims and its software licensing were not independently
  audited. Competitor activity is not proof of quantified demand.

**Positioning:** the open-source, ad-free, design-focused flight-day app for Android. Open
source and broader custom theming are the intended differentiators; Android-native design,
dynamic color, widgets, and inbound-aircraft tracking are not unique claims. Material You
ships at launch; Live Updates are v2 and the first Glance widget is targeted for M4.

---

## 3. Tech stack

July 2026 version snapshot; pin exact versions in the catalog and recheck release notes at
M0 rather than treating this table as a permanent compatibility matrix:

| Concern | Choice | Version (Jul 2026) | Why |
|---|---|---|---|
| Language | Kotlin (K2) | 2.4.10 | Standard; explicitly pin the supported Kotlin override rather than inheriting AGP's older built-in default |
| Build | AGP + Gradle version catalogs, Kotlin DSL | AGP 9.3.0 / Gradle 9.5.0 | Current stable pair; keep plugin versions in one catalog |
| UI | Jetpack Compose (BOM) | BOM 2026.06.01 | The 2026 default for new Android UI |
| Design system | Material 3 | 1.4.0 | Stable line; plan a hop to 1.5 Expressive only when stable. Many Expressive APIs have graduated, but the 1.5 line and remaining APIs are still preview |
| SDK levels | targetSdk/compileSdk **37**, minSdk **26** | — | Android 17 / API 37 became final June 16, 2026. Play's Aug 31 floor is only API 36, but a new app should target current stable after behavior-change testing; API 37 also enforces the adaptive large-screen work already planned. minSdk 26 gives `java.time` + notification channels natively |
| Navigation | Navigation 3 + Material 3 Adaptive bridge | 1.1.4 / `adaptive-navigation3` 1.3.0-rc01 | Compose-first, type-safe routes; the adaptive bridge is still RC, so isolate/pin it and test phones, foldables, tablets, and resizable windows |
| Architecture | MVVM + unidirectional data flow, single immutable `UiState` per screen | — | Google's guidance; MVI-lite pragmatism for an app this size |
| DI | Hilt + lifecycle integration | 2.60.x / androidx.hilt 1.4.0 / lifecycle 2.11.0 | Use `hilt-work`, `hilt-lifecycle-viewmodel-compose`, and `lifecycle-viewmodel-navigation3`; avoid `hilt-navigation-compose`, which pulls Navigation 2 into a Navigation 3 app |
| Networking | Retrofit + OkHttp | 3.0.0 / 4.12.0 | Publisher baseline, pinned exactly; test before a separate OkHttp 5 upgrade. Interceptors redact keys and feed a central coordinator. HTTP caching is used only where terms permit it; network requests are assumed billable even if conditional |
| JSON | kotlinx.serialization | 1.11.x | Compile-time serializers, K2-native |
| Persistence | Room 2 (KSP) + Preferences DataStore | 2.8.4 / 1.2.x | Separate backup-safe user DB, no-backup operational DB, and generated read-only reference DB. Room 3 is stable but a breaking package/API migration; defer it deliberately for v1. Credentials use a distinct encrypted no-backup file |
| Background | WorkManager | 2.11.x | Periodic refresh (≥15-min floor) + one-time delayed jobs for notification lead-ups |
| Images | Coil (`coil-compose`, `coil-network-okhttp`, `coil-svg`) | 3.5.x | Theme assets and any later rights-cleared remote imagery; launch airline identity uses generated monograms |
| Pull-to-refresh | material3 `PullToRefreshBox` | in material3 1.4.0 | Stable API; style the indicator without wrapping the app in an unnecessary experimental opt-in |
| Map | **MapLibre Native** via `org.maplibre.compose:maplibre-compose` | 0.13.1 / transitive native 13.0.2 | Open-source, **no API key, no billing**, F-Droid-friendly. Pin the pre-1.0 wrapper and do not override its Native version independently without tests; classic `MapView` in `AndroidView` is the fallback |
| Map tiles | **OpenFreeMap** (primary); cached-tile/blank-base degradation | — | No key or request limit and commercial use is allowed, but there is no SLA and bulk prefetch is not permitted without approval. Launch fallback keeps the route, pins, and attribution over cached tiles or a neutral canvas. Self-hosted PMTiles is a later operational decision with explicit storage, update, attribution, and bandwidth budgets; never embed a shared commercial key |
| Widgets | Jetpack Glance | 1.1.1 stable | Home-screen "next flight" + "in-flight" widgets; do not take a newer RC/alpha merely for version freshness |

**Modularization:** start with `:app`, `:core:data`, `:core:designsystem`, and feature
modules for list/detail/settings. Keep provider interfaces and implementations separated by
Kotlin packages first; split `:core:model`, `:core:database`, or `:core:network` only when
build times, ownership, or a real dependency boundary justify it. Provider plug-ins do not
require nine Gradle modules on day one.

**Why not KMP/Flutter:** Android-only scope for now. The chosen stack (Room, DataStore,
Coil, maplibre-compose, kotlinx.serialization) is largely KMP-ready if iOS ever happens;
Retrofit→Ktor would be the main swap.

---

## 4. Data sources & API strategy

The defining constraint: **there is no single free API that does everything.** Blipbird
composes four independent data planes, each behind a pluggable provider interface with
graceful degradation. The figures below are a July 22, 2026 snapshot, not product
guarantees; Appendix B links the official sources and records the permissions still needed.

### 4.1 Flight status (schedule, times, gates, delays) — `FlightStatusProvider`

| Provider | Free allowance | Gates/terminals | Lookup | Window | Notes |
|---|---|---|---|---|---|
| **AeroDataBox** (primary candidate, RapidAPI) | **600 units/mo on RapidAPI Basic**; status normally costs 2 units, so at most 300 lookups. `withFlightPlan=true` can double cost. API.Market has a 7-day trial, not this monthly allowance | ✅ terminal, gate, check-in desk, baggage belt (best-effort) | One endpoint accepts **IATA or ICAO**, any case | Up to ±365 days on Basic, subject to availability | Distant-future records are schedules, not a promise of gates or live revisions. Local persistence/derived-history rights require written confirmation |
| **FlightAware AeroAPI** (fallback candidate, Personal) | **Up to $5 of usage waived monthly**, then usage is chargeable; no hard spend cap. `/flights/{ident}` is $0.005 per result set of up to 15 records | ✅ `gate_origin/destination`, `terminal_*`, baggage claim | IATA or ICAO (ICAO recommended); `/canonical` returns possible canonical identifiers, not a dated instance | explicit query range −10 d … +2 d | `scheduled/estimated/actual` × `out/off/on/in`. Personal is personal/academic only; a public BYO-key client needs written approval |
| Other providers | not assumed | varies | varies | varies | May be evaluated through the same contract, retention, coverage, and cost spike; no tertiary provider ships merely because it has a free tier |

**Key architectural consequence — BYO keys (an explicit requirements deviation):** an
open-source repo must not ship embedded API keys. A BYO key solves secret distribution, but
**does not grant Blipbird display, caching, derivative-work, or redistribution rights**.
Provider approval is therefore a release gate (§4.6), not something inferred from a free or
Personal plan. This deviates from the ideal "enter a flight number and everything just
works": without an approved source and user key there is no provider-backed schedule,
countdown, gate, or status-driven notification data. We mitigate rather than hide this:

- Onboarding treats key setup for each **release-cleared provider** as the guided happy
  path: a step-by-step flow with deep links to its signup page, paste-and-validate (noting
  that validation can consume quota), pricing/spend warnings, and a limited-mode option.
- Keys are stored via a custom DataStore `Serializer` that AES-GCM-encrypts the payload
  with a key held in AndroidKeyStore (~100 LOC + tests — there is no first-party encrypted
  DataStore, and `androidx.security-crypto` is deprecated; budgeted in M1). Use a versioned
  envelope and a fresh cryptographically random nonce for every write. The encrypted
  key file is excluded from Auto Backup because its Keystore key is device-bound. If the
  key is invalidated or ciphertext is restored without it, recovery is a clean reset and
  re-prompt, never an onboarding crash.
- **Zero-key requirements coverage** (what works with no key at all):

| Requirement | Zero-key behavior |
|---|---|
| Time to departure | ⚠️ Only from a user-entered scheduled time; adsbdb route data does not provide a trustworthy schedule. User-supplied times are labeled as such |
| Gate numbers | ❌ Needs a status key — honest CTA shown in place |
| Airline info | ✅ Bundled datasets + adsbdb |
| Live map | ✅ For an active aircraft only when callsign/route identity clears the confidence checks in §5 |
| Airport info, weather | ✅ Bundled data + aviationweather.gov |
| Notifications | ❌ Status changes need an approved status source. ADS-B takeoff/landing detection works only while a foreground screen is polling; background position polling is deliberately off |
| Themes, pull-to-refresh, list/detail UI | ✅ Fully functional |

### 4.2 Live aircraft positions — `PositionProvider`

The readsb aggregator family exposes a **compatible ADSBExchange-v2-style envelope**
(`{"ac":[{hex, flight, r, t, lat, lon, alt_baro, gs, track, seen_pos, …}]}`), not a
guaranteed identical contract. One tolerant DTO can cover the common fields, while provider
fixtures must include missing fields, `alt_baro: "ground"`, padded callsigns, non-aircraft
hex prefixes, and absent positions. URL paths also differ: adsb.fi uses `/v2/registration/{reg}`
(not `/v2/reg/`) and `/v3/lat/{lat}/lon/{lon}/dist/{d}` (not `/v2/point/…`) — so the
provider abstraction carries a per-provider path map:

| Provider | Auth | Query by callsign | Rate limit | License |
|---|---|---|---|---|
| **adsb.lol** (primary candidate) | none today; feeder-issued keys are planned | ✅ `/v2/callsign/{cs}` (also `/v2/icao`, `/v2/reg`, `/v2/point`) | unpublished/dynamic | **ODbL**, including attribution and database share-alike obligations; no SLA |
| **airplanes.live** (disabled pending permission) | none today; feeder access may be required later | ✅ `/v2/callsign`, `/v2/reg`, `/v2/hex`, `/v2/point` | 1 req/s | non-commercial/no SLA; generic terms conflict with systematic polling, so obtain written permission |
| **adsb.fi** (disabled pending permission) | none | ✅ path-mapped endpoints | 1 req/s; invalid requests count | personal non-commercial + mandatory attribution; distributed-app use needs written confirmation |
| **OpenSky Network** (optional track import, disabled pending agreement) | OAuth2 client-credentials or anonymous | ❌ icao24 hex only; separate DTO | 400 credits/day/IP anonymous, 4,000/day standard, in independent endpoint buckets | terms require an agreement for operational REST integration |

**Flown track:** client-side accumulation of validated foreground position fixes is the
primary implementation. OpenSky's experimental `GET
/tracks/all?icao24={hex}&time=0` can return a sampled trajectory available so far when it
recognizes an ongoing flight; it is neither complete nor a future route. A live track costs
**4 credits**, so anonymous access permits 100 calls/day, not 400. Keep this provider off by
default until an operational-use agreement exists; if enabled, import at most on map open
and then accumulate locally rather than polling it every five minutes.

**Polling policy:** 8–10 s while the live map is foregrounded when the provider-approved
limit allows it, 30–60 s for a visible list, zero when backgrounded. A process-wide
coordinator enforces each provider's limit across
screens and coalesces duplicate requests. Retry network failures, 429 (respecting
`Retry-After`), and transient 5xx responses with jittered backoff; do **not** retry generic
4xx responses. A 401/403 disables that provider and prompts for credentials, while an
expected empty result is negatively cached briefly. Send a distinct User-Agent and obtain
written production guidance from every provider whose published terms are unclear.

**Coverage honesty:** all free sources are terrestrial community ADS-B — excellent over
Europe/NA/Japan/Australia, gaps over oceans, Siberia, parts of Africa, thin inside mainland
China. The UI shows "last seen X min ago" via `seen_pos` staleness and optionally
dead-reckons along heading at ground speed with a clearly "estimated" ghost marker.

### 4.3 Reference data & enrichment — bundled + `MetadataProvider`

**Bundled in APK:** a `data-sources.lock` records source revision/date, URL, checksum, and
license. A scheduled update job proposes reviewed lockfile/generated-asset changes; release
builds consume pinned inputs and never fetch mutable "latest" data. The result is a separate
read-only SQLite asset, with compressed and installed size measured rather than assumed:

| Dataset | Source | License | Contents |
|---|---|---|---|
| Airports | OurAirports `airports.csv` (nightly-updated; verified public domain) filtered to IATA-coded/scheduled-service rows | Public Domain | name, city, country, IATA/ICAO, lat/lon |
| Airport timezones | Build-time spatial join of airport coordinates against a pinned `timezone-boundary-builder` release | ODbL 1.0 | IANA zone ID; avoids mwgg/Airports' unclear upstream timezone-data provenance |
| Airlines | OpenTravelData `optd_airlines.csv` (verified CC-BY, updated July 2026) filtered to active carriers | CC-BY | name, IATA↔ICAO code, alliance |
| Aircraft types | Pinned Wikipedia type-designator revision → generated JSON | CC BY-SA 4.0 | ICAO type code → family name; generated asset remains separately licensed with revision, attribution, and transformation notice |

**Runtime enrichment (ephemeral unless written terms explicitly allow more):**

- **adsbdb.com** (free, no key, actively maintained): `/v0/callsign/{cs}` returns both
  callsign forms (ICAO + IATA), the airline (incl. radio callsign), and full
  origin/destination airport objects — live-verified with `CCA861` → Air China,
  Beijing ZBAA → Geneva LSGG. Also `/v0/aircraft/{hex|reg}` for aircraft details.
  **License trap:** route data may not be incorporated into another database. Do not put
  route responses in Room, analytics, exports, or fixtures; keep only an in-memory response
  long enough to resolve the current view unless written permission says otherwise.
- **hexdb.io** (fallback; 1,000 req/5 min): route, hex↔reg, aircraft, airport lookups. It
  publishes no clear data reuse or persistence license, so apply the same no-persistence
  rule and do not enable it by default until terms are confirmed.

**Airline logos — the licensing reality (researched):** there is **no fully-licensed free
logo source**; logos are trademarks and the free CDNs grant no license. Strategy:

1. Launch behavior: **generated monogram avatars** (two-letter IATA code on a deterministic
   per-airline color derived from the code hash) — offline, theme-aware, always works.
2. Do not fetch from `pics.avs.io`, Kiwi, Daisycon, or scraped packs: a trademark disclaimer
   does not grant copyright, CDN, or hotlink rights. Add a remote `AirlineLogoProvider` only
   after a source grants documented display/caching rights for this distribution model.
3. Aircraft photos follow the same rule. adsbdb's software license does not grant rights to
   Airport-Data photos; omit photos until per-image display and attribution terms are met.

### 4.4 Weather (origin, destination & en-route) — `WeatherProvider`

**aviationweather.gov data API** (NOAA): free, **no API key**, worldwide METAR + TAF as
JSON. Its published ceiling is 100 requests/minute and guidance says not to consume an
endpoint more often than once/minute per thread; send a custom User-Agent and batch station
IDs. Blipbird decodes METAR into plain language ("Broken clouds at 2,500 ft, wind 12 kt
gusting 22") with observation age and the raw string one tap away, then selects the TAF
conditions valid at estimated arrival time. TAF handling resolves the prevailing initial/
`FM`/`BECMG` group **and** every overlapping `TEMPO`/`PROB` overlay, inheriting unchanged
fields where required and presenting temporary/probability semantics separately; selecting
one decoded array item would hide hazards. Airports without a reporting station or a valid
TAF show an honest unavailable state.

**En-route weather (the requirement's "weather at different points in the flight").**
Airport-only METAR/TAF covers the endpoints but not the hours in between, and flights
routinely cross weather the airports do not describe. The UI must distinguish observations,
forecasts, and advisories rather than presenting current surface weather as conditions at
cruise altitude:

| Layer | Source | Launch scope | Later |
|---|---|---|---|
| Endpoint weather | METAR now + TAF prevailing conditions and active temporary/probability overlays at estimated origin/destination time | ✅ | diversion airport when known |
| Route advisories | Normalize international `/isigmet` plus CONUS domestic `/airsigmet`, deduplicate, then intersect active polygons with a buffered great-circle guide while retaining validity time and altitude band | ✅ | map overlay and filed-route intersection when a rights-cleared route exists |
| Sampled surface context | Batched METARs at a few reporting stations near the guide | optional launch context, explicitly "surface now" | hide when it adds more confusion than value |
| Time/altitude-aware en-route forecast | A provider capable of pressure-level wind, temperature, cloud, and precipitation sampled at estimated passage time | **M0 source/terms spike; required for the M3 launch weather slice** | turbulence/icing only when the selected data product actually supports them |

AWC AIRMET/G-AIRMET and winds-aloft products are region-limited, so they must not be labeled
worldwide fallbacks. A missing advisory is not proof of safe or clear weather; the empty
state says "No applicable advisories found in available AWC data," includes freshness, and
links to the full advisory. All weather is informational and explicitly not for navigation.

### 4.5 Degradation matrix

| Situation | Behavior |
|---|---|
| No status API key configured | Limited mode: bundled airline/airport data, active high-confidence ADS-B position, weather, and a route guide. No provider schedule is implied; status card shows a "connect a data source" CTA |
| Status API has no gate | "Gate —" placeholder; notification for gate only fires when a gate exists |
| Flight not yet airborne | Countdown only when a provider or user supplied a time; map shows a great-circle guide, not a filed route |
| Position lookup empty in-flight (oceanic gap) | "Last seen 24 min ago" + estimated ghost marker; map keeps flown track |
| Callsign ≠ flight number (e.g. BA545 flies as BAW5GU) | Fall back to registration (from status payload) → `/v2/reg/{reg}` (adsb.fi: `/v2/registration/`), then poll by hex (see §5) |
| Callsign ≠ flight number **in zero-key mode** (no status payload → no registration) | Show the route guide and "live position could not be matched." Do not maintain a brittle airline heuristic or display a low-confidence aircraft as the flight |
| En-route weather fetch fails (§4.4) | Endpoint METAR/TAF remains independent; route forecast and advisory cards show their own muted unavailable/stale state rather than blanking the section |
| Flight beyond a provider's window (AeroAPI explicit range = −10 d…+2 d) | Use another configured, approved provider if available. Otherwise retain the requested designator/date and say when status lookup becomes available; do not invent a schedule skeleton from route metadata |
| Quota nearly exhausted | Adaptive cadence backs off; banner explains reduced freshness |
| All providers down | Room renders only normalized data whose retention terms allow it, with independent source/freshness stamps |

### 4.6 Provider feasibility gates (must close before M1)

Public reachability is not production permission. Archive written answers in the repository's
decision records (without credentials or confidential contract text) for these questions:

1. **Status rights:** may a freely distributed open-source Android client use per-user keys,
   display normalized results, retain snapshots locally, and derive an on-device history?
   What TTLs apply? Are 304 and error responses billable?
2. **Position rights:** obtain operational polling approval for any default provider whose
   terms are personal/non-commercial or otherwise ambiguous. Implement adsb.lol ODbL
   attribution and assess whether the accumulated local track database triggers share-alike.
3. **Asset rights:** pin every generated dataset and preserve its license; do not ship logos,
   photos, timezone mappings, or test fixtures whose provenance is unclear.
4. **Contract spike:** test at least 20 representative flights (domestic/international,
   codeshare, multi-leg, overnight/date-line, delayed/cancelled, and missing-gate cases),
   record field coverage and request cost, and verify response handling without committing
   restricted raw payloads.
5. **Go/no-go:** if a provider does not grant the required rights, keep it out of release
   builds and update the feature matrix. Do not replace legal certainty with a disclaimer.

---

## 5. Flight identity resolution

The gnarliest hidden problem in the whole app. One physical flight has up to four names:
IATA flight number (`CA861`), ICAO flight number (`CCA861`), ATC callsign (broadcast on
ADS-B; usually equals the ICAO number but **can differ entirely** — European carriers fly
alphanumeric callsigns like `BAW5GU` for BA545), and codeshare marketing numbers.

**Resolution pipeline** (persist normalized identity and the selected instance only; keep
third-party route-enrichment responses ephemeral unless their terms permit storage):

```
user input ("CA861", "CA 861", "CCA861/CA861", or saved alias)
  │
  ├─ 1. Parse and deduplicate:
  │     resolve an exact saved alias before batch tokenization; batch separators
  │     are comma/newline (not every space). Normalize case and carrier/number
  │     whitespace. Treat a slash pair as alternate designators for one flight,
  │     not two rows. Match prefixes against the bundled airline table instead
  │     of trusting regex alone: IATA is two alphanumerics (at least one letter),
  │     ICAO is three letters, followed by 1–4 digits and an optional suffix.
  │
  ├─ 2. Normalize through the bundled IATA↔ICAO airline table. adsbdb may
  │     validate a current callsign/route at runtime, but its response is not
  │     written to Room. Preserve the exact user-entered marketing designator.
  │
  ├─ 3. Resolve date in the DEPARTURE airport's IANA zone. An explicit date is
  │     passed as `dateLocalRole=Departure`. For dateless input, use a provider's
  │     bounded upcoming-result window when available. For a date-only endpoint,
  │     query departure-local today/tomorrow only when the origin is known;
  │     otherwise ask for a date first. If there is no unambiguous active,
  │     recently departed (~6 h), or next instance, ask for a date rather than
  │     scanning seven billable days or silently choosing a weekly occurrence.
  │
  ├─ 4. Select an instance/leg. Show a sheet for same-day duplicates, multi-leg
  │     routes, or conflicting candidates. Resolve codeshares to the operating
  │     flight while displaying "CA861 · operated by …". Persist a canonical
  │     local key from operating designator + origin + destination + the original
  │     scheduled OUT instant captured at selection, plus provider instance IDs;
  │     revised times must not create a new flight. Never rely on result-array index.
  │
  ├─ 5. Resolve position only inside a plausible operational window. Prefer the
  │     status payload's registration/icao24, then poll by hex. Otherwise query
  │     the ICAO callsign and require a route/time sanity match before binding;
  │     callsigns are reused and can identify the wrong aircraft. A reported
  │     aircraft/registration change invalidates the cached hex before the next fix.
  │
  └─ 6. Assign confidence and provenance. Registration/hex from the selected
        status instance is strongest; callsign + matching endpoints is next.
        A mismatch triggers another allowed source or a route-only state. Never
        render a low-confidence candidate as the live plane; community route
        databases are date-unaware and irregular operations make them stale.
```

Aliases are first-class **saved flights**: a `SavedFlight` maps a name to a designator plus
a notification profile (see §7). Adding an alias resolves and pins one selected occurrence;
reusing it later asks for a new date/occurrence. Recurring date rules ("every Friday") are
v2 and must never silently retarget an already tracked instance.

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
│  FlightRepository (UserDb | no-backup OperationalDb | ReferenceDb)│
│  PositionSession (foreground hot flow; batched/downsampled writes)│
│  ├─ FlightStatusProvider    ← release-cleared implementations     │
│  ├─ PositionProvider        ← AdsbLol | cleared optional sources  │
│  ├─ TrackProvider           ← ClientAccumulated | optional import │
│  ├─ MetadataProvider        ← bundled DB + ephemeral resolvers    │
│  ├─ WeatherProvider         ← AWC + cleared en-route forecast     │
│  └─ AirlineIdentityProvider ← generated Monogram                  │
├─────────────────────────── Platform ─────────────────────────────┤
│  WorkManager workers (refresh, notification lead-ups)             │
│  NotificationEmitter (channels, ProgressStyle Live Updates)       │
│  ProviderRequestCoordinator · Glance widgets                      │
│  DataStore (settings, keys via Keystore)                          │
└───────────────────────────────────────────────────────────────────┘
```

Principles:

- **Room is the durable source of truth, split by retention boundary.** User-authored
  tracking intent/preferences live in a backup-safe database; provider instance IDs,
  rights-cleared snapshots, emitted-event state, and tracks live in an operational database
  excluded from cloud and device-transfer backup; bundled data uses a read-only reference
  database. A foreground map's 8–10-second
  fixes live in a repository-owned `StateFlow`; writing every fix through Room would add
  disk churn and latency. Downsample/batch useful fixes for the flown track and flush on
  lifecycle stop. Network DTOs are mapped immediately and discarded.
- **Every external service sits behind an interface,** but an implementation enters a
  release build only after its terms pass §4.6. Failover is centralized, rate-limited, and
  based on error class; it never routes around a license, authentication error, or user
  spend limit. The request coordinator coalesces concurrent list/map/worker calls for the
  same flight and honors provider-wide `Retry-After`.
- **Pure decision cores.** `FlightPhaseMachine` (phase: Scheduled → CheckIn → Boarding →
  Departed → EnRoute → Approaching → Landed → ArrivedGate, plus Delayed/Cancelled/Diverted
  overlays) and `NotificationPlanner` are pure functions over snapshots — trivially unit
  testable, no Android deps.
- **Provenance is part of the model.** Every displayed field carries source, observed-at
  time, and whether it is reported, derived, projected, or user-entered. A merged snapshot
  must not make a fresh position look like a fresh gate or silently overwrite a
  higher-trust field with a lower-trust fallback.
- **All times are `Instant` + airport IANA `ZoneId`,** converted only at the display edge.
  Countdown math on `Instant` only. Never dataset UTC offsets (verified stale in older
  datasets). Device tzdata self-updates via the Time Zone Data Mainline module on
  Android 10+ (verified); unknown-zone fallback renders fixed-offset with an "approx." flag.

---

## 7. Data model

Core Room entities (abridged):

```kotlin
// UserDatabase: user-authored portable intent only. Auto Backup may include this file.
TrackedFlight(id, userDesignator, departureDateLocal?, selectedInstanceId?,
              userOriginCode?, userDestinationCode?, userScheduledOut?,
              userScheduleZoneId?, userExpectedDurationMin?,
              savedFlightId?, notificationProfileId, createdAt, archived)

SavedFlight(id, name,                         // "Mom's flight home" — first-class alias
             originalDesignator, designatorIata?, designatorIcao?,
             // original is required; normalized equivalents are optional
             recurrenceRule?,                  // v2; null in MVP
             notificationProfileId)

NotificationProfile(id, perEventToggles: Map<EventType, Bool>, quietHours?)

// OperationalDatabase: explicitly excluded from cloud and device-transfer backup.
// selectedInstanceId is a nullable logical link; restore clears/re-resolves it.
FlightInstance(id, canonicalKey,              // operating designator + airports + sched OUT
               designatorIata?, designatorIcao?, operatingDesignator,
               originCode, originCodeType, destinationCode, destinationCodeType,
               originalScheduledOut,
               providerInstanceIds)           // adjunct IDs, never the only local identity

FlightSnapshot(id, flightInstanceId, fetchedAt, statusProvider,
               status,                    // enum incl. Unknown
               depAirportCode, depCodeType, arrAirportCode, arrCodeType,
               depTerminal?, depGate?, depCheckInDesk?,
               arrTerminal?, arrGate?, baggageBelt?,
               aircraftType?, registration?, icao24Hex?,
               codeshareOf?)

FlightMilestone(snapshotId, kind,              // OUT, OFF, ON, IN
                scheduledAt?, estimatedAt?, actualAt?)

FieldProvenance(snapshotId, field, provider, observedAt,
                certainty)                     // REPORTED/DERIVED/PROJECTED/USER_ENTERED

PositionFix(flightInstanceId, at, lat, lon, altitudeFt?, groundSpeedKt?,
             trackDeg?, verticalRateFpm?, seenPosAgeSec, source)

TrackPolyline(flightInstanceId, points: encoded, source, refreshedAt)

// ReferenceDatabase: a separate read-only asset uses synthetic PKs and UNIQUE indexes on
// ICAO and IATA independently. A snapshot remains valid when a code is absent
// from this asset; allowed provider display text stays on the snapshot rather
// than copying restricted enrichment data into a new reference database.
Airport(id, icao?, iata?, name, city, country, lat, lon, tz) // bundled
Airline(id, icao?, iata?, name, alliance?, callsign?)        // bundled
AircraftTypeName(icaoType, marketingName)                    // bundled, separately licensed

EmittedEvent(flightInstanceId, eventType, eventFingerprint, emittedAt)
QuotaLedgerEntry(provider, periodStart?, resetAt?, estimatedUsage,
                 providerReportedRemaining?, reconciledAt?)
FlightLogEntry(...)                              // v2, only for retention-cleared data
```

The four milestones are explicit because six generic departure/arrival timestamps cannot
represent scheduled/estimated/actual `out/off/on/in`. No production entity stores a raw
provider payload: it bloats the database, risks key/PII leakage in diagnostics, and may
violate retention terms. Synthetic/redacted fixtures cover debugging. Snapshot history is
retained only for a provider-approved TTL; if history is not allowed, superseded estimates
exist only for the current process/session and local punctuality statistics stay disabled.

Persisted position tracks are simplified and downsampled (time interval plus heading/
altitude-change points) and pruned after the configured history period. This bounds a
long-haul flight's database growth while the hot foreground stream remains smooth.

---

## 8. Refresh engine & quota budget

AeroDataBox RapidAPI Basic allows at most ~300 ordinary status lookups/month; AeroAPI
waives up to $5 of usage but can charge beyond it and bills result sets rather than calls.
ADS-B sources are not per-call billed, but they are rate-limited, contract-constrained, and
may change access rules. So: **poll visible positions politely and status stingily** on an
adaptive schedule driven by the earlier of original scheduled OUT and the latest estimate,
plus sticky operational-state overrides. A predeparture flight never regresses to a slower
tier merely because a new estimate moved later.

Time-window tiers are non-overlapping; when state and time rules overlap, the most frequent
applicable cadence wins. One
unique coordinator worker queries due flights, coalesces codeshares that resolve to the same
instance, and schedules the next earliest due run; do not create and churn one periodic
worker per flight. All background timing is best effort under Doze/OEM restrictions:

| Window/state trigger | Status fetch cadence | Position cadence |
|---|---|---|
| > 48 h out | on app open / manual only | — |
| 48 h → 24 h | every 6 h, periodic worker armed on app open in this band (a flight never viewed during the window isn't backfilled) | — |
| 24 h → 3 h | every 3 h | — |
| 3 h → T−75 min | every 30 min | — |
| **Gate-critical: T−75 min → T+30 min** | request every 15 min (WorkManager floor; execution may be later) | 30–60 s if map/list visible |
| **Delayed/awaiting departure override once delay is observed during active preflight monitoring** | at least every 30 min until departed/cancelled (the original gate-critical window still tightens this to 15 min); after 12 h, hourly with a reduced-freshness warning | visible-screen position only when identity is active/high-confidence |
| After departure → (landing − 45 min) | every 2 h (mid-cruise ETA drift is usually slow) | 8–10 s foreground map · 30–60 s list · 0 background |
| Approach (landing − 45 min) → arrival transition/deadline | request every 15 min until actual IN, the first landed/terminal observation, or the universal deadline below | same as above until landed |
| Post-arrival with an enabled gate/belt alert still unresolved | every 30 min; stop when all enabled fields arrive or 2 h after actual IN / the first landed-terminal observation, even if status is already terminal | — |

`arrivalMonitoringDeadline` prevents malformed/missing provider times from creating an
immortal worker: while no landing/terminal state has been observed, cap monitoring at the
latest estimated (else scheduled) arrival +4 h; if no arrival milestone exists at all, use
original scheduled OUT +24 h. A terminal observation's `observedAt` is only a polling anchor,
never displayed as the actual landing/gate time.

Rules that keep the budget honest:

- **Anchor workers reuse snapshots:** a notification anchor (§12.2) only fetches if the
  latest snapshot is older than ~10 min; otherwise it diffs against what refresh already
  brought in. Anchor fetches and pull-to-refresh calls **are counted in the ledger**.
- Pull-to-refresh bypasses the app freshness cache but is debounced (min 30 s per physical
  instance); it still respects provider rate limits and spend stops.
- Conditional requests are enabled only if documented, but count as full-cost in the local
  ledger until the provider confirms otherwise. A local cache hit that avoids the network
  is the only safely quota-free request.
- Status errors are classified like position errors: honor `Retry-After`; retry transient
  I/O/5xx; do not retry validation/auth failures. Reconcile estimates with provider usage
  headers or a free account-usage endpoint where available.

**Budget math (planning estimate, recomputed from the table above):** tracked from 48 h out — 48–24 h ≈ 4
(if the app is opened both days), 24–3 h ≈ 7, 3 h→T−75 ≈ 3–4, gate-critical ≈ 7, en-route
0 (short-haul) to ~5 (12-h long-haul), approach ≈ 3, and optional post-arrival ≈ 0–4 →
**~25–35 scheduled lookups, or ~28–40 with manual/canonical headroom**. At 2 units each,
AeroDataBox uses ~56–80 units/flight, or roughly **7–11 flights/month** on 600 units (only
~3–5 if every call
requests a found flight plan at double cost, so that flag is off by default). AeroAPI costs
about **$0.14–$0.20/flight** when every response is one result set, before billable errors
and extra pages. A default local soft stop at $4 leaves headroom for roughly 20–28 ordinary
flights, but it is
not a billing guarantee; Settings must say that the provider account owns the actual spend.
Irregular operations add about two calls per delayed hour under the override and are not in
the baseline; forecast remaining budget before arming it. Do not present provider allowances
as a combined fixed ceiling.

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

- Airline monogram · flight number + alias · **status word with semantic color**
  (On Time green / Delayed amber / Boarding pulse / En Route sky / Landed neutral /
  Cancelled red). Status is always **word + color**, never color alone (accessibility).
- Route as IATA codes with a plane glyph; **the one phase-relevant time**, auto-switching:
  "Departs in 2 h 14 m" → "Lands in 1 h 12 m" → "Landed 14:32 · Bag belt 5".
- Countdown granularity adapts: days → `h m` → minutes. Second-by-second updates are
  reserved for the foreground detail hero near an event, avoiding a constantly recomposing
  list and false precision around provider estimates.
- Thin phase progress bar; gate/terminal chip once known.
- **Tap opens the detail view.** Swipe to archive; long-press to edit alias/notifications.
- Sorted by next-event time.
- Add-flight flow accepts **batch input** by comma/newline, spaces inside a designator, and
  slash-separated equivalents such as `CCA861/CA861`; canonical dedup prevents duplicate
  rows. Existing multi-word aliases are resolved before tokenization.
- In limited mode, unresolved flights can take an optional origin, destination, departure
  local date/time, and expected duration so countdown/route/light features remain useful.
  Every such value is visibly "user entered," editable, and never promoted to provider data.
- `PullToRefreshBox` with a themed indicator (see §10); independent freshness labels avoid
  implying that a fresh position also refreshed status/weather (for example, "Status 3 m ·
  position 8 s · weather 20 m").
- Empty state: friendly onboarding — add a flight, pick sample flight to demo the UI.

### 9.2 Detail view — the flight dossier

Ordered by flight-day usefulness, informed by the competitor benchmark but validated with
Blipbird usability testing:

1. **Hero**: big countdown/ETA, status banner, origin→destination with local times +
   timezone labels ("14:10 CST · your time 08:10"), live map snippet (expands full-screen).
2. **Key facts grid**: dep terminal/gate/check-in desk, arr terminal/gate/baggage belt,
   aircraft type + registration, duration, distance. Photos remain out until a source grants
   documented per-image display rights.
3. **Event timeline** — the centerpiece. Rows: check-in reminder → boarding reminder →
   gate departure → takeoff → cruise → descent → landing → gate arrival → baggage. Three-column
   scheduled/estimated/actual presentation; superseded estimates get struck through when
   provider retention rights permit history. Live row pulses.
   **Derived rows are labeled as estimates** (`~` prefix + "est." chip) because no status
   API supplies them: *boarding reminder* = scheduled departure − a user-visible default
   (for example 40 min); *check-in reminder* = T−3 h unless a rights-cleared airline policy
   says otherwise; *gate departure/pushback* = `out` when reported (do not invent a distinct
   doors-closed time); *takeoff* = `off`; *landing* = `on`; *gate arrival* = `in`;
   *cruise/descent* = inferred from ADS-B altitude + vertical rate (data the
   PositionProvider already returns). Consistent with §1's "never fakes precision."
4. **Inbound aircraft** *(v2, source-gated)*: "Your plane is arriving from Shanghai as
   CA1858, lands 12:40." This needs a provider licensed to expose the assigned aircraft's
   current prior leg; a registration alone is not enough, and AeroAPI Personal historical
   access must not be assumed. A high-confidence late inbound may feed a clearly derived
   delay heads-up.
5. **Weather**: decoded, timestamped METAR at both airports; TAF prevailing conditions plus
   any `TEMPO`/`PROB` overlays valid at ETA; time/altitude-aware route samples when the M0 provider gate closes; and applicable
   worldwide SIGMETs from §4.4. Never summarize absent data as "clear."
6. **Airport context**: full name/city/country, current local time and time-zone difference,
   terminal/gate, weather, and official-site/map intents when a trusted URL exists. Do not
   promise security wait times, lounges, or amenities without a selected current source.
7. **Airport health chip** *(v2, source-gated)*: "PEK: departures averaging +40 min right now."
8. **About the airline**: name, alliance, radio callsign ("AIR CHINA"), and trusted contact links.
9. **Share / Pickup mode**: read-only big-type card (ETA · terminal · progress) exportable
   as an image or serverless deep link containing only designator/date/leg. Aliases, API
   keys, provider IDs, and history are never encoded.

### 9.3 Disruption semantics (designed against Flighty's documented failure)

| Event | Treatment |
|---|---|
| Delay | Amber; old time struck through + new time + delta ("+45 min"); reason only when reported or supported by a high-confidence inbound link |
| Gate change | "Gate C27 → **D14**" notification + field highlighted in UI |
| Cancellation | Red full banner — **only** when the provider status is truly *cancelled* |
| Schedule change / renumbering | Calm blue informational treatment — explicitly never the red banner |
| Diversion | Banner names the new airport; map re-centers; timeline re-anchors |

Reported provider status wins. When only timestamps are available, present the arithmetic
as "Est. +18 min" with `DERIVED` provenance rather than claiming an airline-issued delay.
Stale data cannot retain a reassuring green "On time" state without its age beside it.
Delay notifications use configurable thresholds (for example first crossing +15 min, then
meaningful further slips) and event fingerprints include the new estimate; a reported or
evidenced cause is labeled by source, never asserted from correlation alone.

---

## 10. Theming system

Themes are Blipbird's signature. Implementation:

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
Animations obey the system animator-duration scale and an in-app reduce-motion toggle;
haptics/confetti are independently disableable and absent from High Contrast by default.

---

## 11. Live map

- **Renderer:** MapLibre via `maplibre-compose` (declarative sources/layers), classic
  `MapView`-in-`AndroidView` as the escape hatch if the pre-1.0 wrapper bites.
- **Layers:**
  1. Vector base style per theme (OpenFreeMap Liberty/Dark/Positron…) with attribution
     controls always visible, including the non-interactive hero map.
  2. **Flown track**: solid `LineLayer` (rounded caps) from validated client-accumulated
     fixes; an approved OpenSky import is optional enrichment, never a dependency.
  3. **Route guide**: dashed great-circle arc to destination, explicitly not presented as
     the filed route or the path the aircraft will actually fly.
  4. **Plane marker**: `SymbolLayer` icon rotated to heading; animated interpolation
     from the previously rendered fix to a newly received fix over a short bounded tween;
     continuous motion between unknown future fixes would be extrapolation. Any optional
     velocity projection is time-bounded and uses the "estimated" ghost style. Show "last
     seen X min ago" as soon as `seen_pos` goes stale.
  5. Origin/destination pins with gate labels.
  6. **Light/dark timeline (launch requirement):** solar-position math shows daylight state
     at departure, current/high-confidence position, and arrival, plus estimated sunrise/
     sunset crossings and an optional terminator overlay. Test polar day/night, date-line,
     and no-crossing cases; labels say projected because the route guide is not a flight plan.
- **Camera:** auto-follow with manual override; "recenter" FAB; detail-hero snippet is a
  non-interactive lite view into the same composable.
- Altitude/speed/track readout strip under the map (avgeek candy, hidden until data exists).

---

## 12. Notifications

### 12.1 Taxonomy (each independently toggleable, per-flight profiles)

Modeled on the canonical Flighty set, adapted:

MVP: **estimated check-in reminder · delay (and further slips) · gate change · estimated
boarding reminder · departure (pushback/takeoff) · landing soon (~45 min out) · landed ·
arrival gate + baggage belt · cancellation · diversion**.
v2: aircraft/registration change · inbound-plane-late early warning.
(Check-in and boarding are *derived* times per §9.2; never call them an airport/airline
"boarding call," and include the basis in notification details.)

Use a small stable set of Android channels rather than one channel for every event:
**critical changes** (cancellation/diversion/gate), **status changes** (delay/departed/
landed), **reminders** (check-in/boarding/landing soon), and **ongoing flight**. Fine-grained
event toggles remain in-app. Quiet hours suppress non-critical classes but cannot override
the user's channel settings or Do Not Disturb.

### 12.2 Scheduling mechanics (July 2026 policy snapshot)

- **Baseline (no special permissions):** WorkManager one-time delayed workers anchored to
  the next known event (e.g. ~T−45 min before estimated boarding: reuse the latest
  snapshot if fresher than ~10 min, else fetch → diff → post → schedule the next anchor).
  Periodic refresh rides the §8 cadence.
  **Honest latency statement:** WorkManager gives no upper delivery bound. Doze, App
  Standby, constraints, and OEM battery policy can delay detected disruptions well beyond
  one cadence interval; settings say "best effort" without promising 15–45 minutes.
- **Precision upgrade (opt-in):** request `SCHEDULE_EXACT_ALARM` through the special-access
  screen only when the user enables a genuinely time-sensitive local reminder and the final
  Play policy review accepts that core use. `USE_EXACT_ALARM` is not used. Exact alarms fire
  cached, user-facing reminders such as estimated boarding or landing-soon; they **do not
  drive a network polling loop**. An alarm's temporary power exemption and an expedited
  WorkRequest do not guarantee immediate network execution in Doze, so gate/delay detection
  remains best effort without a push backend.
- Exact alarms are lost on reboot and canceled when access is revoked. A
  `RECEIVE_BOOT_COMPLETED` receiver rebuilds enabled reminders, app startup rechecks
  `canScheduleExactAlarms()`, and the permission-grant broadcast reschedules them. Revocation
  degrades to WorkManager without nagging the user.
- **Offline branch:** if refresh fails, an already enabled time-based reminder may still
  post from the schedule that was current when it was planned, labeled "projected · status
  not refreshed." Never synthesize a gate change, departure, cancellation, or other
  detected event from stale data.
- **`POST_NOTIFICATIONS`** runtime permission (API 33+) is requested when the user first
  enables an alert, not merely when a flight is added; denial leaves tracking fully usable.
- Event *detection* (gate change, delay) happens in refresh workers by diffing the new
  `FlightSnapshot` against the previous one; `NotificationPlanner` decides emissions —
  pure and unit-tested. Dedup rides the **persisted `EmittedEvent` ledger** (§7), so
  duplicate suppression survives process death.
- There is no background ADS-B event detector while background position polling is off.
  Truly timely provider-side disruption alerts would require an optional push service and
  a separate privacy/operations design; that is a v2 decision, not an MVP promise.

---

## 13. Platform surfaces: Live Updates & widgets

The Android-native answer to iOS Live Activities:

- **API 36 `Notification.ProgressStyle`:** flight-phase progress bar with colored segments
  (boarding / taxi / cruise / descent), milestone points (takeoff, landing), and
  `setProgressTrackerIcon(plane)`. This works as a regular progress notification on base
  Android 16; do not describe it as QPR-only.
- **API 36.1 / Android 16 QPR2 promoted Live Updates:** request promoted ongoing treatment
  with `setRequestPromotedOngoing(true)`, declare `POST_PROMOTED_NOTIFICATIONS`, check the
  user's promotion setting/eligibility, and guard 36.1 APIs with `SDK_INT_FULL`/
  `VERSION_CODES_FULL`. Promotion to the status-bar chip, top of drawer, or lock screen is
  never guaranteed; OEM/user criteria apply and AOD is not promised.
- **Pre-API-36 fallback:** ongoing notification with standard progress + big-text state.
- **Glance widgets:** "Next flight" (countdown · gate · status) and "In flight"
  (progress · ETA), matching lock-screen-glance value.
- **What advances progress:** `ProgressStyle.setProgress()` is a posted value; Android does
  not continuously recalculate the bar from a cached record. Recompute and repost only on a
  legitimate worker/provider update (within platform frequency guidance). A notification
  chronometer can advance a countdown without reposting, but it does not move the progress
  bar. Widgets likewise update on scheduled/fresh-data triggers, not continuously.
- **Offline in-flight mode:** before scheduled
  takeoff, pre-compute and cache the full expected flight record (times, phases, projected
  progress) so the app, widget, and Live Update keep working with zero connectivity,
  clearly labeled "projected."

---

## 14. Feature roadmap & milestones

### Release definition

The beta is complete only when a user can add several flights in the documented input
forms, select an ambiguous date/leg, reopen them offline, refresh manually, view a
source-labeled list/detail timeline, follow a high-confidence active aircraft, see endpoint
and en-route weather plus light/dark context, choose three accessible themes, and opt into
honestly labeled local notifications. Every release provider must pass §4.6, all map/data
attribution must be visible, and limited mode must remain useful without permissions or
keys. Inbound-aircraft prediction, social accounts, booking/PNR import, a general radar map,
and guaranteed real-time disruption push are explicit non-goals for v1.

The week estimates below are engineering estimates **after external provider approvals**;
waiting for legal/terms answers is not hidden inside a sprint estimate.

### M0 — Feasibility + skeleton (week 1–2)
Close or explicitly fail the §4.6 gates; decide the app-code license; select and spike a
rights-cleared time/altitude-aware en-route weather source; record API cost/coverage on the
representative-flight matrix. In parallel: project scaffolding, version catalog, CI (build,
unit tests, and lint), Hilt graph, Nav3/adaptive shell, Daylight + Cockpit theme engine,
reproducible bundled reference-data pipeline, and add-flight parser/normalizer using
synthetic providers.
**Exit:** provider/asset decision records exist; `CA861`, `CA 861`, and `CCA861/CA861`
deduplicate correctly; ambiguous fixture flights require date/leg selection; no restricted
runtime response is persisted.

### M1 — Status MVP (week 3–5)
`FlightStatusProvider` with the first **release-cleared** implementation, BYO-key onboarding
including no-backup encrypted credential storage, Room snapshot/milestone/provenance model,
alias entry + display, list view with real status/countdown/progress, detail hero + key-facts
grid + event timeline, pull-to-refresh, adaptive coordinator v1, per-plane freshness, spend
soft stop, and disruption semantics.
**Exit:** track a real flight end-to-end on status alone; unit tests for phase machine,
parser, instance identity, planner, derived times, DST/date-line cases, and quota accounting.

### M2 — Live map + provider resilience (week 6–8)
Release-cleared position provider(s), identity→hex confidence rules, client-accumulated and
downsampled track, MapLibre screen (flown track/route guide, bounded marker tween,
staleness ghost), detail-hero map, lifecycle-aware foreground polling, visible OpenFreeMap
attribution, and launch light/dark timeline/terminator. Add a second status provider only if
its approval gate closes; prepare reproducible-build and F-Droid metadata, but do not file a
premature RFP for an unreleased app.
**Exit:** watch a live flight cross the map smoothly; airplane-mode replay renders cached
track; wrong/colliding callsigns are suppressed; approved status failover is proven with
fault injection; polar and date-line light calculations pass.

### M3 — Notifications + weather (week 9–11)
Channels, `POST_NOTIFICATIONS` flow, anchor workers (snapshot-reuse rule), snapshot-diff
event detection, notification planner + persisted `EmittedEvent` ledger, per-flight
profiles, reboot/revocation recovery, optional exact alarms for local reminders only, and
the constrained offline projection branch. Ship METAR/TAF-at-event cards, route SIGMET
intersection, and the M0-selected time/altitude-aware en-route forecast.
**Exit:** notification lifecycle observed on a real tracked flight from a ground observer's
phone, including induced offline/Doze intervals with no timing guarantee claimed; stale
data never emits a synthetic operational event; weather states remain distinct and dated.

### M4 — Polish & release (week 12–15)
Launch themes locked to **three** (Daylight incl. Dynamic, Cockpit, High Contrast), haptics
and themed pull-to-refresh, **"Next flight" Glance widget** (cheap once the state layer
exists), accessibility audit (TalkBack, contrast, touch targets), quota ledger UI,
About/attribution + privacy screens, Play privacy policy/Data Safety/listing, F-Droid
metadata/submission for a tagged source release, and a `:benchmark` module with baseline
profiles.
**Exit:** the release definition above passes on phone + large-screen emulator, provider
permissions are archived, a real flight-day checklist passes, and the tagged public beta is
reproducible without embedded secrets.

### v2 (post-launch)
**Solari split-flap theme + flip-animation engine · Skyfade theme** · API 36 ProgressStyle
and API 36.1 promoted Live Updates · "In flight" Glance widget · inbound-aircraft section + late
warning · full offline pre-computed in-flight mode · recurring alias date rules · flight
log + Passport stats (flights, km, hours, airports, airlines, aircraft-type badges,
shareable year card — all on-device) · airport-health chip · pickup/share mode ·
multi-flight trip grouping (absorbs multi-leg tracking).

### Delighters (ongoing)
Richer sunrise callouts · landing confetti · route on-time forecast only from
retention-cleared local snapshots · AR "point at the sky" long-shot.

---

## 15. Testing strategy

- **Unit (JVM, the bulk):** designator parser (known carrier prefixes, spaces, slash-pair
  dedup, suffixes, aliases, malformed batches), instance/date selection (DST overlap/gap,
  date line, same-day duplicate, no seven-day scan), milestone/phase transition tables,
  `NotificationPlanner` + event fingerprinting, quota/spend stops, adaptive scheduler,
  METAR decoder, TAF prevailing/inheritance/overlap semantics, dual-feed route/SIGMET
  normalization and intersection, unit conversion, great-circle and
  terminator math including polar day/night.
- **Provider contract tests:** synthetic or provider-approved redacted fixtures cover happy
  path, missing fields, cancellation/diversion, codeshare/multi-leg, polymorphic ADS-B
  values, empty results, 401/403/429/5xx, and pagination. Do not commit captured payloads
  merely because they are useful. Diff public OpenAPI schemas in CI; run low-frequency live
  smoke tests only with provider permission and private BYO secrets, never by anonymously
  hammering free APIs from shared CI addresses.
- **Repository/integration:** Room in-memory + fake providers; request coalescing and
  provider-wide rate limiting under concurrent list/map/workers; allowed failover under
  injected faults; migration, process-death, reboot, alarm-revocation, and snapshot-diff
  tests. Exercise Android backup/restore and inspect the archive to prove operational DB,
  credentials, and reference assets are absent; restored user intent re-resolves cleanly.
  Weather layers fail independently; no low-confidence aircraft reaches UI state.
- **UI:** Compose semantics for list/detail states (loading, degraded, stale, disrupted);
  screenshots per theme × light/dark with RTL, 200% font scale, reduce-motion, color-vision
  checks, and expanded two-pane layout. Instrument the MapLibre screen to verify visible
  attribution and lifecycle stop/start rather than snapshot-testing live tiles.
- **E2E happy path:** Maestro flow on CI emulator — add flight (fixture-backed via
  test-only `FakeProviders` build flavor), see list, open detail, pull-to-refresh.
- **Manual flight-day protocol:** a checklist run against a real tracked flight before each
  release, including mobile data, battery saver, Doze, offline interval, gate-less airport,
  and wrong-callsign checks. Record provider cost and battery use, not the user's itinerary.

---

## 16. Licensing, attribution & privacy

- **App-code license:** Apache-2.0 remains the recommendation for its patent grant, but the
  repository currently uses the Unlicense. Confirm the choice in M0 before accepting
  outside contributions; later relicensing requires contributor consent. Audit dependency
  compatibility separately from data rights: a permissive code license does not relicense
  API results, map data, generated assets, photos, or trademarks.
- **Bundled data is separately licensed:** preserve complete notices in the distribution
  and repository, pin source versions/revisions, publish reproducible transformation
  scripts, and label each generated output. This includes OurAirports (public domain),
  timezone-boundary-builder (ODbL), OpenTravelData (CC BY 4.0; identify changes), and the
  pinned Wikipedia-derived aircraft JSON (CC BY-SA 4.0, not Apache/Unlicense).
- **Runtime attribution:** keep OpenFreeMap/OpenMapTiles/OpenStreetMap attribution visible
  on every map. Associate displayed adsb.lol data with an ODbL notice. adsb.fi attribution
  is mandatory if that source is ever approved; airplanes.live and OpenSky use the exact
  attribution/citation their approval requires. Credit aviationweather.gov and any selected
  forecast source. An About screen supplements, but does not replace, attribution that a
  license requires beside the work.
- **Red lines:** no restricted raw payloads or route databases in Room, exports, analytics,
  or test fixtures; no provider-derived punctuality corpus without permission; no logo CDN,
  scraped logo pack, or aircraft photo without documented rights; no provider enabled in a
  release solely because the user supplies a key. Archive the §4.6 decisions.
- **Privacy:** there is no account, analytics SDK, or Blipbird application backend, but
  network use is not "all on device." Enabled providers receive the flight designator,
  date/range, airports/registration/hex as needed, IP address, and User-Agent; these queries
  can reveal travel interests. Disclose each recipient and purpose before enabling it and
  link its privacy policy. Keys are Keystore-encrypted, excluded from backup/export/logs,
  and redacted from diagnostics. The optional signed provider-config fetch to the project
  repository is a separately disclosed network recipient and defaults off in F-Droid.
  User-enabled OS cloud backup is another disclosed recipient, limited to the user-authored
  subset in §18. Provide in-app export/erase and retention controls.
- **Store compliance:** publish a web-accessible Play privacy policy even if no data is
  collected by the project operator, complete Data Safety based on actual provider traffic,
  and review third-party-content rights. F-Droid metadata must disclose applicable
  `NonFreeNet`/other anti-features rather than treating BYO keys as automatically free.
- **Remote provider config:** if retained, it is a signed, schema-versioned static document
  from the project repository that can disable/reorder only already shipped providers. It
  has expiry, last-known-safe defaults, signature-key rotation, an in-app audit view, and an
  opt-out (F-Droid defaults off). It cannot add code, terms, hosts, or bypass a release gate.

---

## 17. Risks & mitigations

| Risk | Likelihood | Mitigation |
|---|---|---|
| Provider terms do not permit this distributed client or local retention | **High / release-blocking until answered** | §4.6 written approval gates; disable unresolved implementations; preserve a useful limited mode; never infer rights from BYO keys |
| A free API dies, drifts, or adds auth | **High, eventually** | Tolerant DTOs, public-schema diffing, contract fixtures, circuit breakers, safe built-in defaults, and a constrained signed remote disable switch; no claim that interfaces make replacement free |
| Free allowance is exhausted or AeroAPI incurs charges | Medium | Coalescing, adaptive cadence, provider-reported usage reconciliation, visible ledger, flight-plan flag off, conservative local soft stops, and explicit statement that only the provider controls billing |
| Gate data missing/wrong for many airports | Certain, sometimes | Best-effort UI ("Gate —"), never fabricate; notifications conditional on data existing |
| A reused/mismatched callsign attaches the wrong aircraft | Medium | Plausible time window, registration→hex preference, route confidence check, and suppression of low-confidence candidates rather than a warning on a wrong marker |
| Background notification arrives late | **Certain on some devices** | No numeric WorkManager latency promise; exact alarms only for local reminders; surface battery restrictions; optional push architecture is a separate v2 decision |
| maplibre-compose pre-1.0 API churn | Medium | Pin version; `AndroidView` fallback is mature and feature-equivalent |
| OpenFreeMap outage (no SLA) | Low/Medium | Cached tiles or attributed neutral canvas while route/pins continue; self-hosted PMTiles only after a funded operational decision, not an uncosted launch promise |
| Weather is stale, region-limited, or misread as safety guidance | Medium | Observation/forecast/advisory labels and age, worldwide-vs-regional capability checks, independent degradation, and prominent non-navigation disclaimer |
| Play/F-Droid policy or target SDK drifts | Annual certainty | Target/compile API 37 after behavior testing and run a dependency, policy, privacy, and anti-feature checklist each release |
| Flighty-alikes land on Android first (Aviate etc.) | Medium | Ship the calm-open-source-themable wedge; their existence proves the market |

---

## 18. Cross-cutting concerns

Concerns that touch every screen and don't fit cleanly into one section above.

- **Internationalization & locale-aware units.** Blipbird launches in English but
  externalizes all strings (`strings.xml`, zero hardcoded UI text). Units follow the device
  locale *and* a manual override in Settings: distance (km / nm / mi), altitude (ft / m),
  speed (kt / km·h / mph), temperature (°C / °F), pressure (hPa / inHg). Airport names
  render in the device locale where the reference data has a translation, falling back to
  the English/ICAO name. Times use `java.time` locale-aware formatters; compact countdown
  units still come from localized plurals/format resources rather than hardcoded English.
  RTL layouts (Arabic/Hebrew) are validated in the screenshot gate.
- **Large screens & foldables.** Nav3's adaptive scaffold gets a two-pane list-detail
  layout at the `MEDIUM`/`EXPANDED` window-size class (tablets, unfolded foldables,
  ChromeOS), with the map pinnable to a side pane on `EXPANDED`; phone stays single-pane.
  Navigation state support does not make adaptive information architecture free: test pane
  priority, back behavior, posture/hinge occlusion, keyboard/focus, and state restoration.
- **Battery & network-aware polling.** The §8 cadence is a *ceiling*, not a target. The
  refresh engine reads `ConnectivityManager` + `PowerManager`: an opt-in low-data mode
  widens foreground map polling on metered cellular, while small status requests remain
  allowed by default because travelers often have only mobile data. In battery saver, offer
  a reduced position cadence rather than silently pausing a map the user is actively
  watching; always expose freshness. These are centralized, testable policy knobs.
- **Data portability & backup.** Because there is no account-based sync,
  switching phones would otherwise lose tracked flights and aliases. M4 adds
  manual **Export/Import** (JSON via SAF `ACTION_CREATE_DOCUMENT` / `ACTION_OPEN_DOCUMENT`)
  covering only `TrackedFlight`, `SavedFlight`, `NotificationProfile`, and non-secret
  settings' user-authored fields (`selectedInstanceId` is omitted). A separately confirmed
  export may include retention-cleared `FlightLogEntry`
  history; the default never includes API keys, provider IDs, snapshots, tracks, or raw
  data. Auto Backup includes only the user database and non-secret settings DataStore. Both
  cloud and device-transfer rules explicitly exclude the operational/reference databases
  and credential file using current `dataExtractionRules` plus legacy `fullBackupContent`.
  Disclose that enabled OS cloud backup uploads the user-authored subset to the user's
  backup provider. Restore clears stale `selectedInstanceId` links, re-resolves flights, and
  cannot resurrect expired provider data.
- **Performance budgets.** Targets enforced in CI: cold start to first frame < 1.5 s on a
  named mid-range test device (Baseline Profile + androidx Startup), map marker tween with
  p95 frame time within a 60 Hz frame and no frozen frames, a measured AAB budget set after
  the reference-data spike (no invented ~1 MB assumption), bounded track-database growth,
  and `macrobenchmark` regressions gating release. Generated monograms require no runtime
  logo downloads.
- **First-run onboarding.** A single linear flow ties the pieces from across sections
  together: concise value/privacy explanation → optional release-cleared BYO-key setup →
  add a sample or real flight → optional theme pick. Notification permission is deferred
  until an alert is enabled. Every nonessential step is skippable into useful limited mode,
  and setup/provider disclosures are re-enterable from Settings.
- **Local diagnostics without analytics.** A provider-health screen shows last success/error,
  circuit-breaker state, source freshness, and estimated/reported quota. User-initiated
  support export is structured and aggressively redacted: no keys, aliases, full payloads,
  exact itinerary, or stable device identifier. Secret scanning covers source and artifacts.

---

## Appendix A: API quick reference

| Purpose | Endpoint | Auth | Limit |
|---|---|---|---|
| Status by number+date | `GET aerodatabox.p.rapidapi.com/flights/number/{num}/{date}` | user's RapidAPI key | Basic: 600 units/mo; normally 2 units/status request, potentially 4 with a found flight plan |
| Status fallback | `GET aeroapi.flightaware.com/aeroapi/flights/{ident}` | user's `x-apikey` | $0.005/result set; up to $5 usage waived monthly, then chargeable; 10 result sets/min on Personal |
| Ident candidates | `GET …/flights/{ident}/canonical` | same | $0.001/result set; returns candidates, not a dated flight instance |
| Live position | `GET api.adsb.lol/v2/callsign/{cs}` · `/v2/reg/{reg}` · `/v2/icao/{hex}` | none today | dynamic/unpublished; ODbL obligations and no SLA |
| Position candidates (approval-gated) | `GET api.airplanes.live/v2/…` · `GET opendata.adsb.fi/api/v2/…` (adsb.fi maps registration to `/v2/registration/`) | none today | 1 req/s each; not enabled without written distributed-app permission |
| Optional track import (approval-gated) | `GET opensky-network.org/api/tracks/all?icao24={hex}&time=0` | anonymous or OAuth2 | live call costs 4 of 400 anonymous daily track credits/IP; operational agreement required |
| Callsign→route/airline | `GET api.adsbdb.com/v0/callsign/{cs}` | none | unpublished; ephemeral use only, no Room route cache |
| Hex/route fallback | `GET hexdb.io/api/v1/route/icao/{cs}` | none | 1,000 / 5 min; persistence/reuse rights unclear |
| METAR/TAF | `GET aviationweather.gov/api/data/metar?ids={icao}&format=json` (and TAF endpoint) | none (custom UA) | 100/min maximum; do not consume an endpoint more than once/min/thread |
| Route SIGMETs | `GET …/isigmet?format=geojson` + `GET …/airsigmet?format=geojson` | none (custom UA) | international + CONUS domestic feeds; normalize/deduplicate and cache by validity/update time |
| En-route forecast | provider/endpoint selected by the M0 rights + pressure-level capability spike | TBD | must support time/altitude sampling and public-client terms |
| Map tiles | `https://tiles.openfreemap.org/styles/{liberty|dark|positron}` | none | no published request limit; no SLA or bulk prefetch |

## Appendix B: source register & open decisions

All links were reviewed July 22, 2026. These are sources, not frozen guarantees: record the
document/version and relevant response whenever an M0 gate closes, and rerun the review
before release. Market-context claims are directional and must be rechecked before they are
used in store copy; they do not justify a technical or licensing decision.

### Market context

- Flighty [supported platforms](https://flighty.com/help/supported-platforms),
  [Android waitlist](https://flighty.com/android-waitlist),
  [inbound-aircraft feature](https://flighty.com/help/where-is-my-plane),
  [delay predictions](https://flighty.com/help/delay-predictions), and
  [2023 Apple Design Award](https://developer.apple.com/design/awards/2023/)
- App in the Air [archived first-party shutdown notice](https://web.archive.org/web/20241002170603/https://appintheair.com/shutdown)
- Flightradar24 [Play listing](https://play.google.com/store/apps/details?id=com.flightradar24free)
  and [plans](https://www.flightradar24.com/premium); FlightAware
  [Play listing](https://play.google.com/store/apps/details?id=com.flightaware.android.liveFlightTracker)
  and [mobile features](https://www.flightaware.com/mobile/)
- Aviate [site](https://www.aviate.to/), [features](https://www.aviate.to/features),
  [privacy claims](https://www.aviate.to/privacy), and
  [July prerelease notes](https://www.aviate.to/blog/aviate-1-0-0-prelease-7)

### Android platform and distribution

- [Google Play target API requirements](https://support.google.com/googleplay/android-developer/answer/11926878?hl=en),
  [Android 17 final release announcement](https://android-developers.googleblog.com/2026/06/Android-17.html),
  and [SDK setup](https://developer.android.com/about/versions/17/setup-sdk)
- [Kotlin releases](https://kotlinlang.org/docs/releases.html),
  [Android Gradle Plugin releases](https://developer.android.com/build/releases/gradle-plugin),
  [AGP built-in Kotlin guidance](https://developer.android.com/build/releases/agp-9-0-0-release-notes),
  [Compose BOM releases](https://developer.android.com/jetpack/androidx/releases/compose-bom),
  [Navigation 3 releases](https://developer.android.com/jetpack/androidx/releases/navigation3),
  and [maplibre-compose releases](https://github.com/maplibre/maplibre-compose/releases)
- [Material 3 releases](https://developer.android.com/jetpack/androidx/releases/compose-material3),
  [Material 3 Adaptive releases](https://developer.android.com/jetpack/androidx/releases/compose-material3-adaptive),
  [Hilt releases](https://developer.android.com/jetpack/androidx/releases/hilt),
  [Room 3 releases](https://developer.android.com/jetpack/androidx/releases/room3), and
  [Glance releases](https://developer.android.com/jetpack/androidx/releases/glance)
- [WorkManager periodic-work semantics](https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work)
  and [Doze/App Standby restrictions](https://developer.android.com/training/monitoring-device-state/doze-standby)
- [Android exact-alarm guidance](https://developer.android.com/develop/background-work/services/alarms/schedule)
  and [Google Play sensitive permission/API policy](https://support.google.com/googleplay/android-developer/answer/16558241?hl=en)
- [Live Update guide](https://developer.android.com/develop/ui/views/notifications/live-update),
  [`Notification.ProgressStyle`](https://developer.android.com/reference/android/app/Notification.ProgressStyle),
  and [Android 16 QPR2 SDK setup](https://developer.android.com/about/versions/16/qpr2/setup-sdk)
- [Google Play User Data policy](https://support.google.com/googleplay/android-developer/answer/10144311?hl=en)
  and [Intellectual Property policy](https://support.google.com/googleplay/android-developer/answer/9888072?hl=en)
- [Android Auto Backup behavior and include/exclude rules](https://developer.android.com/identity/data/autobackup)
- [F-Droid Inclusion Policy](https://f-droid.org/docs/Inclusion_Policy/) and
  [Anti-Features](https://f-droid.org/docs/Anti-Features/)

### Flight status

- AeroDataBox [pricing](https://aerodatabox.com/pricing/),
  [coverage](https://aerodatabox.com/data-coverage/),
  [terms](https://aerodatabox.com/terms/), and
  [RapidAPI OpenAPI specification](https://doc.aerodatabox.com/docs/openapi-rapidapi-v1.json)
- FlightAware [AeroAPI plans, license scope, and per-result-set pricing](https://www.flightaware.com/commercial/aeroapi/),
  [AeroAPI documentation](https://www.flightaware.com/aeroapi/portal/documentation),
  [OpenAPI specification](https://static.flightaware.com/rsrc/aeroapi/aeroapi-openapi.yml),
  and [Terms of Use](https://www.flightaware.com/about/terms-of-use/)

### Position and track

- adsb.lol [open-data/API terms](https://www.adsb.lol/docs/open-data/api/),
  [API implementation and rate/auth notes](https://github.com/adsblol/api), and
  [ODbL 1.0](https://opendatacommons.org/licenses/odbl/1-0/)
- airplanes.live [API guide](https://airplanes.live/api-guide/),
  [field descriptions](https://airplanes.live/rest-api-adsb-data-field-descriptions/),
  [terms](https://airplanes.live/terms-of-use/), and
  [commercial-use contact](https://airplanes.live/commercial-use/)
- adsb.fi [official endpoints, limits, and terms](https://github.com/adsbfi/opendata/blob/main/README.md)
- OpenSky [REST auth, credits, and experimental tracks](https://openskynetwork.github.io/opensky-api/rest.html)
  and [Terms of Use](https://opensky-network.org/about/terms-of-use)

### Maps, weather, and reference data

- OpenFreeMap [service, attribution, and license summary](https://openfreemap.org/) and
  [Terms of Service](https://openfreemap.org/tos/)
- [OurAirports data and public-domain statement](https://ourairports.com/data/)
- [timezone-boundary-builder](https://github.com/evansiroky/timezone-boundary-builder)
  (pin a release and preserve its ODbL notice)
- [OpenTravelData](https://opentraveldata.github.io/opentraveldata/) and
  [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/)
- [Wikipedia aircraft type designators](https://en.wikipedia.org/wiki/List_of_aircraft_type_designators)
  and [CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/)
- adsbdb [source-specific route-data restrictions](https://github.com/mrjackwills/adsbdb)
  and [hexdb](https://hexdb.io/) (no published persistence license found)
- Aviation Weather Center [Data API products, limits, and OpenAPI](https://aviationweather.gov/data/api/)
  and the [NWS disclaimer](https://www.weather.gov/disclaimer)

### Open decisions

| Decision | Due | Safe outcome if unresolved |
|---|---|---|
| AeroDataBox distributed-client, normalized retention, history, and conditional-billing permission | before M1 | provider omitted; limited/synthetic mode remains |
| FlightAware Personal BYO use in a public client, retention, and no-surprise-spend controls | before adding fallback | provider omitted |
| adsb.lol local accumulated-track ODbL treatment | before M2 | foreground position only, no retained track |
| airplanes.live, adsb.fi, and OpenSky operational/distributed-app permission | before enabling each | implementation excluded from release build |
| Global time/altitude-aware en-route forecast source and terms | M0 | do not mislabel surface METAR as cruise weather; beta cannot claim the route-forecast acceptance item |
| App-code license (current Unlicense vs Apache-2.0) | M0, before outside contributions | retain current license and document decision |
