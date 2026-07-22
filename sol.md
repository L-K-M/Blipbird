# Blipbird Project Review

Review date: 2026-07-22

Reviewed revision: `209822093bf494e591d373bde2d4cc5f0cefa53d` (`origin/main` at the end of the review)

## Scope and evidence

This review covers the Android application, pure decision cores, Room model and schemas,
provider adapters, WorkManager and alarm behavior, Compose UI, MapLibre integration,
resources, reference-data generation, privacy and retention behavior, build scripts, CI/CD,
release automation, README claims, and the much larger design contract in `PLAN.md`.

Verification performed:

- Read every Kotlin production file and all five JVM test files.
- Compared the implementation with `README.md` and the milestone/acceptance criteria in
  `PLAN.md`.
- Ran `./gradlew --no-daemon --max-workers=2 testDebugUnitTest`: all 63 current JVM tests
  pass.
- Reproduced `lintDebug`: 4 errors and 20 warnings. The four errors are one notification
  permission error and three unguarded API-31 exact-alarm calls.
- Confirmed debug compilation and APK packaging complete before lint rejects the build.
- Checked GitHub Actions. CI for the reviewed revision fails at lint for the same findings.
- Confirmed there are currently no GitHub tags or Releases, although the README advertises
  `v0.1.0` and links to `releases/latest`.
- No Android device or emulator is available in this environment. Visual, animation,
  jank, OEM renderer, process-death, and accessibility conclusions are therefore based on
  source analysis rather than screenshot tests or profiler measurements.

The repository changed during the review. Two issues found against the initial revision
were fixed on `main` before this document was finalized: locale-dependent decimal formatting
in Open-Meteo/GeoJSON, and a MapLibre `LocalStyleNode` crash caused by creating sources
outside the map content lambda. They are not listed below as open defects.

## Executive assessment

Blipbird is an unusually ambitious and visually promising prototype. It has a real status
provider chain, encrypted BYO-key storage, Room persistence split by backup boundary,
MapLibre tiles, ADS-B positions, offline solar calculations, METAR decoding, themes,
notifications, exact-alarm scaffolding, and useful pure-JVM tests. The product direction is
strong: calm, personal, honest about data, and differentiated from radar-centric trackers.

It is not release-ready. The most serious problems are not cosmetic:

1. Supported Android 8-11 devices can crash when Settings or reminder reconciliation calls
   API-31 methods.
2. Every visited flight detail can keep polling ADS-B every ten seconds after the screen is
   closed and while the app is backgrounded.
3. A typo, out-of-window flight, rejected provider, or persistent no-result can be retried
   every 15 minutes until the user's monthly allowance is exhausted.
4. Aircraft matching can accept the first coordinate-bearing ADS-B result without proving
   it is the tracked flight, then cache that aircraft for later polls.
5. Flight occurrence selection, provider timestamp semantics, phase transitions, and
   notification delivery contain cases that can show or alert on the wrong event.
6. The README materially overstates privacy, zero-key capability, reminder fallback,
   swipe management, estimate history, and release availability.
7. Several promised aviation visualizations currently imply precision that the model does
   not have, particularly the silent 11 km cabin horizon and fixed 250 hPa route weather.
8. The interface has a good visual foundation but lacks the state handling, accessibility,
   responsive layouts, information hierarchy, and interaction polish expected of a premium
   flight-day product.

The right next move is stabilization before adding more provider breadth. Correct identity,
occurrence, lifecycle, quota, and state semantics first; then make the UI feel exceptional.

## Severity and disposition

- **Blocker:** prevents a responsible release or can present materially wrong operational
  information.
- **High:** substantial correctness, privacy, cost, reliability, accessibility, or battery
  impact.
- **Medium:** meaningful user friction, incomplete degradation, performance risk, or
  maintenance debt.
- **Low:** polish, localized edge case, or bounded inefficiency.
- **Implement now:** narrow enough and sufficiently certain to implement in an isolated PR.
- **Design first:** correct direction is clear, but schema/provider/product choices need an
  explicit decision or a broader migration.
- **Roadmap:** valuable feature work rather than a defect repair.

## Implementation-ready findings

Each row below is intentionally scoped as one independently reviewable change. These are the
entries selected for isolated branches and PRs after this review document is complete.

