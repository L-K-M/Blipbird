# Blipbird — DeepSeek v4 review (DS4)

Comprehensive review of the Blipbird codebase at commit `b835a63`. This
document augments `REVIEW.md`; it includes all still-open items from
REVIEW.md plus new findings. Items are tagged `[NEW]` when unique to
this review or `[REVIEW.md:ID]` when they repeat open items.

---

## 1. Bugs

### 1.1 DS4-B1 · Night-side polygon frozen without live fix — MEDIUM [REVIEW.md: glm 1.15]
`MapLibreRouteMap.kt:107` keys the terminator source on
`lastFix?.at?.epochSecond?.div(600)`. When no position fix exists
(planned-route view), this is `null` forever, so the night shading
stays pinned at the first render. Fix: key on
`(Instant.now().epochSecond / 600)` or use wall-clock deciminutes.

### 1.2 DS4-B2 · Delay notifications never fire from status-only delays — MEDIUM [REVIEW.md: B9]
`NotificationPlanner.diff` only fires DELAY when `est.isAfter(sched)`
with a timestamp delta. AeroDataBox frequently reports `status: delayed`
with no revised time, which the planner ignores. Emit a DELAY event when
`current.status == DELAYED && prev?.status != DELAYED` even without
timestamps.

### 1.3 DS4-B3 · Near-antipodal routes divide by ~0 — MEDIUM [REVIEW.md: glm 1.13]
`GreatCircle.intermediate()` guards the coincident case (`d < 1e-9`) but
not the antipodal case (`d ≈ π` → `sin(d) ≈ 0`). Polar/near-antipodal
routes return garbage, breaking the polyline, terminator, and daylight
sampling. Guard with `d > π - 1e-9`.

### 1.4 DS4-B4 · Antimeridian split leaves a segment gap — LOW [REVIEW.md: glm 1.14]
`GreatCircle.routeSegments`/`splitAtAntimeridian` start the new segment
without inserting the interpolated ±180° vertex, so the polyline
visibly breaks at the date line. Insert interpolated seam vertex.

### 1.5 DS4-B5 · Transition events re-fire after the 3-day prune — MEDIUM [REVIEW.md: glm 1.10]
`EmittedEvent.expiresAt` shares the snapshot TTL. After prune, the next
refresh sees `previous == null` and re-emits cancelled/diverted/departed/
landed. Extend ledger retention or check transition idempotency against
an always-kept tombstone flag.

### 1.6 DS4-B6 · `FlightRepository.delete()` not transactional — LOW [REVIEW.md: glm 1.11]
Four DAO calls across two DBs; a crash mid-way orphans rows in
`blipbird-ops.db`. Wrap in ops-side `@Transaction`.

### 1.7 DS4-B7 · ADS-B aircraft not cross-verified — LOW [REVIEW.md: glm 1.16 / B20]
`PollPosition` takes the first result with lat/lon; no cross-check
against the snapshot's `icao24`/registration or route/time corridor.
A colliding callsign paints someone else's track on the map.

### 1.8 DS4-B8 · `goAsync()` receivers lack timeouts — LOW [REVIEW.md: glm 1.19]
`ReminderAlarmReceiver`/`BootCompletedReceiver` run Room/IO work on a
free-standing scope under the ~10 s budget; boot reconcile should
enqueue a `OneTimeWorkRequest` instead.

### 1.9 DS4-B9 · `OpsDatabase` destructive migration nukes `quota_ledger` — MEDIUM [REVIEW.md: glm 1.20]
A future schema bump silently zeroes user API-credit accounting. Move
the ledger to the user DB or add real migrations for that table.

### 1.10 DS4-B10 · Delay-notification copy under-reports — LOW [REVIEW.md: glm 1.22]
A 29-min slip renders "Delayed 15m" (bucket floor). Show the real
minutes. The bucket is a dedup key, not display copy.

### 1.11 DS4-B11 · Tangential sunrise/sunset sample dropped — LOW [REVIEW.md: glm 1.18]
`DaylightEngine.findCrossings` skips a sample landing exactly on the
threshold (`e0 == 0.0` → `continue`). Change to `e0 * e1 > 0` so
exact hits are treated as crossings.

