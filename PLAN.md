# Blipbird — Design & Implementation Plan

> **Blipbird** is an open-source Android flight tracker. Enter one or more flight numbers
> (`CA861`, `CCA861`, or a saved name like *"Mom's flight home"*) and optionally a date;
> Blipbird shows a beautiful, glanceable list of your flights, a rich detail view with a
> live-updating map and a **flight ribbon** visualizing daylight/darkness and weather along
> the whole route, and sends notifications for the moments that matter — boarding,
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
   box — and a genuine zero-key mode (bundled airline/airport context, endpoint weather and
   advisories, user-entered route/time, and high-confidence active positions) is designed as
   a real first-class mode, not a broken state.
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
| Solar math | commons-suncalc (`org.shredzone.commons:commons-suncalc`) | 3.11 | Apache-2.0, API 26+, and no non-optional runtime dependencies; supplies offline true solar altitude and north-based azimuth. Route sampling, cruise-horizon correction, crossing detection, and map geometry remain Blipbird code (§9.4) |
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
| Time to departure | ⚠️ Only from a user-entered scheduled time; route metadata does not provide a trustworthy schedule. User-supplied times are labeled as such |
| Gate numbers | ❌ Needs a status key — honest CTA shown in place |
| Airline info | ✅ Bundled datasets; release-cleared runtime enrichment is optional |
| Live map | ⚠️ For an active aircraft only when identity clears §5; a route guide also needs user-entered or release-cleared endpoints |
| Airport info, endpoint weather/advisories | ✅ Bundled data + aviationweather.gov; the pressure-level ribbon forecast remains provider-gated |
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

Do not collapse altitude fields into one untyped number. Preserve barometric/standard-
pressure altitude separately from geometric altitude and retain the source-declared datum;
an undocumented datum is `UNKNOWN` and cannot drive pressure/geopotential or horizon math.

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

**Rate-limit bucket policy.** "The coordinator enforces each provider's limit" is the
intent; the implementation is an explicit **per-host token bucket** living in the
`ProviderRequestCoordinator` (§6). Each provider maps to one bucket with a `capacity` and a
`refillRate` derived from its documented limit (airplanes.live / adsb.fi 1 token/s,
aviationweather 100 tokens/min, Open-Meteo the published per-minute/per-hour/per-day
figures enforced as *separate* nested buckets). Providers with *unpublished* limits
(adsb.lol) get a deliberately conservative bucket (e.g., 1 token/2 s) until a provider
statement says otherwise. Requests `tryAcquire` before dispatch; on exhaustion, the call is
queued for the next refill tick rather than fired-and-retried, which is what actually keeps
you under limit. `Retry-After` from a 429 *temporarily lowers* that bucket's refill rate.
The same bucket model backs the §8 spend stops at the provider boundary; the two never
diverge.

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

**Runtime enrichment candidates (disabled unless written terms cover hosted use, display,
and exact retention):**

- **adsbdb.com** (free, no key, actively maintained): `/v0/callsign/{cs}` returns both
  callsign forms (ICAO + IATA), the airline (incl. radio callsign), and full
  origin/destination airport objects — live-verified with `CCA861` → Air China,
  Beijing ZBAA → Geneva LSGG. Also `/v0/aircraft/{hex|reg}` for aircraft details.
  **License trap:** its source terms say route data may not be copied, published, or
  incorporated into another database without permission. Display in a distributed app may
  be publication; in-memory handling does not cure that. Do not call it from a release build
  until written permission covers client polling, display, normalization, and any cache.
- **hexdb.io** (fallback; 1,000 req/5 min): route, hex↔reg, aircraft, airport lookups. It
  publishes no clear display/reuse/persistence license, so keep it disabled until those
  rights and distributed-client use are confirmed. If neither candidate clears, use only
  bundled, user-entered, or status-provider-cleared endpoint data.

**Airline logos — the licensing reality (researched):** there is **no fully-licensed free
logo source**; logos are trademarks and the free CDNs grant no license. Strategy:

1. Launch behavior: **generated monogram avatars** (two-letter IATA code on a deterministic
   per-airline color derived from the code hash) — offline, theme-aware, always works.
2. Do not fetch from `pics.avs.io`, Kiwi, Daisycon, or scraped packs: a trademark disclaimer
   does not grant copyright, CDN, or hotlink rights. Add a remote `AirlineLogoProvider` only
   after a source grants documented display/caching rights for this distribution model.
3. Aircraft photos follow the same rule. adsbdb's software license does not grant rights to
   Airport-Data photos; omit photos until per-image display and attribution terms are met.

### 4.4 Weather (origin, destination & en-route) — `WeatherProvider` + `RouteWeatherProvider`

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
| Time/altitude-aware en-route forecast | **Open-Meteo is the M0 candidate**, sampled at projected passage time with selected/bracketing pressure levels and geopotential height | hard gate for the current beta definition; hosted-use, attribution, capability, and cost gates must close | turbulence/icing only when the selected data product actually supports them |
| Surface route forecast | **MET Norway Locationforecast is an optional candidate**, never a cruise-weather fallback | disabled pending distributed-app traffic approval | surface context only |

Under the current release definition, failure to clear a time/altitude-aware provider blocks
the M3 route-weather slice and beta. The project may explicitly revise beta scope in a
decision record, but it cannot silently substitute surface weather or claim this acceptance
item passed.

AWC AIRMET/G-AIRMET and winds-aloft products are region-limited, so they must not be labeled
worldwide fallbacks. A missing advisory is not proof of safe or clear weather; the empty
state says "No applicable advisories found in available AWC data," includes freshness, and
links to the full advisory. All weather is informational and explicitly not for navigation.

**Open-Meteo candidate constraints.** The hosted endpoint needs no key and may serve a
genuinely noncommercial app without ads or subscriptions under its current terms; being
free or open source does not establish eligibility, so any monetization, sponsorship, or
commercial backing reopens the gate. Returned data is CC BY 4.0, but that data license does
not grant hosted-service access. Every ribbon/card location displaying its data carries a
nearby linked “Weather data by Open-Meteo.com” credit; the legal view also links the license
and identifies transformations.

Comma-separated coordinates are documented, so 10–20 route samples can share one request.
The public documentation does not promise the server's current 1,000-location default.
Request up to 16 forecast days only when needed and tolerate shorter model/variable
availability. Surface `weather_code`, precipitation, visibility, and total/low/mid/high
cloud describe surface/column products, **not** cruise conditions. Pressure-level
temperature, wind, and RH-derived cloud are model approximations: map a standard-pressure/
flight-level input to pressure surfaces with the documented atmosphere conversion, while a
geometric-MSL input brackets returned geopotential heights. Unknown or unconverted ellipsoid
datums select no level; never call 250 hPa “FL340.” A 10–20-location request with a modest
surface set and one pressure level is roughly 13–30 weighted units through 14 days under the
current formula; all-level requests cost far more. Meter the exact query against the
published per-minute/hour/day/month limits. AGPL self-hosting remains a separately costed
data, operations, upstream-license, and compliance decision, not an automatic escape hatch.

**MET Norway candidate constraints.** Locationforecast provides global surface forecasts
for up to nine days but explicitly is not suitable above ground level; its altitude argument
is terrain elevation. It accepts one point per request, prohibits generic Android/OkHttp
User-Agents, requires cache reuse through `Expires`/`Last-Modified`, and says mobile apps
must not fetch while unused. Truncate latitude/longitude to at most four decimal places
before both cache-key creation and request; five or more may receive 403. Traffic above 20
requests/second across **all installations** needs special agreement, which local clients
cannot coordinate, and the service recommends a caching proxy as volume grows. It therefore
ships only after a direct-mobile/aggregate-traffic decision and only as clearly labeled
surface context.