| ID | Severity | Change | Primary files | Why implementation is safe |
|---|---|---|---|---|
| SOL-001 | Blocker | Restore the lint gate and API 26 compatibility by guarding exact-alarm APIs and defensively checking notification permission at delivery. | `ReminderScheduler.kt`, `SettingsScreen.kt`, `NotificationEmitter.kt` | Android API contracts and lint evidence are unambiguous. |
| SOL-002 | Blocker | Stop detail ADS-B polling when the detail UI is not started/disposed. | `FlightDetailViewModel.kt`, `FlightDetailScreen.kt` | The current lifetime leak is deterministic; foreground-only polling is already the documented contract. |
| SOL-003 | Blocker | Prefer status-derived registration over guessed callsign, validate ADS-B responses against the query, reject stale/invalid fixes, and rethrow cancellation. | `FlightRepository.kt`, `PositionProvider.kt` | Returning no plane is explicitly preferable to displaying the wrong plane. |
| SOL-004 | Blocker | Persist status lookup attempt/backoff state so no-snapshot flights are not queried every 15 minutes. | ops Room model/DAO, `FlightRepository.kt`, `RefreshWorker.kt` | Preventing repeated paid no-result calls is clearly required; a separate operational record preserves backup boundaries. |
| SOL-005 | High | Remove the invented default 11 km cruise altitude from daylight calculations and test surface-vs-cabin behavior. | `DaylightEngine.kt`, tests | `null` is already documented as the honest unknown-altitude behavior. |
| SOL-006 | High | Clamp great-circle haversine math and preserve both route halves at the antimeridian. | `GreatCircle.kt`, tests | Pure math fix with deterministic regression tests. |
| SOL-007 | High | Correct heavy-weather token matching and report the lowest relevant METAR ceiling. | `MetarDecoder.kt`, tests | Direct parser bugs with representative fixtures. |
| SOL-008 | High | Mask API keys, disable keyboard suggestions, support explicit removal, and avoid retaining entered secrets after save. | Settings UI/ViewModel, `ProviderKeyStore.kt`, strings | Standard credential hygiene; removal is already supported by the storage layer. |
| SOL-009 | High | Record notification dedup only after an event is eligible and successfully handed to the platform; use a retryable pending/delivered state. | repository notification contract, ops Room model, emitter | The current write-before-delivery ordering deterministically loses alerts. |
| SOL-010 | High | Disable unresolved ADS-B fallback providers in release behavior and align source copy with the approved allowlist. | `PositionProvider.kt`, README/about copy | `PLAN.md` explicitly says these providers remain disabled pending written permission. |
| SOL-011 | High | Replace absolute privacy/zero-key claims with accurate no-backend, OS-backup, and provider-recipient disclosure. | `README.md`, strings/about/onboarding copy | The current claims contradict actual network and backup behavior. |
| SOL-012 | High | Refuse to publish an unsigned APK and verify signed release artifacts. | `release.yml`, README | An unsigned APK is not installable and should never be advertised as a usable release. |
| SOL-013 | High | Fix status-chip contrast and add semantics for progress, navigation, and visual route summaries. | theme/components/detail/settings UI | Current contrast can be measured below WCAG thresholds and important state is visual-only. |
| SOL-014 | High | Add a lifecycle-aware clock so countdowns, phase, sorting, progress, and freshness advance without a provider write. | list/detail ViewModels and UI | Time-dependent UI that does not advance is a deterministic correctness defect. |
| SOL-015 | High | Keep the add sheet open until async parsing finishes and show structured partial-success errors. | add/list UI and ViewModel | The current sheet always hides the only error surface before errors arrive. |

## Correctness and reliability findings

### SOL-C01: API 26-30 exact-alarm crash

**Severity:** Blocker  
**Confidence:** Confirmed  
**Disposition:** Implement now as SOL-001

`minSdk` is 26 (`app/build.gradle.kts:13-16`), but
`AlarmManager.canScheduleExactAlarms()` is called without an SDK guard in
`ReminderScheduler.kt:53-58` and `SettingsScreen.kt:126-132`. Settings can fail as soon as it
composes on Android 8-11. Worker refresh and boot reconciliation can reach the same method.
Below API 31, exact alarms do not require this special-access check; return true and hide the
special-access UI.

### SOL-C02: Notification delivery fails lint and is not race-safe

**Severity:** Blocker  
**Confidence:** Confirmed  
**Disposition:** Implement now as SOL-001

`NotificationEmitter.kt:93-111` checks permission before calling a private helper, but the
actual `notify()` call is neither locally guarded nor prepared for permission revocation
between check and delivery. Lint reports `MissingPermission`, and CI fails. Recheck at the
call site and catch `SecurityException`; API-26 behavior should treat notification permission
as granted because `POST_NOTIFICATIONS` begins at API 33.

### SOL-C03: Off-screen and background ADS-B polling

**Severity:** Blocker  
**Confidence:** Confirmed  
**Disposition:** Implement now as SOL-002

The custom stack in `MainActivity.kt:68-95` has no destination-scoped
`ViewModelStoreOwner`. `hiltViewModel(key = "flight-$flightId")` in
`FlightDetailScreen.kt:73-79` therefore creates activity-retained ViewModels. `bind()` starts a
permanent `viewModelScope` loop (`FlightDetailViewModel.kt:95-116,246-267`). Leaving detail,
opening several flights, or pressing Home does not stop those loops. This leaks travel-interest
queries, battery, data, retained tracks, Room work, and provider capacity. Polling must follow
the visible `STARTED` lifecycle; longer term, use real navigation entries and one process-wide
position coordinator.

### SOL-C04: No-snapshot status calls can consume the monthly allowance

**Severity:** Blocker  
**Confidence:** Confirmed  
**Disposition:** Implement now as SOL-004

`RefreshWorker.kt:54-61` treats `snapshot == null` as always due. `NotFound` calls consume
quota and still leave no snapshot (`FlightRepository.kt:299-310`). At the 15-minute worker
floor, one unresolved AeroDataBox lookup can reach the 560-unit soft stop in roughly 70 hours.
Errors have no persisted retry state and may not be accounted at all. Persist attempt time,
outcome, consecutive failures, and `nextEligibleAt`; negatively cache no-result responses,
back off transient errors, and stop retrying auth/validation failures until user action.

### SOL-C05: Aircraft matching can lock onto the wrong plane

**Severity:** Blocker  
**Confidence:** Confirmed  
**Disposition:** Implement now as SOL-003

`FlightRepository.kt:168-177` tries a cached hex and guessed callsign before the current
snapshot's registration. `PositionProvider.kt:32-45` accepts the first aircraft with
coordinates without checking that returned hex, registration, or callsign matches the query.
Missing `seen_pos` becomes a fresh age of zero at `PositionProvider.kt:50-60`. An aircraft
swap, reused callsign, or ambiguous response can therefore display and persist an unrelated
aircraft. Current status identity must win; response identity, coordinate range, freshness,
and plausible aircraft records must be validated before persistence.

### SOL-C06: Status failover ignores its own error classification

**Severity:** High  
**Confidence:** Confirmed  
**Disposition:** Design first with the request coordinator

Providers distinguish rejected keys, rate limits, and transient failures in
`StatusProviders.kt:55-64,139-148`, but `StatusProviderChain` falls through on every `Error`
at `FlightRepository.kt:299-310`. This contradicts the comment that rejected keys are never
routed around. A rejected primary key can be retried forever while silently spending against
the fallback. The fix needs a typed provider-health state, `Retry-After`, user-visible key
rejection, and conservative accounting for every potentially billable attempt.