### 1.12 DS4-B12 · Quota ledger check-then-record race — LOW [REVIEW.md: B18]
`canSpend` + `record` are non-atomic; bounded overshoot near the soft
stop. Use an `@Transaction` atomic check-and-record.

### 1.13 DS4-B13 · MapLibre track source keyed on size only — LOW [REVIEW.md: B21]
`remember(track.size)` — a pruned+appended track of equal length
renders stale geometry. Key on last-fix timestamp + size.

### 1.14 DS4-B14 · Landed flights sort above imminent departures — LOW [REVIEW.md: B23]
Sorting by `nextEventAt` lets a flight landed 2 h ago outrank one
departing in 45 min. Landed rows should sink to the bottom (and
eventually auto-archive per F2).

### 1.15 DS4-B15 · Notification ID hash collisions possible — LOW [REVIEW.md: B14n]
The current `stableId` uses a mixed-hash that's astronomically unlikely
but not provably collision-free. Switch to `floorMod(flightId, 500M)
* 4 + channelIndex` for deterministic uniqueness.

### 1.16 DS4-B16 · Reference data never re-imports after app update — MEDIUM [REVIEW.md: B12]
`ReferenceImporter` guards on `airportCount() > 0`. A shipped update
with regenerated CSVs never reaches the database. Version the import
(store lockfile hash or `fetched` date; clear + re-import on change).

### 1.17 DS4-B17 · `TrackedFlightEntity.dateLocal` String parse risk — LOW [NEW]
`dateLocal` is stored as `String?` and parsed with `LocalDate.parse(dateLocal)`
in `FlightRepository.kt:138` without `runCatching`. A malformed date
string from a pinned date (written via `trackedDao.pinDate` at line 183
which uses `atZone(zone).toLocalDate().toString()`) will crash the
refresh loop. The `toString()` output is stable (`YYYY-MM-DD`) but
defence-in-depth is warranted.

---

## 2. General Issues

### 2.1 DS4-G1 · Hardcoded English strings — MEDIUM [REVIEW.md: G1]
- "Departs in" / "Lands in" / "Landed" in `FlightListScreen.phaseTime`
  and `FlightDetailScreen.Hero` (lines 293-296)
- "Boarding" / "Pushback" / "Takeoff" / "Landing" / "Gate arrival" in
  `Timeline` (lines 516-522)
