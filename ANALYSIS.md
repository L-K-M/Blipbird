# Blipbird — Analysis & backlog

Living analysis document, successor to `REVIEW.md` and `ds4.md`. Tracks open
bugs, issues, performance, visual/a11y backlog, missing features, and ideas.
Items with active PRs are marked `[PR #N]`; items landed on `main` have been
removed. This is the single source of truth for future work.

---

## Open bugs

### B9 · Delay notifications never fire from AeroDataBox status-only delays — MEDIUM [PR #53]
`NotificationPlanner.diff` compares `scheduled` vs `estimated`; ADB frequently
reports delays via a *status* of `delayed` with no revised time, which the
planner ignored entirely. Fix PR #53 adds status-only DELAY emission (as
fallback; suppressed when a timed-slip event is also produced).

### B12 · Reference data never re-imports after an app update — MEDIUM [PR #41]
`ReferenceImporter` guards on `airportCount() > 0`; a shipped update with
regenerated CSVs never reaches the database. Fix: version the import.

### glm 1.13 · Near-antipodal routes divide by ~0 — MEDIUM [PR #48]
`GreatCircle.intermediate()` guards the coincident case but not the antipodal
case (`d ≈ π` → `sin(d) ≈ 0`). Fix PR #48 adds a pole-route fallback.

### glm 1.14 · Antimeridian split leaves a one-segment gap — LOW (visual) [PR #48]
`GreatCircle.routeSegments` / `splitAtAntimeridian` start the new segment
without inserting the interpolated ±180° vertex. Fix PR #48 inserts seam
vertices at date-line crossings.

### glm 1.15 · Night-side polygon frozen when there's no live fix — MEDIUM [PR #49]
`MapLibreRouteMap` keys the terminator on `lastFix?.at?…` — null forever on
planned-route view. Fix PR #49 keys on wall-clock deciminutes.

### glm 1.10 · Cancelled/diverted/departed/landed re-fire after 3-day prune — MEDIUM
`EmittedEvent.expiresAt` shares the snapshot TTL; after prune the next refresh
sees `previous == null` and re-emits the transition.

### glm 1.11 · `FlightRepository.delete()` is not transactional — LOW
Four DAO calls across two DBs; a crash mid-way orphans rows.

### glm 1.16 / B20 · Position lookup doesn't verify right aircraft — LOW
First result with lat/lon wins; no cross-check against the snapshot's
`icao24`/registration or route/time corridor.

### glm 1.19 · `goAsync()` receivers have no timeout — LOW
`ReminderAlarmReceiver`/`BootCompletedReceiver` run Room/IO work on a
free-standing scope; boot reconcile should enqueue a `OneTimeWorkRequest`.

### glm 1.20 · `OpsDatabase` destructive migration holds `quota_ledger` — MEDIUM
A future schema bump silently zeroes API-credit accounting. Move the ledger to
the user DB or add real migrations for that table.

### glm 1.22 · Delay-notification copy under-reports — LOW [PR #54]
A 29-min slip renders "Delayed 15m" (bucket floor). Fix PR #54 computes real
minutes from the event's old/new timestamps, falling back to the bucket value.

### glm 1.18 · Tangential sunrise/sunset sample can be lost — LOW [PR #47]
`DaylightEngine.findCrossings` drops a sample landing exactly on the
threshold. Fix PR #47 changes the guard to `e0 * e1 > 0 || e0 == 0.0`.

### B18 · Quota ledger check-then-record race — LOW
`canSpend` + `record` are non-atomic; bounded overshoot near the soft stop.

### B21 · MapLibre track source keyed on size only — LOW [PR #49]
`remember(track.size)` — pruned+appended track of equal length renders stale.
Fix PR #49 keys on last-fix timestamp XOR'ed with size.

### B23 · Landed flights sort above imminent departures — LOW [PR #45]
Sorting by `nextEventAt` lets a landed flight outrank one departing soon.

### B14n · Notification ID hash collisions possible — LOW
The `stableId` uses a mixed-hash; provably collision-free alternative:
`floorMod(flightId, 500M) * 4 + channelIndex`.

### DS4-B17 · `TrackedFlightEntity.dateLocal` String parse risk — LOW
`dateLocal` parsed with `LocalDate.parse()` without `runCatching` in refresh
path. Defence-in-depth needed.


## Open general issues

### G1 · Hardcoded English strings — MEDIUM
"Departs in" / "Lands in" / "Landed" in `FlightListScreen.phaseTime` and
`FlightDetailScreen.Hero`, "Boarding" / "Pushback" / "Takeoff" / "Landing" /
"Gate arrival" in `Timeline`, `ATTRIBUTION_TEXT` in settings, map attribution,
"Weather data by Open-Meteo.com".

