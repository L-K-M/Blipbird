# Blipbird — Fable review (July 2026)

A full-codebase review of Blipbird v0.1: every Kotlin file, resource, manifest, Gradle
file, CI workflow and script was read. Findings are grouped as **B**ugs, **G**eneral
issues, **P**erformance, **V**isual/layout, **F**eature gaps, and **I**deas. Each entry
carries a severity, anchor (`file:line`), and a confidence tag where the finding could
not be verified on a device (this review environment has no Android SDK/emulator).

Overall: the architecture is genuinely good — clean provider interfaces, pure JVM
decision cores with tests, sensible two-database split, honest data labeling. The gaps
are mostly in the *living* qualities of the app: things that tick, animate, respond,
and recover — plus a handful of real correctness bugs listed first.

---

## B — Bugs

### B1 · Back stack is lost on rotation and process death — HIGH
`MainActivity.kt:73` — the hand-rolled navigation stack lives in
`remember { mutableStateListOf<Screen>(...) }`. `remember` survives recomposition but
not Activity re-creation, so rotating the phone (or theme change on some OEMs, or
process death in background) while viewing a flight kicks the user back to the list.
**Fix:** `rememberSaveable` with a custom `Saver` (Screen is a tiny sealed type —
encode as `List<Long>` where `-1` = list, `-2` = settings, `id` = detail).

### B2 · Locale-dependent number formatting corrupts API requests and GeoJSON — HIGH
`WeatherRepository.kt:56-57` builds Open-Meteo coordinate lists with
`"%.3f".format(...)`, and `MapLibreRouteMap.kt:153,162,170` builds GeoJSON with
`"[%.5f,%.5f]".format(...)`. `String.format` uses the default locale: on a device set
to French/German/most European locales, `47.462` renders as `47,462` — the Open-Meteo
query becomes garbage (silently caught → empty route weather forever) and the GeoJSON
is invalid (route/track/night layers silently disappear). The developer's own locale
region (Switzerland) is affected in French/Italian.
**Fix:** `String.format(Locale.ROOT, …)` / `"%.5f".format(Locale.ROOT, …)` at every
machine-facing format call site. (Display-facing `%,d` formatting can stay locale-aware.)

### B3 · Countdowns and progress bars never tick — HIGH
`FlightListViewModel.kt:74-90` derives `FlightPhaseMachine.View` (and thus
"Departs in 2h 13m", progress fraction, status word) only when a Room snapshot
emission arrives. Between refreshes (15+ min in background cadence; forever with no
key) the numbers are frozen. Same on the detail hero (`FlightDetailScreen.kt:246-248`
reads `Instant.now()` but only recomposes on state change; `FlightDetailViewModel.kt:118`
has no clock source). A flight tracker whose countdown doesn't count is the single
most visible "broken" feel in the app.
**Fix:** combine a 30–60 s ticker flow into both view models' state derivation so
`derive(...)` re-runs on the tick; statuses will also roll over (ON_TIME → DEPARTED)
without a fetch.