A general radar layer remains out of v1 until a global source passes the same rights,
coverage, attribution, caching, and operational review.

### 4.5 Degradation matrix

| Situation | Behavior |
|---|---|
| No status API key configured | Limited mode: bundled airline/airport data, endpoint weather/advisories, and an active high-confidence ADS-B position. Show a route guide only from user-entered or separately release-cleared endpoints. No provider schedule is implied; status card shows a "connect a data source" CTA |
| Status API has no gate | "Gate —" placeholder; notification for gate only fires when a gate exists |
| Flight not yet airborne | Countdown only when a provider or user supplied a time; map shows a great-circle guide, not a filed route |
| Position lookup empty in-flight (oceanic gap) | "Last seen 24 min ago" + estimated ghost marker; map keeps flown track |
| Callsign ≠ flight number (e.g. BA545 flies as BAW5GU) | Fall back to registration (from status payload) → `/v2/reg/{reg}` (adsb.fi: `/v2/registration/`), then poll by hex (see §5) |
| Callsign ≠ flight number **in zero-key mode** (no status payload → no registration) | If endpoint rights/input permit, show only the route guide plus "live position could not be matched"; otherwise show no route. Do not maintain a brittle airline heuristic or display a low-confidence aircraft as the flight |
| En-route weather fetch fails (§4.4) | Endpoint METAR/TAF remains independent; route forecast and advisory cards show their own muted unavailable/stale state rather than blanking the section |
| Flight beyond a provider's window (AeroAPI explicit range = −10 d…+2 d) | Use another configured, approved provider if available. Otherwise retain the requested designator/date and say when status lookup becomes available; do not invent a schedule skeleton from route metadata |
| Quota nearly exhausted | Adaptive cadence backs off; banner explains reduced freshness |
| All providers down | Room renders only normalized data whose retention terms allow it, with independent source/freshness stamps |

### 4.6 Provider feasibility gates (close or explicitly fail before the dependent milestone)

Public reachability is not production permission. Archive written answers in the repository's
decision records (without credentials or confidential contract text) for these questions:

1. **Status rights:** may a freely distributed open-source Android client use per-user keys,
   display normalized results, retain snapshots locally, and derive an on-device history?
   What TTLs apply? Are 304 and error responses billable?
2. **Position rights:** obtain operational polling approval for any default provider whose
   terms are personal/non-commercial or otherwise ambiguous. Implement adsb.lol ODbL
   attribution and assess whether the accumulated local track database triggers share-alike.
3. **Asset and metadata rights:** pin every generated dataset and preserve its license; do
   not ship logos, photos, timezone mappings, test fixtures, or runtime route/enrichment
   display whose provenance and distributed-client rights are unclear.
4. **Weather rights and capability:** confirm hosted-client eligibility, adjacent
   attribution, normalized retention, aggregate traffic, and exact weighted-query behavior.
   Validate pressure/geopotential selection and keep surface, pressure-level, observation,
   forecast, and advisory products visibly distinct.
5. **Contract spike:** test at least 20 representative flights (domestic/international,
   codeshare, multi-leg, overnight/date-line, delayed/cancelled, and missing-gate cases),
   record field coverage and request cost, and verify response handling without committing
   restricted raw payloads.
6. **Go/no-go:** if a provider does not grant the required rights, keep it out of release
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
  ├─ 2. Normalize through the bundled IATA↔ICAO airline table. A release-cleared
  │     metadata resolver may validate a current callsign/route at runtime under
  │     its exact display/retention rules. Preserve the exact user-entered
  │     marketing designator.
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
│  BuildProjectedRouteProfile (milestones + user input + fixes)      │
│  DaylightEngine (pure: projected profile → solar elevation,       │
│                  light bands, sunrise/sunset events, terminator)  │
├───────────────────────────── Data ───────────────────────────────┤
│  FlightRepository (UserDb | no-backup OperationalDb | ReferenceDb)│
│  PositionSession (foreground hot flow; batched/downsampled writes)│
│  ├─ FlightStatusProvider    ← release-cleared implementations     │
│  ├─ PositionProvider        ← AdsbLol | cleared optional sources  │
│  ├─ TrackProvider           ← ClientAccumulated | optional import │
│  ├─ MetadataProvider        ← bundled DB + approved resolvers     │
│  ├─ WeatherProvider         ← AWC airport/advisory feeds          │
│  ├─ RouteWeatherProvider    ← cleared pressure-level forecast     │
│  └─ AirlineIdentityProvider ← generated Monogram                  │
├─────────────────────────── Platform ─────────────────────────────┤
│  WorkManager workers (refresh, notification lead-ups)             │
│  NotificationEmitter (channels, ProgressStyle Live Updates)       │
│  ProviderRequestCoordinator · ProviderHealth/CircuitBreaker       │
│  · RateLimitBuckets · RetentionPruner · Glance widgets            │
│  Clock (injectable Instant + monotonic source) · Heartbeat        │
│  CrashRouter (local, redacted uncaught-exception sink)            │
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
- **Retention decisions are executable policy, not prose.** Each provider/data class maps to
  a reviewed persistence permission and maximum TTL. A provider-derived row cannot be
  written without an expiry unless its decision record explicitly permits indefinite local
  retention. One pruner runs at startup and after refresh; archive, delete, and erase invoke
  the same cascade immediately. It removes expired instances/snapshots/route forecasts/
  tracks/event fingerprints and cancels related alarms/cache entries. A provider with no
  approved retention remains memory-only. Before deleting an operational `FlightInstance`,
  the coordinator clears its cross-database `TrackedFlight.selectedInstanceId`; a crash-safe
  startup integrity pass clears any dangling link and queues re-resolution.
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
- **A single injectable `Clock` is the only source of "now."** Every read of wall-clock
  time (`Instant.now()`, `System.currentTimeMillis()`, `Clock.systemUTC()`) and every
  monotonic read (`System.nanoTime`/`elapsedRealtime`) in domain, data, and platform layers
  goes through one `core.time.Clock` interface (`now(): Instant`, `monotonicMillis(): Long`).
  Production wires `SystemClock`-backed implementations; tests inject a `FakeClock` they can
  advance deterministically. This is what actually makes the "pure decision cores" unit-
  testable across DST, date-line, and "is it T−45 yet?" boundaries — without it the phase
  machine, cadence policy, and notification planner all hide untestable wall-clock reads.
- **Failover is a real component, not a paragraph.** `ProviderHealth`/`CircuitBreaker` holds
  per-provider state `CLOSED → OPEN → HALF_OPEN`: it opens after N consecutive
  timeout/5xx/dns failures within a window, stays open for a cool-down, and probes with one
  request in `HAL_OPEN` before closing. The coordinator routes around an `OPEN` provider and
  surfaces its state to the diagnostics screen (§18). 401/403 and spend-stop are *not*
  circuit-breaker states (they do not self-heal); they are separate disable signals that
  require user/key action, and failover never routes around them.
- **Countdowns tick on one shared Heartbeat.** A single process-wide
  `Heartbeat: Flow<Instant>` (a minute tick, switching to a second tick only inside the
  foreground detail hero near an event) is collected once per screen; list rows re-derive
  their countdown strings from the emitted instant rather than each spinning up its own
  `launch { while (active) { delay(60_000) } }`. This avoids a thundering herd of timers at
  the top of each minute and the row-wide recomposition that is the classic list-jank
  footgun.