### SOL-C07: Status spending is not atomically reserved

**Severity:** High  
**Confidence:** Confirmed race  
**Disposition:** Design first with SOL-C06

`QuotaLedger.kt:28-35` performs `canSpend` separately from the later network call and record.
List add, worker, detail startup, and pull-to-refresh can overlap. Multiple calls can all pass
the soft stop, and `force = true` bypasses the nominal 30-second freshness guard. A provider
mutex plus atomic `tryReserve()` transaction is needed. Billing cycles must use provider reset
anchors rather than UTC calendar month.

### SOL-C08: Explicit AeroAPI date lookup covers nearly two UTC days

**Severity:** High  
**Confidence:** Confirmed  
**Disposition:** Design first because origin timezone may not yet be known

`StatusProviders.kt:129-138` sends `date 00:00Z` through the end of the following UTC day.
This can exclude a positive-offset departure early on the requested local date and include
adjacent-day flights. Selection then ranks relative to now rather than enforcing the requested
departure-local date. Query a bounded UTC superset and filter every candidate using the
origin IANA zone, or ask the user when the origin/date cannot be resolved unambiguously.

### SOL-C09: Dateless repair can still pin the wrong day and adds paid calls

**Severity:** High  
**Confidence:** Confirmed residual risk  
**Disposition:** Design with SOL-C08

The new second-lookup heuristic in `FlightRepository.kt:104-126` improves one observed case,
but if the verification call is blocked, fails, or is empty, the suspicious provisional
tomorrow instance is still selected and permanently pinned. The helper falls back to UTC and
uses an arbitrary four-hour cutoff (`InstanceSelector.kt:58-68`). It can also double provider
cost. The app needs explicit occurrence confidence and an ambiguity state, not another silent
heuristic.

### SOL-C10: Instance selection can discard the active codeshare or prefer stale partial data

**Severity:** High  
**Confidence:** Confirmed  
**Disposition:** Design first

`InstanceSelector.kt:21-30` removes all codeshare records whenever any operating record exists,
even if the codeshare is today's active occurrence and the unrelated operating result is
tomorrow. Any historical record with an actual departure and no arrival also wins indefinitely.
Rank occurrence/phase across all candidates first, group records representing the same physical
instance, bound incomplete airborne records by plausible duration, and prompt on ambiguity.

### SOL-C11: Provider-reported and terminal phases can regress

**Severity:** High  
**Confidence:** Confirmed  
**Disposition:** Design first

`FlightPhaseMachine.kt:56-77` can turn reported `ARRIVED`, `LANDED`, or `EN_ROUTE` into
`DEPARTED` when timestamps are incomplete. Snapshots are inserted independently without a
monotonic milestone merge. A later lower-quality snapshot can regress an arrived flight.
Define trust/provenance, preserve actual milestones and terminal state, and permit correction
only from a higher-trust explicit source.

### SOL-C12: AeroDataBox runway fields are treated as actual without proving actuality

**Severity:** Blocker for alerts  
**Confidence:** High based on provider contract  
**Disposition:** Design first with provider fixtures

`StatusProviders.kt:93-102` always maps `runwayTime` to `runwayActual`, although the provider
uses fields whose actual/estimated meaning depends on state. This can mark a scheduled flight
airborne/landed and emit departure/landing alerts. Preserve certainty or classify the field
using a contract-tested provider state before it drives phase or notifications.

### SOL-C13: AeroAPI `cancelled` is treated as authoritative airline cancellation

**Severity:** Blocker for alerts  
**Confidence:** High based on provider contract  
**Disposition:** Design first with provider fixtures

`StatusProviders.kt:151-160` gives the boolean absolute priority, and
`NotificationPlanner.kt:48-50` can issue a critical cancellation. The provider field can also
mean tracking stopped for another reason. Until a reliable reason/status is available, map it
to ended/unknown tracking and do not send an authoritative cancellation alert.

### SOL-C14: Reminder fallback promised by README does not exist

**Severity:** High  
**Confidence:** Confirmed  
**Disposition:** Design first

`ReminderScheduler.kt:55-58` simply returns when exact-alarm access is unavailable. There is
no unique one-time WorkManager reminder. Reconciliation happens only after a successful due
periodic refresh, not after initial add, manual refresh, settings changes, archive, or permission
changes. README lines 36-39 promise graceful WorkManager fallback. One-time work should be the
baseline, with exact alarms as an optional precision replacement.

### SOL-C15: Stale, disabled, archived, cancelled, or completed reminders can fire

**Severity:** High  
**Confidence:** Confirmed  
**Disposition:** Design with SOL-C14

Archive and reminder toggles do not cancel alarms. The receiver at
`ReminderScheduler.kt:123-150` does not recheck current settings, archived status, cancellation,
actual OUT/OFF/ON/IN, target fingerprint, or target-time tolerance. Centralize one pure
`shouldSchedule/shouldFire` predicate and run it both at scheduling and receive time.

### SOL-C16: Dedup is committed before notification delivery

**Severity:** High  
**Confidence:** Confirmed  
**Disposition:** Implement now as SOL-009

`FlightRepository.kt:133-147` inserts the fingerprint before `NotificationEmitter` checks OS
permission or user settings (`NotificationEmitter.kt:63-71`). A disabled or denied notification
is permanently considered emitted. Use an outbox or pending/delivered state with a stable
notification ID, marking delivered only after a successful platform call.

### SOL-C17: Initial snapshots and improving delays can create misleading alerts

**Severity:** High  
**Confidence:** Confirmed  
**Disposition:** Design with SOL-009

`NotificationPlanner.kt:26-64` can emit historical departed/landed/cancelled facts on first
tracking. Delay events use buckets without comparing previous delay, so an improvement from
46 to 31 minutes can produce another delay alert. Gate fingerprints suppress a real later
return to a previously used gate. Establish first snapshot as baseline, compare transitions,
and fingerprint normalized old->new state.