### B4 · Detail ViewModels and their poll loops leak for the whole session — HIGH
`FlightDetailScreen.kt:76` uses `hiltViewModel(key = "flight-$flightId")` under
hand-rolled navigation, so every keyed VM is scoped to the *Activity* and is never
cleared when the user navigates back. Each one runs `startPolling`
(`FlightDetailViewModel.kt:246-268`): an ADS-B network hit every 10 s while a flight
is airborne, every 120 s otherwise — even after leaving the screen, even with the app
backgrounded (`viewModelScope` doesn't care about lifecycle). Open five details during
a trip and five loops poll concurrently until the process dies.
**Fix:** gate the loop on `uiState.subscriptionCount > 0` (the `stateIn` already uses
`WhileSubscribed`), or drive polling from a `DisposableEffect` in the screen.

### B5 · No UI exists to delete, archive, or rename a flight — HIGH
`FlightListViewModel.kt:127-128` has `archive()`/`delete()`, `strings.xml:16-17` has
"Archive"/"Delete", the README promises "swipe actions" — but no composable calls any
of it. Tracked flights accumulate forever; the only cleanup is uninstalling the app.
Alias editing after creation is equally impossible.
**Fix:** swipe actions on list rows (`SwipeToDismissBox`) with an undo snackbar,
plus an overflow menu on the detail screen (rename alias / archive / delete).
Also cancel `ReminderScheduler` alarms on archive/delete (currently only guarded for
*deleted* flights by a null check at `ReminderScheduler.kt:134`; archived flights keep
firing reminders because the entity still exists).

### B6 · AeroAPI date window misses red-eye flights — MEDIUM
`StatusProviders.kt:133-135`: for a user-entered *departure-airport-local* date the
AeroAPI query window is `dateT00:00:00Z … (date+1)T23:59:59Z`. Any flight whose local
date maps to an earlier UTC day is outside the window — e.g. a 00:30 JST departure on
2026-07-25 departs 2026-07-24T15:30Z and is never found, while AeroDataBox (passed the
local date natively) finds it. Asymmetric fallback behavior.
**Fix:** start the window at `date-1` (the instance selector already disambiguates).

### B7 · Add-flight errors are invisible — MEDIUM
`FlightListScreen.kt:108-117`: `onAdd` fires `viewModel.addFlights(...)` and
immediately sets `showAddSheet = false`. Parse errors land in `state.addError`
(`FlightListViewModel.kt:96-102`) but the sheet that displays them is already gone;
the stale error then greets the user the *next* time they open the sheet. A typo'd
flight number today just… does nothing.
**Fix:** keep the sheet open on error, or surface errors via snackbar on the list.

### B8 · Notification tap handling recreates the Activity and misroutes — MEDIUM
`NotificationEmitter.kt:94-97` uses `FLAG_ACTIVITY_CLEAR_TOP` with standard
launchMode and `MainActivity` never overrides `onNewIntent` (`MainActivity.kt:48-66`
reads the deep link only in `onCreate`). Tapping a notification while the app is open
tears down and recreates the Activity (state loss, visual jank); the deep link works
only by accident of that recreation.
**Fix:** `launchMode="singleTask"` (or singleTop) + `onNewIntent` routing into the
saved back stack.

### B9 · Delay notifications can never fire from AeroDataBox data — MEDIUM
`NotificationPlanner.diff` (`NotificationPlanner.kt:38-46`) compares
`scheduled` vs `estimated`. The ADB mapper (`StatusProviders.kt:93-102`) populates
`estimated` from `revisedTime ?: predictedTime` — fine — but ADB frequently reports
delays via a *status* of `delayed` with no revised time, mapped at
`StatusProviders.kt:78` to `FlightStatus.DELAYED`, which the planner ignores entirely
(no event type for it). A "Delayed" push is the #1 reason to install a flight tracker.
**Fix:** emit a DELAY event when `current.status == DELAYED && prev?.status != DELAYED`
even without timestamps (text: "Flight reported delayed"), keeping the bucketed
fingerprint path when times exist. *(Confidence: high on code reading; ADB payload
variance not verifiable offline.)*

### B10 · MetarDecoder: `+RA`/`+SN` can never match, and other decoding gaps — MEDIUM
`MetarDecoder.kt:49-51`: WX codes are interpolated raw into
`Regex("(^| )$code( |$)")`, so `+RA` becomes `(^| )+RA( |$)` — the `+` turns into a
quantifier and the literal `+RA` token never matches: heavy rain/snow METARs show *no*
weather at all. Also: only the first phenomenon is reported (`break` at line 50);
`VIS_M` (line 11) is defined but never used, so visibility is never decoded; wind
*direction* is parsed but omitted from the text (line 57-60); `VRB` and calm winds
read oddly ("wind 0 kt").
**Fix:** `Regex.escape(code)`, collect all phenomena, decode visibility, include wind
direction, special-case calm/variable.

### B11 · Status-bar icons wrong in dark/Cockpit themes — MEDIUM
`themes.xml:6` pins `android:windowLightStatusBar=true` with no `values-night`
variant, and `MainActivity.kt:50` calls `enableEdgeToEdge()` which styles bars from
the *system* light/dark setting — not the app theme. In Cockpit (forced dark) on a
light-mode device, dark status-bar icons sit on a near-black background: unreadable.
There's also a white flash at cold start in dark themes (no splash theme).
**Fix:** re-invoke `enableEdgeToEdge(statusBarStyle=…, navigationBarStyle=…)` from
inside `setContent` keyed on the resolved theme; add `values-night/themes.xml`;
consider the SplashScreen API.

### B12 · Reference data never re-imports after an app update — MEDIUM
`ReferenceImporter.kt:22` guards on `airportCount() > 0`, so a shipped update with a
regenerated `airports.csv`/`airlines.csv` never reaches the database; users keep
first-install data forever (new airports, renamed airlines, tz fixes never land).
**Fix:** version the import — store the lockfile's `fetched` date (or a hash of the
CSVs) and re-import (clear + insert) when it changes.