---

## 7. Data model

Core Room entities (abridged):

```kotlin
Altitude(valueFt, reference)                   // STANDARD_PRESSURE, GEOMETRIC_MSL,
                                               // GEOMETRIC_ELLIPSOID, or UNKNOWN

// UserDatabase: user-authored portable intent only. Auto Backup may include this file.
TrackedFlight(id, userDesignator, departureDateLocal?, selectedInstanceId?,
              userOriginCode?, userDestinationCode?, userScheduledOut?,
              userScheduleZoneId?, userExpectedDurationMin?, userCruiseAltitude?,
              savedFlightId?, notificationProfileId, createdAt, archived)

SavedFlight(id, name,                         // "Mom's flight home" — first-class alias
             originalDesignator, designatorIata?, designatorIcao?,
             // original is required; normalized equivalents are optional
             recurrenceRule?,                  // v2; null in MVP
             notificationProfileId)

NotificationProfile(id, perEventToggles: Map<EventType, Bool>, quietHours?)

// OperationalDatabase: explicitly excluded from cloud and device-transfer backup.
// selectedInstanceId is a nullable logical link; restore/expiry clears and re-resolves it.
FlightInstance(id, canonicalKey,              // operating designator + airports + sched OUT
               designatorIata?, designatorIcao?, operatingDesignator,
               originCode, originCodeType, destinationCode, destinationCodeType,
               originalScheduledOut, expiresAt)

ProviderInstanceRef(flightInstanceId, provider, externalId, expiresAt)
                                               // adjunct ID, never the only local identity

FlightSnapshot(id, flightInstanceId, fetchedAt, statusProvider,
               status,                    // enum incl. Unknown
               depAirportCode, depCodeType, arrAirportCode, arrCodeType,
               depTerminal?, depGate?, depCheckInDesk?,
               arrTerminal?, arrGate?, baggageBelt?,
               aircraftType?, registration?, icao24Hex?, reportedCruiseAltitude?,
               codeshareOf?, expiresAt)

FlightMilestone(snapshotId, kind,              // OUT, OFF, ON, IN
                scheduledAt?, estimatedAt?, actualAt?)

FieldProvenance(snapshotId, field, provider, observedAt, certainty, expiresAt?)
                                               // REPORTED/DERIVED/PROJECTED/USER_ENTERED

PositionFix(flightInstanceId, at, lat, lon, pressureAltitude?, geometricAltitude?,
             groundSpeedKt?,
             trackDeg?, verticalRateFpm?, seenPosAgeSec, source, expiresAt)

RoutePoint(flightInstanceId, seq, overflightAt, lat, lon,
           solarTrueAltitudeDeg, lightBand,      // projected route; computed offline
           projectedAltitude?, altitudeCertainty?,
           forecastProvider?, forecastModel?, forecastIssuedAt?, forecastValidAt?,
           pressureLevelHpa?,
           pressureLevelGeopotentialM?, cruiseTempC?, cruiseCloudPct?,
           cruiseWindKt?, cruiseWindDirDeg?, surfaceWeatherCode?,
           surfacePrecipProbPct?, fetchedAt?, expiresAt?)
                                               // normalized, source-labeled forecast

TrackPolyline(flightInstanceId, points: encoded, source, refreshedAt, expiresAt)

// ReferenceDatabase: a separate read-only asset uses synthetic PKs and UNIQUE indexes on
// ICAO and IATA independently. A snapshot remains valid when a code is absent
// from this asset; allowed provider display text stays on the snapshot rather
// than copying restricted enrichment data into a new reference database.
Airport(id, icao?, iata?, name, city, country, lat, lon, tz) // bundled
Airline(id, icao?, iata?, name, alliance?, callsign?)        // bundled
AircraftTypeName(icaoType, marketingName)                    // bundled, separately licensed

EmittedEvent(flightInstanceId, eventType, eventFingerprint, emittedAt, expiresAt)
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
`EmittedEvent` contains only the minimum dedup fingerprint and expires after the shorter of
its operational relevance window and any applicable provider-derived-data TTL.

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

Route weather has a separate budget and never inherits status-provider allowance. If the M0
gate selects Open-Meteo, coalesce one multi-coordinate request at the 24 h, 3 h, and
gate-critical boundaries, plus meaningful schedule changes (for example ≥30 min), but skip
it while the applicable forecast is still fresh. Estimate the exact weighted query before
sending it: 10–20 locations with a modest surface set and one selected pressure level is
roughly 13–30 units through 14 forecast days under the current formula; more variables,
models, levels, or days increase that cost. Enforce every published time-window limit, not
only the daily figure. Request pressure-level variables only when the projected profile has
a credible altitude; otherwise omit those fields rather than spending quota on a nominal
level. MET Norway is never background-polled. Daylight bands are offline math (§9.4), but
recomputation remains bounded, cancellable, and benchmarked.

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
  list and false precision around provider estimates. Driven by the single shared
  `Heartbeat` flow (§6), never per-row timers.
- Thin phase progress bar; gate/terminal chip once known.
- **Tap opens the detail view.** Swipe to archive; long-press to edit alias/notifications.
- Sorted by next-event time.
- Add-flight flow accepts **batch input** by comma/newline, spaces inside a designator, and
  slash-separated equivalents such as `CCA861/CA861`; canonical dedup prevents duplicate
  rows. Existing multi-word aliases are resolved before tokenization.
- In limited mode, unresolved flights can take an optional origin, destination, departure
  local date/time, expected duration, and cruise altitude with an explicit flight-level/
  standard-pressure or geometric-MSL reference. This keeps countdown/route/light features
  and altitude-dependent ribbon fields useful. Every such value is visibly "user entered,"
  editable, and never promoted to provider data.
- `PullToRefreshBox` with a themed indicator (see §10); independent freshness labels avoid
  implying that a fresh position also refreshed status/weather (for example, "Status 3 m ·
  position 8 s · weather 20 m").
- Empty state: friendly onboarding — add a flight, pick sample flight to demo the UI.
- **Information hierarchy is explicit and degrades gracefully.** At `COMPACT` width or 200 %
  font scale, the row drops elements in a defined order: alias truncates first, then the
  gate chip moves to detail-only, then the progress bar — the countdown and status word are
  always last to go. The row must never wrap chaotically or horizontally scroll.
- **Status uses a bespoke glyph set, not platform emoji.** A small, consistent, two-tone
  line-icon family (✈ en route · 🛬 landed · ⚠ delayed · ✕ cancelled · ⏱ boarding) reads
  faster than text at a glance and matches how real departure boards signal phase. The set
  is theme-tinted via the §10.2 color tokens, never a stock emoji font (which varies across
  OEM densities and clashes with a refined palette).
- **All numeric displays use tabular figures** (`FontFeatureSettings.TNUM` / `"tnum"`), and
  the row sits on the §10.2 4 dp spacing rhythm. Without tabular figures, the countdown
  visibly jiggles as digits change width — a small but constant "amateur" tell.

### 9.2 Detail view — the flight dossier

Ordered by flight-day usefulness, informed by the competitor benchmark but validated with
Blipbird usability testing:

1. **Hero**: big countdown/ETA, status banner, origin→destination with local times +
   timezone labels ("14:10 CST · your time 08:10"). Instead of a literal (and on a phone,
   too-small-to-be-useful, too-busy-to-be-beautiful) map snippet in the hero, render a
   **stylized route diagram**: origin code → great-circle arc → destination code, the plane's
   fractional position along the arc, and the §9.4 daylight gradient painted along it. Uses
   zero map tiles, is glanceable, and reads as *designed* rather than generic. Tapping it
   expands to the full interactive §11 map. The full map still exists; the hero just stops
   trying to be one.
2. **Key facts grid**: dep terminal/gate/check-in desk, arr terminal/gate/baggage belt,
   aircraft type + registration, duration, distance. Photos remain out until a source grants
   documented per-image display rights. **The grid adapts to phase, honoring §1's
   "the single most relevant fact is the biggest thing on screen":** before departure the
   **departure gate** dominates and the bag belt is suppressed; on approach/after landing the
   **baggage belt** dominates and the departure gate demotes. A static 2×3 block of equal
   cells violates the plan's own north-star.
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
   On a 360 dp screen the three-column schedule/estimate/actual table reads as a wall of
   numbers; render it as a **vertical timeline with a "now" marker** (each row: event glyph,
   label, single best time with superseded values struck through inline) — the airport-board
   three-column view is a secondary, opt-in layout for avgeeks.
4. **Inbound aircraft** *(v2, source-gated)*: "Your plane is arriving from Shanghai as
   CA1858, lands 12:40." This needs a provider licensed to expose the assigned aircraft's
   current prior leg; a registration alone is not enough, and AeroAPI Personal historical
   access must not be assumed. A high-confidence late inbound may feed a clearly derived
   delay heads-up.
5. **Flight ribbon:** projected daylight and source-labeled weather along the route (§9.4).
6. **Weather:** decoded, timestamped METAR at both airports; TAF prevailing conditions plus
   every `TEMPO`/`PROB` overlay valid at ETA; time/altitude-aware route samples when the M0
   provider gate closes; and applicable worldwide SIGMETs from §4.4. Never summarize absent
   data as "clear."
7. **Airport context**: full name/city/country, current local time and time-zone difference,
   terminal/gate, weather, and official-site/map intents when a trusted URL exists. Do not
   promise security wait times, lounges, or amenities without a selected current source.
8. **Airport health chip** *(v2, source-gated)*: "PEK: departures averaging +40 min right now."
9. **About the airline**: name, alliance, radio callsign ("AIR CHINA"), and trusted contact links.
10. **Share / Pickup mode**: read-only big-type card (ETA · terminal · progress) exportable
   as an image or serverless deep link containing only designator/date/leg. Aliases, API
   keys, provider IDs, and history are never encoded. **Pickup Mode is a first-class screen,
   not just a shareable card:** the person meeting the flight gets a full-screen, always-on,
   brightness-pinned display — huge ETA/countdown, terminal + belt, "lands in 47 min," route
   diagram — basically Google-Maps-navigation for the arrivals hall. This is *the* flight-day
   use case for non-flyers; long-press the hero countdown to enter it, and it survives screen
   timeout via a wakelock while foregrounded.

**Loading states are skeletons, never spinners.** Every async surface (list first paint,
detail sections, timeline) renders content-shaped placeholder skeletons, not a centered
`CircularProgressIndicator`. Skeletons read as "premium and fast"; a spinner over an empty
screen reads as "mid Android." On error, the skeleton stays with an inline retry affordance
so the layout never collapses.

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

### 9.4 The flight ribbon — daylight & weather along the route

A horizontal strip represents the whole projected flight (x = elapsed time), answering at
a glance *"how much of my red-eye is actually dark?"* while keeping route and forecast
uncertainty visible:

```
 PEK ─────────────────────────────────────────────── GVA
 │ ☀ daylight  │▒ dusk ▒│   ★ night   │▒ dawn ▒│  ☀   │
 │  ☁ 80%   🌧 showers  │  ✦ clear    │ ☁ 40%  │  ⛅   │
 14:10        17:52 🌅                 05:41 🌅   06:30
