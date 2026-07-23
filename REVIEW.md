# Blipbird — Review findings & work backlog

The living successor to the July 2026 review documents: the Fable full-codebase
review, the GLM 5.2 review, the DeepSeek v4 review, and the interim
`ANALYSIS.md` backlogs. The raw point-in-time snapshots are archived under
[`docs/reviews/`](docs/reviews/) (`fable.md`, `glm.md`, `glm-analysis.md`,
`ds4.md`); **this file is the single source of truth going forward.** IDs are
stable so PRs and discussions can keep referencing them: `B/G/P/V/F/I` from the
Fable review, `glm x.y` from the GLM review, `DS4-*` from the DeepSeek review.

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
| Coroutine hygiene | #33 | glm 1.8, 1.9 | Rethrow `CancellationException` in all network catch blocks; require `seenPos` on ADS-B records |
| Map geometry | #34 | glm 1.13, 1.14, 1.15 | Near-antipodal slerp guard, interpolated dateline vertex on both antimeridian segments, night polygon keyed on wall-clock (was frozen with no live fix) |
| Visual/perf polish | #35 | glm 1.17, 2.1, 2.4-part, 3.3–3.6, 3.9, V4, V7-parts | WCAG-aware `StatusWord` on-color, ribbon brush hoisted out of the draw lambda, theme-aware sunrise/sunset/aircraft ribbon colors (new `ExtendedColors` roles), civil-twilight band rendered, non-emoji sun-event arrows, progress-bar Path reuse + shorter tween + dead draw removed |
| Repo hygiene | #36 | glm 7.1, 7.3 | `CONTRIBUTING.md`, `GLOSSARY.md`, `SECURITY.md`, ADR template, `.editorconfig`, `.gitattributes` |
| Review docs | #37 (content) | glm 7.2 | Raw review snapshots archived under `docs/reviews/`; new findings folded into this file |
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

### Second cycle (PRs #38–#55)

| Area | PR(s) | Items | What landed |
|------|-------|-------|-------------|
| Geometry follow-ups | #39 (supersedes #48, #49) | B21, dateline guard | Flown-track source keyed on boundary timestamps + size; exact-±180° division guard; pole/dateline tests |
| Daylight crossing | #47 | glm 1.18 | Exact-threshold samples no longer swallow a sunrise/sunset crossing; `ds4.md` review archived |
| Reference re-import | #41 | B12 | Import versioned by an MD5 of the data-sources lockfile; wipe+refill inside one transaction; fingerprint persisted only after success |
| Network hygiene | #43 (supersedes #52) | G6, G8 | 10 MB OkHttp disk cache; ADB minute-precision timestamps parse offset-first |
| List sort | #45 | B23 | Landed/arrived flights sink below active ones, most recently landed first |
| Notification planner | #40 + ported #53 (supersedes #54) | F8, F9, B9, glm 1.22, G9-part | Gate-assigned events, delay-recovered (bucket-shrink + back-on-schedule), status-only delay with double-fire guard, honest delay minutes in copy, 16-case planner test suite |
| Accessibility | #42 + #50-part | glm 4.1, 4.3, 4.4/4.4b, glm 3.11 | Back-arrow content descriptions, timeline `heightIn` for 200 % font scale, spoken quota line, Cockpit `#05050A` background (kept `main`'s luminance-based StatusWord on-color over #50's per-status table) |
| Timeline | #55-part | V13 | Derived Check-in/Boarding rows (and the ~ note) hidden once the flight departs (kept `main`'s continuous civil-twilight band over #55's discontinuous variant) |
| Repo hygiene | #44 | G4, G2 | `.idea/` untracked; dead `RouteMap.kt`, 11 dead strings, dead `onboardingDone` setting, 3 dead DAO queries removed |
| Detail actions | #46 | F3, F4 | Share sheet (status text summary) + add-to-calendar intent in the detail top bar; `statusText()` shared with the chip |
| Release hardening | #38 | SOL-012 | Tag releases fail without all four signing secrets; apksigner verification + SHA-256 checksum published; unsigned APKs never released |
| Privacy disclosures | #51 | SOL-011 | README/PLAN/onboarding copy states the real backup boundary, zero-key capability, and third-party network access; no absolute privacy claims |

