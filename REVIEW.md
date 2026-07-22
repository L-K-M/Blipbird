# Blipbird — Review findings & work backlog

The living successor to the July 2026 review documents: the Fable full-codebase
review (`fable.md`, added and consolidated on the review branch), the GLM 5.2
review (`glm.md`, committed briefly on `glm/metar-decoder` — see that branch's
history), and the interim `ANALYSIS.md`. All three originals live in git
history; **this file is the single source of truth going forward.** IDs are
stable so PRs and discussions can keep referencing them: `B/G/P/V/F/I` from the
Fable review, `glm x.y` from the GLM review.

---

## Landed in the July 2026 review cycle

Everything below is on `main`. Where two reviews found the same bug
independently, both IDs are listed; "superseded" PRs are closed with a pointer
here.

| Area | PR(s) | Items | What landed |
|------|-------|-------|-------------|
| Plan: design tokens & premium motion | #13 | glm 1.6, 5.x, 7.x | §10.2 token system (typography/motion/shapes/elevation), phase-adaptive facts grid, vertical timeline, skeleton loading policy |
| Plan: delighters | #14 | glm 4.2–4.8, 8.8, 8.11 | Pickup Mode as first-class, route-diagram hero, sunset headline, ribbon scrubber + axis decision, contrail, reverse-geocode, cabin sunrise alarm, expanded roster |
| Plan: identity hardening | #15 | glm 1.7–1.9 | Tab/semicolon separators, unknown-prefix acceptance, renumbering re-track affordance |
| Plan: architecture hardening | (branch `glm/architecture-hardening`) | glm §8 | Clock / CircuitBreaker / Heartbeat / CrashRouter / rate-limit buckets in §4.2, §6, §17 |
| Plan: a11y & jank budgets | (branch `glm/accessibility-perf-budgets`) | glm §8 | Accessibility + jank budgets designed in from M0 |
| Frozen countdowns | #30 (supersedes #19) | B3, B15, glm 1.1, 1.12 | Shared multicast tick per ViewModel; detail VM property/init order; `countdownText` past-due clamp (ported from #19) |
| Notification/alarm correctness | #32 (supersedes #27) | B14, B16-part, glm 1.3, 1.4, 5.7 | Stable non-negative notification/alarm IDs, boarding-after-airborne guard (runway-actual counts as "up"), exact-alarm label re-check on resume |
| METAR decoder | #24 + #31 (union) | B10, G7, glm 1.5–1.7 | Token-based decode: `+RA`/multi-phenomena/MPS→kt/calm/VRB, visibility (m + mixed-number SM), CB/TCU, wind direction phrasing, **ceiling = first BKN/OVC** (from #31), locale-safe formatting, 15 regression tests |
| Coroutine hygiene | (branch `glm/coroutine-hygiene`) | glm 1.8, 1.9 | Rethrow `CancellationException` in all network catch blocks; require `seenPos` on ADS-B records |
| Review consolidation + lint gate | #16 | B24, glm 1.2 | This document's ancestor (`ANALYSIS.md`); the API-31 `canScheduleExactAlarms` guards had already landed on `main` independently |
| Back stack & deep links | #17 | B1, B8 | `rememberSaveable` back stack, `singleTask` + `onNewIntent`, consumed-deep-link guard across process death |
| Machine-locale formatting | #18 | B2 | `Locale.ROOT` for Open-Meteo coordinates (GeoJSON was already pinned on `main`) |
| Detail polling gate | #20 | B4, glm 1.21 | Live-position polling suspends when the screen is not started (`LifecycleStartEffect` + `collectLatest` gate) |
| List management | #21 | B5, V8/V9/V11 parts, G3 part | Swipe right → archive / left → delete, both with snackbar Undo; reminder cancellation on removal; unarchive/restore re-arm the refresh worker; no-key CTA card; empty-state Add button; belt shown after landing; FAB clearance |
| Provider edges | #22 | B6, B19 | ADB `CanceledUncertain` → CANCELLED (`main` already had the wider AeroAPI red-eye window) |
| Add-sheet errors | #23 | B7, V6 part | Sheet stays open on parse errors; all-caps keyboard, autocorrect off |
| System bars & night theme | #25 | B11, P5 part | Bar icons follow the resolved theme (Cockpit forces dark); `values-night` window theme kills the dark-mode cold-start flash |
| Idle battery | #26 | B13 | Refresh worker self-cancels with no flights; `BackgroundRefreshController` re-arms on track/unarchive/restore |
| Ribbon delighter | #29 | I1 | Window-side sunrise/sunset callout ("🌇 18:42 · left side") with a ±20° dead-ahead confidence gate |
| (earlier, direct to `main`) | — | B24, F17-part, V12-part | API-31 alarm guards; long-press rename/delete menu; adaptive two-pane detail layout ≥620 dp; sky-gradient list cards; map route fit + projected plane |