```

**Distilling the ribbon into one sentence (the relatable half).** Most travelers do not
want a ribbon; they want an answer. Derive a single headline callout from the same
`ProjectedRouteProfile` — *"You'll see the sunrise at 05:41, about 1 h 19 min before
landing"* (or *"no sunrise on this daytime flight"*) — and show it above/beside the ribbon.
When the §9.4 window-side model is confident, append *"projected on the left/right side."*
This is the ribbon's mass-market face; the ribbon itself remains for avgeeks.

**Ribbon axis — a deliberate decision, not a default.** The strip is *time*-axis (x =
elapsed time), which matches how daylight/weather actually unfold and how the user
experiences the flight. But users intuitively read it as *space* ("where am I over the
ocean right now?"), and the §11 map is spatial. Reconcile by: (1) painting the plane's
*current* position on the ribbon at its time-axis location, (2) tapping a ribbon segment
recenters the §11 map on the corresponding great-circle point, and (3) offering a
distance-axis toggle for users who think spatially. Acknowledge the tension rather than
hide it.

**Time-travel scrubber.** Drag a finger along the ribbon to scrub the projected
weather/light at any point in the flight; the §11 plane marker ghosts to that projected
position and the headline callout updates. This makes the ribbon interactive rather than
just readable, and reuses the same offline `DaylightEngine` math.

**Projection inputs and provenance:** build one `ProjectedRouteProfile` before rendering or
fetching weather. For time, select each milestone's best reported value (actual, then
estimated, then scheduled) and prefer a coherent OFF→ON interval. If that pair is absent,
use OUT→IN as a visibly labeled gate-to-gate approximation; limited mode may use a
user-entered departure instant plus positive expected duration. Observed fixes anchor the
flown portion. Different certainty levels within one pair are valid (for example actual OFF
plus estimated ON) and remain visible; never combine OFF with IN, OUT with ON, or endpoints
from different selected instances. Non-positive, implausibly long, or otherwise invalid
intervals produce an unavailable projection rather than invented timing, and the UI exposes
the chosen basis.

Altitude has independent provenance **and datum**. Use observed fixes only for the flown
portion; future cruise samples require a retention-cleared reported cruise altitude or the
optional user-entered value. A standard-pressure/flight-level value maps to weather pressure
surfaces through a documented standard-atmosphere conversion; geometric MSL can instead be
bracketed against returned geopotential height. Never compare raw `alt_baro` or ellipsoid
height directly with geopotential height. Cabin-horizon correction needs geometric height:
prefer geometric MSL, use only a visibly approximate documented conversion from standard-
pressure altitude, and suppress it when an ellipsoid/unknown datum cannot be converted.
There is no silent 11 km default and no invented climb/descent profile. Without credible
future altitude, standard geometric daylight bands still work, but omit the cabin-visible
crossing adjustment and pressure-level/cruise forecast fields.

**Daylight band (pure math, fully offline — `DaylightEngine`):**

- Interpolate a spherical great-circle **guide** across the valid profile; future points are
  never called a filed route. Target one-minute steps while bounding the job to 2,048
  samples with an adaptive step for unusually long valid durations. Handle coincident and
  near-antipodal endpoints explicitly. Benchmark the complete workload on the named low-end
  test device before setting a latency budget or allowing eager recomputation.
- At each `(lat, lon, overflight Instant)`, use commons-suncalc 3.11
  `getTrueAltitude()` so the geometric center-of-Sun result matches the USNO thresholds:
  conventional sunrise/sunset at −0.8333°, civil at −6°, nautical at −12°, and astronomical
  at −18°. The geometric 0° terminator is a separate map boundary. Render a continuous
  gradient while retaining these semantic bands for labels and accessibility.
- A cabin-visible sunrise/sunset marker is a separate approximate model. At 11 km, mean-
  Earth geometry gives about 3.36° of horizon dip; adding an explicit refraction and solar-
  radius model places the true Sun-center threshold near −4.2°. The time shift is not a
  universal 12–20 minutes: latitude, altitude, atmosphere, aircraft direction, and speed all
  matter. Show projected event times to the minute. Bisection may narrow a sampled bracket
  below one second, but that is numerical localization, not one-second observational
  accuracy.
- Find and classify every crossing rather than stopping after the first; also test local
  extrema so tangent contacts, polar day/night, reversed crossings, and multiple sunrises or
  sunsets do not silently disappear.
- A window-side callout is optional. Compare north-based solar azimuth with a route tangent
  or reliable true track, suppress the result around ahead/behind uncertainty boundaries,
  and say “projected left/right based on the route.” ADS-B ground track is not guaranteed
  fuselage heading, so the app never promises which window to book.

**Weather band (release-gated `RouteWeatherProvider`, §4.4):** sample roughly 10–20 route
points at their projected passage hours. If Open-Meteo passes M0, one documented
multi-coordinate request can supply selected/bracketing pressure-level temperature,
RH-derived cloud, wind, and geopotential height plus separately labeled surface/column
fields. Select pressure surfaces using the profile's reference-aware conversion and record
that transformation with the result. Head/tailwind is explicitly derived against the
projected route bearing. Tapping a segment shows the actual sampled condition/cloud/
wind values, provider, forecast-valid time, expected passage time, fetch time, position, and
pressure/geopotential context. Show a model or issue/run time only when an explicit source
supplies auditable values; never relabel API processing/fetch time as forecast issuance.
Endpoint observations remain timestamped METAR; event-time expectations come from TAF or a
labeled forecast.

Degradation states stay distinct while the offline daylight band remains: a confirmed
forecast-horizon miss says “available closer to departure”; an omitted/unresolved provider
says route forecasting is not supported by this build; missing credible altitude withholds
only altitude-dependent cruise fields; and a transient failure shows a rights-permitted
stale result with age or “temporarily unavailable.” Do not substitute MET Norway surface
output as cruise weather or turn absent advisories into “clear.” Every Open-Meteo-derived
ribbon/card carries its required linked attribution beside the display.

The ribbon appears in detail item 5 and, after readability/performance testing, may condense
to the daylight gradient behind list/widget progress. The same projected light bands tint
the route guide on the map (§11). The ribbon is inherently visual; it must ship with a text
alternative (ordered, TalkBack-readable summary of the bands and events) per the §18
accessibility requirement — never the only representation of its data.

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

### 10.1 App icon & brand

The canonical app-icon artwork is **`media-sources/icon.png`** (1254×1254 RGB, provided by
the project owner): a white bird in flight over a blue radar sweep with concentric range
rings and blips — the "blip" and the "bird." All launcher/store assets derive from this
source at build/design time; the 1.5 MB master itself is never packaged into the APK.

M0 asset-generation tasks (standard Android icon pipeline):

- **Adaptive icon** (required since Android 8): re-layer the artwork into a foreground
  layer (bird, transparent background, sized to the 66×66 dp safe zone of the 108 dp
  canvas so no OEM mask clips the wings) and a background layer (the blue radar-gradient
  with rings). If clean layer separation from the flat PNG proves too lossy, recreate the
  two layers as vector drawables matching the artwork — visually identical, and it makes
  the radar rings crisp at every density.
- **Monochrome layer** for Android 13+ themed icons (bird-with-ring silhouette, single
  color) so the icon participates in Material You theming.
- **Legacy mipmaps** (round + square, all densities) generated from the same layers.
- **Play Store 512×512** listing asset and feature-graphic crops from the master.
- The bird mark also seeds in-app brand moments: the empty-state illustration, the
  about-screen logo, and the notification small icon (flat white silhouette, as Android
  requires alpha-only small icons).

Accent colors sampled from the icon (radar cyan ≈ `#19D3F3`-family, deep blue field)
inform the Daylight theme's seed palette so launcher icon and app UI feel related.

