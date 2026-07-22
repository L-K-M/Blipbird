# Blipbird — Analysis & backlog

> Living document driving future work. Seeded by the **GLM 5.2** review
> (`glm.md`) against the real v0.1 codebase (not the stale planning branch).
> Items already fixed in code by this review are **cleared**; everything below
> is verified-but-not-yet-implemented, with `file:line` references.
>
> Severity key: **bug** · **minor-bug** · **perf** · **visual** · **a11y** ·
> **ux** · **risk**. Every item was read against source. See §A for what landed.

---

## A. What this review delivered

### Plan-level PRs (`PLAN.md`, all merged-able independently)
| PR | Title |
|---|---|
| [#11](https://github.com/L-K-M/Blipbird/pull/11) | Architecture hardening: Clock, CircuitBreaker, Heartbeat, CrashRouter, rate-limit buckets |
| [#12](https://github.com/L-K-M/Blipbird/pull/12) | Accessibility & jank budgets designed-in from M0, not audited at M4 |
| [#13](https://github.com/L-K-M/Blipbird/pull/13) | Design-token system + premium-aesthetic refinements |
| [#14](https://github.com/L-K-M/Blipbird/pull/14) | Delighters: Pickup Mode, route-diagram hero, sunrise alarm, ribbon scrubber, contrail, reverse-geocode |
| [#15](https://github.com/L-K-M/Blipbird/pull/15) | Harden flight identity resolution: separators, unknown-prefix, renumbering detection |

### Code-level PRs (real `app/` fixes, verified against source)
| PR | Title | Fixes |
|---|---|---|
| [#30](https://github.com/L-K-M/Blipbird/pull/30) | Countdown heartbeat + detail-VM init order | glm 1.1, 1.12 |
| [#31](https://github.com/L-K-M/Blipbird/pull/31) | METAR decoder: +RA, ceiling, multi-phenomenon, MPS | glm 1.5, 1.6, 1.7 |
| [#32](https://github.com/L-K-M/Blipbird/pull/32) | Notification/alarm ID scheme, boarding-after-airborne, stale grant label | glm 1.3, 1.4, 5.7 (glm 1.2 was already fixed on `main`) |
| [#33](https://github.com/L-K-M/Blipbird/pull/33) | Rethrow CancellationException; require seenPos | glm 1.8, 1.9 |
| [#34](https://github.com/L-K-M/Blipbird/pull/34) | Map geometry: near-antipodal guard, antimeridian vertex, unfreeze night polygon | glm 1.13, 1.14, 1.15 |
| [#35](https://github.com/L-K-M/Blipbird/pull/35) | Ribbon perf + theme-awareness, StatusWord contrast, progress bar | glm 1.17, 2.1, 2.4(progress), 3.3, 3.4, 3.5, 3.6, 3.9 |
| [#36](https://github.com/L-K-M/Blipbird/pull/36) | Repo hygiene: CONTRIBUTING/GLOSSARY/SECURITY/ADR template/editorconfig/gitattributes/Gradle pin | glm 7.1, 7.3 |

**Cleared (no longer backlog):** glm 1.1, 1.2, 1.3, 1.4, 1.5, 1.6, 1.7, 1.8, 1.9,
1.12, 1.13, 1.14, 1.15, 1.17, 2.1, 2.4 (progress bar only), 3.3, 3.4, 3.5, 3.6,
3.9, 5.7, 7.1, 7.3.

---

## B. Remaining verified code bugs / risks

> These are real defects confirmed by reading source; not yet patched (each
> needs either a policy decision or a larger change than fit a focused PR).

- **[bug]** `FlightRepository.kt:142` — **cancelled/diverted/departed/landed
  re-fire after the 3-day prune.** `EmittedEvent.expiresAt` shares the
  snapshot TTL (arrival + 3d); after pruning, the next refresh sees
  `previous == null`, so `NotificationPlanner.diff(null, current)` re-emits the
  transition and the pruned ledger can't dedup it. A long-tracked cancelled
  flight re-notifies on every open after day 3. Fix needs a policy decision:
  key transition-event ledger retention to flight lifetime (e.g.
  `trackedFlight.createdAt + 30d`), or don't prune transition events until
  archive/delete.
- **[minor-bug]** `FlightRepository.kt:76-81` — **`delete()` is not
  transactional.** Four DAO calls across two DBs; a crash mid-way orphans
  snapshots/fixes/events. Wrap the ops-db deletes in one `@Transaction` DAO
  method (the user-db `trackedDao.delete` is a separate DB and must stay
  separate).
- **[bug]** `FlightRepository.kt:171` — **callsign position lookup has no
  route/time sanity check** (PLAN.md §5 step 5 violation). A stale callsign
  match can be persisted as the live track. After a callsign fetch, verify the
  returned `AdsbAircraft`'s hex matches the cached `icao24`, or its lat/lon is
  within the active great-circle corridor, before persisting.
- **[risk]** `DaylightEngine.kt:114` — **tangent sunrise/sunset sample lost.**
  `if (e0 == 0.0 || e0 * e1 >= 0) continue` drops a sample landing exactly on
  the threshold; grazing crossings at a sample point vanish. Treat
  `abs(e1) < EPS` as a crossing at `samples[i].fraction`.
- **[risk]** `ReminderScheduler.kt:128-153,160-168` — **`goAsync()` receivers
  have no timeout.** `ReminderAlarmReceiver` / `BootCompletedReceiver` run
  under `goAsync()`'s ~10 s ANR budget on a free-standing scope; slow Room/IO
  at boot can exceed it and skip alarms. Enqueue a `OneTimeWorkRequest` for
  boot reconcile, and `withTimeoutOrNull(8_000)` in the alarm receiver.
- **[risk]** `Databases.kt:44` — **`OpsDatabase` uses destructive migration
  but holds non-rebuildable `quota_ledger`.** Any future schema bump silently
  zeroes the user's API-credit accounting, letting them overshoot paid quota.
  Provide real migrations for `quota_ledger` (and reference data), or split
  quota tracking into a third persisted DB.
- **[risk]** `FlightDetailViewModel.kt:246-267` — **detail polling never
  pauses** when the screen is off / the VM is on the back stack. `startPolling`
  fires `pollPosition` every 10–120 s for the VM lifetime. Gate on subscription
  state (`WhileSubscribed`) or `Lifecycle.ON_STOP`.
- **[minor-bug]** `NotificationPlanner.kt:43` — **delay copy under-reports.**
  A 29-min slip buckets to "15" and renders "Delayed 15m" (not "15m+" or the
  real minutes). Label as "15m+" or format actual minutes and rely on the
  fingerprint for dedup.
- **[minor-bug]** `FlightPhaseMachine.kt:53-54` — **future-dated fixes treated
  as fresh.** `.abs()` accepts fixes up to 30 min in the future (clock skew,
  buggy source) as airborne. Use `!lastFix.at.isAfter(now) && Duration...<30m`.
- **[minor-bug]** `MetarDecoder.kt` — wind `00000KT` (calm) now reads "wind
  calm" after PR #31, but variable-wind `VRB` direction and visibility
  (`VIS_M` was removed) are still not surfaced distinctly; consider a
  `variableWind` flag and a visibility decode pass.
- **[minor-bug]** `StatusProviders.kt` — codeshare self-reference: when ADB
  reports `IsCodeshared`, `codeshareOf = number` marks the flight as its own
  codeshare-of (ADB exposes no operating field here). Prefer `null` on both
  until enriched.
- **[minor-bug]** `StatusProviders.kt` — ADB time `"2026-07-22 14:10Z"` (no
  seconds) can yield `null` and silently lose dep/arr times. Pre-normalize
  missing seconds.
- **[risk]** `ReferenceImporter.kt:63-80` — minimal CSV parser doesn't handle
  embedded newlines in quoted fields and mishandles a trailing doubled `"`.
  Consider kotlinx-csv / opencsv, or harden the edge cases.
- **[risk]** No `Retry-After` honoring across the provider chain; 429 just
  falls through. Add an OkHttp interceptor / `QuotaLedger` short-circuit.
- **[risk]** `QuotaLedger.kt:28-34` — `canSpend` then `record` is a non-atomic
  check-and-increment; concurrent refreshes can both pass and double-spend.
  Collapse into one `trySpend` SQL `UPDATE … RETURNING`/affected-row-count.
- **[risk]** `BlipbirdApp.kt:42-50` — crash logger writes to disk on the
  crashing thread inside `setDefaultUncaughtExceptionHandler`; cap the write
  size / defer to a dedicated crash thread so it can't extend the ANR dialog.

## C. Remaining verified perf / visual / a11y / UX

### Perf
- **[perf]** `FlightDetailViewModel.kt:118-155` — 11-way `combine` rebuilds
  the entire `DetailUiState` per fix (~10 s), cascading into recomposing every
  detail item including the heavyweight `track`/`DaylightEngine.Result`. Split
  map-only state (`lastFix`/`track`) into a separately-collected flow.
- **[perf]** `FlightDetailScreen.kt:183-186` — hero `Brush.verticalGradient`
  reallocated every recomposition. `remember(cs)`.
- **[perf]** `RouteMap.kt`, `MapLibreRouteMap.kt` — `Path`/`Brush` allocated
  inside `Canvas` draw lambdas. Hoist + `rewind()`. (PR #35 fixed only
  `FlightProgressBar`.)
- **[perf]** `FlightListScreen.kt:100-103` — per-item `onClick` lambda
  disables skippable. Hoist to a remembered `(Long) -> Unit`.
- **[perf]** Monograms regenerated per row; cache in a small LRU keyed by
  airline code.
- **[perf]** No Baseline Profile generated until M4 (PLAN PR #12 moves it to
  M1, but the code doesn't generate one yet).

### Visual
- **[visual]** `Theme.kt:171` — **still no `Typography` or `Shapes`** passed
  to `MaterialTheme` (PR #35 added ribbon roles only, deferring this as
  higher-touch). The single biggest premium lever; adopt a type scale + shape
  scale and migrate ad-hoc `22.dp`/`26.dp`/`20.dp` corner literals.
- **[visual]** No **tabular figures** on any numeric display → countdown
  digits shift width (visible jitter). Add a `tabularFigures()` TextStyle
  helper and apply to the hero countdown, timeline times, stats line.
- **[visual]** Spacing is off the 4 dp grid; corner radii ad-hoc per call site.
- **[visual]** `FlightListScreen.kt:148`, `FlightDetailScreen.kt:215` — plane
  glyph always points right regardless of heading (LHR→NRT flies backwards).
  Rotate by `lastFix?.trackDeg` or dep→arr bearing.
- **[visual]** No loading skeletons (empty + refreshing shows nothing).
- **[visual]** `Theme.kt:81` Cockpit uses pure `#000000` (penTile smear risk);
  prefer ~`#05050A`.
- **[visual]** `MapLibreRouteMap` / `RouteMap` night polygon triple-draw alpha-
  stacks (`-360/0/360` copies) → visible darkening bands on wide views;
  bounding-box-cull each shift.

### Accessibility
- **[a11y]** Back arrow `contentDescription = null` (`FlightDetailScreen.kt:94`,
  `SettingsScreen.kt:60`) — TalkBack announces nothing for primary nav.
- **[a11y]** Map and ribbon have no `Modifier.semantics`/`contentDescription`
  (`MapLibreRouteMap.kt`, `RouteMap.kt`, `Ribbon.kt`). PLAN PR #12 mandates a
  ribbon text alternative; the Compose annotation is still needed.
- **[a11y]** `FlightDetailScreen.kt:495` timeline row hard-pinned to 44 dp →
  clips at 200 % font scale. `heightIn(min = 44.dp)`.
- **[a11y]** `FlightListScreen.kt:130` rows aren't `mergeDescendants` → 3
  TalkBack stops per row.
- **[a11y]** `SettingsScreen.kt:142` quota `Text` renders `∞` with no
  `contentDescription`.

### UX
- **[ux]** `FlightListViewModel.kt:127-128` — `archive`/`delete` are exposed
  but wired to nothing. Add `SwipeToDismissBox` (archive on end-swipe) +
  `SnackbarHost` Undo.
- **[ux]** `FlightDetailScreen.kt:344-373` — key-facts grid not phase-adaptive
  (shows `baggageBelt` "—" pre-flight). PLAN PR #13 fixed the plan; code needs
  a `phaseFacts(view.status)` builder.
- **[ux]** `FlightListScreen.kt:92` — no day grouping (Today/Tomorrow/Later).
- **[ux]** `FlightListScreen.kt:67` — `hasStatusKey` computed but never
  surfaced; keyless users get the generic empty state, not a "connect a data
  source" CTA.
- **[ux]** **Hardcoded user-facing strings throughout** (`SettingsScreen`,
  `AddFlightSheet`, `FlightDetailScreen`: "Boarding"/"Pushback"/"Takeoff"/
  "Landing"/"Gate arrival"/"Landed at"/"© OpenFreeMap …"/"YYYY-MM-DD"/
  "Granted"/"Allow precise alerts"). Mechanical but large; move to
  `strings.xml`.
- **[ux]** `MainActivity.kt:80-95` — no predictive-back animation
  (`enableOnBackInvokedCallback` + `PredictiveBackHandler`).
- **[ux]** `AddFlightSheet.kt:74-77` — date parse error silent (no
  `supportingText`).
- **[ux]** `MainActivity.kt:48-65` — notification deep link only read in
  `onCreate`; tapping a notification while the app is foregrounded doesn't
  navigate (no `onNewIntent`). Override `onNewIntent` + observable flight id.

## D. Implement the plan additions in code

PLAN PRs #11–#14 improved the plan; the code doesn't realize them yet:
- **[plan→code]** Injectable `core.time.Clock` (PLAN #11): code still reads
  `Instant.now()` directly in `FlightRepository` (multiple sites), domain
  cores, etc. Introduce the interface, inject, fake in tests. (PR #30 added
  SharedFlow *hearts* in the VMs but not a testable repository-level Clock.)
- **[plan→code]** Formal `ProviderHealth`/`CircuitBreaker` + `RateLimitBuckets`
  (PLAN #11): the chain currently just `continue`s on error; no
  OPEN/HALF_OPEN state, no per-host token bucket, no `Retry-After` honoring.
- **[plan→code]** `CrashRouter` (PLAN #11): `BlipbirdApp.kt` has a basic
  uncaught-exception logger; formalize/redact per plan + add a diagnostics
  view entry.
- **[plan→code]** Design-token system (PLAN #13): adopt `BlipbirdTypography`/
  `BlipbirdMotion`/`BlipbirdShapes`/`BlipbirdElevation`; predictive back;
  edge-to-edge translucent bars; theme schedule.
- **[plan→code]** Delighters (PLAN #14): Pickup Mode screen, route-diagram
  hero, sunset headline, sunrise alarm, tap-to-reverse-geocode, contrail,
  copy-ETA, time-travel ribbon scrubber, connection-risk indicator.
- **[plan→code]** Accessibility foundations (PLAN #12): the §18 a11y spec now
  exists; M0 code should ship the 48 dp scaffold + `semantics` conventions.

## E. Plan-level recommendations still open

- **Split M0** into M0a (engineering skeleton) and M0b (provider legal gates)
  running in parallel; current M0 is overloaded (glm 2.2).
- **Module-split heuristic** — state the rule for when to split a Gradle
  module (glm 2.6).
- **Primary-key strategy** — document ID approach for a sync-free/restore app
  (glm 2.8).
- **`hilt-navigation-compose` exclusion** as a lint/build-time check, not just
  prose (glm 2.9).
- **R8 keep-rule checklist** as an M1 release-build task (glm 2.10).
- **Provider-cost simulator** — "what will this flight cost me?" preview
  before adding (glm 2.11).
- **FTS search** over airports/airlines/saved flights (glm 4.1).
- **Per-event lead-time customization** in `NotificationProfile` (glm 4.12).
- **Notification actions** (Track on map / Snooze / Copy ETA / Share ETA)
  (glm 4.13).
- **Wear OS** complication (glm 4.14).
- **Clipboard-text export** alongside JSON (glm 4.17).
- **Third "airport departure board" widget** (glm 4.18).
- **M0 split**, usability-testing budget, CLA/DCO process (glm 2.4, 2.5, 16).

## F. Not changed, and why

- The **data-source / legal / retention spine** (`PLAN.md` §4/§16, Room
  backup split) is the strongest part of the project; PRs only *added* to it,
  never weakened a gate.
- The **MapLibre + OpenFreeMap** choice and the **no-backend, no-analytics**
  privacy stance are hard constraints — all delighter ideas stay
  on-device-compatible.
- The **milestone ordering** is preserved; only M0 is recommended to split.