**Superseded in this cycle:** #48/#49 → already on `main` via #34 (+#39 for the
track key); #52 → #43; #54 → #40; #53 → ported by hand onto #40's planner (its
branch would have conflicted with the richer recovery model). #56 (a competing
root-level `ANALYSIS.md`) closed — this file remains the single backlog.

### Third cycle

| Area | PR(s) | Items | What landed |
|------|-------|-------|-------------|
| Daylight altitude API | #57 | — | `DaylightEngine.compute` defaults to the surface threshold and validates explicit altitudes; the detail ribbon now passes the ~11 km cruise assumption explicitly, so app behavior is unchanged |
| ADS-B identity | #58 | glm 1.16 / B20 / DS4-B7 (identity part) | Position fixes validated against the normalized query identity (hex/registration/callsign), freshest valid record wins, malformed/stale (>5 min) records rejected, cached hex skipped when the status payload carries a fresh registration (aircraft-swap guard) |


---

## Open bugs

### glm 1.10 · Cancelled/diverted/departed/landed re-fire after the 3-day prune — MEDIUM
`EmittedEvent.expiresAt` shares the snapshot TTL; after prune the next refresh
sees `previous == null` and re-emits the transition. Needs a policy decision on
ledger retention vs transition idempotency.

### glm 1.11 · `FlightRepository.delete()` is not transactional — LOW
Four DAO calls across two DBs; a crash mid-way orphans rows. Needs an ops-side
`@Transaction` method.

### glm 1.16 / B20 · remainder: route-corridor sanity check — LOW
#58 landed identity matching (fixes only accepted when the record's
hex/registration/callsign equals the query) and freshness/validity gates. Still
open: for CALLSIGN-derived fixes the identity can genuinely collide day-to-day —
a great-circle-corridor plausibility check (PLAN.md §5 step 5) would close it.

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

### B22 · DaylightEngine bisection early-exit is a no-op — LOW (code health)
`return@repeat` as the last statement just continues the loop; the precision
early-exit never fires (harmless — always 24 iterations).

### B18 · Quota ledger check-then-record race — LOW
`canSpend` + `record` are non-atomic; bounded overshoot near the soft stop.

### B14n · Notification ID scheme note
The landed scheme is a mixed hash (astronomically unlikely but not impossible
collisions). #27 proposed a provably collision-free alternative
(`floorMod(flightId, 500M) * 4 + channelIndex`) — adopt if IDs ever matter
forensically.

### DS4-new: unique findings from the DeepSeek review (`docs/reviews/ds4.md`)
- **DS4-B17 — `dateLocal` parsed without `runCatching`** in
  `FlightRepository.refreshStatus`; the stored format is stable (`YYYY-MM-DD`)
  but a malformed pin would crash the refresh loop. Defence-in-depth.
- **DS4-G9 — `phaseTime` calls `Instant.now()` at composition time** (list
  card); the ViewModel already ticks a shared clock — pass `now` through the
  row data.