### SOL-C18: Delete is crash-unsafe and races in-flight work

**Severity:** High  
**Confidence:** Confirmed  
**Disposition:** Design first

`FlightRepository.kt:74-81` deletes across two databases and several statements. A refresh or
position request that already holds the flight can insert provider data and notifications after
deletion; process death can leave orphans. Serialize per-flight work, cancel alarms first, use
a deletion tombstone, recheck before writes, and perform idempotent startup orphan cleanup.

### SOL-C19: Reference import can become permanently partial

**Severity:** High  
**Confidence:** Confirmed  
**Disposition:** Design first because it needs a versioned import contract

`ReferenceImporter.kt:20-59` treats any airport row as complete, inserts many batches without
one transaction, and imports airlines last. Process death after the first batch permanently
skips the rest. Updated assets are never imported on existing installs. Import both datasets
transactionally and commit a source/version hash only after success, or ship a prebuilt
read-only database.

### SOL-C20: Expired provider data remains visible while offline

**Severity:** Medium  
**Confidence:** Confirmed  
**Disposition:** Design first

Pruning only runs inside a network-constrained worker (`RefreshWorker.kt:35-38`). Queries do
not filter `expiresAt`. Offline devices can retain and display expired rows indefinitely. Prune
at startup without a network constraint, index expiry fields, and exclude expired rows at read
time.

### SOL-C21: Great-circle edge cases produce NaN or route gaps

**Severity:** High  
**Confidence:** Confirmed  
**Disposition:** Implement now as SOL-006

`GreatCircle.kt:20-24` does not clamp the haversine intermediate, so near-antipodal rounding
can make `sqrt(1-h)` NaN. `routeSegments()` drops the crossing edge rather than interpolating
points at `+180/-180` (`GreatCircle.kt:59-67`), leaving a visible trans-Pacific gap. Add stable
near-antipodal behavior and boundary interpolation with finite-output tests.

### SOL-C22: METAR heavy weather and cloud ceiling are decoded incorrectly

**Severity:** High  
**Confidence:** Confirmed  
**Disposition:** Implement now as SOL-007

Weather codes are interpolated into regex without escaping (`MetarDecoder.kt:49-50`), so
`+RA`/`+SN` are not matched literally. `lastOrNull()` chooses the highest/last cloud layer
(`MetarDecoder.kt:43-47`), which can hide a much lower broken/overcast ceiling. Escape tokens
and report the lowest BKN/OVC ceiling, with fixtures.

### SOL-C23: Daylight invents an unknown cruise altitude

**Severity:** High  
**Confidence:** Confirmed  
**Disposition:** Implement now as SOL-005

The KDoc says null means surface threshold, but `DaylightEngine.compute` defaults to 11,000 m
(`DaylightEngine.kt:65-90`) and the detail caller supplies no altitude. This falsely marks every
flight as cabin-corrected. Default to null and apply cabin correction only after a reference-aware
geometric altitude is supplied.

### SOL-C24: Daylight terminator geometry is mathematically unreliable

**Severity:** High visual correctness  
**Confidence:** Confirmed by equation substitution  
**Disposition:** Design first; disable overlay if not repaired

`DaylightEngine.isoLatitudeDeg()` folds one root without corresponding longitude changes and
cannot represent two-root/polar cases (`DaylightEngine.kt:163-183`). The resulting global night
polygon can shade the wrong hemisphere. Implement a spherical small circle around the anti-solar
point with correct winding and antimeridian splitting, or remove the overlay until verified.

## Performance, battery, and stutter findings

### SOL-P01: Track storage and rendering grow quadratically

**Severity:** High  
**Confidence:** Confirmed algorithm; jank not device-measured  
**Disposition:** Design first

Every successful ten-second poll inserts a Room row (`FlightRepository.kt:164-178`). Every
insert invalidates a query returning the entire track, maps every row, rebuilds the full detail
state, and formats complete GeoJSON (`MapLibreRouteMap.kt:73-80`). A 12-hour flight can exceed
4,000 rows, with increasing flash writes, allocations, and map source rebuilds. Keep live fixes
in memory, reject duplicates, downsample meaningful track points, batch persistence, cap render
points, and update the map incrementally.

### SOL-P02: One position poll can fan out to twelve HTTP calls

**Severity:** High  
**Confidence:** Confirmed  
**Disposition:** Partially addressed by SOL-003; coordinator still needed

Up to four identities are attempted and each traverses three providers. There is no per-provider
rate limiter, `Retry-After`, duplicate coalescing, or negative cache. Several providers have a
one-request-per-second policy. A process-wide coordinator should own token buckets and single-flight
requests; unresolved providers should not be in the release chain at all.

### SOL-P03: Route weather overfetches thousands of values

**Severity:** Medium  
**Confidence:** Confirmed request shape  
**Disposition:** Design first

Twelve route points request 14 days of hourly arrays and multiple variables, but only one nearest
hour per point is used (`WeatherApis.kt:41-54`, `WeatherRepository.kt:53-86`). The request can
return tens of thousands of scalar values and reruns whenever `fetchedAt` changes. Request only
the needed date range/variables and cache by route plus overflight-hour bucket.

### SOL-P04: Static detail content can recompose for every live fix

**Severity:** Medium  
**Confidence:** Inferred from Compose state topology  
**Disposition:** Design after SOL-002

An eleven-flow combine creates one large `DetailUiState` (`FlightDetailViewModel.kt:118-155`),
which is read and distributed across the complete dossier. Position changes can therefore
invalidate hero, facts, timeline, ribbon, weather, airline, and map wrappers. Split live map
state from static dossier state and use immutable, narrowly scoped parameters.

### SOL-P05: Time-dependent UI is frozen between data emissions

**Severity:** High  
**Confidence:** Confirmed  
**Disposition:** Implement now as SOL-014