### 10.2 Design tokens & premium motion

"Themes" is more than recoloring. A high-value app tokens the *whole* design system and
every theme maps to the same token set, so swapping a theme changes the feel coherently
rather than just the hue. Beyond the existing `ExtendedColors`, Blipbird defines:

- **`BlipbirdTypography`** — a fixed type scale (display / headline / title / body / label
  with weight and tracking), all numeric roles opted into **tabular figures**. The hero
  countdown is a dedicated *display-numeral* role — variable-weight, optically sized, the
  single biggest element on the detail screen — designed explicitly per theme the way a clock
  face is designed, not just "big bold text."
- **`BlipbirdMotion`** — durations, easings, and spring specs as *data*, not ad-hoc
  `tween(300)` calls. Default to **spring-based, critically-damped motion** (the Material 3
  Expressive feel) even on the 1.4 line; reserve linear/eased tweens for deterministic
  progress. Each transition (row tap → detail, theme switch, status flip, sheet present) has
  a named spec. All motion respects the system animator-duration scale, the system
  reduce-motion intent, *and* the in-app reduce-motion toggle (§18 accessibility).
- **`BlipbirdShapes`** — a corner-radius scale (e.g., 4 / 8 / 16 / 28 dp) and a 4 dp
  spacing/whitespace rhythm applied consistently to cards, chips, and padding so the layout
  reads as deliberately gridded, not default-M3.
- **`BlipbirdElevation`** — a tonal/overlay elevation system rather than generic drop
  shadows, paired with edge-to-edge content and translucent layered app bars on API 31+
  (the standard "premium Android" tell). Cockpit uses true-near-black overlays carefully
  (avoid pure `#000000`, which smears on penTile panels at low brightness — use ~`#05050A`).
- **Iconography weight/family per theme** — Cockpit draws thin technical strokes, Daylight a
  friendlier weight, High Contrast the heaviest. A single `Icons.Default` set across themes
  reads cheap.

**Premium-Android baseline requirements** that every theme shares: edge-to-edge with
transparent system bars; predictive-back gesture support (detail → list reveals during the
swipe); translucent, blur-backed floating layers; per-theme MapLibre style precedence
(theme-map > system-dark > system-light). The pull-to-refresh indicator, attribution chip,
and weather/celestial iconography are all spec'd per theme (size, threshold, success/failure
states), never left as "theme-aware" hand-waving. A **theme schedule** (auto-switch to
Cockpit near local sunset, Daylight near sunrise, optionally tied to the departure airport's
daylight engine) is a v1 delighter, not v2.

Without this token system, "five themes" collapses to "five recolors" and the app reads as
themed Android rather than a designed product.

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
  4. **Plane marker**: `SymbolLayer` icon rotated to **ground track** (what ADS-B reports),
     never labeled "heading" — fuselage heading is not guaranteed (§9.4). Animated
     interpolation from the previously rendered fix to a newly received fix over a
     critically-damped spring (not a linear tween), tweened in screen space after camera
     projection; continuous motion between unknown future fixes would be extrapolation. Any
     optional velocity projection is time-bounded and uses the "estimated" ghost style. Show
     "last seen X min ago" as soon as `seen_pos` goes stale. A short, fading **contrail**
     trails the marker for the last few minutes — pure cosmetic, reuses the flown-track
     geometry, reads as "premium."
  5. Origin/destination pins with gate labels.
  6. **Projected light along the route:** tint route-guide segments by the §9.4 light band
     at passage time and mark approximate cabin-visible crossings. Labels remain projected
     because the guide is not a flight plan. A global geometric-terminator/twilight overlay
     ships only after a dedicated spherical small-circle prototype handles zero/two
     intersections, poles, winding, world wrapping, and RFC 7946 antimeridian splitting;
     Leaflet.Terminator's 0° one-latitude formula cannot simply be generalized to −6/−12/−18°.
     The overlay carries its own explicit instant (current map time by default, or a
     scrubber-selected time) and is visually separate from route segments colored at their
     different projected passage times.
  7. *(Optional layer, later)* normalized worldwide SIGMET polygons from both AWC feeds
     (§4.4), validity- and altitude-filtered.
