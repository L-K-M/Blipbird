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

### glm 1.19 · `goAsync()` receivers have no timeout — LOW
`ReminderAlarmReceiver`/`BootCompletedReceiver` run Room/IO work on a
free-standing scope under the ~10 s budget; boot reconcile should enqueue a
`OneTimeWorkRequest` instead.

### glm 1.20 · remainder: quota-ledger placement — LOW
#60 removed the destructive fallback and added a real v1→2 migration, so schema
bumps no longer zero the ledger. Optional follow-up: move `quota_ledger` (and
the backoff table) out of the "rebuildable by design" ops DB conceptually, or
document that the ops DB is no longer fully rebuildable.

### B18 · Quota ledger check-then-record race — LOW
`canSpend` + `record` are non-atomic; bounded overshoot near the soft stop.

### B14n · Notification ID scheme note
The landed scheme is a mixed hash (astronomically unlikely but not impossible
collisions). #27 proposed a provably collision-free alternative
(`floorMod(flightId, 500M) * 4 + channelIndex`) — adopt if IDs ever matter
forensically.

### DS4-new: unique findings from the DeepSeek review (`docs/reviews/ds4.md`)

- **DS4-P10 — `planeBitmap` renders on the composition thread** on first use
  (cached after); consider pre-rendering or a vector drawable.
- **DS4-V19 — Ribbon weather glyphs are evenly spaced** (`weight(1f)`) while
  sample points are positioned along the great-circle fraction — glyphs drift
  from their true positions (sharpens V7).

- **DS4-V21 — Detail countdown freezes between 15 s ticks** vs the list's 30 s
  cadence — unify tick sources, consider animation smoothing.

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
- **P4 — List rows recompose wholesale on any row change** — `contentType` +
  stable row classes.
- **P5 — remainder: theme flash on cold start.** The `values-night` window
  theme landed (#25); the theme spec is still collected with a default
  `initialValue` — read the first value blocking or cache it in a fast-path
  pref.
- **P6 — No baseline profile / macrobenchmark** (PLAN M4's `:benchmark`).
- **P7 — GeoJSON string building on the composition thread.**
- **glm 2.3 — 13-way detail `combine` rebuilds everything per fix** (~10 s while
  airborne), recomposing heavyweight items. Split map-only state out.

---

## Visual, layout & accessibility backlog (the "premium iOS" gap)

- **V2 — remainder: motion-token adoption.** The `BlipbirdMotion` token object
  and the named push/pop screen transitions landed (July 2026); still open:
  migrating component-embedded specs (status-chip color flip, sheet present)
  onto the tokens, and the in-app reduce-motion toggle (§18).
- **V3 — No haptics** (PLAN M4): pull-to-refresh completion, swipe thresholds,
  wheels-down.
- **V5 — Empty map card** renders header + attribution and nothing else when
  coordinates are unknown. Placeholder or hide.
- **V6 — Add sheet:** plain text field for the date (use `DatePickerDialog`),
  no loading state while resolving, silent date parse errors (glm 5.8).
- **V7 remainder — Ribbon niggles:** weather glyphs drift left of their sample
  positions (DS4-V19); sunrise/sunset times render in device TZ unlabeled;
  events can crowd on narrow phones.
- **V9 — Detail density:** `Tag` icon reused for check-in and registration;
  registration duplicated in `AirlineCard`; "Gate arrival" next to "Landed" in
  the timeline reads inconsistently.
- **V10 — Top bars don't collapse** (`scrollBehavior`); large-title collapse is
  half the "expensive iOS" gestalt.
- **V12 — remainder: large-screen pass.** Detail two-pane landed; list/tablet
  grid and landscape-specific layouts still open.
- **glm 3.8 — Plane glyphs always point right** in the list/hero route rows —
  a LHR→NRT flight visually flies backwards.
- **glm 3.10 — No loading skeletons** (PLAN §9.2 now specifies them, #13).
- **glm 4.2 — remainder: the map has no semantics** for TalkBack (the ribbon
  summary landed in #59).

---

## Missing features (value ÷ effort ordered)

- **F2 — Auto-archive landed flights** after ~24 h into a "Past flights"
  section. *(S)*
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

- **I3 — Wheels-down haptic heartbeat** — soft double-pulse on LANDED while the
  screen is on.
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

1. Remaining small fixes: DS4-G9/G10, glm 1.11 transactional delete,
   glm 1.16 route-corridor check, glm-A CSV/Retry-After/crash-logger items.
2. G5 remainder — surface lookup outcomes in the UI (the data is persisted
   since #60).
3. V2 motion design — the remaining aesthetic lever now that the V1 type
   system has landed; PLAN §10.2 is the spec.
4. Flagship feature: F6 ongoing in-flight notification (owner pick over F10).
5. Structural: G10 navigation rework (unlocks VM scoping, predictive back, and
   removes the B4 workaround).