`Instant.now()` is evaluated only when a Flow or composition already updates. List sorting,
countdowns, progress, phase, detail countdown, and freshness do not advance on their own.
Position staleness uses the provider's frozen age rather than current time minus fix time. Add
one lifecycle-aware minute ticker for list/detail and a faster visible-map ticker only when needed.

### SOL-P06: Reference startup and periodic work are always armed

**Severity:** Low  
**Confidence:** Confirmed structure  
**Disposition:** Later optimization

Every process start initializes/enqueues WorkManager, even with no tracked flights, and the
periodic worker remains forever. Schedule on first active flight and cancel when none remain;
separate low-frequency retention maintenance from network refresh.

### SOL-P07: Canvas and solar crossing code allocate/do extra work

**Severity:** Low  
**Confidence:** Confirmed  
**Disposition:** Later optimization

Ribbon/progress drawing rebuilds gradients and paths during draw. Daylight bisection uses
`return@repeat`, which does not break (`DaylightEngine.kt:119-124`), so it performs all 24 solar
evaluations after reaching one-second precision. Use `drawWithCache`, remembered paths, and a
breakable loop.

## UX, accessibility, and visual findings

### SOL-U01: Add-flight errors are hidden before they exist

**Severity:** High  
**Confidence:** Confirmed  
**Disposition:** Implement now as SOL-015

`FlightListScreen.kt:108-115` launches async add and immediately closes the sheet. Parse errors
arrive later but are rendered only inside that closed sheet. Old errors are not reliably cleared,
batch input can partially succeed without a summary, and repeat submission remains possible.
Keep the sheet open through parsing, model loading/success/partial/failure explicitly, and present
recognized/rejected tokens.

### SOL-U02: Loading, no-key, not-found, and network errors look the same

**Severity:** High  
**Confidence:** Confirmed  
**Disposition:** Design first

The list collects `hasStatusKey` but does not render it. Detail state has no loading/error/not-found
variant. A first launch briefly looks empty; a no-key flight can remain Unknown with dots and no
explanation; failed refreshes provide no feedback. Use explicit `Loading`, `Content`, `LimitedMode`,
`NotFound`, and typed provider-error states with clear actions.

### SOL-U03: Navigation is not restorable or adaptive

**Severity:** High  
**Confidence:** Confirmed  
**Disposition:** Design first

The stack is `remember { mutableStateListOf(...) }` in `MainActivity.kt:73-80`. Rotation,
window-size changes, locale changes, and process death can return users to the list. There is no
predictive back, deep-link handling despite notification extras, transition state, or list-detail
two-pane mode. Adopt Navigation 3/Compose with saveable entries and destination-scoped ViewModels,
then provide adaptive panes on medium/expanded windows.

### SOL-U04: Status contrast fails in several themes

**Severity:** High accessibility  
**Confidence:** Confirmed from color values  
**Disposition:** Implement now as SOL-013

`StatusWord` always uses white text (`Common.kt:23-48`). Bright Cockpit and High Contrast status
colors produce roughly 1.3-2.8:1 contrast; some Daylight combinations also miss 4.5:1. Select a
tested on-color per semantic background, and do the same for generated airline monograms.

### SOL-U05: Core graphics have no semantic equivalent

**Severity:** High accessibility  
**Confidence:** Confirmed  
**Disposition:** Implement now as SOL-013

The progress bar has no `ProgressBarRangeInfo`; ribbon daylight and event markers are Canvas-only;
the map has no route/latest-fix summary; timeline certainty is primarily shape/color/strike-through.
Add progress semantics, merged route/ribbon summaries, and explicit scheduled/estimated/actual text.
Back buttons need "Navigate up" labels, settings rows should be fully toggleable, and section titles
should be headings.

### SOL-U06: Large-font, landscape, tablet, and foldable layouts are rigid

**Severity:** High UX  
**Confidence:** Confirmed source risk  
**Disposition:** Design first

Detail always uses one column, a fixed 280 dp map, fixed two-column facts, and fixed-height timeline
rows. Theme chips use hardcoded rows; list route and phase compete in one line; settings are an
uncapped wide column. Use `FlowRow`, `heightIn`, compact/adaptive stacking, a readable max width,
and hinge-aware list-detail panes. Validate at 200% font and narrow split-screen widths.

### SOL-U07: The information hierarchy is not flight-day focused enough

**Severity:** Medium  
**Confidence:** Product judgment  
**Disposition:** Visual roadmap

Airport codes dominate detail while the next action/countdown is only medium-sized. A premium
flight-day product should make "Boards in 42 min", "Gate changed to D14", or "Lands in 18 min"
the hero, with freshness immediately below. Airport codes, provider details, and secondary facts
should support that one decision.

### SOL-U08: The map lacks honest loading/offline/error states

**Severity:** High UX  
**Confidence:** Confirmed  
**Disposition:** Design first

Missing endpoints prevent map composition, yet adjacent copy can still say a planned route is
shown. Style/tile failure has no UI, and the unused schematic `RouteMap` is never used as fallback.
The interactive map competes with vertical scrolling and has no explicit full-screen action.
Use a non-interactive card preview, typed loading/offline/route-only/live states, the schematic
fallback, and an accessible full-screen map action.

### SOL-U09: Map state can become stale with unchanged list sizes

**Severity:** Medium  
**Confidence:** Confirmed  
**Disposition:** Later map refactor

Camera setup ignores a later focus because it is remembered only by endpoints. Track GeoJSON is
remembered only by `track.size` (`MapLibreRouteMap.kt:77-80`), so same-count corrections are ignored.
Night shading advances only when a position timestamp changes. Key by immutable content/version,
drive astronomy from a visible clock, and preserve explicit user camera overrides.

### SOL-U10: Key entry exposes credentials and cannot remove them

**Severity:** High security/UX  
**Confidence:** Confirmed  
**Disposition:** Implement now as SOL-008

`SettingsScreen.kt:199-219` uses an ordinary multiline visible field with no password keyboard,
reveal control, or remove action. The store supports null deletion, but ViewModel/UI do not expose
it. Mask by default, disable suggestions/autocorrect, make it single-line, provide reveal/replace/
remove actions, and expose validation/save errors.