- **Camera:** auto-follow with manual override; "recenter" FAB; detail-hero snippet is a
  non-interactive lite view into the same composable.
- Altitude/speed/track readout strip under the map (avgeek candy, hidden until data exists);
  tappable to expand into a derived avgeek panel (Mach from ground-speed + altitude, outside
  air temp, headwind component).
- **Gesture vocabulary** is explicit: pan, pinch-zoom, double-tap zoom-in, two-finger tilt,
  and two-finger rotate (with a **north-up / track-up toggle** — essential for avgeeks, with
  north-up as the accessible default so the map never disorients casual users). Predictive
  back from the full-screen map returns to detail.
- **Tap-to-reverse-geocode (offline).** Long-press anywhere on the map (or a ribbon segment)
  resolves the nearest bundled city/landmark — fully offline, answers "what is that down
  there?" on a window stare. Cheap, on-brand, no extra data source.

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
- **Cabin sunrise/sunset alarm (delighter).** Combine the §9.4 `DaylightEngine` with exact
  alarms: opt in to "wake me ~20 min before the cabin sunrise" (or sunset) on an overnight
  flight. Fires only when the projected crossing confidence margin passes and the flight is
  actually overnight; reconciled like any other reminder (rescheduled/cancelled on profile or
  milestone change, rebuilt on reboot). A genuinely novel integration nobody else ships.
- A desired-alarm reconciler uses one stable `PendingIntent` identity per flight/event. It
  replaces or cancels reminders whenever their source milestone changes, the flight becomes
  cancelled/diverted/terminal, the event/profile is disabled, or the flight is archived,
  deleted, or retention-pruned. The receiver rechecks the latest local flight/profile state
  before posting, so an old alarm cannot emit a reminder that is no longer desired.
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
Close or explicitly fail every §4.6 gate needed by M1 and the M0 route-weather decision;
record owners/deadlines and safe omissions for later position, metadata, and fallback
candidates. Decide the app-code license; select and spike a rights-cleared time/altitude-aware
en-route weather source; record API cost/coverage on the representative-flight matrix. In
parallel: project scaffolding, version catalog, CI (build, unit tests, and lint), Hilt graph,
Nav3/adaptive shell, Daylight + Cockpit theme engine, reproducible bundled reference-data
pipeline, add-flight parser/normalizer using synthetic providers, and the app-icon asset
pipeline from `media-sources/icon.png` (§10.1: adaptive foreground/background layers,
monochrome themed-icon layer, legacy mipmaps, notification silhouette).
**Accessibility is designed in from M0, not audited at M4** (§18): the type scale, 48 dp
touch-target scaffold, Compose `semantics` conventions, and reduce-motion wiring land with
the first screen so later milestones verify rather than retrofit.

**Exit:** provider/asset decision records exist; `CA861`, `CA 861`, and `CCA861/CA861`
deduplicate correctly; ambiguous fixture flights require date/leg selection; the launcher
shows the adaptive Blipbird icon on a masked and a themed launcher; no restricted
runtime response is persisted.

### M1 — Status MVP (week 3–5)
`FlightStatusProvider` with the first **release-cleared** implementation, BYO-key onboarding
including no-backup encrypted credential storage, Room snapshot/milestone/provenance model,
alias entry + display, list view with real status/countdown/progress, detail hero + key-facts
grid + event timeline, pull-to-refresh, adaptive coordinator v1, per-plane freshness, spend
soft stop, executable provider-retention policies/pruner, and disruption semantics.
**Exit:** track a real flight end-to-end on status alone; unit tests for phase machine,
parser, instance identity, planner, derived times, DST/date-line cases, quota accounting, and
expiry/cascade behavior for the selected provider.

### M2 — Live map + provider resilience (week 6–8)
Release-cleared position provider(s), identity→hex confidence rules, client-accumulated and
downsampled track, MapLibre screen (flown track/route guide, bounded marker tween,
staleness ghost), detail-hero map, lifecycle-aware foreground polling, visible OpenFreeMap
attribution, `DaylightEngine`, the daylight half of the ribbon, crossing markers, and a
light-band-tinted route guide. Prototype global terminator/twilight polygons, but ship that
overlay only if its spherical-geometry/rendering gate passes. Add a second status provider
only if its approval gate closes; prepare reproducible-build and F-Droid metadata, but do
not file a premature RFP for an unreleased app.
**Exit:** watch a live flight cross the map smoothly; airplane-mode replay renders cached
track **when** local track retention cleared (otherwise record the intentional omission);
wrong/colliding callsigns are suppressed; if a second status provider cleared, prove failover
with fault injection, otherwise do not claim it. A known red-eye fixture has the expected
projected light sequence; polar, date-line, multiple-crossing, and tangent-contact cases pass.

### M3 — Notifications + weather (week 9–11)
Channels, `POST_NOTIFICATIONS` flow, anchor workers (snapshot-reuse rule), snapshot-diff
event detection, notification planner + persisted `EmittedEvent` ledger, per-flight
profiles, desired-alarm reconciliation, reboot/revocation recovery, optional exact alarms for
local reminders only, and the constrained offline projection branch. Ship METAR/TAF-at-event
cards, route SIGMET intersection, and the weather half of the ribbon through the M0-selected,
release-cleared time/altitude-aware `RouteWeatherProvider`.
If no provider clears M0, this slice and the current beta definition remain blocked until an
explicit scope decision; MET Norway surface output is not the fallback.
**Exit:** notification lifecycle observed on a real tracked flight from a ground observer's
phone, including induced offline/Doze intervals with no timing guarantee claimed; stale
data never emits a synthetic operational event, and schedule/profile changes reconcile old
alarms. Weather states remain distinct, dated, and attributed beside the display; missing
altitude suppresses pressure-level fields, and surface fields are never presented as cruise
conditions.

### M4 — Polish & release (week 12–15)
Launch themes locked to **three** (Daylight incl. Dynamic, Cockpit, High Contrast), haptics
and themed pull-to-refresh, **"Next flight" Glance widget** (cheap once the state layer
exists), accessibility **verification pass** (TalkBack focus order, contrast AA/AAA, touch
targets, ribbon text alternative, 200 % font scale — confirming the a11y designed in from
M0; nothing structural retrofit here), quota ledger UI,
About/attribution + privacy screens, user-authored Export/Import, erase and retention
controls, Play privacy policy/Data Safety/listing, F-Droid metadata/submission for a tagged
source release, and a `:benchmark` module with baseline profiles.
**Exit:** the release definition above passes on phone + large-screen emulator, provider
permissions are archived, export/import/erase/retention acceptance tests pass, a real
flight-day checklist passes, and the tagged public beta is reproducible without embedded
secrets.

