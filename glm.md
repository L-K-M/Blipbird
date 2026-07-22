# Blipbird — GLM 5.2 review

> Reviewer: **GLM 5.2** (`zai-coding-plan/glm-5.2`). Date: July 22, 2026.
>
> **Correction note.** A first pass of this document was written against the
> `review/plan-corrections` branch, which is *behind* `main` and lacks the actual
> application. `main` is a **working v0.1 Android app** (~60 Kotlin files, Gradle,
> CI, scripts, tests, MapLibre, adaptive icons). This rewrite reviews the **real
> code** plus `PLAN.md`. Every code finding below was verified by reading the file
> at the cited line; items tagged **[IN PR #n]** are implemented in separate PRs.
>
> Each item: **Severity** (bug / minor-bug / perf / visual / a11y / ux / risk) and
> a confidence note. Verified bugs found by independent parallel scans + my own read.

---

## 0. What's strong (don't break these)

0.1 The **data/legal/retention spine** in `PLAN.md` §4/§16 and the Room split
(`UserDatabase` backed up vs `OpsDatabase` excluded) are correct and careful.
0.2 **Pure decision cores** (`FlightPhaseMachine`, `DaylightEngine`, `MetarDecoder`,
`NotificationPlanner`, `GreatCircle`, `InstanceSelector`) are plain JVM with tests —
good testability for the hard math.
0.3 The **MapLibre Vulkan→OpenGL workaround** (`app/build.gradle.kts:96-101`) is the
right call given Huawei driver crashes; recent commits show active crash-fixing.
0.4 Provider **failover chain** with quota gating (`StatusProviderChain`) and the
persisted `EmittedEvent` dedup ledger are sound in concept.

---

## 1. Bugs (verified, will bite users)

1.1 **Countdowns never tick — the headline UX bug.** `FlightListViewModel.kt:76` and
`FlightDetailViewModel.kt:144` call `FlightPhaseMachine.derive(snapshot, …, Instant.now())`
*inside* a `map`/`combine` that only re-emits when a source snapshot/fix changes. So
"Departs in 2 h 14 m" is frozen until the next network write or app re-entry. *(Severity:
bug. Confidence: very high — confirmed by read. [IN PR #16])*

1.2 **`canScheduleExactAlarms()` crashes on Android 7–10.** `ReminderScheduler.kt:53`
(and `SettingsScreen.kt:127`) call `AlarmManager.canScheduleExactAlarms()` directly, but
that method is API 31+ and `minSdk = 26` → `NoSuchMethodError` on pre-S. *(bug. very high.
[IN PR #18])*

1.3 **Boarding alarm can fire for an already-airborne flight.** `ReminderScheduler.kt:62`
gates the boarding alarm on `snapshot.depTimes.actual == null`, but AeroDataBox only fills
`runwayActual` (wheels-off), not gate `actual`. A flight whose only "up" milestone is
`runwayActual` keeps its boarding alarm armed after takeoff. *(bug. high. [IN PR #18])*

1.4 **Notification / alarm IDs overflow and collide.** `NotificationEmitter.kt:99,111`:
`flightId.toInt()` truncates; `(flightId % Int.MAX_VALUE).toInt() * 10 + channel.hashCode() % 10`
overflows `Int` (negative IDs), collapses large `flightId`s onto the same slot, and
`hashCode() % 10` can be negative. Different flights replace each other's notifications.
`ReminderScheduler.kt:102`: `(flightId * 10 + …).toInt()` has the same truncation. *(bug.
high. [IN PR #18])*

1.5 **METAR `+RA`/`+SN` never decode, and the decoder stops at the first weather
phenomenon.** `MetarDecoder.kt:50` builds `Regex("(^| )$code( |$)")` per code, so a `+`-prefixed
code becomes a quantifier and never matches; the `break` drops any second phenomenon (`TSRA BR`
loses the mist). *(bug. very high. [IN PR #17])*

1.6 **METAR ceiling shows the last cloud layer, not the ceiling.** `MetarDecoder.kt:43`
`CLOUD.findAll(raw).lastOrNull()` → `BKN025 OVC100` reports "overcast at 10,000 ft" instead of
the 2,500 ft ceiling. *(bug. high. [IN PR #17])*

1.7 **METAR wind drops MPS (most non-US stations) and never flags calm/variable.**
`MetarDecoder.kt:10` only matches `KT`; European/Asian METARs with `MPS` lose wind entirely.
*(minor-bug. high. [IN PR #17])*

1.8 **`catch (_: Exception)` swallows `CancellationException`** in
`PositionProvider.kt:43` (and the same pattern in `StatusProviders.kt`, `WeatherRepository.kt`),
so cancelling the ViewModel/Worker scope doesn't actually cancel the in-flight Retrofit call
— structured concurrency is broken. *(bug. high. [IN PR #19])*

1.9 **Null `seenPos` is treated as a fresh position.** `PositionProvider.kt:51`
`Instant.now().minusMillis(((seenPos ?: 0.0) * 1000).toLong())` makes a record with no
position-timestamp look brand-new and it gets persisted as the latest fix. *(bug. high.
[IN PR #19])*

1.10 **Cancelled/diverted/departed/landed re-fire after the 3-day prune.**
`FlightRepository.kt:142` keys `EmittedEvent.expiresAt` to `expiryFor(snapshot)` (arrival + 3d,
shared with snapshot TTL). After prune, the next refresh sees `previous == null`, so
`NotificationPlanner.diff(null, current)` re-emits the transition and the pruned ledger can't
dedup it — a long-tracked cancelled flight re-notifies on every open after day 3. *(bug. high.
Recommendation in ANALYSIS.md; needs a policy decision on ledger retention vs. transition
idempotency — not patched here to avoid changing retention semantics without sign-off.)*

1.11 **`FlightRepository.delete()` is not transactional.** `:76-81` does four DAO calls
across two DBs; a crash mid-way orphans snapshots/fixes/events (or leaves ops rows for a
deleted tracked flight). *(minor-bug. high. Recommendation in ANALYSIS.md — needs an ops-side
`@Transaction` DAO method.)*

1.12 **`FlightDetailViewModel` init/property-init order is fragile.** `:86-88` `init { bind() }`
runs *before* the property initializers at `:90-93` (`boundId = -1`, etc.), so `bind` writes
`boundId` and the initializer then resets it to `-1`. Currently latent (works because `bind`'s
collections still launch), but one reorder away from a real bug. *(minor-bug. high.
[IN PR #16])*

1.13 **Near-antipodal routes divide by ~0.** `GreatCircle.kt:30-44` `intermediate()` guards the
coincident case (`d < 1e-9`) but not the antipodal case (`d ≈ π` → `sin(d) ≈ 0`), so polar /
near-antipodal routes return garbage lat/lon, breaking the route polyline, terminator, and
daylight sampling. *(bug. high. [IN PR #20])*

1.14 **Antimeridian split leaves a one-segment gap.** `GreatCircle.kt:64` starts the new
segment at `cur` without inserting the interpolated ±180° vertex, so the polyline visibly
breaks across the date line. *(minor-bug / visual. high. [IN PR #20])*

1.15 **Night-side polygon is frozen when there's no live fix.**
`MapLibreRouteMap.kt:87-89` keys `remember(lastFix?.at?.epochSecond?.div(600))` — when
`lastFix` is null (planned-route view, no ADS-B match) the key is null forever, so the
terminator is computed once at composition and never advances. *(bug. high. [IN PR #20])*

1.16 **Callsign position lookup has no route/time sanity check.** `FlightRepository.kt:171`
queries by `callsignGuess` and persists the first hit, but PLAN.md §5 step 5 requires a
route/time sanity match first (callsigns are reused daily). A stale callsign match can be
persisted as the live track. *(bug / risk. medium. Recommendation in ANALYSIS.md — needs the
snapshot's `icao24`/corridor to validate against.)*

1.17 **`FlightProgressBar` animation never settles.** `FlightProgressBar.kt:32`
`tween(700)` restarts on every ~10 s ADS-B poll while airborne, so the bar is perpetually
mid-tween. *(minor-bug / perf. high. [IN PR #22])*

1.18 **Tangential sunrise/sunset sample can be lost.** `DaylightEngine.kt:114`
`if (e0 == 0.0 || e0 * e1 >= 0) continue` drops a sample landing exactly on the threshold;
grazing crossings at a sample point vanish. *(risk. medium. Recommendation in ANALYSIS.md.)*

1.19 **`goAsync()` receivers have no timeout.** `ReminderScheduler.kt:128-153,160-168`
`ReminderAlarmReceiver` / `BootCompletedReceiver` create a free-standing
`CoroutineScope(Dispatchers.IO)` under `goAsync()`'s ~10 s ANR budget; slow Room/IO at boot
can exceed it and skip alarms. *(risk. high. Recommendation in ANALYSIS.md — boot reconcile
should enqueue a `OneTimeWorkRequest`.)*

1.20 **`OpsDatabase` uses destructive migration but holds non-rebuildable `quota_ledger`.**
`Databases.kt:44` `fallbackToDestructiveMigration(dropAllTables = true)` would silently zero
the user's API-credit accounting on any future schema bump, letting them overshoot paid quota.
*(risk. high. Recommendation in ANALYSIS.md.)*

1.21 **Detail polling loop never pauses when the screen is off.**
`FlightDetailViewModel.kt:246-267` `startPolling` runs for the ViewModel lifetime (back-stack
included) and fires `pollPosition` every 10–120 s even when no UI is consuming. *(risk. medium.
Recommendation in ANALYSIS.md — gate on subscription/lifecycle.)*

1.22 **Delay-notification copy under-reports.** `NotificationPlanner.kt:43` buckets a 29-min
slip to "15" and `notif_delay` renders "Delayed 15m" (not "15m+" or the real minutes).
*(minor-bug. medium. Recommendation in ANALYSIS.md.)*

---

## 2. Performance / jank (verified)

2.1 **Ribbon gradient rebuilt every frame.** `Ribbon.kt:73-77` `Brush.horizontalGradient(...)`
is constructed inside the `Canvas` draw lambda (re-runs every recomposition + every position
poll). *(perf. very high. [IN PR #22])*

2.2 **Hero background brush reallocated every recomposition.** `FlightDetailScreen.kt:183-186`
`Brush.verticalGradient(listOf(...))` on every poll. *(perf. high. [IN PR #22])*

2.3 **11-way `combine` rebuilds the entire `DetailUiState` per fix.**
`FlightDetailViewModel.kt:118-155` — a new `lastFix` (~10 s) cascades into recomposing every
visible detail item including the heavyweight `track`/`DaylightEngine.Result`. *(perf. high.
Recommendation in ANALYSIS.md — split map-only state out.)*

2.4 **`Path` + `Brush` allocated inside `Canvas` draw lambdas.** `FlightProgressBar.kt:63`,
`RouteMap.kt`, `MapLibreRouteMap.kt`. *(perf. medium. [IN PR #22] for the progress bar.)*

2.5 **List `onClick` lambda allocated per item per recomposition** disables skippable.
`FlightListScreen.kt:100-103`. *(perf. medium. [IN PR #22].)*

2.6 **No Baseline Profile / no jank budgets enforced.** (Plan-level — addressed in PLAN PR #2.)

2.7 **Monograms not cached.** Generated per row; on long scrolls this redraws. *(perf. low.
Recommendation in ANALYSIS.md.)*

---

## 3. Visual / aesthetic ("mid Android" tells — verified)

3.1 **No `Typography` and no `Shapes` in `MaterialTheme`.** `Theme.kt:171`
`MaterialTheme(colorScheme = …, content = content)` passes neither → default Roboto + default
M3 shapes. The single biggest premium-gap given the rest of the design effort. *(visual. very
high. [IN PR #22])*

3.2 **No tabular figures on any numeric display.** `Common.kt:64` (and all `Text` using
countdowns/times) → countdown digits shift width as they change, visible horizontal jitter.
*(visual. very high. [IN PR #22])*

3.3 **`StatusWord` white-on-amber/neutral fails WCAG AA.** `Common.kt:42-48` always uses
`Color.White`; `statusDelayed` (#B26A00) ≈ 3.0:1 and `statusNeutral` (#5F6368) ≈ 4.6:1 on
small bold text. *(visual / a11y. high. [IN PR #22])*

3.4 **Ribbon sunrise/sunset/aircraft colors are hardcoded, not theme-aware.** `Ribbon.kt:85,95`
`Color(0xFFFFD54F)/Color(0xFFFF8A65)/Color(0xFF1667D9)` ignore `ExtendedColors` → Cockpit and
High-Contrast get the wrong palette. *(visual. high. [IN PR #22])*

3.5 **Ribbon labels use raw emoji** (`🌅`/`🌇`) despite the file's own comment (line 138)
warning emoji render as tofu on OEM fonts. *(visual. high. [IN PR #22])*

3.6 **`bandColor` skips civil twilight.** `Ribbon.kt:126-133` jumps day→nautical, hiding the
civil band the `LightBand` enum models. *(visual. medium. [IN PR #22])*

3.7 **Spacing is off the 4 dp grid and corner radii are ad-hoc** (`22.dp`/`26.dp`/`20.dp` per
call site). *(visual. medium. [IN PR #22] via Shapes.)*

3.8 **Plane glyph in the list/detail route row always points right** regardless of heading
(`FlightListScreen.kt:148`, `FlightDetailScreen.kt:215`) — a LHR→NRT flight visually flies
backwards. *(visual. medium. Recommendation in ANALYSIS.md.)*

3.9 **`drawCircle(Color.Transparent, …)` is a no-op dead line.** `FlightProgressBar.kt:57`.
*(visual/trivial. [IN PR #22])*

3.10 **No loading skeletons** — empty + refreshing shows nothing; only pull-to-refresh shows a
spinner. Premium apps use content-shaped skeletons. *(visual. medium. Recommendation in
ANALYSIS.md.)*

3.11 **Cockpit uses pure `#000000`** (`Theme.kt:81`) → smear risk on penTile at low brightness.
*(visual. low. Recommendation in ANALYSIS.md.)*

---

## 4. Accessibility (verified)

4.1 **Back arrow has `contentDescription = null`.** `FlightDetailScreen.kt:94`,
`SettingsScreen.kt:60` — TalkBack announces nothing for the primary nav affordance. *(a11y.
high. [IN PR #22])*

4.2 **Map and ribbon have no `semantics`/`contentDescription`.** `MapLibreRouteMap.kt:94`,
`RouteMap.kt:52`, `Ribbon.kt:61` — TalkBack users get zero route/daylight info. (Plan-level
text alternative addressed in PLAN PR #2; the Compose `semantics` annotation is a code fix.
Recommendation in ANALYSIS.md for the map; ribbon done in [PR #22].)*

4.3 **Timeline row is hard-pinned to 44 dp** (`FlightDetailScreen.kt:495`) — at 200 % font
scale the two stacked times clip. *(a11y. medium. Recommendation in ANALYSIS.md.)*

4.4 **List rows aren't `mergeDescendants`** → "BA286 … On time" becomes 3 TalkBack swipe stops.
`FlightListScreen.kt:130`. *(a11y. medium. Recommendation in ANALYSIS.md.)*

4.4b **Quota `Text` renders `∞`** with no `contentDescription`. `SettingsScreen.kt:142`.
*(a11y. low. Recommendation in ANALYSIS.md.)*

---

## 5. UX / convenience (verified)

5.1 **`archive`/`delete` are exposed by the VM but wired to nothing.**
`FlightListViewModel.kt:127-128` — no swipe/undo. *(ux. high. Recommendation in ANALYSIS.md.)*

5.2 **Key-facts grid is not phase-adaptive** — shows `baggageBelt` as "—" pre-flight.
`FlightDetailScreen.kt:344-373`. (My PLAN PR #3 addresses the plan-level north-star; the code
fix is a Recommendation in ANALYSIS.md.)*

5.3 **No day grouping** in the list — multi-day trips are a flat list. `FlightListScreen.kt:92`.
*(ux. medium. Recommendation in ANALYSIS.md.)*

5.4 **`hasStatusKey` is computed but never surfaced** — keyless users get the generic empty
state, not a "connect a data source" CTA. `FlightListViewModel.kt:46`, `FlightListScreen.kt:67`.
*(ux. medium. Recommendation in ANALYSIS.md.)*

5.5 **Hardcoded user-facing strings throughout** (`SettingsScreen`, `AddFlightSheet`,
`FlightDetailScreen`: "Boarding"/"Pushback"/"Takeoff"/"Landing"/"Gate arrival"/"Landed at"/
"© OpenFreeMap …"/"YYYY-MM-DD"/"Granted"…) — none localizable. *(ux. high. Recommendation in
ANALYSIS.md — large, mechanical.)*

5.6 **No predictive-back support.** `MainActivity.kt:80-95` uses `BackHandler` with no
predictive animation. *(ux. low. Recommendation in ANALYSIS.md.)*

5.7 **`SettingsScreen` exact-alarm button label goes stale** — re-reads
`canScheduleExactAlarms()` immediately after `startActivity`, before the user grants.
`SettingsScreen.kt:126-136`. *(ux. medium. [IN PR #18].)*

5.8 **Add-flight date error is silent** (no `supportingText`). `AddFlightSheet.kt:74-77`.
*(ux. low. Recommendation in ANALYSIS.md.)*

---

## 6. Delighters / missing features (recommendations)

(The full delighter set is in PLAN PR #4 at the plan level. Code-side, these are
ANALYSIS.md recommendations: Pickup Mode screen, route-diagram hero, sunset headline,
sunrise alarm, tap-to-reverse-geocode, fading contrail, copy-ETA, connection-risk
indicator, swipe-to-archive+undo, day grouping, loading skeletons, theme schedule.)

---

## 7. Repo hygiene (accurate against `main`)

7.1 **No `CONTRIBUTING.md`, `GLOSSARY.md`, `SECURITY.md`, `.editorconfig`, `.gitattributes`,
or `docs/decisions/` template.** `main` does have `README.md`, `.gitignore`, CI, scripts,
adaptive icons — so the earlier "nothing exists" claim was wrong. These contributor/onboarding
docs are genuine gaps. *(general. high. [IN PR #23])*

7.2 **No `ANALYSIS.md`** (this review seeds it). *(process. [this PR set].)*

7.3 **`PLAN.md` ↔ `AGENTS.md` Gradle pin disagreed** (9.5.0 vs 9.6.1). *(general. high.
Addressed in PLAN PR set / repo-hygiene PR.)*

---

## 8. Plan-level findings (PLAN.md, retained from first pass; valid)

These remain valid plan improvements independent of the code. Implemented in PLAN PRs
#11–#15 (see §9): runtime `Clock`/`CircuitBreaker`/`Heartbeat`/`CrashRouter`/rate-limit
buckets; accessibility & jank budgets designed-in-from-M0; full design-token system +
premium-aesthetic refinements; the delighter roster (Pickup Mode, route-diagram hero,
sunset headline, sunrise alarm, contrail, reverse-geocode, time-travel scrubber, axis
decision); identity-resolution hardening (tab/semicolon separators, unknown-prefix
acceptance, renumbering detection).

Plan-level recommendations *not* implemented (carried to ANALYSIS.md): M0 scope split
(M0a engineering / M0b legal); decision-record discipline already added via repo-hygiene;
module-split heuristic; primary-key strategy; `hilt-navigation-compose` build-time guard;
R8 keep-rule checklist; provider-cost simulator; FTS search; per-event lead-time
customization; notification actions; Wear OS; clipboard-text export; third widget; etc.

---

## 9. PRs opened by this review

### Plan-level (`PLAN.md`) — branches off `main`, applied cleanly
| PR | Title | PLAN sections |
|---|---|---|
| #11 | Architecture hardening: Clock, CircuitBreaker, Heartbeat, CrashRouter, rate-limit buckets | §4.2, §6, §9.1, §17 |
| #12 | Accessibility & jank budgets: designed in from M0, not audited at M4 | §9.4, §14, §18 |
| #13 | Design-token system + premium-aesthetic refinements | §9.1, §9.2, §10 |
| #14 | Delighters: Pickup Mode, route-diagram hero, sunrise alarm, ribbon scrubber, contrail, reverse-geocode | §9.2, §9.4, §11, §12, §14 |
| #15 | Harden flight identity resolution: separators, unknown-prefix, renumbering detection | §5, §9.3 |

### Code-level (real `app/` fixes — branches off `main`, verified against source)
| PR | Title | Files | Driven by |
|---|---|---|---|
| #16 | Countdown heartbeat (frozen-countdown fix) + detail-VM init order | `FlightListViewModel.kt`, `FlightDetailViewModel.kt` | 1.1, 1.12 |
| #17 | METAR decoder correctness (+RA/ceiling/MPS/multi-phenomenon) | `MetarDecoder.kt` (+test) | 1.5, 1.6, 1.7 |
| #18 | Notification/alarm correctness: ID scheme, exact-alarm API<31 guard, boarding-after-airborne, stale settings label | `NotificationEmitter.kt`, `ReminderScheduler.kt`, `SettingsScreen.kt` | 1.2, 1.3, 1.4, 5.7 |
| #19 | Network coroutine hygiene (rethrow CancellationException) + null-seenPos | `PositionProvider.kt`, `StatusProviders.kt`, `WeatherRepository.kt` | 1.8, 1.9 |
| #20 | Map geometry: near-antipodal guard, antimeridian vertex, frozen night-polygon key | `GreatCircle.kt`, `MapLibreRouteMap.kt` | 1.13, 1.14, 1.15 |
| #22 | Visual/perf polish: Typography+Shapes, tabular figures, StatusWord contrast, ribbon theme-awareness+emoji+civil band, hero/ribbon brush remember, progress-bar tween+Path+dead code, list lambda, back-arrow a11y | `Theme.kt`, `Common.kt`, `Ribbon.kt`, `FlightProgressBar.kt`, `FlightDetailScreen.kt`, `FlightListScreen.kt` | 2.1, 2.2, 2.4, 2.5, 3.1–3.9, 4.1 |
| #23 | Repo hygiene: CONTRIBUTING, GLOSSARY, SECURITY, decision-record template, .editorconfig, .gitattributes | new files | 7.1 |

Everything not tagged **[IN PR #n]** is carried into `ANALYSIS.md` as a recommendation.