### B13 · RefreshWorker runs every 15 minutes forever, even with zero flights — MEDIUM
`BlipbirdApp.kt:31` schedules the periodic worker unconditionally and nothing ever
cancels it (`RefreshWorker.kt:70-77`). With no tracked flights (or all archived) the
device still wakes every 15 min for a no-op DB query — measurable idle battery cost
for a "calm" app.
**Fix:** cancel the unique work when `activeFlights()` is empty at the end of
`doWork`; re-schedule whenever a flight is added (small platform-side
`BackgroundRefreshController` interface, mirroring the `NotificationSink` pattern).

### B14 · Notification IDs can collide across channels — LOW
`NotificationEmitter.kt:111`: `id = flightId*10 + channel.hashCode() % 10`.
`hashCode()%10` may be negative and two channels' last digits can coincide, so a
"gate change" can overwrite a "delayed" notification for the same flight (and the
`% Int.MAX_VALUE` doesn't prevent Long→Int overflow artifacts).
**Fix:** stable small channel index (0/1/2) and a documented ID scheme.

### B15 · Negative countdowns render as "Lands in -2h 15m" — LOW
`Common.kt:52-62` faithfully renders negative durations with a minus sign; with stale
data (`FlightPhaseMachine.kt:72` derives DEPARTED from a past `bestDep` alone) the
list happily shows "Lands in -3h 5m".
**Fix:** clamp: past-due countdowns should read "any moment" / "overdue" (and the
phase machine could fall back to UNKNOWN when `bestArr` is long past with no actuals).

### B16 · Exact-alarm button state goes stale — LOW
`SettingsScreen.kt:126-136` re-checks `canScheduleExactAlarms()` immediately after
`startActivity` — i.e. before the user has granted anything. Returning from system
settings leaves the button un-updated until the screen is rebuilt.
**Fix:** re-check in a `LifecycleResumeEffect`.

### B17 · Saved API keys cannot be cleared, and toggling reminders doesn't cancel alarms — LOW
`SettingsScreen.kt:180-201`: the save button only appears for non-blank input, so a
stored key can never be removed. `SettingsViewModel.kt:60-62`: switching *Reminders*
off doesn't call `ReminderScheduler`; already-scheduled exact alarms fire until the
next refresh-path reconcile.
**Fix:** add a clear affordance per key; reconcile alarms on toggle.

### B18 · Quota ledger check-then-record race — LOW
`QuotaLedger.kt:28-34` + `FlightRepository.kt` call `canSpend` then `record`
non-atomically; simultaneous pull-to-refresh + worker can both pass the check near
the soft stop. Bounded overshoot, but the SQL upsert (`Daos.kt:107-111`) could easily
be a single conditional statement.

### B19 · ADB `CanceledUncertain` status unmapped — LOW
`StatusProviders.kt:73-82` maps `canceled`/`cancelled` but AeroDataBox also emits
`CanceledUncertain`, which falls to `UNKNOWN` — a probably-cancelled flight shows as
"Unknown" with no notification. **Fix:** map it to CANCELLED (or DELAYED + note).
*(Confidence: medium — based on documented ADB status values.)*

### B20 · Position lookup doesn't verify it found the right aircraft — LOW
`PositionProvider.kt:41` takes the first result with a lat/lon for a
callsign/registration query and `FlightRepository.pollPosition` persists it. No
cross-check against the snapshot's `icao24`/registration when known — a stale or
colliding callsign paints someone else's track. PLAN.md M2 exit criteria explicitly
call for collision suppression.

### B21 · MapLibre track source keyed on size only — LOW
`MapLibreRouteMap.kt:77` `remember(track.size)` — a pruned+appended track of equal
length renders stale geometry. Key on last fix timestamp + size.

### B22 · `DaylightEngine` bisection early-exit is a no-op — LOW (code health)
`DaylightEngine.kt:119-124`: `return@repeat` as the last statement of the lambda just
continues the loop; the intended precision early-exit never happens (harmless — it
always runs all 24 iterations).

### B23 · Landed flights sort above imminent departures — LOW (design)
`FlightListViewModel.kt:66` sorts by `nextEventAt`; a flight landed 2 h ago
(`nextEventAt` = landing time, past) outranks one departing in 45 min. Active/upcoming
flights should lead; landed ones should sink (and eventually auto-archive — see F2).

---

## G — General issues

- **G1 — Hardcoded English strings** scattered through composables while a full
  `strings.xml` exists: "Departs in"/"Lands in"/"Landed" (`FlightListScreen.kt:186-190`),
  timeline labels "Boarding"/"Pushback"/"Takeoff"/"Landing"/"Gate arrival"
  (`FlightDetailScreen.kt:449-454`), "Save"/"Granted"/"Allow precise alerts" and the
  whole `ATTRIBUTION_TEXT` (`SettingsScreen.kt`), "Weather data by Open-Meteo.com"
  (`FlightDetailScreen.kt:127`). Localization is currently impossible.
- **G2 — Dead code:** `RouteMap.kt` (the whole offline canvas map — 175 lines, no
  callers since the MapLibre migration), `ProviderKeyStore`'s… fine; unused strings
  (`status_boarding`, `timeline_scheduled/estimated/actual`, `estimated_chip`,
  `sunrise_at`, `sunset_at`, `ribbon_unavailable`, `weather_unavailable`,
  `attribution_openfreemap`, `onboarding_keys_skip`), unused `onboardingDone`
  setting (`SettingsRepository.kt:37,43`), unused `VIS_M` regex, a
  `drawCircle(Color.Transparent, …)` no-op (`FlightProgressBar.kt:57`), unused
  `latestTwo`/`history`/`aliased` DAO queries.
- **G3 — README overpromises v0.1:** "swipe actions" (absent, B5), "landed + belt"
  list line (belt never shown in the list), "raw METAR one tap away" (it's always
  visible, no tap), "connect a data source CTA" (never rendered — `hasStatusKey`
  is computed and dropped, `FlightListViewModel.kt:71`).
- **G4 — `.idea/` files are committed** (`.idea/noctule.xml` — a rename remnant from
  the project's former name) although `.gitignore` lists `.idea/`. Remove from git.
- **G5 — Error observability:** every provider failure collapses to `null`/empty
  (`StatusProviderChain` drops `StatusResult.Error` messages; weather repo catches
  all). The UI can't distinguish "no key" / "quota exhausted" / "rate limited" /
  "offline" — all render as silent nothing. At minimum thread the last error into
  the list/detail state ("Couldn't refresh — key rejected").
- **G6 — No network cache:** `AppModule.kt:40` builds OkHttp without an HTTP cache
  directory; METAR/Open-Meteo/tile-adjacent requests re-fetch fully. One line
  (`Cache(context.cacheDir/"http", 10MB)`) plus it enables offline revalidation.
- **G7 — MetarDecoder test is locale-dependent** (`SupportingTest.kt:106` expects
  `"2,500 ft"` from `"%,d"`) — fails on a non-English dev JVM.
- **G8 — `AdbTime.instant()` depends on `Instant.parse` accepting minute-precision
  ISO strings** (`StatusProviders.kt:68-72`), which older Android `libcore` rejects;
  the `OffsetDateTime` fallback saves it, but the ordering means every ADB timestamp
  on older devices throws-and-recovers via `runCatching` — flip the order.
- **G9 — Tests are good but thin at the edges:** no tests for `NotificationPlanner`
  (delay bucket re-notification, gate-appears vs gate-changes), `InstanceSelector`
  date-pinning path, `MetarDecoder` heavy precip (would have caught B10), or
  `CadencePolicy` around the exact 48 h boundary.

---

## P — Performance

- **P1 — Foreground poll loop runs while backgrounded** (see B4) — network + battery.
- **P2 — Ribbon/weather recomputed on every snapshot insert:**
  `FlightDetailViewModel.kt:178-182` gates on `fetchedAt`, which changes on *every*
  refresh even when times/route are identical → suncalc over ~1000 samples plus two
  weather HTTP calls per refresh. Gate on (route, wheelsUp, wheelsDown) instead;
  refresh METARs on their own (longer) cadence.
- **P3 — First-launch reference import races the UI:** 750 KB CSV parse
  (`ReferenceImporter.kt`) is async; airport enrichment silently returns nulls
  meanwhile (no coords → no map, no tz) with no retry once import completes. Trigger
  re-enrichment when import finishes, or await import before first enrich.
- **P4 — `LazyColumn` list rows recompose wholesale on any row change** — rows carry
  `view` objects recreated per emission; fine at 5 flights, but adding `contentType`
  and stable row data classes is cheap insurance. Items lack `animateItem()` so
  reorders jump-cut (also a V issue).
- **P5 — Theme flash on cold start:** `MainActivity.kt:54` collects the theme with
  `initialValue = DAYLIGHT_DYNAMIC`, so Cockpit users get a light frame before
  DataStore delivers. Read the first value in a blocking `runBlocking { first() }`
  (it's a tiny proto read) or persist last theme in a fast-path pref.
- **P6 — No baseline profile / macrobenchmark** (PLAN M4 promises a `:benchmark`
  module). Cold-start and list-scroll jank on mid-range devices go unmeasured.
- **P7 — `nightPolygon` allocates per remember-key change on the composition thread**
  (`MapLibreRouteMap.kt:87-89`); trivial today (~120 points), but it belongs off the
  hot path with the rest of the GeoJSON string building if the map grows layers.

---

## V — Visual & layout

The bones are good (cards, gradient hero, honest labels). What separates it from a
high-end iOS app is typography, motion, and state transitions:

- **V1 — No typography system.** Default Material 3 / Roboto everywhere; no display
  face, no weight rhythm, and — most visibly for a flight tracker — **no tabular
  figures**: once countdowns tick (B3), digits will wiggle. A `Typography` with a
  distinctive display family for airport codes/countdowns and
  `fontFeatureSettings = "tnum"` on all numeric text would transform the feel.
- **V2 — No motion design.** Screen changes are hard cuts (`MainActivity.kt:82-95`
  switches composables directly — no `AnimatedContent`/shared-element transition);
  list items don't animate in/out/reorder; status words snap; the add sheet is the
  only springy element (framework-provided). Even two additions — slide/fade
  navigation transitions and `Modifier.animateItem()` — buy disproportionate polish.
- **V3 — No haptics** anywhere (PLAN M4 promises them). Pull-to-refresh completion,
  swipe-action thresholds, and phase transitions (wheels down!) are natural moments.
- **V4 — Status chip contrast:** `Common.kt:40-48` always uses white text; on
  `statusDelayed` amber (light themes) that's ~2.6:1 — fails WCAG AA. Use
  color-scheme-aware on-colors per chip.
- **V5 — Empty map card:** with unknown coordinates `MapCard`
  (`FlightDetailScreen.kt:304-338`) renders a header, attribution lines and nothing
  else. Show a placeholder ("Map appears when the route is known") or hide the card.
- **V6 — Add sheet is bare:** plain text field for the date (`AddFlightSheet.kt:52-59`,
  format guesswork, no `DatePicker`), no keyboard capitalization/IME actions for
  flight numbers, no loading state while resolving, lone right-aligned "Track" button.
- **V7 — Ribbon niggles:** weather glyphs are start-aligned in their weight cells so
  they drift left of their sample positions (`Ribbon.kt:49-57`); sunrise/sunset times
  render in *device* timezone unlabeled (`Ribbon.kt:106`) — neither departure, arrival
  nor en-route zone; with 2+ events on a narrow phone the center row can crowd/wrap;
  aircraft marker colors are hardcoded white/blue ignoring theme (`Ribbon.kt:94-96`).
- **V8 — Empty state has no action** (`FlightListScreen.kt:209-223`): icon + text
  only; add a prominent "Add your first flight" button (the FAB is easy to miss on
  first run) and, when no API key is configured, the promised "Connect a data source"
  CTA (strings already exist: `onboarding_keys_title/body`).
- **V9 — List/detail density:** the list card shows terminal/gate but never baggage
  belt after landing (README promises it); "LANDED" + belt is the single most useful
  post-flight glance. Detail `KeyFacts` reuses the `Tag` icon for both check-in and
  registration; registration duplicates in `AirlineCard`.
- **V10 — Top bars don't collapse** — no `TopAppBarDefaults.scrollBehavior`, so
  scrolling the detail dossier wastes 64 dp forever; large-title-collapsing top bars
  are half the "expensive iOS" gestalt.
- **V11 — FAB overlaps the last list card** (contentPadding bottom is 16 dp; needs
  ~88 dp) — classic mid-Android tell.
- **V12 — No landscape/tablet layout:** single column everywhere; the detail dossier
  in landscape should be two-pane (map+hero | facts+timeline). PLAN M4 requires a
  large-screen pass.
- **V13 — Timeline shows derived Check-in/Boarding rows even days after departure** —
  collapse or dim past derived rows; and "Gate arrival" wording next to a "Landed"
  status word reads inconsistently ("On blocks"/"At gate").
- **V14 — Cockpit theme is the app's best asset and it's buried** — consider making
  theme part of onboarding, and auto-switching to Cockpit during the airborne window
  (delight, see I-list).

---

## F — Missing features

Ordered roughly by (value ÷ effort) for a personal flight tracker:

- **F1 — Flight management UI** (delete/archive/rename) — see B5. *(S)*
- **F2 — Auto-archive landed flights** after ~24 h into a "Past flights" section —
  keeps the departure board calm without user gardening. *(S)*
- **F3 — Share a flight** — share sheet with a compact status text ("LX1612 GVA→PEK ·
  departs 14:30 gate A61 · on time"), and optionally a rendered share-card image.
  Every "when do you land?" text message is this feature. *(S/M)*
- **F4 — Add to calendar** — insert/export the flight as a calendar event with gate
  and terminal in the location field. *(S)*
- **F5 — "Boarding" as a real status** — `FlightStatus` lacks BOARDING;
  ADB `boarding`/`gateClosed` collapse into SCHEDULED (`StatusProviders.kt:74`), and
  the derived `derivedBoardingAt` never surfaces as a status word even though
  `strings.xml:23` already ships "Boarding". At the gate is exactly when people stare
  at this app. *(M)*
- **F6 — Ongoing in-flight notification** (Android's Live-Activity answer):
  `Notification.ProgressStyle` on API 36+ with plane tracker icon, plain progress
  notification below — posted while EN_ROUTE, updated by the existing worker. PLAN
  §13 specifies it; nothing implements it. *(M)*
- **F7 — "Next flight" Glance widget** — countdown · gate · status. PLAN M4 calls it
  "cheap once the state layer exists"; the state layer exists. *(M)*
- **F8 — Gate-assigned notification** — the planner only fires on gate *change*
  (`NotificationPlanner.kt:31-35`); the first assignment is at least as valuable. *(S)*
- **F9 — "Delay recovered" notification** — delay shrinking / back on time is never
  reported. *(S)*
- **F10 — Layover awareness** — when two tracked flights chain (arr airport = next
  dep airport within 24 h), show connection time on the list and warn when a delay
  eats the buffer. This is Flighty's killer feature and the data is already local. *(M)*
- **F11 — Trip grouping** — group flights by trip with a shared alias (PLAN v2). *(M)*
- **F12 — Per-flight notification profiles** (PLAN §12.1) — currently only three
  global toggles. "Mom's flight: landed only." *(M)*
- **F13 — Flight log / Passport stats** — km flown, hours, airports, airlines,
  aircraft types, yearly shareable card; all on-device from retained snapshots
  (PLAN v2). Needs retention of *summary* rows past the 3-day prune. *(L)*
- **F14 — Export/import** user data (PLAN M4: user-authored data is backed up but
  not exportable). *(S)*
- **F15 — In-app airline/airport info** — the airline card could show alliance,
  check-in URL, contact; airports could deep-link to Maps. *(S)*
- **F16 — Offline projected mode** — pre-compute the expected timeline for airplane
  mode (PLAN §13); today the detail view just goes stale silently. *(L)*

---

## I — Ideas (novel / delightful / quirky)

- **I1 — Window-side sunset callout** ⭐ — the data is *already computed*:
  `SunEvent.azimuthDeg` + the route bearing at the event point give the cabin side.
  "🌇 Sunset 18:42 — left side of the cabin." No other tracker does this; it's pure
  Blipbird (the ribbon is the brand) and it's ~40 lines. PLAN lists it as a delighter
  behind a confidence gate; the great-circle bearing at the event fraction clears it
  for non-degenerate routes.
- **I2 — Bird-flight pull-to-refresh** — replace the stock spinner with the bird
  silhouette flapping along the pull arc; release = a little swoop. The app icon is
  already a bird; nobody forgets an app with a signature gesture animation.
- **I3 — Wheels-down haptic heartbeat** — a soft double-pulse when status flips to
  LANDED while the screen is on (you're waiting at arrivals; the phone taps you).
- **I4 — Split-flap status transitions** — animate status-word changes
  (ON TIME → DELAYED) with a Solari-board flip; PLAN v2 plans a whole Solari theme,
  but the 12-character flip on one chip is a weekend and reads as pure luxury.
- **I5 — Jet-lag ribbon extension** — the daylight engine already knows light bands
  at origin and destination; a small "body clock" strip (shift = +7 h; suggested
  light exposure window tonight) turns dead data into a caring feature.
- **I6 — Airline-colored monograms** — a tiny bundled IATA→brand-color table (top ~100
  carriers) so LX is red, LH is yellow-on-blue; falls back to today's hash palette.
  Cheap, and the list instantly looks "designed".
- **I7 — Cockpit auto-engage** — offer to switch to the Cockpit theme during the
  airborne window at night ("dim cabin lights"), reverting after landing.
- **I8 — Ribbon scrubbing** — drag a finger along the ribbon to read time/position/
  weather at that fraction (the samples are all in memory).
- **I9 — Landed confetti** (PLAN delighter) — one-shot particle burst on ARRIVED, in
  brand colors, respecting reduced-motion settings.
- **I10 — Moon and stars on night segments** — the ribbon's night band could show
  moon phase (suncalc has `MoonIllumination`) — red-eye flights get a tiny waxing
  gibbous over the dark stretch.
- **I11 — "Point at the sky"** AR long-shot (PLAN) — parked, correctly, as v-later.

---

## Implementation shortlist (high confidence, done in separate PRs)

| # | Item | Branch |
|---|------|--------|
| 1 | B1 + B8: saveable back stack, `onNewIntent` deep link | `claude/fix-backstack-deeplink` |
| 2 | B2: locale-safe machine formatting | `claude/fix-locale-formatting` |
| 3 | B3 + B15: ticking countdowns, no negative countdowns | `claude/fix-ticking-countdowns` |
| 4 | B4: detail polling tied to visible subscribers | `claude/fix-detail-polling` |
| 5 | B5 + parts of V8/G3: swipe delete/archive + undo, no-key CTA, empty-state CTA, belt on landed rows | `claude/feat-list-management` |
| 6 | B6 + B19: AeroAPI red-eye window, CanceledUncertain mapping | `claude/fix-provider-edges` |
| 7 | B7: add-sheet error handling (sheet stays open) | `claude/fix-add-sheet-errors` |
| 8 | B10 + G7: MetarDecoder fixes + locale-safe tests | `claude/fix-metar-decoder` |
| 9 | B11: theme-aware system bars | `claude/fix-system-bars` |
| 10 | B13: idle worker cancellation | `claude/fix-idle-worker` |
| 11 | B14: stable notification IDs | `claude/fix-notification-ids` |
| 12 | B16 + B17: settings polish (alarm state, key clearing, reminder reconcile) | `claude/fix-settings-polish` |
| 13 | I1: window-side sunset callout | `claude/feat-sunset-side` |

Items deliberately *not* auto-implemented (need product/owner judgment or a device):
typography overhaul (V1), motion system (V2), widgets/ongoing notification (F6/F7),
layover logic (F10), delay-status notification semantics (B9 — needs live ADB
payloads to verify), importer versioning (B12 — needs a migration decision),
auto-archive policy (F2 — retention semantics), theme flash fix (P5 — startup
blocking trade-off).