### v2 (post-launch)
**Solari split-flap theme + flip-animation engine · Skyfade theme** · API 36 ProgressStyle
and API 36.1 promoted Live Updates · "In flight" Glance widget · inbound-aircraft section + late
warning · full offline pre-computed in-flight mode · recurring alias date rules · flight
log + Passport stats (flights, km, hours, airports, airlines, aircraft-type badges,
shareable year card — all on-device) · airport-health chip · pickup/share mode ·
multi-flight trip grouping (absorbs multi-leg tracking).

### Delighters (ongoing)
Projected sunrise-side callouts only when the route/heading confidence margin passes ·
landing confetti · route on-time forecast only from retention-cleared local snapshots · AR
"point at the sky" long-shot · **"Will I see the sunrise/sunset?" headline** distilled from
the ribbon · **Pickup Mode** full-screen always-on arrivals-hall display · **route-diagram
hero** replacing the too-small map snippet · **time-travel ribbon scrubber** · **cabin
sunrise/sunset alarm** · **tap-to-reverse-geocode** "what is that down there?" · **fading
contrail** on the plane marker · **engine-start haptic** when ADS-B ground speed crosses
takeoff roll · **copy-ETA / share-ETA** long-press on the hero · **connection-risk
indicator** derived from same-day tracked flights vs bundled minimum-connect times ·
**theme schedule** (auto Cockpit at sunset) · **constellation overlay** on night segments
(offline star math, v2) · **lifetime/year stats card** (on-device Passport, v2).

---

## 15. Testing strategy

- **Unit (JVM, the bulk):** designator parser (known carrier prefixes, spaces, slash-pair
  dedup, suffixes, aliases, malformed batches), instance/date selection (DST overlap/gap,
  date line, same-day duplicate, no seven-day scan), milestone/phase transition tables,
  `NotificationPlanner` + event fingerprinting, quota/spend stops, adaptive scheduler,
  METAR decoder, TAF prevailing/inheritance/overlap semantics, dual-feed route/SIGMET
  normalization and intersection, unit conversion, and Open-Meteo multi-coordinate/
  pressure-level mapping. Test `ProjectedRouteProfile` milestone precedence, OUT/IN and
  user-entered fallbacks, valid mixed-certainty pairs, cross-family/instance rejection,
  invalid intervals, observed/future boundaries, altitude provenance/reference conversion,
  raw `alt_baro` isolation, and unknown/no-altitude suppression. Test `DaylightEngine`
  true-vs-apparent altitude, exact USNO band edges, conventional-vs-cruise-visible thresholds,
  all crossing directions, tangent contacts, polar day/night, antimeridian, coincident/near-
  antipodal routes, bounded sampling, and low-confidence window-side suppression against
  independently checked fixtures.
- **Provider contract tests:** synthetic or provider-approved redacted fixtures cover happy
  path, missing fields, cancellation/diversion, codeshare/multi-leg, polymorphic ADS-B
  values, empty results, 401/403/429/5xx, and pagination. Do not commit captured payloads
  merely because they are useful. Diff public OpenAPI schemas in CI; run low-frequency live
  smoke tests only with provider permission and private BYO secrets, never by anonymously
  hammering free APIs from shared CI addresses. Route-weather fixtures cover shorter-than-
  requested horizons, missing variables, standard-pressure and geometric-MSL level-selection
  paths, geopotential bracketing, weighted-cost stops, independent surface/cruise provenance,
  actual-value drill-down, and absent optional model/issue metadata. If MET Norway is
  approved, test coordinate truncation, identifying User-Agent, and exact cache revalidation
  behavior without live CI traffic.
- **Repository/integration:** Room in-memory + fake providers; request coalescing and
  provider-wide rate limiting under concurrent list/map/workers; allowed failover under
  injected faults; migration, process-death, reboot, alarm-revocation, desired-alarm
  replace/cancel/receiver-recheck, and snapshot-diff tests. Exercise TTL expiry and cascading
  cleanup for provider IDs, snapshots, route points, tracks, dedup events, alarms, and cache;
  archive/delete and in-app erase must leave no operational orphan. Expiring an instance
  clears its cross-database selection before deletion, and the startup integrity pass repairs
  an injected dangling link. Exercise Android backup/restore and inspect the archive to prove
  operational DB, credentials, and reference assets are absent; restored user intent
  re-resolves cleanly. Export/Import includes only its
  documented user-authored allowlist. Weather layers fail independently; no low-confidence
  aircraft reaches UI state.
- **UI:** Compose semantics for list/detail states (loading, degraded, stale, disrupted);
  screenshots per theme × light/dark with RTL, 200% font scale, reduce-motion, color-vision
  checks, expanded two-pane layout, ribbon no-data states, nearby linked weather credit, and
  accessible non-color band labels. Instrument MapLibre to verify attribution, lifecycle
  stop/start, and route-segment rendering rather than snapshot-testing live tiles. The
  optional terminator overlay has separate equinox/solstice, pole, winding, world-wrap, and
  RFC 7946 antimeridian geometry tests.
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
- **Code/dependency notices:** preserve commons-suncalc's Apache-2.0 license in the shipped
  notices. If implementation code is copied or adapted from a route or terminator project,
  record its exact revision and license; citing a formula is not a substitute for complying
  with copied-code terms. The terminator overlay remains original, separately tested
  spherical/GeoJSON work rather than an assumed feature of the solar library.
- **Runtime attribution:** keep OpenFreeMap/OpenMapTiles/OpenStreetMap attribution visible
  on every map. Associate displayed adsb.lol data with an ODbL notice. adsb.fi attribution
  is mandatory if that source is ever approved; airplanes.live and OpenSky use the exact
  attribution/citation their approval requires. Credit aviationweather.gov. If Open-Meteo is
  selected, place its linked credit beside every displayed weather location and put the CC
  BY 4.0 link/change notice in the legal view. Do the equivalent for MET Norway if approved.
  An About screen supplements, but does not replace, attribution required beside the work.
