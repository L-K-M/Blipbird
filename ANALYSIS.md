# Blipbird — Analysis & work backlog

The living successor to the July 2026 full-codebase review (`fable.md`, now merged
into this document — the original snapshot lives in git history). Everything that
was fixed in the review cycle is listed once with its PR; everything still open is
kept in full detail as the backlog future work builds on. IDs (B/G/P/V/F/I) are
stable so PRs and discussions can keep referencing them.

---

## Fixed in the July 2026 review cycle

All PRs target the working branch `claude/flight-tracker-review-g3uvhu`
(`main` holds only the initial commit until #16 merges the app there).

| PR | Items | Summary |
|----|-------|---------|
| — (direct) | B24 | Unguarded API 31+ `canScheduleExactAlarms` calls (crash on Android 8–11) + unchecked `notify` — the CI lint gate |
| #17 | B1, B8 | Saveable back stack (rotation/process death), `singleTask` + `onNewIntent` deep links, process-death re-fire guard |
| #18 | B2 | `Locale.ROOT` for Open-Meteo coordinates and all GeoJSON |
| #19 | B3, B15 | 30 s ticker → countdowns/progress/derived statuses move between refreshes; past-due countdowns clamp |
| #20 | B4, P1 | Detail live-position polling gated on screen visibility (`LifecycleStartEffect` + `collectLatest`) |
| #21 | B5, V8·V9·V11 parts, G3 part | Swipe archive/delete + undo, reminder cancellation on removal, no-key CTA card, empty-state Add button, belt on landed rows, `animateItem`, FAB clearance |
| #22 | B6, B19 | AeroAPI red-eye date window; ADB `CanceledUncertain` → CANCELLED |
| #23 | B7, V6 part | Add sheet stays open on parse errors; all-caps keyboard, no autocorrect |
| #24 | B10, G7 | Token-based METAR decoder: `+RA`/MPS/mixed-number SM/CB·TCU/visibility/wind direction; locale-safe tests |
| #25 | B11, P5 part | Theme-aware system bars (Cockpit), `values-night` dark window theme (kills the dark-mode white flash) |
| #26 | B13 | Periodic worker self-cancels with no flights; `BackgroundRefreshController` re-arms on track |
| #27 | B14 | Collision-free notification IDs |
| #28 | B16, B17 | Exact-alarm state re-check on resume, removable API keys, reminders toggle reconciles alarms |
| #29 | I1 | Window-side sunrise/sunset callout on the ribbon ("🌇 18:42 · left side") |

**Merge-order notes:** #19/#21/#23 all touch `FlightListViewModel`/`FlightListScreen`
(disjoint but adjacent hunks); #17/#25 both touch `MainActivity.setContent`.
Whichever merges second may need a trivial conflict resolution. After merging #21
and #26 together, `restore()`/`unarchive()` should also call
`BackgroundRefreshController.ensureScheduled()` (they insert flights without going
through `track()`) — small follow-up.

---

## Open bugs

### B9 · Delay notifications can never fire from AeroDataBox status-only delays — MEDIUM
`NotificationPlanner.diff` compares `scheduled` vs `estimated`; ADB frequently
reports delays via a *status* of `delayed` with no revised time, which the planner
ignores entirely (no event type for it). A "Delayed" push is the #1 reason to
install a flight tracker. **Fix:** emit a DELAY event when
`current.status == DELAYED && prev?.status != DELAYED` even without timestamps
("Flight reported delayed"), keeping the bucketed fingerprint path when times
exist. *Held back because live ADB payloads couldn't be verified offline.*

### B12 · Reference data never re-imports after an app update — MEDIUM
`ReferenceImporter.kt:22` guards on `airportCount() > 0`; a shipped update with
regenerated CSVs never reaches the database (new airports, renamed airlines, tz
fixes never land). **Fix:** version the import — store the lockfile's `fetched`
date (or a CSV hash) and clear + re-import when it changes. Needs a small
migration decision (where to persist the marker).

### B18 · Quota ledger check-then-record race — LOW
`QuotaLedger.canSpend` + `record` are non-atomic; simultaneous pull-to-refresh +
worker can both pass near the soft stop. Bounded overshoot; the SQL upsert could
be one conditional statement.

### B20 · Position lookup doesn't verify it found the right aircraft — LOW
`PositionProvider.fetch` takes the first result with a lat/lon for a
callsign/registration query; no cross-check against the snapshot's
`icao24`/registration when known — a colliding callsign paints someone else's
track. PLAN.md M2 exit criteria explicitly call for collision suppression.

### B21 · MapLibre track source keyed on size only — LOW
`MapLibreRouteMap.kt:77` `remember(track.size)` — a pruned+appended track of equal
length renders stale geometry. Key on last-fix timestamp + size.

### B22 · `DaylightEngine` bisection early-exit is a no-op — LOW (code health)
`DaylightEngine.kt:119-124`: `return@repeat` as the last statement just continues
the loop; the precision early-exit never fires (harmless — always 24 iterations).

### B23 · Landed flights sort above imminent departures — LOW (design)
`FlightListViewModel` sorts by `nextEventAt`; a flight landed 2 h ago outranks one
departing in 45 min. Active/upcoming should lead; landed ones should sink (and
eventually auto-archive — F2).

---

## Open general issues

- **G1 — Hardcoded English strings** (partially fixed by #28): remaining —
  "Departs in"/"Lands in"/"Landed" (`FlightListScreen.phaseTime`), timeline labels
  "Boarding"/"Pushback"/"Takeoff"/"Landing"/"Gate arrival"
  (`FlightDetailScreen.kt`), `ATTRIBUTION_TEXT` (`SettingsScreen.kt`),
  "Weather data by Open-Meteo.com" (`FlightDetailScreen.kt:127`).
- **G2 — Dead code:** `RouteMap.kt` (the whole 175-line offline canvas map, no
  callers since the MapLibre migration), unused strings (`status_boarding`,
  `timeline_scheduled/estimated/actual`, `estimated_chip`, `sunrise_at`,
  `sunset_at`, `ribbon_unavailable`, `weather_unavailable`,
  `attribution_openfreemap`, `onboarding_keys_skip`), unused `onboardingDone`
  setting, a `drawCircle(Color.Transparent, …)` no-op in `FlightProgressBar`,
  unused `latestTwo`/`history`/`aliased` DAO queries.
- **G3 — README/impl drift** (mostly fixed by #21): remaining — "raw METAR one
  tap away" (it's always visible; either collapse it behind a tap or update the
  README).
- **G4 — `.idea/` files are committed** (`.idea/noctule.xml` is a rename remnant)
  although `.gitignore` lists `.idea/`. Remove from git.
- **G5 — Error observability:** every provider failure collapses to `null`/empty
  (`StatusProviderChain` drops `StatusResult.Error` messages; weather catches
  all). The UI can't distinguish "no key" / "quota exhausted" / "rate limited" /
  "offline". Thread the last error into list/detail state ("Couldn't refresh —
  key rejected").
- **G6 — No OkHttp cache:** one line (`Cache(context.cacheDir/"http", 10 MB)`)
  plus offline revalidation for METAR/Open-Meteo traffic.
- **G8 — ADB timestamp parse order:** `Instant.parse` on minute-precision strings
  throws-and-recovers via the `OffsetDateTime` fallback on older Android
  `libcore`; flip the order so the common path doesn't rely on `runCatching`.
- **G9 — Test gaps:** no tests for `NotificationPlanner` (delay bucket
  re-notification, gate-appears vs gate-changes), `InstanceSelector` date-pinning
  path, or `CadencePolicy` around the exact 48 h boundary. (`MetarDecoder` heavy
  precip is now covered by #24.)
- **G10 — Follow-up from #20 (new):** the root cause of B4 is that detail
  ViewModels are Activity-scoped under the hand-rolled navigation and are never
  cleared — memory for snapshots/tracks/jobs grows with every flight opened.
  Consider nav-entry-scoped ViewModels (or Navigation 3, the original PLAN.md
  pick) so `onCleared` fires on pop and the visibility gate isn't load-bearing.

---

## Open performance items

- **P2 — Ribbon/weather recomputed on every snapshot insert:**
  `FlightDetailViewModel.onSnapshot` gates on `fetchedAt`, which changes every
  refresh even when times are identical → suncalc over ~1000 samples plus two
  weather HTTP calls per refresh. Gate on (route, wheelsUp, wheelsDown); refresh
  METARs on their own longer cadence.
- **P3 — First-launch reference import races the UI:** airport enrichment silently
  returns nulls while the 750 KB CSV import runs (no coords → no map/tz) and never
  retries once import completes. Trigger re-enrichment when import finishes.
- **P4 — List rows recompose wholesale on any row change** — `contentType` and
  stable row classes are cheap insurance at larger flight counts.
- **P5 — Theme flash on cold start** (partially fixed by #25's dark window theme):
  a Cockpit user with a light-mode OS still gets one light frame because the theme
  is collected with `initialValue = DAYLIGHT_DYNAMIC`. Read the first value
  blocking (tiny proto read) or cache the last theme in a fast-path pref.
- **P6 — No baseline profile / macrobenchmark** (PLAN M4's `:benchmark` module).
- **P7 — GeoJSON string building on the composition thread**
  (`MapLibreRouteMap`) — trivial today, worth moving off the hot path if the map
  grows layers.

---

## Visual & layout backlog (the "premium iOS" gap)

The bones are good (cards, gradient hero, honest labels). What separates it from a
high-end iOS app is typography, motion, and state transitions:

- **V1 — No typography system.** Default Material 3 / Roboto everywhere; no
  display face, no weight rhythm, and — most visibly now that countdowns tick —
  **no tabular figures**: digits wiggle as they change. A `Typography` with a
  distinctive display family for airport codes/countdowns and
  `fontFeatureSettings = "tnum"` on all numeric text would transform the feel.
  *Biggest single aesthetic lever; needs a font choice from the owner.*
- **V2 — No motion design.** Screen changes are hard cuts (`MainActivity`
  switches composables directly — no `AnimatedContent`/shared-element
  transition); status words snap. Even slide/fade navigation transitions buy
  disproportionate polish. (#21 added list-item animation.)
- **V3 — No haptics** anywhere (PLAN M4 promises them). Pull-to-refresh
  completion, swipe-action thresholds, and phase transitions (wheels down!) are
  the natural moments.
- **V4 — Status chip contrast:** `StatusWord` always uses white text; on the
  amber `statusDelayed` (light themes) that's ~2.6:1 — fails WCAG AA. Use
  per-chip on-colors.
- **V5 — Empty map card:** with unknown coordinates `MapCard` renders a header
  and attribution and nothing else. Placeholder ("Map appears when the route is
  known") or hide.
- **V6 — Add sheet** (keyboard fixed in #23): still a plain text field for the
  date — use a `DatePickerDialog`; no loading state while resolving; lone
  right-aligned "Track" button.
- **V7 — Ribbon niggles:** weather glyphs are start-aligned in their weight cells
  (drift left of their sample positions); sunrise/sunset times render in *device*
  timezone unlabeled; 2+ events can crowd the center row on narrow phones;
  aircraft marker colors are hardcoded white/blue ignoring theme.
- **V9 — Detail density** (list belt fixed in #21): `KeyFacts` reuses the `Tag`
  icon for check-in and registration; registration duplicates in `AirlineCard`.
- **V10 — Top bars don't collapse** — no `scrollBehavior`; large-title-collapsing
  top bars are half the "expensive iOS" gestalt.
- **V12 — No landscape/tablet layout:** single column everywhere; detail in
  landscape should be two-pane (map+hero | facts+timeline). PLAN M4 requires a
  large-screen pass.
- **V13 — Timeline shows derived Check-in/Boarding rows days after departure** —
  collapse or dim past derived rows; "Gate arrival" next to a "Landed" status
  word reads inconsistently ("On blocks"/"At gate").
- **V14 — Cockpit theme is the app's best asset and it's buried** — consider
  making theme part of onboarding, or auto-engaging it in flight (I7).

---

## Missing features (value ÷ effort ordered)

- **F2 — Auto-archive landed flights** after ~24 h into a "Past flights" section —
  keeps the departure board calm without gardening. *(S)*
- **F3 — Share a flight** — share sheet with compact status text ("LX1612 GVA→PEK
  · departs 14:30 gate A61 · on time"); optionally a rendered share-card image.
  Every "when do you land?" text is this feature. *(S/M)*
- **F4 — Add to calendar** — flight as a calendar event, gate/terminal in the
  location field. *(S)*
- **F5 — "Boarding" as a real status** — `FlightStatus` lacks BOARDING; ADB
  `boarding`/`gateClosed` collapse into SCHEDULED and `derivedBoardingAt` never
  surfaces as a status word even though `strings.xml` ships "Boarding". At the
  gate is exactly when people stare at this app. *(M)*
- **F6 — Ongoing in-flight notification** (Android's Live-Activity answer):
  `Notification.ProgressStyle` on API 36+ with plane tracker icon, plain progress
  notification below — posted while EN_ROUTE, updated by the existing worker
  (PLAN §13 specifies it). *(M)*
- **F7 — "Next flight" Glance widget** — countdown · gate · status; PLAN M4 calls
  it "cheap once the state layer exists", and it exists. *(M)*
- **F8 — Gate-assigned notification** — the planner only fires on gate *change*;
  the first assignment is at least as valuable. *(S)*
- **F9 — "Delay recovered" notification** — delay shrinking / back on time is
  never reported. *(S)*
- **F10 — Layover awareness** — when two tracked flights chain (arr airport =
  next dep airport within 24 h), show connection time and warn when a delay eats
  the buffer. Flighty's killer feature; the data is already local. *(M)*
- **F11 — Trip grouping** — group flights by trip with a shared alias (PLAN v2). *(M)*
- **F12 — Per-flight notification profiles** (PLAN §12.1) — currently only three
  global toggles. "Mom's flight: landed only." *(M)*
- **F13 — Flight log / Passport stats** — km flown, hours, airports, airlines,
  aircraft types, yearly shareable card; all on-device (PLAN v2). Needs summary
  rows retained past the 3-day prune. *(L)*
- **F14 — Export/import** user data (PLAN M4). *(S)*
- **F15 — In-app airline/airport info** — alliance, check-in URL, Maps deep link. *(S)*
- **F16 — Offline projected mode** — pre-compute the expected timeline for
  airplane mode (PLAN §13); today the detail view just goes stale silently. *(L)*
- **F17 — Alias editing after creation** — `setAlias` DAO exists; no UI. Fold
  into a detail-screen overflow menu (rename / archive / delete). *(S)*

---

## Ideas (novel / delightful / quirky)

- **I2 — Bird-flight pull-to-refresh** — replace the stock spinner with the bird
  silhouette flapping along the pull arc; release = a little swoop. The icon is
  already a bird; nobody forgets an app with a signature gesture animation.
- **I3 — Wheels-down haptic heartbeat** — a soft double-pulse when status flips
  to LANDED while the screen is on (you're at arrivals; the phone taps you).
- **I4 — Split-flap status transitions** — animate status-word changes
  (ON TIME → DELAYED) with a Solari-board flip; PLAN v2 plans a whole Solari
  theme, but one flipping chip is a weekend and reads as pure luxury.
- **I5 — Jet-lag ribbon extension** — the daylight engine knows light bands at
  both ends; a small "body clock" strip (shift = +7 h; suggested light exposure
  tonight) turns existing data into a caring feature.
- **I6 — Airline-colored monograms** — a tiny bundled IATA→brand-color table
  (top ~100 carriers), falling back to today's hash palette. Cheap; the list
  instantly looks "designed".
- **I7 — Cockpit auto-engage** — offer to switch to the Cockpit theme during the
  airborne window at night ("dim cabin lights"), reverting after landing.
- **I8 — Ribbon scrubbing** — drag along the ribbon to read time/position/weather
  at that fraction (the samples are all in memory).
- **I9 — Landed confetti** (PLAN delighter) — one-shot particle burst on ARRIVED,
  brand colors, respecting reduced-motion.
- **I10 — Moon and stars on night segments** — suncalc has `MoonIllumination`;
  red-eyes get a tiny waxing gibbous over the dark stretch.
- **I11 — "Point at the sky"** AR long-shot (PLAN) — parked, correctly, as
  v-later.

---

## Suggested next cycle

1. Merge the open PRs (order: #17→#25, #19→#21→#23, rest independent), then #16
   to land everything on `main`.
2. Quick wins: B9 (verify against a live ADB payload first), G4, G6, F8, F9, F17.
3. The two aesthetic levers: V1 typography (owner picks a face) + V2 motion.
4. One flagship feature: F6 ongoing notification or F10 layover awareness.
5. Structural: G10 navigation rework (unlocks proper VM scoping, predictive-back
   transitions, and removes the B4 workaround).
