# Blipbird 🐦📡

[![CI](https://github.com/L-K-M/Blipbird/actions/workflows/ci.yml/badge.svg)](https://github.com/L-K-M/Blipbird/actions/workflows/ci.yml)

**Latest release:** v<!-- version -->0.1.0<!-- /version --> · [Download](https://github.com/L-K-M/Blipbird/releases/latest)

**The calm, beautiful flight-day companion for Android.** Enter one or more flight
numbers (`CA861`, `CCA861`, `CCA861/CA861`, or paste a whole list) with an optional
date and a friendly name, and Blipbird shows a departure-board list of your
flights, a rich detail dossier with a live-updating route map, a **flight ribbon**
visualizing daylight/darkness and weather along the whole route, and sends
notifications for the moments that matter — delays, gate changes, departure,
landing, cancellation.

Open-source, ad-free, no accounts, no analytics. All your data stays on the device.

## Features (v0.1)

- **Departure-board list** — one line per flight: status word with semantic color,
  the one phase-relevant time (countdown → lands-in → landed + belt), thin progress
  bar, gate/terminal chips, pull-to-refresh, batch add, swipe actions.
- **Flight dossier** — hero with big countdown and airport-local times,
  terminal/gate/check-in/baggage facts grid, scheduled → estimated → actual
  timeline (superseded estimates struck through; Blipbird-derived rows honestly
  marked `~`), airline card, METAR weather decoded to plain language with the raw
  string one tap away.
- **Flight ribbon** — the whole flight as a horizontal strip: continuous
  day/twilight/night gradient computed offline from solar geometry (USNO angles,
  cruise-altitude horizon-dip correction: cabin sunsets run ~12–20 min later than
  on the ground), sunrise/sunset markers with times, and Open-Meteo weather glyphs
  at sampled waypoints evaluated at their overflight hour.
- **Live route map** — offline schematic projection with graticule, real
  day/night terminator shading, flown track (solid) vs great-circle guide
  (dashed), heading-rotated aircraft marker with staleness ghosting, and an
  altitude/speed/track readout.
- **Notifications** — delay (per-15-min slip buckets), gate change, cancellation,
  diversion, departed, landed via snapshot diffing with a persisted dedup ledger;
  boarding and landing-soon reminders ride user-granted exact alarms
  (rebuilt on boot), degrading gracefully to WorkManager cadence.
- **Themes** — Daylight (+ Material You dynamic), Cockpit (AMOLED avionics
  green/amber), High Contrast. Instant switching, no Activity recreate.
- **Quota-aware refresh engine** — one policy-driven periodic worker with
  adaptive cadence tiers (6 h → 15 min around the gate-critical window), local
  per-provider usage ledger with soft stops, visible in Settings.

## Data sources (bring your own keys)

Blipbird composes free/BYO-key services — see `PLAN.md` §4 for the full strategy:

| Plane | Source | Key |
|---|---|---|
| Schedule/status/gates | [AeroDataBox](https://aerodatabox.com/) via RapidAPI (free tier ≈ 300 lookups/mo) | your RapidAPI key |
| Status fallback | [FlightAware AeroAPI](https://www.flightaware.com/commercial/aeroapi/) Personal ($5/mo usage waived; personal use) | your key |
| Live positions | [adsb.lol](https://adsb.lol) (ODbL) → airplanes.live → adsb.fi | none |
| Airport weather | [aviationweather.gov](https://aviationweather.gov/data/api/) METAR | none |
| Route weather | [Open-Meteo](https://open-meteo.com/) (CC BY 4.0, non-commercial) | none |
| Airports/airlines | bundled: OurAirports (PD) + mwgg/Airports tz (MIT) + OpenTravelData (CC BY 4.0) | — |

### Generating a key for FlightAware

1. Subscribe to the free tier
2. Go to your Account
3. Go to "Subscription"
4. Your key will be there

**Without any key** the app still works in limited mode: live map, airline/airport
info, weather, themes — with an honest "connect a data source" CTA where status
data would be. Paste keys in **Settings → Data sources** (stored AES-GCM-encrypted
with an Android Keystore key, excluded from backups).

## Building

```bash
./gradlew assembleDebug        # requires JDK 17+; Gradle 9.6.1 via wrapper
./gradlew testDebugUnitTest    # 52 unit tests
```

Helper scripts:

```bash
scripts/build.sh               # release APK staged into dist/ (--debug for debug)
scripts/install-debug.sh       # build debug APK + adb install on a connected phone
scripts/release.sh 0.2.0 --push # bump version, tag v0.2.0, push — CI publishes the release
```

Regenerate bundled reference data / icons:

```bash
python3 scripts/generate_reference_data.py   # writes app/src/main/assets/reference/ + lockfile
python3 scripts/generate_icons.py            # regenerates launcher mipmaps from media-sources/icon.png
```

## Releasing

`scripts/release.sh X.Y.Z --push` (a stub over the shared
[release-tool](https://github.com/L-K-M/release-tool) engine) bumps
`versionName`/`versionCode` in `app/build.gradle.kts` plus the version line at
the top of this README, commits, tags `vX.Y.Z`, and pushes. The tag triggers
[`release.yml`](.github/workflows/release.yml), which re-runs tests + lint,
builds the release APK, signs it when the `ANDROID_KEYSTORE_BASE64` /
`ANDROID_KEYSTORE_PASSWORD` / `ANDROID_KEY_ALIAS` / `ANDROID_KEY_PASSWORD`
secrets are configured (attaching an unsigned APK with a warning otherwise),
and publishes the GitHub Release. Pull requests are additionally reviewed by
GLM 5.2 via [`zai-code-review.yml`](.github/workflows/zai-code-review.yml)
when the `ZAI_API_KEY` secret is set.

## Architecture

Single-module Kotlin 2.4 + Jetpack Compose (Material 3) app, MVVM with
unidirectional data flow. Room is the source of truth, split by backup boundary:
`blipbird-user.db` (your tracked flights — backed up) vs `blipbird-ops.db`
(provider-derived snapshots/fixes + bundled reference tables — excluded,
rebuildable, TTL-pruned). Every external service sits behind a provider interface
with ordered failover; pure decision cores (phase machine, notification planner,
cadence policy, daylight engine) are plain JVM code with unit tests.

Full design rationale, verified API research, and the roadmap live in
[`PLAN.md`](PLAN.md).

### Known deviations from PLAN.md in v0.1

- **Navigation:** hand-rolled 3-screen back stack instead of Navigation 3.
- **Map:** offline Canvas route map instead of MapLibre vector tiles (MapLibre
  integration is the tracked follow-up; the schematic map needs zero network and
  themes perfectly).
- **Modules:** single `:app` module ("Kotlin packages first", per plan §3).
- **Aliases:** stored on the tracked flight; separate `SavedFlight` entity and
  recurring rules remain roadmap.
- Provider legal gates from PLAN.md §4.6 (written permissions for aggregators,
  runtime enrichment services) remain open items for a store release; v0.1 is a
  source release.

## License & attribution

Code: see `LICENSE`. Data/attribution: displayed in-app under **Settings →
About**, including "Weather data by Open-Meteo.com" (CC BY 4.0), OurAirports
(public domain), mwgg/Airports (MIT), OpenTravelData (CC BY 4.0), adsb.lol
(ODbL), commons-suncalc (Apache-2.0), great-circle math after Chris Veness (MIT),
terminator math after Leaflet.Terminator (MIT). Airline names and codes are
trademarks of their respective owners, used for identification only. All flight
data is informational — **not for navigation or operational use**.