- **Red lines:** no restricted raw payloads or route databases in Room, exports, analytics,
  or test fixtures; no runtime route/enrichment call or display without explicit rights; no
  provider-derived punctuality corpus without permission; no logo CDN, scraped logo pack, or
  aircraft photo without documented rights; no provider enabled in a release solely because
  the user supplies a key. Archive the §4.6 decisions.
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
| Uncaught exception leaks PII or silently kills debuggability | Medium | A `CrashRouter` installed via `Thread.setDefaultUncaughtExceptionHandler` writes a **local, aggressively redacted** crash record (no keys, aliases, payloads, exact itinerary, or stable device IDs — the same redaction rules as the support export) and never auto-uploads anything. The user can share it from the diagnostics screen. This is decided at M0 so logging is wired correctly once; "add crash reporting later" leaks into every file. No backend, no analytics SDK, no third-party crash sink — the privacy stance is not negotiable for a crash feature |

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
- **Accessibility is a cross-cutting requirement, not an M4 audit.** Travelers use this app
  stressed, in bright sunlight, one-handed, sometimes with assistive tech. It is designed in
  from M0 and verified every milestone, not retrofitted at the end:
  - **Structure:** minimum 48 dp touch targets; full TalkBack focus order with semantic
    `Role`/`StateDescription`/`LiveMode` on every interactive node; phase changes announced
    via `LiveRegion` (polite) so a status flip is spoken; explicit `contentDescription` for
    the map, ribbon, progress bar, and any purely decorative element.
  - **Vision:** WCAG-AA contrast minimum (AAA where the type is small or the user picked High
    Contrast); full support for 200 % font scale without horizontal scroll or overlap; support
    high-contrast-text and the system reduce-motion intent (not just animator-duration scale);
    never encode meaning by color alone (the §9 status word + glyph pattern).
  - **Motor/cognitive:** generous swipe regions; confirm-before-archive with an Undo snackbar
    (§9.1); plain-language summaries ahead of raw METAR; consistent back behavior.
  - **The ribbon has a text alternative.** The §9.4 ribbon is information-dense visual art;
    for users who cannot perceive it, the same data renders as an ordered text/Compose
    `semantics` list ("Departure 14:10 daylight; sunset at 17:52 about 3 h 42 min in; night
    until sunrise at 05:41 about 1 h 19 min before landing; arrival 06:30 daylight") that
    TalkBack reads. The visual ribbon is never the *only* representation of its data.
- **Performance budgets.** Targets enforced in CI: cold start to first frame < 1.5 s on a
  named mid-range test device (Baseline Profile + androidx Startup), map marker tween with
  p95 frame time within a 60 Hz frame and no frozen frames, a measured AAB budget set after
  the reference-data spike (no invented ~1 MB assumption), bounded track-database growth,
  and `macrobenchmark` regressions gating release. Generated monograms require no runtime
  logo downloads. **Jank and recomposition budgets** (added): list fling at p95 frame time ≤
  16 ms / p99 ≤ 1 frozen frame over a 50-flight scroll; detail first-content paint < 500 ms;
  ribbon first-draw < 150 ms on the named low-end device with incremental recompute on
  milestone change < 30 ms, off the UI thread and cancellable; Compose recomposition counts
  asserted in UI tests (rows must not recompose when an unrelated `State` changes — the
  classic "mid Android app" tell). Baseline Profiles are generated from M1 onward (as soon as
  there is a runnable app), not deferred to M4, so measurements through M2/M3 are on a
  profiled path.
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
| Metadata candidate (approval-gated) | `GET api.adsbdb.com/v0/callsign/{cs}` | none | source terms restrict copying/publication/database use; disabled pending distributed polling/display/retention permission |
| Metadata fallback candidate (approval-gated) | `GET hexdb.io/api/v1/route/icao/{cs}` | none | 1,000 / 5 min; display/reuse/persistence rights unclear, so disabled |
| METAR/TAF | `GET aviationweather.gov/api/data/metar?ids={icao}&format=json` (and TAF endpoint) | none (custom UA) | 100/min maximum; do not consume an endpoint more than once/min/thread |
| Route SIGMETs | `GET …/isigmet?format=geojson` + `GET …/airsigmet?format=geojson` | none (custom UA) | international + CONUS domestic feeds; normalize/deduplicate and cache by validity/update time |
| En-route forecast candidate | `GET api.open-meteo.com/v1/forecast` with documented latitude/longitude lists and selected/bracketing pressure fields | no key; hosted free endpoint only while use is genuinely noncommercial | weighted: fewer than 600/min, 5,000/h, 10,000/d, 300,000/mo under current terms; up to 16 forecast days subject to model/variable availability |
| Surface route-context candidate | `GET api.met.no/weatherapi/locationforecast/2.0/compact?lat={lat}&lon={lon}` | identifying app/contact User-Agent | one point/request; truncate coordinates to ≤4 decimals; cache until `Expires`, no unused-app fetches; >20 req/s across all installs needs agreement |
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
  and [hexdb](https://hexdb.io/) (no published display/reuse/persistence license found)
- Aviation Weather Center [Data API products, limits, and OpenAPI](https://aviationweather.gov/data/api/)
  and the [NWS disclaimer](https://www.weather.gov/disclaimer)
- Open-Meteo [hosted-service terms](https://open-meteo.com/en/terms),
  [pricing and weighted-call calculator](https://open-meteo.com/en/pricing#faq),
  [multi-coordinate/pressure-level/forecast documentation](https://open-meteo.com/en/docs),
  and [data license and attribution placement](https://open-meteo.com/en/licence)
- Open-Meteo server [current location-limit default](https://github.com/open-meteo/open-meteo/blob/acfe608b825da1a8b42a755297eb61121986e9da/Sources/App/configure.swift),
  [current query-weight implementation](https://github.com/open-meteo/open-meteo/blob/acfe608b825da1a8b42a755297eb61121986e9da/Sources/App/Helper/Writer/ForecastApiResult.swift),
  [AGPL license](https://github.com/open-meteo/open-meteo/blob/acfe608b825da1a8b42a755297eb61121986e9da/LICENSE),
  and [self-hosting guide](https://github.com/open-meteo/open-meteo/blob/acfe608b825da1a8b42a755297eb61121986e9da/docs/getting-started.md)
- MET Norway [terms](https://api.met.no/doc/TermsOfService),
  [Locationforecast documentation](https://api.met.no/weatherapi/locationforecast/2.0/documentation),
  [data model/coverage](https://api.met.no/doc/locationforecast/datamodel),
  [caching guide](https://api.met.no/doc/locationforecast/HowTO),
  [OpenAPI schema](https://api.met.no/weatherapi/locationforecast/2.0/swagger), and
  [license policy](https://api.met.no/doc/License)
- commons-suncalc [documentation](https://shredzone.org/maven/commons-suncalc/),
  [solar-position API](https://shredzone.org/maven/commons-suncalc/apidocs/org/shredzone/commons/suncalc/SunPosition.html),
  [twilight behavior](https://shredzone.org/maven/commons-suncalc/usage.html#twilight), and
  [v3.11 Apache-2.0 license](https://github.com/shred/commons-suncalc/blob/v3.11/LICENSE-APL.txt)
- US Naval Observatory [rise/set and twilight definitions](https://aa.usno.navy.mil/faq/RST_defs)
- Movable Type [spherical intermediate-point formula](https://www.movable-type.co.uk/scripts/latlong.html#intermediate-point),
  Ed Williams [aviation formulary](https://www.edwilliams.org/avform.htm#Intermediate),
  Leaflet.Terminator [source](https://github.com/joergdietrich/Leaflet.Terminator/blob/master/index.js),
  and RFC 7946 [antimeridian guidance](https://www.rfc-editor.org/rfc/rfc7946#section-3.1.9)

### Open decisions

| Decision | Due | Safe outcome if unresolved |
|---|---|---|
| AeroDataBox distributed-client, normalized retention, history, and conditional-billing permission | before M1 | provider omitted; limited/synthetic mode remains |
| FlightAware Personal BYO use in a public client, retention, and no-surprise-spend controls | before adding fallback | provider omitted |
| adsb.lol local accumulated-track ODbL treatment | before M2 | foreground position only, no retained track |
| airplanes.live, adsb.fi, and OpenSky operational/distributed-app permission | before enabling each | implementation excluded from release build |
| adsbdb/hexdb distributed polling, route/enrichment display, and exact retention | before enabling either | omit runtime metadata; use bundled, user-entered, or status-cleared endpoints |
| Open-Meteo hosted-use eligibility, adjacent attribution, pressure/geopotential mapping, normalized retention, and exact weighted-query budget | M0 | current beta is blocked unless an explicit decision revises its release definition |
| MET Norway direct mobile use and aggregate all-installation traffic | before enabling surface fallback | omit it; never substitute surface data for cruise weather |
| Projected time/altitude profile, crossing model, and optional terminator GeoJSON prototype | before M2/M3 dependent exits | standard daylight only where valid; omit unproven cruise marker/weather/side-callout/overlay pieces |
| App-code license (current Unlicense vs Apache-2.0) | M0, before outside contributions | retain current license and document decision |