**Superseded (closed unmerged):** #19 → #30 (its `countdownText` clamp was
ported); #27 → #32 (alternative collision-free ID scheme noted below in B14n).

---

## Open bugs

### B9 · Delay notifications can never fire from AeroDataBox status-only delays — MEDIUM
`NotificationPlanner.diff` compares `scheduled` vs `estimated`; ADB frequently
reports delays via a *status* of `delayed` with no revised time, which the
planner ignores entirely. A "Delayed" push is the #1 reason to install a flight
tracker. **Fix:** emit a DELAY event when `current.status == DELAYED &&
prev?.status != DELAYED` even without timestamps, keeping the bucketed
fingerprint path when times exist. *Held back because live ADB payloads couldn't
be verified offline.*

### B12 · Reference data never re-imports after an app update — MEDIUM
`ReferenceImporter` guards on `airportCount() > 0`; a shipped update with
regenerated CSVs never reaches the database. **Fix:** version the import (store
the lockfile's `fetched` date or a CSV hash; clear + re-import on change).

### glm 1.13 · Near-antipodal routes divide by ~0 — MEDIUM
`GreatCircle.intermediate()` guards the coincident case (`d < 1e-9`) but not the
antipodal case (`d ≈ π` → `sin(d) ≈ 0`): polar/near-antipodal routes return
garbage, breaking the polyline, terminator, and daylight sampling.

### glm 1.14 · Antimeridian split leaves a one-segment gap — LOW (visual)
`GreatCircle.routeSegments`/`splitAtAntimeridian` start the new segment without
inserting the interpolated ±180° vertex, so the polyline visibly breaks at the
date line.

### glm 1.15 · Night-side polygon frozen when there's no live fix — MEDIUM
`MapLibreRouteMap` keys the terminator on `lastFix?.at?.epochSecond?.div(600)` —
null forever on the planned-route view, so the night shading never advances.
Key on wall-clock deciminutes instead.

### glm 1.10 · Cancelled/diverted/departed/landed re-fire after the 3-day prune — MEDIUM
`EmittedEvent.expiresAt` shares the snapshot TTL; after prune the next refresh
sees `previous == null` and re-emits the transition. Needs a policy decision on
ledger retention vs transition idempotency.

### glm 1.11 · `FlightRepository.delete()` is not transactional — LOW
Four DAO calls across two DBs; a crash mid-way orphans rows. Needs an ops-side
`@Transaction` method.

### glm 1.16 / B20 · Position lookup doesn't verify it found the right aircraft — LOW
First result with lat/lon wins; no cross-check against the snapshot's
`icao24`/registration or a route/time corridor (PLAN.md §5 step 5). A colliding
callsign paints someone else's track.

### glm 1.19 · `goAsync()` receivers have no timeout — LOW
`ReminderAlarmReceiver`/`BootCompletedReceiver` run Room/IO work on a
free-standing scope under the ~10 s budget; boot reconcile should enqueue a
`OneTimeWorkRequest` instead.

### glm 1.20 · `OpsDatabase` destructive migration holds `quota_ledger` — MEDIUM (risk)
A future schema bump silently zeroes the user's API-credit accounting. Move the
ledger to the user DB or add real migrations for that table.

### glm 1.22 · Delay-notification copy under-reports — LOW
A 29-min slip renders "Delayed 15m" (bucket floor). Show the real minutes (the
bucket is a dedup key, not display copy).

### glm 1.18 · Tangential sunrise/sunset sample can be lost — LOW
`DaylightEngine.findCrossings` drops a sample landing exactly on the threshold
(`e0 == 0.0` continue). Also B22: the bisection early-exit `return@repeat` is a
no-op (harmless; always 24 iterations).

### B18 · Quota ledger check-then-record race — LOW
`canSpend` + `record` are non-atomic; bounded overshoot near the soft stop.

### B21 · MapLibre track source keyed on size only — LOW
`remember(track.size)` — a pruned+appended track of equal length renders stale
geometry. Key on last-fix timestamp + size.

### B23 · Landed flights sort above imminent departures — LOW (design)
Sorting by `nextEventAt` lets a flight landed 2 h ago outrank one departing in
45 min. Landed rows should sink (and eventually auto-archive — F2).

### B14n · Notification ID scheme note
The landed scheme is a mixed hash (astronomically unlikely but not impossible
collisions). #27 proposed a provably collision-free alternative
(`floorMod(flightId, 500M) * 4 + channelIndex`) — adopt if IDs ever matter
forensically.

---

## Open general issues

- **G1 — Hardcoded English strings** (partially fixed by #28): "Departs in"/
  "Lands in"/"Landed" (list `phaseTime`), timeline labels "Boarding"/"Pushback"/
  "Takeoff"/"Landing"/"Gate arrival", `ATTRIBUTION_TEXT`, "Weather data by
  Open-Meteo.com", map attribution line. (glm 5.5 is the same finding.)
- **G2 — Dead code:** `RouteMap.kt` (175-line offline canvas map, no callers
  since MapLibre), unused strings (`status_boarding`, `timeline_*`,
  `estimated_chip`, `sunrise_at`, `sunset_at`, `ribbon_unavailable`,
  `weather_unavailable`, `attribution_openfreemap`, `onboarding_keys_skip`),
  unused `onboardingDone` setting, unused `latestTwo`/`history`/`aliased` DAO
  queries.
- **G3 — README/impl drift** (mostly fixed by #21): "raw METAR one tap away" —
  it's always visible; collapse it or update the README.
- **G4 — `.idea/` files are committed** (`.idea/noctule.xml` is a rename
  remnant) although `.gitignore` lists `.idea/`.
- **G5 — Error observability:** provider failures collapse to `null`/empty; the
  UI can't distinguish "no key" / "quota" / "rate limited" / "offline". Thread
  the last error into list/detail state.
- **G6 — No OkHttp cache:** one line (`Cache(cacheDir/"http", 10 MB)`) plus
  offline revalidation for METAR/Open-Meteo traffic.
- **G8 — ADB timestamp parse order:** flip `Instant.parse` and the
  `OffsetDateTime` fallback so the common minute-precision path doesn't rely on
  `runCatching`.
- **G9 — Test gaps:** `NotificationPlanner` (delay bucket re-notification,
  gate-appears vs gate-changes), `InstanceSelector` date-pinning path,
  `CadencePolicy` at the exact 48 h boundary.
- **G10 — Detail ViewModels are Activity-scoped and never cleared** (root cause
  of B4; the #20 gate treats the symptom). Memory grows with every flight
  opened. Consider nav-entry-scoped ViewModels or Navigation 3 so `onCleared`
  fires on pop.
- **glm 7.1 — Contributor docs missing:** `CONTRIBUTING.md`, `GLOSSARY.md`,
  `SECURITY.md`, `.editorconfig`, `.gitattributes`, `docs/decisions/` template.

---

## Open performance items

- **P2 — Ribbon/weather recomputed on every snapshot insert:** the
  `fetchedAt` gate re-runs suncalc (~1000 samples) + two weather calls per
  refresh even when times are identical. Gate on (route, wheelsUp, wheelsDown);
  refresh METARs on their own cadence.
- **P3 — First-launch reference import races the UI:** enrichment returns nulls
  during the CSV import and never retries. Trigger re-enrichment on completion.
- **P4 — List rows recompose wholesale on any row change** — `contentType` +
  stable row classes.
- **P5 — Theme flash on cold start** (partially fixed by #25): the theme is
  collected with `initialValue = DAYLIGHT_DYNAMIC`; read the first value
  blocking or cache it in a fast-path pref.
- **P6 — No baseline profile / macrobenchmark** (PLAN M4's `:benchmark`).
- **P7 — GeoJSON string building on the composition thread.**
- **glm 2.3 — 11-way detail `combine` rebuilds everything per fix** (~10 s while
  airborne), recomposing heavyweight items. Split map-only state out.
- **glm 2.1/2.2/2.4 — Brushes/Paths built inside draw lambdas** (ribbon
  gradient, hero brush, progress bar); hoist into `remember`.
- **glm 2.7 — Monograms redrawn per row**; cache.

---

## Visual, layout & accessibility backlog (the "premium iOS" gap)

- **V1 / glm 3.1–3.2 — No typography system, no tabular figures.** Default
  Roboto everywhere; countdown digits wiggle as they tick (worse now that they
  actually tick). A `Typography` with a display face + `"tnum"` on all numeric
  text is the single biggest aesthetic lever. *Needs a font choice from the
  owner.* PLAN §10.2 (merged in #13) specifies the target.
- **V2 — No motion design.** Screen changes are hard cuts; even slide/fade
  transitions buy disproportionate polish. (#21 added list-item animation.)
- **V3 — No haptics** (PLAN M4): pull-to-refresh completion, swipe thresholds,
  wheels-down.
- **V4 / glm 3.3 — `StatusWord` white text fails WCAG AA on amber/neutral.**
  Use per-chip on-colors.
- **V5 — Empty map card** renders header + attribution and nothing else when
  coordinates are unknown. Placeholder or hide.
- **V6 — Add sheet:** plain text field for the date (use `DatePickerDialog`),
  no loading state while resolving, silent date parse errors (glm 5.8).
- **V7 / glm 3.4–3.6 — Ribbon niggles:** weather glyphs drift left of their
  sample positions; sunrise/sunset times render in device TZ unlabeled; events
  can crowd on narrow phones; marker colors hardcoded (ignore Cockpit/High
  Contrast); raw emoji despite the file's own tofu warning; `bandColor` skips
  civil twilight.
- **V9 — Detail density:** `Tag` icon reused for check-in and registration;
  registration duplicated in `AirlineCard`.
- **V10 — Top bars don't collapse** (`scrollBehavior`); large-title collapse is
  half the "expensive iOS" gestalt.
- **V13 — Timeline shows derived Check-in/Boarding rows days after departure**;
  "Gate arrival" next to "Landed" reads inconsistently.
- **V14 — Cockpit theme is buried** — surface in onboarding or auto-engage in
  flight (I7). glm 3.11: avoid pure `#000000` (penTile smear); use ~`#05050A`.
- **glm 3.8 — Plane glyphs always point right** in the list/hero route rows —
  a LHR→NRT flight visually flies backwards.
- **glm 3.10 — No loading skeletons** (PLAN §9.2 now specifies them, #13).
- **glm 4.1 — Back arrows have `contentDescription = null`** (detail +
  settings) — TalkBack announces nothing for the primary nav affordance.
- **glm 4.2 — Map and ribbon have no semantics** for TalkBack.
- **glm 4.3 — Timeline row hard-pinned to 44 dp** clips at 200 % font scale.
- **glm 4.4 — List rows aren't `mergeDescendants`** (3 swipe stops per row);
  quota `∞` has no contentDescription (4.4b).
- **V12 — Large-screen pass** (detail two-pane landed; list/tablet grid and
  landscape-specific layouts still open).

---

## Missing features (value ÷ effort ordered)

- **F2 — Auto-archive landed flights** after ~24 h into a "Past flights"
  section. *(S)*
- **F3 — Share a flight** — status text share sheet; optional share-card
  image. *(S/M)*
- **F4 — Add to calendar.** *(S)*
- **F5 — "Boarding" as a real status** — ADB `boarding`/`gateClosed` collapse
  into SCHEDULED; `strings.xml` already ships the word. *(M)*
- **F6 — Ongoing in-flight notification** (`Notification.ProgressStyle` on API
  36+, plain progress below; PLAN §13). *(M)*
- **F7 — "Next flight" Glance widget.** *(M)*
- **F8 — Gate-assigned notification** (planner only fires on gate *change*).
  *(S)*
- **F9 — "Delay recovered" notification.** *(S)*
- **F10 — Layover awareness** — chained tracked flights: connection time +
  buffer warnings. *(M)*
- **F11 — Trip grouping** (PLAN v2). *(M)*
- **F12 — Per-flight notification profiles** (PLAN §12.1). *(M)*
- **F13 — Flight log / Passport stats** (needs summary rows kept past the
  prune). *(L)*
- **F14 — Export/import user data** (PLAN M4). *(S)*
- **F15 — In-app airline/airport info** — alliance, check-in URL, Maps deep
  link. *(S)*
- **F16 — Offline projected mode** (PLAN §13). *(L)*
- **F17 — remainder: rename from the detail screen** (list long-press rename
  landed; detail overflow menu still open). *(S)*
- **glm 5.3 — Day grouping in the list** for multi-day trips. *(S)*

---

## Ideas (novel / delightful / quirky)

- **I2 — Bird-flight pull-to-refresh** — the bird silhouette flapping along the
  pull arc; release = a little swoop.
- **I3 — Wheels-down haptic heartbeat** — soft double-pulse on LANDED while the
  screen is on.
- **I4 — Split-flap status transitions** — one Solari-flip chip reads as pure
  luxury; full Solari theme is PLAN v2.
- **I5 — Jet-lag ribbon extension** — "body clock" strip from the daylight
  engine (shift = +7 h; light-exposure suggestion).
- **I6 — Airline-colored monograms** — bundled IATA→brand-color table for the
  top ~100 carriers, hash palette fallback.
- **I7 — Cockpit auto-engage** during the airborne window at night ("dim cabin
  lights"), reverting after landing.
- **I8 — Ribbon scrubbing** — drag to read time/position/weather at that
  fraction (samples are in memory; PLAN §9.4 scrubber landed in #14 at plan
  level).
- **I9 — Landed confetti** (respecting reduced-motion).
- **I10 — Moon and stars on night segments** (suncalc has `MoonIllumination`).
- **I11 — "Point at the sky" AR** — parked as v-later.

---

## Plan-level leftovers (from glm §8, not yet in PLAN.md)

M0 scope split (M0a engineering / M0b legal); module-split heuristic;
primary-key strategy note; `hilt-navigation-compose` build-time guard; R8
keep-rule checklist; provider-cost simulator; FTS search; per-event lead-time
customization; notification actions; Wear OS; clipboard-text export; third
widget.

---

## Suggested next cycle

1. Quick wins: glm 1.15 (night-polygon key), glm 1.13/1.14 (great-circle
   guards), B21, glm 4.1 (back-arrow descriptions), G4, G6, F8, F9.
2. B9 delay-status notifications (verify against a live ADB payload first).
3. The two aesthetic levers: V1 typography (owner picks a face) + V2 motion —
   PLAN §10.2 is the spec.
4. One flagship feature: F6 ongoing notification or F10 layover awareness.
5. Structural: G10 navigation rework (unlocks VM scoping, predictive back, and
   removes the B4 workaround); glm 1.20 quota-ledger migration safety.