- **DS4-G10 — No per-row data-freshness indicator in the list** (detail shows
  "Updated X ago"; the list doesn't).
- **DS4-P10 — `planeBitmap` renders on the composition thread** on first use
  (cached after); consider pre-rendering or a vector drawable.
- **DS4-V19 — Ribbon weather glyphs are evenly spaced** (`weight(1f)`) while
  sample points are positioned along the great-circle fraction — glyphs drift
  from their true positions (sharpens V7).
- **DS4-V20 — Past-due countdown reads "Departs in 0m"** — switch to
  "Departed"-style copy when the target has passed.
- **DS4-V21 — Detail countdown freezes between 15 s ticks** vs the list's 30 s
  cadence — unify tick sources, consider animation smoothing.
- **DS4-F18 — "Track return flight"** button that inverts the city pair. *(S)*
- **DS4-I12–I15 — Ideas:** shared live-progress link; tail-number "spotted"
  badge when the tracked registration matches ADS-B; altitude-profile
  sparkline from the fix history; timezone-hopping indicator ("UTC+2 → UTC+8").

### glm-A: further verified defects (from the GLM backlog)
- **Future-dated ADS-B fixes treated as fresh** — `FlightPhaseMachine` uses
  `Duration.between(...).abs() < 30m`, so a fix timestamped in the future (clock
  skew, buggy feeder) counts as airborne. Require `!fix.at.isAfter(now)`.
- **Codeshare self-reference** — when ADB reports `IsCodeshared`,
  `codeshareOf = number` marks the flight as its own codeshare (ADB exposes no
  operating field there). Prefer null on both until enriched.
- **ADB minute-precision timestamps** — `"2026-07-22 14:10Z"` relies on the
  `OffsetDateTime` fallback; pre-normalize missing seconds so the common path
  doesn't throw-and-recover (extends G8).
- **ReferenceImporter CSV parser** doesn't handle embedded newlines in quoted
  fields / trailing doubled quotes. Harden or use a real CSV lib.
- **No `Retry-After` honoring** — 429 just falls through the provider chain.
- **Crash logger writes on the crashing thread** (`BlipbirdApp`) — cap the
  write size / defer so it can't extend the ANR dialog.
- **Night polygon triple-draw alpha-stacks** — the −360/0/+360 copies overlap
  on wide views, producing visible darkening bands; cull each shift by
  bounding box.

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
- **glm 2.2 — Hero brush built inside the composable per recomposition**
  (`remember(cs)` it); ribbon and progress bar were fixed in #35, `RouteMap.kt`
  is dead code (G2).
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
- **V5 — Empty map card** renders header + attribution and nothing else when
  coordinates are unknown. Placeholder or hide.
- **V6 — Add sheet:** plain text field for the date (use `DatePickerDialog`),
  no loading state while resolving, silent date parse errors (glm 5.8).
- **V7 remainder — Ribbon niggles:** weather glyphs drift left of their sample
  positions; sunrise/sunset times render in device TZ unlabeled; events can
  crowd on narrow phones. (Theme-aware colors, emoji, and the civil band were
  fixed in #35.)
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

## Plan → code gaps (the merged PLAN additions the code doesn't realize yet)

- **Injectable `core.time.Clock`** — code still reads `Instant.now()` directly
  across the repository and domain cores; introduce, inject, fake in tests.
- **`ProviderHealth`/`CircuitBreaker` + rate-limit buckets** — the chain just
  `continue`s on error; no OPEN/HALF_OPEN state, no per-host token bucket.
- **`CrashRouter`** — formalize/redact the basic crash logger per plan.
- **Design-token system** (`BlipbirdTypography`/`Motion`/`Shapes`/`Elevation`),
  predictive back, theme schedule — PLAN §10.2 exists; code doesn't.
- **Delighters in code** — Pickup Mode, route-diagram hero, sunset headline,
  sunrise alarm, reverse-geocode, contrail, scrubber (PLAN §9/§11/§12/§14).
- **A11y foundations** — 48 dp scaffold + `semantics` conventions from §18.

## Plan-level leftovers (from glm §8, not yet in PLAN.md)

M0 scope split (M0a engineering / M0b legal); module-split heuristic;
primary-key strategy note; `hilt-navigation-compose` build-time guard; R8
keep-rule checklist; provider-cost simulator; FTS search; per-event lead-time
customization; notification actions; Wear OS complication; clipboard-text
export; third "airport departure board" widget; usability-testing budget;
CLA/DCO process.

---

## Suggested next cycle

1. Quick wins: B21 (track source key), glm 4.1 (back-arrow descriptions),
   glm-A future-dated fixes + codeshare self-reference, G4, G6, F8, F9.
2. B9 delay-status notifications (verify against a live ADB payload first).
3. The two aesthetic levers: V1 typography (owner picks a face) + V2 motion —
   PLAN §10.2 is the spec.
4. One flagship feature: F6 ongoing notification or F10 layover awareness.
5. Structural: G10 navigation rework (unlocks VM scoping, predictive back, and
   removes the B4 workaround); glm 1.20 quota-ledger migration safety.