### G2 · Dead code and dead resources — LOW [PR #44]
`RouteMap.kt`, unused strings (`status_boarding`, `timeline_*`, etc.), unused
`onboardingDone` setting, unused DAO queries (`latestTwo`/`history`/`aliased`).

### G4 · `.idea/` files committed to git — LOW [PR #44]
`noctule.xml` and possibly other files committed despite `.gitignore`.

### G5 · Error observability missing — MEDIUM
Provider failures collapse to `null`/empty. UI can't distinguish "no key" /
"quota exhausted" / "rate limited" / "offline". Thread last error into state.

### G6 · No OkHttp cache — MEDIUM [PR #52]
Fix PR #52 adds 10 MB cache for offline revalidation.

### G8 · ADB timestamp parse order — LOW [PR #43]
Flip `Instant.parse` and the `OffsetDateTime` fallback so the common
minute-precision path doesn't rely on `runCatching`.

### G9 · Test gaps — MEDIUM
`NotificationPlanner` (delay bucket re-notification, gate-appears vs changes,
status-only delay), `InstanceSelector` date-pinning path, `CadencePolicy` at
the exact 48 h boundary.

### G10 · Detail ViewModels are Activity-scoped and never cleared — HIGH
Root cause of B4; the #20 gate treats the symptom. Transition to
nav-entry-scoped ViewModels or Navigation 3.

### glm 7.1 · Contributor docs missing — LOW
`CONTRIBUTING.md`, `GLOSSARY.md`, `SECURITY.md`, `.editorconfig`,
`.gitattributes`, `docs/decisions/` template.

### DS4-G9 · `phaseTime` calls `Instant.now()` in composable — LOW
Unstable composable; pass `now` through a CompositionLocal or row data.

### DS4-G10 · List doesn't show data-freshness per row — LOW
Only the detail screen shows "Updated X ago". Show per-row staleness in list.

### DS4-G11 · Quota `∞` has no contentDescription — LOW [PR #42]


## Open performance items

### P2 · Ribbon/weather recomputed on every snapshot insert — MEDIUM
`fetchedAt` gate re-runs suncalc (~1000 samples) + two weather calls per
refresh even when times are identical.

### P3 · First-launch reference import races the UI — MEDIUM
Enrichment returns nulls during CSV import and never retries.

### P4 · List rows recompose wholesale on any row change — LOW
Add `contentType` + stable row data classes.

### P5 · Theme flash on cold start — LOW
Theme collected with `initialValue = DAYLIGHT_DYNAMIC`. Read first value
blocking or cache in fast-path pref.

### P6 · No baseline profile / macrobenchmark — MEDIUM

### P7 · GeoJSON string building on the composition thread — LOW

### glm 2.3 · 11-way detail `combine` rebuilds everything per fix — MEDIUM
Every position fix triggers full `DetailUiState` rebuild. Split map-only state.

### glm 2.1/2.2/2.4 · Brushes/Paths built inside draw lambdas — LOW
Hoist ribbon gradient, hero brush, progress bar gradient into `remember`.

### glm 2.7 · Monograms redrawn per row — LOW


## Visual, layout & accessibility backlog

### V1 / glm 3.1-3.2 · No typography system, no tabular figures — HIGH
Default Roboto; countdown digits wiggle as they tick. Single biggest aesthetic
lever. PLAN §10.2 specifies the target. Needs font choice from owner.

### V2 · No motion design — MEDIUM
Screen changes are hard cuts. Slide/fade transitions + shared-element
transitions buy disproportionate polish.

### V3 · No haptics — LOW
Pull-to-refresh completion, swipe thresholds, wheels-down.

### V4 / glm 3.3 · `StatusWord` white text fails WCAG AA on amber/neutral — LOW [PR #50]
Fix PR #50 adds per-chip on-colors: black text on amber/diverted, white
on dark backgrounds.

### V5 · Empty map card renders skeleton — LOW
When coordinates are unknown, show placeholder or hide the card.

### V6 · Add sheet: no DatePickerDialog, no loading state — LOW
Plain text field for date; no loading indicator during identity resolution.