### SOL-U11: Tracked flights cannot be managed from the UI

**Severity:** High UX  
**Confidence:** Confirmed  
**Disposition:** Roadmap after alarm/delete correctness

Archive/delete methods and strings exist but no card gesture or menu calls them. Add swipe-to-archive
with Undo and a clear overflow menu for rename/delete. Do not expose delete until alarm cancellation
and cross-database cleanup are made idempotent.

### SOL-U12: Time and timezone presentation is ambiguous

**Severity:** High travel UX  
**Confidence:** Confirmed  
**Disposition:** Design first

Visible times are fixed `HH:mm`, ignore the user's 12/24-hour setting, and often silently fall back
to device zone while appearing airport-local. Notifications also use device time. Label airport
zones, optionally show "your time", bidi-isolate codes/times, and explicitly mark UTC/approximate
fallbacks.

### SOL-U13: Weather presentation lacks provenance, age, and honest failures

**Severity:** High  
**Confidence:** Confirmed  
**Disposition:** Design first

Observation age is modeled but not displayed; raw METAR is always expanded; source credits are not
linked beside data. Route weather collapses outage, unsupported variable, malformed response, and
forecast-horizon miss into empty and labels all of them "available closer to departure". Model
typed states and show source/product/valid time/freshness next to every weather claim.

### SOL-U14: Ribbon markers and labels are not aligned

**Severity:** Medium visual  
**Confidence:** Confirmed  
**Disposition:** Visual roadmap

Weather glyphs are evenly spaced rather than positioned from sample fraction. Sunrise/sunset text
is centered in a separate row instead of aligned with marker positions, and multiple events can
collide. Use one constrained overlay keyed to route fraction, collision handling, an accessible
legend, and visible confidence/provenance.

### SOL-U15: Themes are palette swaps rather than complete visual systems

**Severity:** Medium visual  
**Confidence:** Product judgment plus incomplete roles  
**Disposition:** Visual roadmap

Cockpit and High Contrast define only part of the Material role set and share generic typography,
spacing, containers, and motion. Give Cockpit restrained EFIS typography/dividers and give High
Contrast stronger borders, larger critical values, complete role coverage, and no low-alpha
meaningful text. Daylight should remain the polished default, not be overwhelmed by dynamic color.

### SOL-U16: User-visible text is extensively hardcoded

**Severity:** Medium  
**Confidence:** Confirmed  
**Disposition:** Broad cleanup, not auto-implemented

Phase strings, units, detail labels, Settings copy, attribution, diagnostics, weather decoding, and
map text bypass resources/plurals. This breaks translation, RTL, compact-unit grammar, and locale
format preferences. Externalize copy, use plurals and Android date/time/number formatters, and avoid
uppercasing translated strings in code.

## Privacy, security, legal, build, and delivery findings

### SOL-S01: Privacy statement is false as written

**Severity:** High  
**Confidence:** Confirmed  
**Disposition:** Implement now as SOL-011

README line 15 says all data stays on device. Enabled providers receive flight identifiers/date
ranges and IP metadata; map/weather hosts receive traffic; OS Auto Backup includes the user database
and settings (`data_extraction_rules.xml:8-16`). The accurate promise is no Blipbird account,
analytics SDK, or operated backend. Disclose OS backup and each external recipient/purpose before
enablement, and publish a privacy policy before store release.

### SOL-S02: Zero-key mode is materially overstated

**Severity:** High  
**Confidence:** Confirmed  
**Disposition:** Copy correction in SOL-011; functionality is roadmap

README lines 66-69 and onboarding strings promise live map, airport info, and weather without a key.
The model has no user-entered endpoints/time/duration, so without a status snapshot those features
usually have no route inputs and automatic polling does not start. Either implement a real manual
itinerary mode or narrow the claim to bundled identity/themes plus best-effort active-aircraft lookup.

### SOL-S03: Pending-permission ADS-B providers are enabled in release behavior

**Severity:** Blocker for distribution  
**Confidence:** Confirmed against project policy  
**Disposition:** Implement now as SOL-010

`PositionProvider.kt:20-24` always includes airplanes.live and adsb.fi, while `PLAN.md` says both
remain disabled pending permission. BYO/no-key reachability is not distribution permission. Compile
or runtime-allowlist only approved providers and archive the decision evidence before enabling more.

### SOL-S04: The review workflow executes mutable code with a secret

**Severity:** Blocker security  
**Confidence:** Confirmed  
**Disposition:** Requires owner trust decision

`.github/workflows/zai-code-review.yml` runs `L-K-M/zai-code-review@main` in
`pull_request_target` with `ZAI_API_KEY` and write permissions. A force-push or compromise can steal
the secret or act on the repository, and arbitrary fork PRs can consume paid quota. Pin a reviewed
full commit SHA, minimize permissions, and gate secret-backed review to trusted actors/labels or a
protected environment.

### SOL-S05: CI is red and there is no current release

**Severity:** Blocker  
**Confidence:** Confirmed  
**Disposition:** SOL-001 fixes the current lint errors; SOL-011 corrects release copy

The current CI runs compile/tests/assembly successfully and then fails on four lint errors. There
are no tags or Releases, but README advertises v0.1.0 and a latest-release download. Keep release
claims tied to an actual published/tagged artifact.

### SOL-S06: Release workflow can publish an unusable unsigned APK

**Severity:** High  
**Confidence:** Confirmed  
**Disposition:** Implement now as SOL-012

`.github/workflows/release.yml` treats absent signing secrets as success and attaches an unsigned
APK. Normal Android installation rejects it. The signed path does not run `apksigner verify` or
publish hash/certificate identity. Fail the release or create a draft/source-only release when
signing is unavailable; verify signed artifacts and publish SHA-256 plus signer fingerprint.

### SOL-S07: Supply-chain and reproducibility controls are incomplete