- `ATTRIBUTION_TEXT` in `SettingsScreen.kt:260`
- Map attribution in `MapCard` ("© OpenFreeMap · © OpenMapTiles · ©
  OpenStreetMap contributors" — line 393)
- "Weather data by Open-Meteo.com" in `FlightDetailScreen.kt:157`

### 2.2 DS4-G2 · Dead code and dead resources — LOW [REVIEW.md: G2]
- `RouteMap.kt` (175-line offline canvas map, no callers since MapLibre)
- Unused strings: `status_boarding`, `timeline_*`, `estimated_chip`,
  `sunrise_at`, `sunset_at`, `ribbon_unavailable`, `weather_unavailable`,
  `attribution_openfreemap`, `onboarding_keys_skip`
- Unused `onboardingDone` setting in `SettingsRepository`
- Unused `latestTwo`/`history`/`aliased` DAO queries

### 2.3 DS4-G3 · `.idea/` files committed to git — LOW [REVIEW.md: G4]
`.idea/noctule.xml` (rename remnant) and possibly other files committed
despite `.gitignore` listing `.idea/`. Remove, re-gitignore.

### 2.4 DS4-G4 · Error observability missing — MEDIUM [REVIEW.md: G5]
Provider failures collapse to `null`/empty. The UI can't distinguish
"no key" / "quota exhausted" / "rate limited" / "offline" / "server
error". Thread the last error into list and detail state for a subtle
indicator (e.g. orange dot on the card, "last refresh failed" snackbar).

### 2.5 DS4-G5 · No OkHttp cache — MEDIUM [REVIEW.md: G6]
One-line addition: `Cache(cacheDir.resolve("http"), 10L * 1024 * 1024)`
plus offline revalidation for METAR/Open-Meteo traffic. Saves quota
and works during connectivity blips.

### 2.6 DS4-G6 · Test gaps — MEDIUM [REVIEW.md: G9]
- `NotificationPlanner`: delay-bucket re-notification, gate-appears vs
  gate-changes, status-only delay
- `InstanceSelector`: date-pinning paths
- `CadencePolicy`: exact 48 h boundary
- `MetarDecoder`: edge cases for extreme values

### 2.7 DS4-G7 · Detail ViewModels are Activity-scoped and never cleared — HIGH [REVIEW.md: G10]
Root cause of B4; the #20 gate treats the symptom. Memory grows with
every flight opened. Transition to nav-entry-scoped ViewModels, or adopt
Navigation 3 so `onCleared` fires on pop. This also unlocks predictive
back and removes the manual `screenVisible` gate.

### 2.8 DS4-G8 · Contributor docs missing — LOW [REVIEW.md: glm 7.1]
`CONTRIBUTING.md`, `GLOSSARY.md`, `SECURITY.md`, `.editorconfig`,
`.gitattributes`, `docs/decisions/` template.

### 2.9 DS4-G9 · `phaseTime` calls `Instant.now()` in composable — MEDIUM [NEW]
`FlightListScreen.phaseTime` (lines ~410) calls `Instant.now()` at
composition time. This is unstable in Compose terms (different return
value for same inputs) and triggers unnecessary recompositions. The
ViewModel already ticks a shared clock every 30s — pass `now` through
the row data or a `CompositionLocal`.

### 2.10 DS4-G10 · List doesn't show data-freshness per row — LOW [NEW]
The detail screen shows "Updated X ago" but the list screen has no
per-row staleness indicator. A flight's data could be hours stale
without the user knowing. Show `lastUpdated` inline (e.g. subtle text
in the card footer).

### 2.11 DS4-G11 · `SettingsScreen` quota display uses bare `∞` — LOW [NEW]
`QuotaRow.allowance` is `Long?` and `null` maps to `"∞"` — this Unicode
character has no `contentDescription` for TalkBack. Use a string
resource with accessibility label.

---

## 3. Performance

### 3.1 DS4-P1 · Ribbon/weather recomputed on every snapshot insert — MEDIUM [REVIEW.md: P2]
`fetchedAt` gate re-runs suncalc (~1000 samples) + two weather calls per
refresh even when times are identical. Gate on (route, wheelsUp,
wheelsDown) to avoid redundant computation. Refresh METARs on their own
shorter cadence.

### 3.2 DS4-P2 · First-launch reference import races UI — MEDIUM [REVIEW.md: P3]
Enrichment returns nulls during the CSV import and never retries.
Trigger re-enrichment on import completion via a callback or SharedFlow.

### 3.3 DS4-P3 · List rows recompose wholesale — LOW [REVIEW.md: P4]
Any row change triggers full list recomposition. Add `contentType` +
stable row data classes to scope recomposition to changed rows.

### 3.4 DS4-P4 · Theme flash on cold start — LOW [REVIEW.md: P5]
Theme is collected with `initialValue = DAYLIGHT_DYNAMIC`. Read the
first value blocking or cache in a fast-path pref to eliminate the
one-frame flash.

### 3.5 DS4-P5 · No baseline profile / macrobenchmark — MEDIUM [REVIEW.md: P6]
PLAN M4's `:benchmark` module. Startup, list scroll, and map render
profiles needed.

### 3.6 DS4-P6 · GeoJSON string building on composition thread — LOW [REVIEW.md: P7]
`MapLibreRouteMap` builds GeoJSON strings inside `remember` blocks on
the composition thread. Race-free but expensive for large track lists.
Pre-compute in the ViewModel, off the main thread.

### 3.7 DS4-P7 · 11-way detail `combine` rebuilds everything per fix — MEDIUM [REVIEW.md: glm 2.3]
`FlightDetailViewModel` combines 12 flows. Every position fix (~10 s
while airborne) triggers a full `DetailUiState` rebuild, recomposing
the map, ribbon, hero, and timeline. Split map-only state into a
separate flow so position updates don't retrigger the sky-gradient hero
or timeline.

### 3.8 DS4-P8 · Brushes/Paths built inside draw lambdas — LOW [REVIEW.md: glm 2.1/2.2/2.4]
Ribbon vertical gradient, hero background brush, progress bar gradient —
all allocated inside `Canvas {}` or `background {}` lambdas. Hoist into
`remember` with stable keys.

### 3.9 DS4-P9 · Monograms redrawn per row — LOW [REVIEW.md: glm 2.7]
Each `FlightRowCard` paints its monogram from scratch. Cache the
computed `Color` per airline code (the `monogramColor` function is
pure but called N times per list).

### 3.10 DS4-P10 · `planeBitmap` creates ImageBitmap on composition thread — LOW [NEW]
`MapLibreRouteMap.kt:111` calls `planeBitmap(ext.routeLine, sizePx=72)`
which creates a `Path` + renders to `Canvas(ImageBitmap)` on the
composition thread. This is fine if `remember` caches it (it does,
keyed on `ext.routeLine`), but the first call is heavy. Consider
pre-rendering at app startup or using a vector drawable.

---

## 4. Visual, Layout & Accessibility

### 4.1 DS4-V1 · No typography system, no tabular figures — HIGH [REVIEW.md: V1 / glm 3.1-3.2]
Default Roboto everywhere; countdown digits wiggle as they tick. A
`Typography` with a display face + `"tnum"` on all numeric text is the
single biggest aesthetic lever. PLAN §10.2 specifies the target. Needs
a font choice from the owner.

### 4.2 DS4-V2 · No motion design — MEDIUM [REVIEW.md: V2]
Screen changes are hard cuts. Even slide/fade transitions between
screens + shared-element transitions (departure code in list → hero
in detail) buy disproportionate polish.

### 4.3 DS4-V3 · No haptics — LOW [REVIEW.md: V3]
Pull-to-refresh completion, swipe thresholds, wheels-down landing.
PLAN M4 specifies haptic feedback for key moments.

### 4.4 DS4-V4 · StatusWord white text fails WCAG on amber — LOW [REVIEW.md: V4 / glm 3.3]
`StatusWord` always uses `Color.White` text. Amber/neutral backgrounds
(hex `#FFB26A00`, `#FF5F6368`) with white text fail WCAG AA (contrast
~3.2:1 and ~3.5:1 respectively — need 4.5:1). Use per-chip on-colors:
black text on amber, white on dark colors, dark text on neutral.

### 4.5 DS4-V5 · Empty map card renders skeleton — LOW [REVIEW.md: V5]
When coordinates are unknown, the map card renders header +
attribution and a blank 280 dp space. Show a placeholder or hide
the card entirely.

### 4.6 DS4-V6 · Add sheet: no DatePickerDialog, no loading state — LOW [REVIEW.md: V6 / glm 5.8]
Plain text field for date instead of a native date picker. No loading
indicator while resolving the flight identity. The parse error flow for
dates is silent (4-digit year accepted, any other format silently
ignored, producing no date hint).

### 4.7 DS4-V7 · Ribbon niggles — MEDIUM [REVIEW.md: V7 / glm 3.4-3.6]
- Weather glyphs drift left of their sample positions (evenly spaced
  in Row vs. geometrically along the great-circle path)
- Sunrise/sunset times render in device TZ, not flight-local
- Events can crowd on narrow phones (wrap to multiple lines)
- Marker colors hardcoded (ignore Cockpit/High Contrast themes)
- Raw emoji despite known tofu-box issues on OEM fonts
- `bandColor` skips civil twilight; only transitions night→dusk→day

### 4.8 DS4-V8 · Detail density inconsistencies — LOW [REVIEW.md: V9]
`Tag` icon reused for check-in and registration. Registration
duplicated on `AirlineCard` if present. `Domain` icon reused for
terminal and key facts. Replace with semantically distinct icons.

### 4.9 DS4-V9 · Top bars don't collapse — MEDIUM [REVIEW.md: V10]
Large-title collapse is half the "expensive iOS" gestalt. Wire
`TopAppBarScrollBehavior` via `nestedScrollConnection` on the
`LazyColumn` for the list and detail screens.

### 4.10 DS4-V10 · Timeline shows stale derived rows — LOW [REVIEW.md: V13]
"Check-in" and "Boarding" derived rows persist days after departure.
Hide derived pre-departure rows once the flight has departed.

### 4.11 DS4-V11 · Cockpit theme background is pure `#000000` — LOW [REVIEW.md: V14 / glm 3.11]
`Color(0xFF000000)` in `CockpitScheme.background` causes penTile smear
on AMOLED panels (particularly visible during scroll). Use `#05050A`
or `#030305`.

### 4.12 DS4-V12 · Plane glyphs always point right — LOW [REVIEW.md: glm 3.8]
`Icons.Filled.Flight` with `rotate(90f)` in list and detail — a
LHR→NRT (west→east) flight visually flies east (which is fine), but
a JFK→LAX flight shows the plane heading rightward while flying
westward on the map. Orient the glyph based on great-circle bearing.

### 4.13 DS4-V13 · No loading skeletons — MEDIUM [REVIEW.md: glm 3.10]
PLAN §9.2 specifies skeleton loading. Add shimmering placeholder
cards for the list while resolving, and a skeleton layout for the
detail screen.

### 4.14 DS4-V14 · Back arrows have no contentDescription — LOW [REVIEW.md: glm 4.1]
Both `FlightDetailScreen.kt:109` and `SettingsScreen.kt:62` set
`contentDescription = null` on the back navigation `IconButton`.
TalkBack announces nothing. Provide "Back" content description.

### 4.15 DS4-V15 · Map and ribbon have no semantics — LOW [REVIEW.md: glm 4.2]
Map and ribbon are invisible to TalkBack. Add `contentDescription`
describing the route and day/night distribution.

### 4.16 DS4-V16 · Timeline row hard-pinned to 44 dp — LOW [REVIEW.md: glm 4.3]
`TimelineRow` uses `Modifier.height(44.dp)`. Clips at 200% font scale.
Use `minHeight` or scale-aware sizing.

### 4.17 DS4-V17 · List rows lack `mergeDescendants` — LOW [REVIEW.md: glm 4.4]
TalkBack announces 3 swipe stops per row + nested controls. Use
`Modifier.semantics(mergeDescendants = true)` on the card surface.

### 4.18 DS4-V18 · Large-screen pass incomplete — LOW [REVIEW.md: V12]
Detail two-pane landed. List/tablet grid and landscape-specific
layouts remain open.

### 4.19 DS4-V19 · Ribbon weather glyph alignment broken — LOW [NEW]
The weather `Row` uses `Modifier.weight(1f)` per glyph, which evenly
distributes them across the screen width. But the spatial sample
points are geometric along the great-circle path (denser near poles,
sparser near equator on certain projections). The glyph positions
don't correspond to the correct fraction on the ribbon strip.

### 4.20 DS4-V20 · `phaseTime` countdown shows 0m for just-passed times — LOW [NEW]
`countdownText(Duration.between(Instant.now(), at))` clamps negative
durations to 0 via `d.seconds.coerceAtLeast(0)`. This means a flight
whose departure just passed shows "Departs in 0m" instead of something
more useful like "Departed now" or switching to the landed format.

### 4.21 DS4-V21 · Detail screen countdown freezes between clock ticks — LOW [NEW]
The detail screen Hero countdown uses `Instant.now()` inside the
`combine` block (via the `clock` SharedFlow at 15 s). Between ticks
it's frozen. Contrast this with the list which recomposes at 30 s
but `phaseTime` recomputes on any composition. Synchronize both
screens to use the same tick source with animation smoothing.

---

## 5. Missing Features

All from REVIEW.md, re-ranked by value/effort for implementation sequencing,
plus new items:

### 5.1 DS4-F1 · Auto-archive landed flights after ~24 h — SMALL EFFORT [REVIEW.md: F2]
Into a "Past flights" section below active flights.

### 5.2 DS4-F2 · Share a flight — SMALL/MEDIUM EFFORT [REVIEW.md: F3]
Status text share sheet; optional share-card image with departure
airport, arrival airport, times, airline.

### 5.3 DS4-F3 · Add to calendar — SMALL EFFORT [REVIEW.md: F4]
Create an `Intent` with flight departure/arrival times, airport codes,
and airline info.

### 5.4 DS4-F4 · "Boarding" as a real status — MEDIUM EFFORT [REVIEW.md: F5]
ADB `boarding`/`gateClosed` collapse into SCHEDULED. The `strings.xml`
already ships the word. Expose Boarding as a first-class phase between
On Time and Departed.

### 5.5 DS4-F5 · Ongoing in-flight notification — MEDIUM EFFORT [REVIEW.md: F6]
`Notification.ProgressStyle` on API 36+, plain progress bar below
for API <36. Shows flight progress, ETA, altitude.

### 5.6 DS4-F6 · "Next flight" Glance widget — MEDIUM EFFORT [REVIEW.md: F7]

### 5.7 DS4-F7 · Gate-assigned notification — SMALL EFFORT [REVIEW.md: F8]
Planner currently fires only on gate CHANGE. Add gate ASSIGNED event
when `prev?.depGate == null && current.depGate != null`.

### 5.8 DS4-F8 · "Delay recovered" notification — SMALL EFFORT [REVIEW.md: F9]
When a delayed flight moves back ON_TIME. Planner needs a
DELAY_RECOVERED event.

### 5.9 DS4-F9 · Layover awareness — MEDIUM EFFORT [REVIEW.md: F10]
Chained tracked flights: connection time + buffer warnings in the
list and detail.

### 5.10 DS4-F10 · Trip grouping — MEDIUM EFFORT [REVIEW.md: F11]
PLAN v2 feature.

### 5.11 DS4-F11 · Per-flight notification profiles — MEDIUM EFFORT [REVIEW.md: F12]
Toggle which notification types fire per flight.

### 5.12 DS4-F12 · Flight log / Passport stats — LARGE EFFORT [REVIEW.md: F13]
Summary rows kept past the prune: total miles, airports visited,
airlines flown.

### 5.13 DS4-F13 · Export/import user data — SMALL EFFORT [REVIEW.md: F14]
JSON export/import of tracked flights + settings.

### 5.14 DS4-F14 · In-app airline/airport info — SMALL EFFORT [REVIEW.md: F15]
Alliance, check-in URL, Maps deep link to terminal.

### 5.15 DS4-F15 · Offline projected mode — LARGE EFFORT [REVIEW.md: F16]

### 5.16 DS4-F16 · Rename from detail screen — SMALL EFFORT [REVIEW.md: F17 remainder]
List long-press rename landed; detail screen still has no overflow menu.

### 5.17 DS4-F17 · Day grouping in list — SMALL EFFORT [REVIEW.md: glm 5.3]
For multi-day trips, group by departure date with sticky headers.

### 5.18 DS4-F18 · Clone/combine flights [NEW] — SMALL EFFORT
A "track return flight" button on the detail screen that inverts the
city pair and auto-fills designators.

---

## 6. Ideas (Novel / Delightful / Quirky)

All from REVIEW.md plus new ones:

### 6.1 DS4-I1 · Window-side sunrise/sunset callout — LANDED [REVIEW.md: I1 / #29]
"🌇 18:42 · left side" already implemented.

### 6.2 DS4-I2 · Bird-flight pull-to-refresh [REVIEW.md: I2]

### 6.3 DS4-I3 · Wheels-down haptic heartbeat [REVIEW.md: I3]

### 6.4 DS4-I4 · Split-flap status transitions [REVIEW.md: I4]

### 6.5 DS4-I5 · Jet-lag ribbon extension [REVIEW.md: I5]

### 6.6 DS4-I6 · Airline-colored monograms [REVIEW.md: I6]

### 6.7 DS4-I7 · Cockpit auto-engage during night + airborne [REVIEW.md: I7]

### 6.8 DS4-I8 · Ribbon scrubbing [REVIEW.md: I8]

### 6.9 DS4-I9 · Landed confetti [REVIEW.md: I9]

### 6.10 DS4-I10 · Moon and stars on night segments [REVIEW.md: I10]

### 6.11 DS4-I11 · "Point at the sky" AR [REVIEW.md: I11]

### 6.12 DS4-I12 · Shared flight tracking / live link [NEW]
Generate a short-lived URL that shows flight progress (read-only,
no account needed). For sharing with friends/family picking up.

### 6.13 DS4-I13 · Tail number spotting badges [NEW]
Show a badge when the tracked registration matches the actual ADS-B
feed: "N123AB spotted" = more confidence in position data.

### 6.14 DS4-I14 · Altitude profile sparkline [NEW]
A mini sparkline in the detail screen showing altitude over time from
the ADS-B track data. Reads like a mini ECG for the flight.

### 6.15 DS4-I15 · Timezone-hopping visual [NEW]
A small visual indicator showing how the local time changes along the
route (e.g., "UTC+2 → UTC+8" with a subtle gradient bar).

---

## 7. Suggested Implementation Order

### Phase A — Quick wins (est. 2-4 h total)
1. **DS4-V14** — Back-arrow content descriptions (1 line each, 2 files)
2. **DS4-B1** — Night-polygon wall-clock key (1 line in MapLibreRouteMap)
3. **DS4-B3** — GreatCircle antipodal guard (5 lines)
4. **DS4-B4** — Antimeridian seam vertex (10 lines)
5. **DS4-B13** — MapLibre track key on timestamp+size (1 line)
6. **DS4-G3** — Remove committed .idea/ files (git rm)
7. **DS4-G5** — OkHttp cache (3 lines in AppModule)
8. **DS4-V4** — StatusWord per-chip colors (10 lines)
9. **DS4-V11** — Cockpit near-black background (1 line)
10. **DS4-B11** — DaylightEngine exact-hit crossing (1 line)

### Phase B — Medium Effort (est. 4-8 h)
1. **DS4-B2** — Status-only delay notification (15 lines in NotificationPlanner)
2. **DS4-V14 (full)** — Accessibility pass: mergeDescendants, TalkBack labels, content descriptions
3. **DS4-V7** — Ribbon fixes: marker colors from theme, bandColor civil twilight, weather glyph spacing
4. **DS4-P1** — Ribbon/weather recomputation gating
5. **DS4-G1** — Extract remaining hardcoded strings to strings.xml
6. **DS4-G9** — Fix `phaseTime` `Instant.now()` → pass from ViewModel clock
7. **DS4-B16** — Reference data versioned re-import
8. **DS4-F1** — Auto-archive landed flights after 24 h
9. **DS4-F7 + DS4-F8** — Gate-assigned + delay-recovered notifications

### Phase C — Structural (est. 8-16 h)
1. **DS4-G7** — Navigation rework (VM scoping, Navigation 3, predictive back)
2. **DS4-P7** — Split detail combine into independent flows
3. **DS4-V1** — Typography system with tabular figures (depends on font choice)
4. **DS4-V2** — Motion design / screen transitions
5. **DS4-B9** — Quota ledger migration safety (move to user DB or real migrations)

### Phase D — Features & Delighters
1. **DS4-F5** — Ongoing in-flight notification
2. **DS4-F9** — Layover awareness
3. **DS4-F2** — Share a flight
4. **DS4-F14** — In-app airline/airport info
5. **DS4-F18** — Track return flight button
6. **DS4-I2** — Bird-flight pull-to-refresh
7. **DS4-I3** — Wheels-down haptic heartbeat
8. **DS4-I6** — Airline-colored monograms
9. **DS4-I14** — Altitude profile sparkline
