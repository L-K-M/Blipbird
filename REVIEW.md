# Blipbird — Review findings & work backlog

The living successor to the July 2026 review documents: the Fable full-codebase
review, the GLM 5.2 review, the DeepSeek v4 review, and the interim
`ANALYSIS.md` backlogs. The raw point-in-time snapshots are archived under
[`docs/reviews/`](docs/reviews/) (`fable.md`, `glm.md`, `glm-analysis.md`,
`ds4.md`); **this file is the single source of truth going forward.** IDs are
stable so PRs and discussions can keep referencing them: `B/G/P/V/F/I` from the
Fable review, `glm x.y` from the GLM review, `DS4-*` from the DeepSeek review.

Everything implemented has been pruned from this file (July 2026 cleanup, at
v1.0.0). The record of *what* landed and *which PR carried it* lives in the git
history of this file, the closed PRs (#13–#62), and the archived snapshots.
Every item below was re-verified against the code at cleanup time — it is
genuinely still open.

---

## Open bugs

None currently prioritized — the remaining known findings are deliberate
deferrals, recorded under **Won't do (for now)** below.

---

## Won't do (for now)

Fixable, but the cost/benefit doesn't land yet — parked here so they stop
resurfacing in every review. Each notes what would flip the call.

- **B14n — collision-free notification IDs.** #27's
  `floorMod(flightId, 500M) * 4 + channelIndex` is provably collision-free vs the
  landed mixed hash (collisions astronomically unlikely, not impossible).
  *Not now:* the ongoing in-flight notification would orphan on the update that
  ships the new scheme — the new id can't cancel a card posted under the
  old-hash id — for a benefit that only bites if ids ever need to be forensically
  stable. *Revisit* if ids start mattering forensically, or fold it into a
  notification rework that can clear the stale cards.

- **DS4-P10 — `planeBitmap` renders on the composition thread** on first use
  (cached after). *Not now:* moving it off-thread would make the live aircraft
  icon appear a frame late — worse than the sub-millisecond one-time draw it
  removes. *Revisit* by converting it to a bundled vector drawable (no runtime
  draw at all), which is the real fix.

- **DS4-V19 — Ribbon weather glyphs are evenly spaced** (`weight(1f)`) rather than
  at their true along-route position (would sharpen V7). *Not now:*
  `WeatherSample.fraction` is only an even `i/(n-1)` index, so the real position
  isn't available at the ribbon; a correct fix needs
  `WeatherRepository.routeWeather` to carry the true fraction through. *Revisit*
  together with the V7 ribbon pass.

- **DS4-V21 — Detail countdown updates on a 15 s tick** vs the list's 30 s.
  *Not now:* the remedy ("unify tick sources") spans two independent ViewModels
  for a barely-perceptible effect — minute-granularity countdowns don't visibly
  change faster than 15 s anyway. *Revisit* if a shared clock source lands for
  another reason.

---

## Open general issues

- **G1 — Hardcoded English strings** (partially fixed by #28): "Departs in"/
  "Lands in"/"Landed" (list `phaseTime`), timeline labels "Boarding"/"Pushback"/
  "Takeoff"/"Landing"/"Gate arrival", `ATTRIBUTION_TEXT`, "Weather data by
  Open-Meteo.com", map attribution line. (glm 5.5 is the same finding.)

- **G9 — remaining test gaps:** `InstanceSelector` date-pinning path,
  `CadencePolicy` at the exact 48 h boundary (the NotificationPlanner suite
  landed in #40).

---

## Open performance items

- **P2 — Ribbon/weather recomputed on every snapshot insert:** the
  `fetchedAt` gate re-runs suncalc (~1000 samples) + two weather calls per
  refresh even when times are identical. Gate on (route, wheelsUp, wheelsDown);
  refresh METARs on their own cadence.
- **P3 — First-launch reference import races the UI:** enrichment returns nulls
  during the CSV import and never retries. Trigger re-enrichment on completion.
- **P5 — remainder: theme flash on cold start.** The `values-night` window
  theme landed (#25); the theme spec is still collected with a default
  `initialValue` — read the first value blocking or cache it in a fast-path
  pref.
- **P6 — No baseline profile / macrobenchmark** (PLAN M4's `:benchmark`).
- **glm 2.3 — 13-way detail `combine` rebuilds everything per fix** (~10 s while
  airborne), recomposing heavyweight items. Split map-only state out.

---

## Visual, layout & accessibility backlog (the "premium iOS" gap)

- **V2 — remainder: component motion-token adoption.** The `BlipbirdMotion`
  token object, the named push/pop screen transitions, and now the in-app
  reduce-motion toggle (§18 — a persisted setting OR-ed with the system animator
  scale, gating every `rememberReducedMotion` flourish app-wide) have landed.
  Still open: migrating the component-embedded specs (status-chip color flip,
  sheet present) onto the `BlipbirdMotion.DURATION_*` tokens — a cosmetic
  refactor whose intended durations want an on-device eye.
- **V3 — remainder: swipe-to-dismiss haptic.** The pull-to-refresh threshold
  haptic landed (#69); a swipe-to-dismiss threshold haptic was tried and reverted
  — firing mid-drag read as the archive/delete committing before finger-release.
  Revisit only if it can fire on the release-commit without that premature feel.
- **V7 remainder — Ribbon niggles:** weather glyphs drift left of their sample
  positions (DS4-V19); sunrise/sunset times render in device TZ unlabeled;
  events can crowd on narrow phones.
- **V10 — remainder: detail top bar.** The index screens (My flights, Settings,
  Past flights) now use a collapsing `LargeTopAppBar` (`exitUntilCollapsed`
  scroll behavior). The detail screen deliberately keeps a regular bar — it
  leads with the sky-gradient hero, so a large collapsing title above it would
  double up. *Revisit* only if the hero is ever reworked.
- **V12 — remainder: list-detail two-pane.** Detail two-pane landed; the flight
  list and the archived list now flow into a multi-column `LazyVerticalGrid`
  (`Adaptive(380.dp)` → one column on phones, two+ on tablets/foldables/
  landscape). Still open: a canonical list-detail layout (tap a flight → detail
  in a side pane on wide screens) — a larger nav change deferred on its own.
- **glm 3.8 — Plane glyphs always point right** in the list/hero route rows —
  a LHR→NRT flight visually flies backwards.

---

## Missing features (value ÷ effort ordered)

- **F2 — remainder: auto-archive on landing.** The archived-flights screen
  landed — reached from a deemphasized link at the bottom of the list, with
  restore and permanent-delete (closing the recoverability gap where an archived
  flight vanished once the undo snackbar dismissed). Still open: automatically
  archiving landed flights after ~24 h. *(S)*
- **F5 — "Boarding" as a real status** — ADB `boarding`/`gateClosed` collapse
  into SCHEDULED; `strings.xml` already ships the word. *(M)*
- **F7 — "Next flight" Glance widget.** *(M)*
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
- **DS4-F18 — "Track return flight"** button that inverts the city pair. *(S)*
- **glm 5.3 — Day grouping in the list** for multi-day trips. *(S)*

---

## Ideas (novel / delightful / quirky)

- **I5 — Jet-lag ribbon extension** — "body clock" strip from the daylight
  engine (shift = +7 h; light-exposure suggestion).
- **I7 — Cockpit auto-engage** during the airborne window at night ("dim cabin
  lights"), reverting after landing.
- **I8 — Ribbon scrubbing** — drag to read time/position/weather at that
  fraction (samples are in memory; PLAN §9.4 scrubber landed in #14 at plan
  level).
- **I9 — Landed confetti** (respecting reduced-motion).
- **I11 — "Point at the sky" AR** — parked as v-later.
- **DS4-I12–I15 — Ideas:** shared live-progress link; tail-number "spotted"
  badge when the tracked registration matches ADS-B; altitude-profile
  sparkline from the fix history; timezone-hopping indicator ("UTC+2 → UTC+8").

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
- **A11y foundations — remainder:** the §18 48 dp scaffold + `semantics`
  conventions as a systematic pass (#59 landed the first slice).

## Plan-level leftovers (from glm §8, not yet in PLAN.md)

M0 scope split (M0a engineering / M0b legal); module-split heuristic;
primary-key strategy note; `hilt-navigation-compose` build-time guard; R8
keep-rule checklist; provider-cost simulator; FTS search; per-event lead-time
customization; notification actions; Wear OS complication; clipboard-text
export; third "airport departure board" widget; usability-testing budget;
CLA/DCO process.

---

## Suggested next cycle

(The previous next-cycle list landed wholesale via #63–#67: small fixes, G5
outcome surfacing, V2 motion core, the F6 flagship, and the G10 nav rework.)

1. Quick wins: glm 1.19 receiver timeouts, B18 quota race, DS4-P10/P7 thread
   hops, glm 2.2/P4 recomposition hygiene.
2. V2 remainder — migrate component-embedded animation specs onto the
   BlipbirdMotion tokens; in-app reduce-motion toggle (§18).
3. Polish pass: V5 empty map card, V6 add-sheet date picker, V7 ribbon
   niggles, V9 detail density, V10 collapsing top bars.
4. Next feature from the value/effort list: F2 auto-archive or F5 real
   Boarding status.
5. Structural: P2 ribbon/weather recompute gating; glm 2.3 detail-state split.