**Severity:** High  
**Confidence:** Confirmed  
**Disposition:** Separate hardening roadmap

The Gradle wrapper lacks `distributionSha256Sum`; dependency verification/locking is absent; Actions
mostly use mutable tags; reference generation fetches mutable latest/master URLs and records hashes
after download rather than enforcing expected hashes. Pin immutable inputs, enable Gradle dependency
verification, record generated output hashes/tool versions, and verify reproducibility in CI.

### SOL-S08: Third-party notices are incomplete

**Severity:** High distribution risk  
**Confidence:** High; legal conclusion depends on jurisdiction  
**Disposition:** Legal/release work

The app/repository distributes MIT, CC-BY, ODbL, Apache, and adapted formula/data material but has
only the project license and a short static attribution paragraph. Packaging excludes common license
resources. Add a complete `THIRD_PARTY_NOTICES`/license bundle with copyright, license text, source,
revision, and transformation details, and make required attributions actionable in-app.

### SOL-S09: Raw crash logs are persisted in release storage

**Severity:** Medium privacy/security  
**Confidence:** Confirmed  
**Disposition:** Revisit diagnostics design

`BlipbirdApp.kt:36-50` writes an unredacted complete throwable to plaintext. Settings reads the full
file on the main thread and shows only its first 4,000 characters. Stack messages can include URLs,
provider errors, identifiers, or implementation details; minified release logs are not useful without
mapping files. Restrict raw diagnostics to debug builds or redact/bound them, provide clear delete/
share consent, and retain R8 mapping privately for release support.

### SOL-S10: KeyStore corruption handling is too broad

**Severity:** Medium  
**Confidence:** Risk  
**Disposition:** Design first

The custom serializer catches broad failures and the corruption handler resets all keys. Transient
KeyStore/provider or cancellation failures can be mistaken for permanent ciphertext corruption.
Catch only authenticated-decryption/envelope/serialization corruption, rethrow cancellation/fatal
errors, and retry transient I/O before reset.

### SOL-S11: Ops destructive migration can reset spend and dedup state

**Severity:** High future-release risk  
**Confidence:** Confirmed architecture  
**Disposition:** Address with first ops migration

The ops database contains quota and emitted-event state but permits destructive migration. A future
schema change can silently reset spending and dedup while tracked flights/keys survive. Provide
explicit migrations or separate non-destructive operational policy state from rebuildable snapshots.

## Feature coverage and missing product capabilities

| Capability | Status | Key gap |
|---|---|---|
| Flight entry/parser | Partial | No carrier validation, canonical dedup, reusable alias resolution, or occurrence/leg chooser. |
| Departure-board list | Partial | No swipe management, baggage summary, clock updates, explicit limited/error state, or adaptive layout. |
| Detail dossier | Partial | No true snapshot history/superseded estimates, provenance, robust local-time labels, or loading/error model. |
| Live map | Partial | Real MapLibre works, but lifecycle, identity confidence, offline fallback, full-screen flow, and bounded track are incomplete. |
| Daylight ribbon | Partial/misleading | Real solar samples exist, but altitude is invented and terminator math is unverified. |
| Airport METAR | Partial | Basic decoder works; freshness, provenance, TAF, hazards, and reliable degradation are missing. |
| En-route weather | Prototype/misleading | Surface products and fixed 250 hPa wind are not altitude-aware cruise weather. |
| Notifications | Partial | Snapshot diff exists; delivery accounting, initial baseline, reminder fallback, profiles, gate/belt events, and alarm lifecycle are incomplete. |
| Refresh/quota | Partial | Cadence and ledger exist; no single-flight coordinator, atomic reservation, backoff, delayed sticky anchor, or provider reset cycle. |
| Zero-key itinerary | Missing | No user-entered route/time/duration/altitude, so most promised limited-mode features have no inputs. |
| Flight management/history | Stub | DAO methods exist; no UI, reusable aliases, safe delete, export/import, or local flight log. |
| Widget/Live Updates | Missing/roadmap | No Glance widget or promoted live update. |
| Privacy controls | Missing | No in-app privacy screen, recipient disclosure flow, export, erase-all, or retention controls. |
| Adaptive/accessibility | Missing | No two-pane navigation, screenshot/UI test matrix, complete semantics, RTL pass, or large-font pass. |

## Recommended product sequence

### Phase 0: Stop unsafe behavior

1. Merge SOL-001 through SOL-004 and SOL-009 through SOL-012.
2. Keep pending-permission providers disabled.
3. Remove authoritative alerts driven by ambiguous provider fields until fixtures prove semantics.
4. Make CI green, then add API-26/API-31/API-33 instrumentation coverage.

### Phase 1: Make the core trustworthy

1. Introduce a canonical physical-flight instance: operating designator, endpoints, original
   scheduled OUT, provider instance IDs, explicit occurrence choice, and field provenance.
2. Implement one provider coordinator with rate limiting, single-flight requests, typed failures,
   `Retry-After`, atomic spend reservation, and provider-specific reset windows.
3. Model OUT/OFF/ON/IN separately and reduce snapshots monotonically.
4. Keep live positions in a lifecycle-bound in-memory session; downsample durable tracks.
5. Add contract fixtures and Room/repository integration tests before expanding provider behavior.

### Phase 2: Make limited mode genuinely useful

1. Let users enter/edit origin, destination, departure-local time, duration, and optional altitude
   with reference type.
2. Unlock countdown, route, truthful surface/cabin daylight, endpoint METAR, and limited local
   reminders without a commercial status source.
3. Add a clear confidence/freshness strip: `Status 3m | position 8s | weather 20m`.

### Phase 3: Make it feel premium

1. Promote the next event to the hero and demote provider/secondary facts.
2. Add adaptive list-detail panes and a focused full-screen map.
3. Turn the route, map, ribbon, and timeline into one consistent visual language: dashed planned
   route, solid flown track, aligned light/weather events, and explicit uncertainty.