### V7 / glm 3.4-3.6 · Ribbon niggles — MEDIUM
Weather glyphs drift left of sample positions; sunrise/sunset times in device
TZ; events crowd on narrow phones; marker colors hardcoded; raw emoji tofu
risk; civil twilight band now added [PR #55]. Remaining: weather glyph spatial
alignment, times in flight-local TZ, theme-colored markers.

### V9 · Detail density inconsistencies — LOW
`Tag` icon reused for check-in and registration; registration duplicated on
`AirlineCard`; `Domain` icon reused for terminal.

### V10 · Top bars don't collapse — MEDIUM
Large-title collapse via `scrollBehavior` / `nestedScrollConnection`.

### V13 · Timeline shows stale derived rows days after departure — MEDIUM [PR #55]
Fix PR #55 hides Check-in and Boarding rows once the flight has departed.

### V14 · Cockpit theme background is pure `#000000` — LOW [PR #50]
Fix PR #50 changes Cockpit `background` to `#05050A` to avoid penTile smear.

### glm 3.8 · Plane glyphs always point right — LOW
A LHR→NRT (west→east) flight visually flies backwards. Orient by bearing.

### glm 3.10 · No loading skeletons — MEDIUM
PLAN §9.2 specifies skeleton loading.

### glm 4.1 · Back arrows have no contentDescription — LOW [PR #50]
Fix PR #50 provides `"Back"` contentDescription via `R.string.back`.

### glm 4.2 · Map and ribbon have no semantics — LOW

### glm 4.3 · Timeline row hard-pinned to 44 dp — LOW [PR #42]
Clips at 200% font scale. Use `minHeight`.

### glm 4.4 · List rows aren't `mergeDescendants` — LOW [PR #42]
TalkBack reads 3 swipe stops per row.

### V12 · Large-screen pass — LOW
List/tablet grid and landscape-specific layouts open after detail two-pane.

### DS4-V19 · Ribbon weather glyph alignment broken — LOW
Evenly spaced via `weight(1f)` vs. geometric along great-circle path.

### DS4-V20 · `phaseTime` countdown shows 0m for just-passed times — LOW
`countdownText` clamps negative durations to 0m instead of switching format.

### DS4-V21 · Detail screen countdown freezes between clock ticks — LOW
Hero countdown uses `Instant.now()` inside `combine` → frozen between 15 s
ticks. List recomputes live. Synchronize both to same source with smoothing.


## Missing features

### F2 · Auto-archive landed flights after ~24 h — SMALL

### F3 · Share a flight — SMALL/MEDIUM [PR #46]

### F4 · Add to calendar — SMALL [PR #46]

### F5 · "Boarding" as a real status — MEDIUM
ADB `boarding`/`gateClosed` collapse into SCHEDULED.

### F6 · Ongoing in-flight notification — MEDIUM

### F7 · "Next flight" Glance widget — MEDIUM

### F8 · Gate-assigned notification — SMALL
Planner only fires on gate CHANGE. Add gate ASSIGNED event.

### F9 · "Delay recovered" notification — SMALL [PR #53]
PR #53 adds `DELAY_RECOVERED` event type.

### F10 · Layover awareness — MEDIUM

### F11 · Trip grouping — MEDIUM

### F12 · Per-flight notification profiles — MEDIUM

### F13 · Flight log / Passport stats — LARGE

### F14 · Export/import user data — SMALL

### F15 · In-app airline/airport info — SMALL

### F16 · Offline projected mode — LARGE

### F17 · Rename from detail screen — SMALL

### glm 5.3 · Day grouping in list — SMALL

### DS4-F18 · Clone/combine flights — SMALL
"Track return flight" button on detail screen.


## Ideas (novel / delightful / quirky)

- **I1** · Window-side sunrise/sunset callout — LANDED (#29)
- **I2** · Bird-flight pull-to-refresh
- **I3** · Wheels-down haptic heartbeat
- **I4** · Split-flap status transitions
- **I5** · Jet-lag ribbon extension
- **I6** · Airline-colored monograms
- **I7** · Cockpit auto-engage during night + airborne
- **I8** · Ribbon scrubbing
- **I9** · Landed confetti
- **I10** · Moon and stars on night segments
- **I11** · "Point at the sky" AR
- **I12** · Shared flight tracking / live link
- **I13** · Tail number spotting badges
- **I14** · Altitude profile sparkline
- **I15** · Timezone-hopping visual


---

## Suggested next cycle

1. **Merge pending PRs**: merge order matters — #47/#48/#49/#50/#52 are
   independent; #53 then #54 (both touch NotificationEmitter); #55 independent.

2. **Quick wins not yet in PR**: G5 (error observability — a single error
   state field), V5 (empty map placeholder), V20 (0m countdown), F17 (rename
   from detail), glm 3.8 (plane bearing in list).

3. **Aesthetic levers**: V1 typography + V2 motion — the two biggest visual
   quality jumps.

4. **Structural**: G10 navigation rework (VM scoping); glm 1.20 quota ledger
   migration safety.

5. **Features**: F6 ongoing notification, F10 layover awareness, F2 auto-archive.