4. Give each theme a complete identity while preserving accessibility and reduced motion.
5. Add Compose semantics, screenshot matrices, baseline profiles, and macrobenchmarks.

## High-value aesthetic improvements

1. **Next-event hero:** A large action line such as `Boards in 42 min`, `Gate changed to D14`, or
   `Lands in 18 min`, with calm secondary route codes and freshness below.
2. **Live trust strip:** Three independently aging pills for status, position, and weather. Animate
   only when a genuinely new datum arrives.
3. **Route story:** Use the same progress fractions and colors across list bar, ribbon, and map.
   Planned route is dashed; flown track is solid; projected position is a translucent ghost.
4. **Change choreography:** Briefly highlight only changed characters in a gate/time using a subtle
   split-flap transition. Do not reanimate whole cards.
5. **Premium cards without glassmorphism:** Prefer generous whitespace, crisp type, low-noise
   separators, and restrained elevation. The aviation data is already visually rich.
6. **Adaptive cockpit:** On unfolded/tablet layouts, keep the flight board at left and pin the
   hero/map at right while facts/timeline scroll independently.
7. **Designed source sheet:** Replace the attribution wall with grouped, linked data-source cards
   showing purpose, last use, key status, privacy policy, cost/reset, and license.
8. **Theme-specific craft:** Daylight uses soft sky neutrals; Cockpit uses EFIS-like dividers and
   numerals sparingly; High Contrast has strong outlines and larger critical values rather than
   neon colors with white text.

## Novel and delightful ideas

1. **Expected silence zones:** Shade likely oceanic/sparse ADS-B coverage ahead of time so a vanished
   plane is reassuringly explained instead of looking broken.
2. **Window observatory:** Add projected sunrise/sunset side, Moon position/phase, and a gentle
   `look out now` prompt only above a confidence threshold.
3. **Connection-margin tape:** Pair tracked legs and show how inbound delay, terminal changes, and a
   user-set transfer buffer consume connection margin, entirely on-device.
4. **Four aviation clocks:** A compact OUT/OFF/ON/IN strip exposing taxi-out, airborne, taxi-in, and
   block time; later compare with the user's local history where retention permits.
5. **Runway wind rose:** Combine public runway headings with METAR wind for a clearly non-navigational
   likely-runway/window-view hint.
6. **Local itinerary inbox:** An Android share target for pasted itinerary text, `.ics`, boarding
   passes, or screenshots, parsed locally and confirmed before any provider call.
7. **Honest radar ping:** Pulse one ring only when a genuinely new ADS-B fix arrives. Never loop fake
   motion while data is stale.
8. **Runway refresh:** On pull-to-refresh, the bird rolls down a tiny runway and lifts off only on
   success; use a static substitute under reduced motion.
9. **Airframe reunion:** With explicit opt-in and rights-cleared retention, note `You last flew this
   aircraft in 2024` and build a private airframe/family log.
10. **Calm arrival flourish:** A tiny contrail curl or feather drift after an on-time arrival, with
    optional haptic and no effect in reduced-motion/High Contrast modes.

## Existing strengths worth preserving

- The product is focused on personal flight-day decisions rather than generic radar engagement.
- There are no ads, accounts, analytics SDKs, or Blipbird-operated backend in the current source.
- Provider keys use AES-GCM with fresh IVs and an Android Keystore key, and are excluded from backup.
- Backup rules intentionally separate user-authored intent from provider-derived operations data.
- Runtime endpoints are HTTPS and no HTTP body/header logger or embedded provider secret was found.
- Provider interfaces and pure decision cores are the right architecture direction.
- WorkManager uses one unique network-constrained worker rather than one periodic worker per flight.
- PendingIntents are immutable and custom receivers are not exported.
- The parser, phase/planner, cadence, METAR, geometry, and daylight cores already have useful JVM
  tests, even though integration/platform coverage is still missing.
- The icon and Blipbird personality are distinctive and suitable for a premium identity.
- The map/ribbon concept is genuinely differentiating if uncertainty and provenance are made honest.

## Test gaps to close

1. Provider contract fixtures for HTTP 204, auth, rate limit, AeroDataBox estimated/actual runway
   fields, AeroAPI cancellation semantics/date windows/distance units, and malformed/missing ADS-B
   fields.
2. Repository/Room integration for concurrent refresh, delete races, status-attempt backoff, atomic
   quota reservation, expiry visibility, migration preservation, and reference import interruption.
3. Lifecycle tests proving detail polling stops on `STOPPED`, disposal, and backgrounding.
4. Alarm/notification tests on API 26, 30, 31, and 33 for toggles, permission revocation, archive,
   cancellation, schedule changes, reboot, WorkManager fallback, and outbox retry.
5. Compose tests for add failures, no-key/error states, navigation restoration, TalkBack semantics,
   200% font scale, RTL, IME/landscape sheets, and adaptive panes.
6. Screenshot tests for every theme in light/dark, compact/expanded, disruption states, missing data,
   and long airport/airline names.
7. Baseline profile and macrobenchmarks for cold start, large tracked lists, long live tracks, detail
   scroll, and map/ribbon updates.
8. Minified release smoke tests for Hilt, Room, Retrofit serialization, MapLibre, notifications, and
   R8 mapping artifact retention.

## Final release gate

Do not publish a store or installable release until all of the following are true:

- CI tests, lint, debug assembly, and minified release smoke tests pass.
- API 26-30 Settings/reminder behavior is verified.
- Off-screen/background position traffic is proven absent.
- Wrong-aircraft candidates are rejected by identity and route/time confidence.
- Failed/no-result lookups have bounded, persisted retry behavior.
- Authoritative cancellation/departure/landing alerts are backed by contract-tested fields.
- Pending provider permissions and retention rights are encoded as disabled release behavior.
- Privacy, backup, data-recipient, cost, attribution, and release claims match reality.
- The signed artifact is verified and its checksum/signer identity are published.
