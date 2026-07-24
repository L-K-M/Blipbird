# Multi-flight itineraries and connection guidance

> **Status:** Proposed
>
> **Research date:** 2026-07-23
>
> **Code baseline:** `main` at `9264059` (PR #80, the in-app reduce-motion
> toggle, merged at `7c95802` after the research date; noted in section 7.15)
>
> **Independent review:** re-verified 2026-07-24; see Appendix A
>
> **Related roadmap items:** `REVIEW.md` F10 (layover awareness), F11
> (trip grouping), F15 (airport information), F16 (offline projected mode),
> and `PLAN.md` v2 multi-flight grouping.

## Executive summary

Blipbird should add an **itinerary** as a user-owned, ordered collection of the
tracked flights it already understands. A neutral **transition** joins every
pair of adjacent legs. The user identifies that transition as a direct
connection, destination stay, surface transfer, or unknown; Blipbird must not
treat every later flight as a transfer. This preserves the current per-flight
status, map, weather, notification, and refresh machinery rather than replacing
it with a second flight model.

The itinerary program has two honest release gates. The first public **local
itinerary release** should provide this well:

1. Add, group, name, reorder, archive, and delete multiple flight legs as one
   itinerary.

The first public **live-connection release** must additionally provide these
capabilities end to end; it must not claim them merely because disabled code or
empty UI states exist:

1. For a confirmed direct connection, show the **latest scheduled connection
   window** and **latest calculated connection window**, from gate arrival
   (`IN`) to gate departure (`OUT`). Explain whether each endpoint is scheduled,
   estimated, or actual and show the age of both snapshots.
2. Report where the inbound flight arrives and where the next flight is reported
   to depart: airport, terminal, and gate when available. Pair those facts with a
   verified link to the airport's own connections guide, terminal map,
   accessibility page, or wait-time page **when the reviewed registry covers
   that airport**.

The local release may ship while provider rights are unresolved, but its naming,
store copy, screenshots, and acceptance claims must describe an itinerary
organizer rather than live connection guidance. The live release requires at
least one rights-approved, gate-capable status path and declared official-link
coverage.

The first release should **not** claim that a connection is safe, feasible,
protected, or guaranteed. It should not invent walking times, infer through-
checked baggage, decide visa requirements, or assume that flights booked together
form one ticket or a protected connection. Those answers require data that a
flight number and status snapshot do not contain.

There is no verified open global minimum-connection-time (MCT) dataset that can
simply be bundled in an open-source APK. IATA SSIM defines how MCT information
is exchanged, but the manual is not the current rules dataset. OAG offers a
commercial dataset with more than 157,000 standards and exceptions, updated up
to daily, through files or Snowflake. Indoor routing is similarly venue-data-
dependent: MapLibre is a renderer, OpenStreetMap indoor coverage is uneven, the
standard `tile.openstreetmap.org` service prohibits offline prefetch, and
commercial products such as Mappedin still require production venue access and
contractual mobile/content rights.

The honest product is therefore a staged one:

| Stage | User value | Data basis |
|---|---|---|
| 1. Local itinerary | Grouping, order, designators, dates, transition type, offline shell | User-owned Room data |
| 2. Live connection window | Latest scheduled/calculated break, delay erosion, arrival and onward terminal/gate | Currently integrated BYO-key provider candidates, after rights and occurrence-identity gates close |
| 3. Official guidance | Connections guide, terminal map, accessibility, security/wait links | Small reviewed registry of official URLs |
| 4. Licensed assessment | Filed MCT comparison with exact matched rule and caveat | Contracted MCT provider and approved delivery architecture |
| 5. Venue routing | Floor-aware, accessible gate-to-gate route and duration | Airport/venue geometry, routing, positioning, and mobile rights |

The recommended initial UI is a journey timeline, not a stack of full-size
flight cards. Completed legs collapse, the current leg and next confirmed
transfer get visual priority, while destination stays remain quiet breaks in the
timeline. Each direct-connection card answers four questions in order:

- What transition did the user intend?
- How much time is between the latest reported gate times?
- What changed, what is each time's basis, and how old are the inputs?
- Where are the inbound and onward flights currently reported?

## Contents

1. [Product decision](#1-product-decision)
2. [Goals and non-goals](#2-goals-and-non-goals)
3. [Current Blipbird baseline](#3-current-blipbird-baseline)
4. [Terminology and invariants](#4-terminology-and-invariants)
5. [Research findings](#5-research-findings)
6. [Product scope and capability tiers](#6-product-scope-and-capability-tiers)
7. [Information architecture and UX](#7-information-architecture-and-ux)
8. [Transition and connection semantics](#8-transition-and-connection-semantics)
9. [Wayfinding and transfer guidance](#9-wayfinding-and-transfer-guidance)
10. [Data model](#10-data-model)
11. [Architecture and state flow](#11-architecture-and-state-flow)
12. [Refresh, quota, and offline behavior](#12-refresh-quota-and-offline-behavior)
13. [Notifications and Android surfaces](#13-notifications-and-android-surfaces)
14. [Privacy, security, licensing, and retention](#14-privacy-security-licensing-and-retention)
15. [Implementation plan](#15-implementation-plan)
16. [Testing strategy](#16-testing-strategy)
17. [Acceptance criteria](#17-acceptance-criteria)
18. [Risks and mitigations](#18-risks-and-mitigations)
19. [Decisions and review triggers](#19-decisions-and-review-triggers)
20. [Research source register](#20-research-source-register)
21. [Appendix A: independent verification log](#appendix-a-independent-verification-log-2026-07-24)

## 1. Product decision

### 1.1 Recommended feature definition

An itinerary is a named or unnamed ordered set of at least two tracked flight
occurrences. The user, not an inference algorithm, owns membership and order.
Blipbird may suggest a grouping when adjacent flights appear compatible, but it
must require confirmation.

Every pair of adjacent legs creates a neutral transition edge. The edge can
combine:

- Two existing tracked-flight records.
- Their latest eligible occurrence-bound normalized status snapshots when live
  capability gates are satisfied.
- User-confirmed transition intent and, for a direct connection, optional
  booking and baggage facts.
- Airport identity and official-guide metadata.
- An optional, separately licensed MCT result in a later release.

The transition is where an ordinary stay, layover duration, or surface-transfer
context lives. Connection timing/guidance is enabled only for a confirmed direct
connection. Flight status, phase, map, weather, and daylight stay leg-scoped.

### 1.2 Core product promise

> Keep every leg of a journey together, show how the time between flights is
> changing, and point the traveler toward the next verified source of action.

This wording is intentionally narrower than "make your connection." Blipbird
does not control airline operations, queues, border decisions, baggage, airport
closures, or the traveler's pace.

### 1.3 Why grouping comes before risk scoring

The current roadmap lists layover awareness before trip grouping. In the data
model, grouping is the prerequisite: transition state needs a stable ordered
pair, user intent/facts, lifecycle, notification identity, and deep-link target.
Inferring pairs ad hoc from whichever flights happen to be active would create
unstable alerts and surprising regrouping as estimates change.

Implement the durable itinerary aggregate first, then derive connection windows
only from direct-connection transitions. Automatic suggestions remain a
convenience layer, never the source of truth.

### 1.4 Why the first label is "connection window," not "connection risk"

A connection window is arithmetic. A useful risk assessment is contextual.

Flighty's July 2026 description of its Connection Assistant says it uses MCT as
the baseline input for classifying relaxed/normal/tight/at-risk, then
personalizes by airport, route, nationality, baggage recheck, seat location,
passport control, security, terminal transfer, **historical-gate prediction**,
and live conditions. Critically, Flighty displays the MCT figure directly
alongside its own connection classification — it does not substitute MCT with
proprietary logic or hide the filed minimum. Blipbird currently has only a
subset of those inputs. A generic red/amber/green score would look more certain
than its evidence.

The first version may truthfully say:

- `Latest scheduled: 1 h 35 min`
- `Latest calculated: 1 h 08 min`
- `27 min shorter than latest scheduled`
- `Estimated gate arrival, inbound fetched 4 min ago; scheduled gate departure,
  onward fetched 7 min ago`

It should not say:

- `Safe connection`
- `You will make it`
- `Protected connection`
- `Bags transfer automatically`
- `12-minute walk` without a current venue-routing source
- `No transit visa needed`

## 2. Goals and non-goals

### 2.1 Goals

- Let a user create an itinerary with different dates for each leg.
- Let a user group already tracked flights without re-fetching or duplicating
  them.
- Preserve explicit leg order even when schedules or estimates move.
- Display a resolved itinerary route, planned date span, current leg, next
  transition, and all legs when the required status data exists.
- Distinguish direct connections, destination stays, surface transfers, and
  unresolved transitions.
- Display latest scheduled and calculated gate-to-gate windows only for
  confirmed direct connections.
- Display connection-window change without implying feasibility.
- Identify same-airport continuity, different-airport transfers, long
  stopovers, unknown continuity, and clock-overlap errors.
- Show arrival and onward airport, terminal, and gate with independent missing
  and stale states.
- Ask contextually for the minimum user facts needed to avoid dangerous
  assumptions: whether flights were booked together and the checked-bag plan.
- Provide official airport and, in a later separately keyed registry, airline
  links when a reviewed source exists.
- Work as a useful itinerary organizer with no Blipbird account or backend.
- Keep user-authored itinerary data eligible for Android cloud backup and device
  transfer under the existing include-only rules.
- Reuse the existing single worker, per-flight refresh policy, flight detail,
  map, weather, daylight, and notification systems.
- Remain understandable offline and honest about stale data.
- Support TalkBack, keyboard/switch access, 200% font scale, reduced motion,
  high contrast, and large screens from the first release.

### 2.2 Non-goals for the first release

- Booking, ticketing, check-in, rebooking, or boarding-pass storage.
- PNR, ticket number, passport, nationality, date of birth, or visa collection.
- Email inbox access or a Blipbird email-forwarding service.
- Inferring whether flights share a ticket from airline, alliance, codeshare, or
  timing.
- A universal safe/tight/at-risk score.
- A bundled global MCT database without explicit rights.
- A guarantee that a carrier will protect or rebook a connection.
- A baggage tracking or through-check guarantee.
- A travel-document decision engine.
- A global security, immigration, or customs wait-time feed.
- Indoor positioning or turn-by-turn airport routing without venue data.
- A made-up terminal walking-time table.
- Treating GTFS, AIDX, NDC, ONE Order, IMDF, or IndoorGML as if a standard were
  itself a live data source.
- Merging all legs into one continuous flight map or daylight ribbon.
- Automatically changing itinerary order when estimated times change.

### 2.3 Later opportunities

- On-device import from Android share intents or calendar events, always with a
  review screen and no source-document retention.
- A rights-cleared MCT provider.
- Airport-by-airport official wait-time integrations.
- Airport-by-airport indoor routing pilots.
- Ground-transfer legs using publisher-licensed GTFS feeds.
- User-defined personal connection targets, clearly distinct from MCT.
- Whole-itinerary calendar/share/export surfaces.
- A next-journey Glance widget and itinerary-level Live Update.

## 3. Current Blipbird baseline

### 3.1 What already works

Blipbird is already capable of tracking many flights at once. It lacks
relationships between them, not multi-flight refresh capacity.

- `TrackedFlightDao.observeActive()` returns every non-archived flight.
- `FlightListViewModel` creates one row flow per active flight and sorts rows by
  next event.
- `RefreshWorker` iterates all active flights under one unique WorkManager job.
- `ReminderScheduler` and `NotificationEmitter` use stable per-flight identity.
- `FlightDetailViewModel`, route map, weather, ribbon, and ADS-B track are all
  correctly scoped to one flight leg.
- Archived flights have a dedicated screen with restore and permanent delete.
- Flight and archive indexes use a shared `LazyVerticalGrid` with
  `GridCells.Adaptive(380.dp)`; existing flight detail already has a wide-screen
  two-pane treatment.
- The hand-rolled navigation stack now supports saved restoration, animated
  transitions, predictive back, and route-scoped ViewModel stores.

### 3.2 Current constraints that matter

| Area | Current behavior | Itinerary consequence |
|---|---|---|
| User database | Version 1 contains only `tracked_flight` | New user-owned aggregate and migration are required |
| Batch add | All tokens share one optional date; alias only applies to a one-token batch | It cannot represent an overnight or multi-day itinerary |
| Batch failure | Valid tokens can be inserted while invalid tokens fail | Itinerary creation needs all-or-nothing user-DB writes |
| Flight identity | `InstanceSelector.select()` picks one candidate by operational priority; the repository may pin its departure-local date without recording whether that date was user-authored | Ambiguous same-day/multi-leg candidates need explicit identity selection and date provenance |
| Status model | The normalized model separates gate `OUT/IN` from runway `OFF/ON`, but current provider adapters populate them differently | Gate-to-gate windows are possible only when the selected adapter supplies the required values |
| Reference data | Airports and airlines only | No MCT, terminal graph, metro-airport relation, or guide links exist |
| List sort | Active flights sorted ascending by next event; finished flights sunk below, sorted descending by next event | It would destroy user-authored leg order inside a group |
| Adaptive list | Flight/archive cards already flow through an adaptive 380 dp grid | Itinerary index cards should join that grid rather than replacing it with a narrower single-column shell |
| Navigation save format | Screens encode as untagged `Long`; negative values are sentinels | A second positive-ID route needs tagged serialization |
| ViewModel stores | Keyed by `Screen` equality | Navigation must prevent duplicate equal routes or adopt serialized unique entry IDs |
| Notifications | Events, IDs, PendingIntents, and deep links use one `flightId` | Transition events need their own identity, persisted state, and destination |
| Operational retention | Snapshots and fixes expire around arrival plus three days | The itinerary survives; historical live connection evidence does not unless separately summarized and rights-cleared |
| Backup | User DB is included; ops DB and credentials are excluded | Itinerary intent belongs in the user DB |

### 3.3 Relevant code extension points

- `app/src/main/java/ch/lkmc/blipbird/core/database/Entities.kt`
- `app/src/main/java/ch/lkmc/blipbird/core/database/Daos.kt`
- `app/src/main/java/ch/lkmc/blipbird/core/database/Databases.kt`
- `app/src/main/java/ch/lkmc/blipbird/core/data/FlightRepository.kt`
- `app/src/main/java/ch/lkmc/blipbird/core/model/Models.kt`
- `app/src/main/java/ch/lkmc/blipbird/domain/InstanceSelector.kt`
- `app/src/main/java/ch/lkmc/blipbird/domain/NotificationPlanner.kt`
- `app/src/main/java/ch/lkmc/blipbird/ui/list/FlightListViewModel.kt`
- `app/src/main/java/ch/lkmc/blipbird/ui/list/FlightListScreen.kt`
- `app/src/main/java/ch/lkmc/blipbird/platform/RefreshWorker.kt`
- `app/src/main/java/ch/lkmc/blipbird/platform/NotificationEmitter.kt`
- `app/src/main/java/ch/lkmc/blipbird/MainActivity.kt`
- `app/src/main/java/ch/lkmc/blipbird/NavEntryScoping.kt`

### 3.4 Architectural conclusion

Do not make an itinerary a larger `TrackedFlightEntity`, and do not duplicate
snapshots at itinerary level. Add a composition layer over existing tracked
flights. This keeps operational identity, quota, refresh cadence, live position,
weather, and legal retention leg-scoped.

## 4. Terminology and invariants

### 4.1 Canonical terms

| Term | Definition |
|---|---|
| Itinerary | User-owned, ordered collection of tracked flight occurrences |
| Leg | One membership that points to one existing `TrackedFlightEntity` |
| Transition | Neutral directed edge from one leg to the immediately following leg |
| Direct connection | User-confirmed transfer between adjacent flights at the same airport |
| Destination stay | Break where the traveler is at a destination before a later flight; not a transfer |
| Connection window | Time from inbound gate arrival (`IN`) to onward gate departure (`OUT`) |
| Layover | Direct connection with a relatively short break; display term, not a legal rule |
| Surface transfer | User-confirmed transfer between different airports; travel time is not inferred |
| Booking arrangement | User assertion: booked together, booked separately, or not sure; not proof of one ticket/protection |
| Baggage plan | User assertion: no checked bag, through-checked, collect/recheck, or unknown |
| MCT | Filed minimum connecting time matched to applicable airport/carrier/terminal rules |
| Guidance | Facts and verified links; not turn-by-turn navigation unless a routing source exists |

### 4.2 Persistence invariants

- A tracked flight belongs to at most one itinerary in the first version.
- A persisted itinerary contains at least two legs. An unsaved one-leg draft
  remains editor state and is not shown on Home.
- Leg order is explicit and independent of flight timestamps.
- Ordinals are unique within an itinerary.
- Exactly one transition exists between each adjacent pair; a valid itinerary
  with `n` legs has `n - 1` transitions and no branches.
- Transition intent is user-owned. Timing/airport heuristics may suggest but
  never silently set direct connection, stay, or surface transfer.
- `DIRECT_CONNECTION` is compatible only with a confirmed same-airport pair;
  `SURFACE_TRANSFER` is compatible only with a confirmed different-airport pair.
  Conflicting intent is an unresolved reclassification state, not a window.
- Only compatible direct-connection intent with two non-expired confirmed
  occurrence bindings, occurrence-bound eligible snapshots, and a matching
  operational same-airport pair receives connection windows, procedural prompts,
  MCT comparisons, or timing notifications. A continuity-change event has the
  narrower exception defined in section 13: unchanged confirmed bindings may
  report that their current endpoints stopped matching.
- Reordering, insertion, or removal rebuilds affected transitions.
- User-entered transition facts attach to a specific ordered pair and are not
  silently carried to a new pair after reorder.
- Flight status remains the operational source of truth for each leg.
- Itinerary name, order, transition intent, and user answers are portable user
  data eligible for the existing Android backup rules.
- Provider snapshots, provider instance IDs, MCT responses, and notification
  ledgers are operational data and remain excluded from backup unless a
  documented contract explicitly changes that boundary.

### 4.3 Product invariants

- Blipbird never silently groups flights.
- Blipbird never silently reorders an itinerary.
- Blipbird never equates itinerary adjacency with a connection.
- Blipbird never equates a same-airport match with a transfer or a feasible
  connection.
- Blipbird never equates a carrier/alliance match or one checkout flow with one
  ticket or airline protection.
- Blipbird never labels baggage as transferred unless the user explicitly says
  it is through-checked or an authorized booking source says so.
- Blipbird never substitutes runway times for gate times without changing the
  label.
- Blipbird never hides time basis, source, or retrieval recency behind a color
  score.
- A map is never the only representation of transfer guidance.

## 5. Research findings

All external facts in this section were checked on 2026-07-23. Provider claims
are their own published claims and are not independent coverage or SLA audits.

### 5.1 What can be derived now

The existing normalized status model can support the following when its selected
provider adapter actually populates the required fields:

- Scheduled gate-to-gate connection duration.
- Latest calculated gate-to-gate duration using actual, then estimated, then
  scheduled milestones.
- Difference between scheduled and current duration.
- Inbound arrival terminal/gate and onward departure terminal/gate.
- Same-airport continuity when IATA/ICAO identities normalize to one airport.
- Different-airport discontinuity.
- Cancellation, diversion, terminal change, gate change, and stale/missing data.

The status snapshots cannot establish:

- One booking, one ticket, fare relationship, or passenger protection.
- Through-checked baggage.
- The traveler's passport/nationality or document requirements.
- Whether immigration, customs, security, or baggage reclaim is required.
- Gate closure or boarding cutoff unless a provider reports that separate
  milestone.
- Walking/transit route or duration between terminals/gates.
- An applicable MCT rule.
- Queue lengths, closures, mobility pace, or assistance arrangements.

### 5.2 Minimum connection times

IATA's Standard Schedules Information Manual describes schedule exchange and
contains a chapter for the presentation, application, and transfer of MCT data.
It is a commercial annual standard, not a current global MCT feed and not a
license to redistribute current rules.

OAG advertises a commercial MCT dataset containing more than 157,000 standards
and airline-specific exceptions, configurable by airport, carrier, connection
type, terminal, equipment, codeshare, flight number, effective date, partner
restrictions, and exclusions. Delivery is through files or Snowflake, with
updates up to daily. No public APK redistribution right or public price was
found.

Conclusions:

- Remove the `PLAN.md` assumption that Blipbird can use "bundled minimum-connect
  times" without a provider decision.
- Treat MCT as a provider interface with no enabled implementation at first.
- Even with MCT, compare the scheduled itinerary to the filed minimum first,
  then show a separately labeled live margin. Never call either result `Safe`.
  Applicability is provider/rule-specific: an ordinary filed MCT must not be
  applied to separately booked/self-transfer flights unless the matched source
  explicitly says that rule applies to that arrangement.
- Preserve every match dimension returned by the provider and permitted for
  retention, plus effective dates, source, and retrieval time.

### 5.3 Booking relationships and baggage

Google documents that virtual interlines or self-transfer fares generally
require separate check-in and baggage collection/recheck and do not provide the
same inter-airline communication. Heathrow separately states that two separately
booked flights are treated as arrival plus departure and may require immigration,
baggage collection, check-in, bag drop, and security; its published connecting-
flight MCT does not apply to that separate-booking path.

IATA Resolution 753 defines baggage custody tracking responsibilities at key
handover points. It is not a public passenger API and does not answer whether a
specific traveler's baggage is through-checked.

Conclusions:

- Ask whether the flights were booked together and ask about baggage only after
  the user identifies a transition as a direct connection.
- Default both answers to `Unknown`.
- Phrase them as user assertions: `Booked together, according to you`, followed
  by `This does not establish one ticket, airline protection, or bag transfer.`
- Do not ask for a PNR or ticket number in this feature.
- Open the existing flight dossier when unknown/disrupted. Add an operating-
  airline status/contact link only after a separate reviewed airline registry or
  rights-cleared metadata source exists; it is not in the initial airport-link
  scope.

### 5.4 Travel documents, immigration, customs, and security

IATA Timatic is a commercial travel-document rules service based on traveler and
itinerary details. IATA directs individual travelers to the IATA Travel Centre
for personalized passport, visa, and health requirements. Government guidance,
such as the UK transit-visa service, demonstrates why nationality, destination,
route, documents, and border-control path matter.

Airport procedures are local and mutable. Heathrow says all connecting
passengers pass security there, while immigration and baggage requirements vary
by route and booking. That rule cannot be generalized to another airport.

Conclusions:

- Do not build a universal procedural rules engine from a handful of airport
  pages.
- Do not store passport or nationality for the MVP.
- Link to IATA Travel Centre and government/airport sources.
- Curate links, not copied procedural paragraphs that silently become stale.

### 5.5 Airport maps and indoor wayfinding

MapLibre renders maps; it does not supply airport floor plans, indoor positions,
or routes. OpenStreetMap supports indoor feature tagging under ODbL, but coverage
and routability vary. The public `tile.openstreetmap.org` service is best effort,
has no SLA, and explicitly prohibits bulk download, prefetch, and offline packs.

IMDF is an indoor exchange format. IndoorGML is an indoor spatial/navigation data
model and XML schema that can represent supplied geometry and topology. Neither
provides an airport venue dataset, positioning, closures, or routing service.
GTFS Pathways can describe circulation and
accessibility in a published transit station feed. It does not supply a global
airport map and should not be stretched into a flight standard.

Mappedin and HERE offer commercial indoor mapping capabilities. Mappedin's
public materials document multi-floor wayfinding, live integrations, Android
SDK credentials, caching/offline MVF loading, accessible routes, airport demos,
and public Free/Pro tiers. The Free tier excludes SDK & API access and data
export — those begin at Pro ($165 per map per month) — and the self-serve
tiers target venue owners publishing their own maps, not a catalog of licensed
airport venues a third-party app can simply consume. HERE documents mobile/web
libraries and GeoJSON map access. Production access to the needed real-airport
content, a safe public-client credential model, pricing for the intended use,
and content retention/redistribution rights still require confirmation. Google
Maps can expose indoor levels for supported buildings but does
not document a universal airport gate-to-gate routing API. Mapbox offline
regions do not create missing venue data or indoor routes.

Conclusions:

- First ship terminal/gate facts and official airport map links.
- Treat indoor routing as an airport-by-airport provider capability.
- Never turn terminal labels into guessed walking minutes.
- Keep textual instructions and accessibility equivalents even if a map provider
  is added.

### 5.6 Standards are not feeds

| Standard | What it is | Relevance to Blipbird |
|---|---|---|
| IATA AIDX | XML messaging standard for operational flight-leg exchange | Useful only if an airport/airline/vendor supplies an operational feed under applicable access/use terms |
| IATA SSIM | Schedule, coordination, and MCT exchange manual | Defines semantics; does not provide current rules by itself |
| IATA NDC | Airline retailing/distribution messaging standard | Relevant to authorized shopping/order integrations, not arbitrary flight tracking |
| IATA ONE Order | Order-centric airline retailing/fulfillment model | Could expose booking relationships through an authorized order source, not anonymously |
| ACI ACRIS | Airport interoperability best practices | Useful vocabulary and integration guidance, not a public endpoint |
| ACI Data Dictionary | Standard airport terminology | Useful for naming/model review, not live data |
| AIDM | Shared airline-industry data model | Modeling reference, not a live API |
| IMDF | Indoor venue exchange format | Useful if a licensed airport dataset is supplied |
| IndoorGML | Indoor spatial/navigation topology standard | Useful if geometry is supplied |
| GTFS/Pathways | Ground-transit schedules/realtime and station pathways | Useful only for airports/transport agencies that publish feeds with usable rights |

### 5.7 Booking APIs are not an import shortcut

Duffel and Travelport are authenticated commercial booking platforms, not
arbitrary flight-number import APIs. Both offer test or self-service/trial entry
points, while production use remains governed by commercial terms and booking/
traveler responsibilities. A shared production credential in a public APK would
normally require a backend or a provider-approved mobile credential model. That
materially different privacy/security architecture must not be smuggled into
itinerary grouping.

## 6. Product scope and capability tiers

### 6.1 Tier 0: local-only itinerary

Works without a status key:

- Create and name an itinerary.
- Add ordered designators and per-leg dates.
- Group existing tracked flights.
- Reorder, remove, archive, restore, and delete.
- Record transition intent and, for a direct connection, optional booking/bag
  answers.
- Keep user-authored data eligible for Android backup/device transfer.
- Show unresolved placeholders honestly.

Without status data, Tier 0 contains only name, ordered designators, per-leg
departure-local dates, transition intent, and user answers. Airports, arrival
dates, route, and times are unavailable because the current tracking model does
not ask for them. The UI should say `Live route and time details need an approved
status source`, not show a fabricated route summary.

### 6.2 Tier 1: existing status-provider itinerary

When a currently integrated provider candidate has both a user key and written
rights for Blipbird's display/normalization/retention/derived use:

- Resolve route and gate times per leg.
- Calculate latest scheduled/calculated connection windows.
- Show terminal/gate destinations and changes.
- Show stale/missing/disrupted states.
- Recompute locally when either leg in a direct connection receives a snapshot.
- Avoid additional network requests solely for connection math.

### 6.3 Tier 2: verified official guidance

Bundle a validated registry of reviewed official URLs:

- Connection/transfer guide.
- Airport terminal map.
- Inter-terminal transport guide.
- Accessibility/assistance page.
- Official security/immigration wait page where available.

This is airport-by-airport enhancement. Missing registry coverage shows no
official action. A separately labeled `Unreviewed web results` action may be
offered, but it must not look like verified guidance.

### 6.4 Tier 3: licensed MCT

Enable only after an ADR records:

- Direct-mobile or backend delivery architecture.
- Display and derived-result rights.
- Local cache and TTL rights.
- Offline and redistribution rights.
- Price, update cadence, and spend limits.
- Exact matching semantics and test fixtures.
- Attribution and deletion requirements.

The UI remains an MCT comparison, not a guarantee.

### 6.5 Tier 4: venue routing

Enable per airport only when Blipbird has:

- Rights-cleared, current multi-floor geometry.
- Gate/terminal identifiers that map reliably to venue nodes.
- A routing graph with closures and transfer modes where promised.
- Accessible-route support and textual equivalents.
- A permitted mobile credential model.
- Offline rights if offline guidance is advertised.
- A visible source/verification-age/coverage explanation.

## 7. Information architecture and UX

### 7.1 Design principles

1. **Current action over complete inventory.** Emphasize the current leg and the
   next user-confirmed transfer; keep destination stays quiet and collapse
   completed/distant detail.
2. **A journey spine, not nested cards.** Use compact leg rows connected by
   explicit layover nodes. Full sky-gradient cards remain appropriate for
   ungrouped flights and selected/current leg heroes.
3. **Two windows, not one score.** For a direct connection, keep the latest
   scheduled and latest calculated duration visible together.
4. **Facts before advice.** Present booking/bag uncertainty and reported airport/
   terminal/gate facts before external guidance. Do not turn facts into
   unsupported route instructions.
5. **Unknown is a real state.** Missing gate, booking relationship, baggage plan,
   procedure, or map route is shown, not inferred.
6. **Stable order.** Delays change timing, never list order.
7. **Text before map.** Every route/map action has a textual destination and
   accessible equivalent.
8. **Calm escalation.** Use red only for factual disruption or a user/licensed
   threshold breach, not generic anxiety.

### 7.2 Home screen structure

Keep `My flights` for the first release to avoid turning itinerary support into a
top-level navigation redesign. Add two sections when relevant:

```text
My flights                                      [Settings]

ITINERARIES
+----------------------------------------------------+
| Japan trip                         18-19 September |
| GVA -> FRA -> HND                         2 flights |
|                                                    |
| NOW  LX 2801  GVA -> FRA        Lands in 42 min   |
|      FRA connection             1 h 08 min latest |
|      NH 204   FRA -> HND        Gate B42           |
+----------------------------------------------------+

OTHER FLIGHTS
[existing sky-gradient flight card]

                                                   [+]
```

Home behavior:

- Reuse the current adaptive `LazyVerticalGrid` and shared 380 dp minimum card
  width. Section headers and source CTAs span `maxLineSpan`; itinerary and
  ungrouped-flight cards occupy normal cells. Do not nest independently scrolling
  grids.
- Sort itinerary cards by their next incomplete leg's next event.
- Preserve ordinal order inside each card.
- Keep ungrouped flights in the existing next-event sort.
- Collapse completed legs to a one-line summary.
- Show at most the current leg, next confirmed transfer, and next leg in the
  compact card; `View itinerary` reveals the full journey. Do not elevate a
  destination stay as an urgent transfer.
- Use outcome-specific unresolved copy such as `No approved status source`,
  `Flight not found`, `Provider unavailable`, or `Ready to retry`.
- Do not place multiple full `FlightRowCard`s inside another rounded card.

### 7.3 Add entry point

The FAB opens a small action sheet:

- `Add itinerary`
- `Track flight(s)`
- `Group existing flights` when at least two ungrouped flights exist

The existing batch-capable add remains one tap away. The empty state may promote
`Add itinerary` and retain `Track flight(s)` as a secondary action.

### 7.4 Itinerary composer

The composer is a full screen rather than a bottom sheet because every leg needs
its own date, validation, reorder controls, and asynchronous resolution state.

```text
Create itinerary                              [Save]

Name (optional)
[ September in Japan                              ]

FLIGHTS
1  [ LX 2801       ]
   Departure-airport date [ Fri 18 Sep ] [Move later]
   Resolves to GVA -> FRA  07:10-08:25

   Transition: [Direct connection v]

2  [ NH 204        ]
   Departure-airport date [ Fri 18 Sep ] [Move earlier]
   Resolves to FRA -> HND  11:10-06:35 +1

[ + Add flight ]                 [ Paste several ]

[ Save itinerary ]
```

Composer rules:

- Parse all designators locally before any write.
- Require at least two valid legs before Save. A one-leg draft stays in the
  editor and never appears on Home.
- Limit an itinerary to 20 legs, its name to 120 characters, raw batch-paste
  input to 4 KiB UTF-8, and the serialized saved-state draft to 32 KiB. Reject
  excess input with an announced field error; never truncate it silently.
- Label each date as the date at that leg's departure airport.
- Pasting a batch creates editable draft rows; it does not immediately persist
  unrelated flights.
- Use one optional itinerary name. Do not apply that name as every leg alias.
- Derive an unnamed fallback such as `Geneva to Tokyo` only when endpoints are
  resolved; otherwise use `2-flight itinerary - 18 Sep`.
- Allow adding an existing ungrouped flight.
- Initialize order from entry order, not provider result order.
- Provide visible `Move earlier`/`Move later` controls with keyboard focus and
  semantic actions. Drag can be a later convenience, never the only path.
- Ask in plain language, `What happens after this flight?`, with `Connect to the
  next flight`, `Stay before the next flight`, `Travel to another airport`, and
  `Not sure`. Map those choices to domain intent. Heuristics may preselect a
  suggestion only on an explicit confirmation screen.
- Save all new tracked flights, itinerary, memberships, and transition edges in
  one User DB transaction. Use the serialized `draftId` as a unique creation
  request ID: retrying Save returns the already committed graph instead of
  creating a duplicate.
- Start per-flight status refresh only after the user transaction commits.
- A provider outage does not erase the draft or block saving local intent.
- Give each editor route a serialized `draftId`. Store its small user-authored
  draft in an activity-level `ItineraryDraftStoreViewModel` `SavedStateHandle`
  keyed by that ID; the current per-entry `NavEntryOwner` does not provide a
  saved-state registry capable of restoring route-scoped handles after process
  death. The activity-level ViewModel uses the activity’s `SavedStateRegistry`
  (the same one that backs `NavEntryStoresViewModel`), so `SavedStateHandle`
  writes survive process death. Multiple concurrent drafts use distinct
  `SavedStateHandle` keys within the single ViewModel; the `draftId` serves
  that purpose. A draft restored after process death is not a committed
  itinerary — the editor re-checks the creation request ID with the repository
  before allowing save. Clear after an idempotent Save returns the committed
  graph, or on Discard. Back with edits offers `Keep editing` and `Discard
  draft`.
- On Save, focus and announce the first invalid field.
- A date does not resolve same-day duplicate or multi-leg provider candidates.
  Live route, timing, and guidance remain suppressed until each new leg has a
  stable occurrence binding and the user reviews route/time candidates. The
  local itinerary shell may still be saved unresolved.
- `Connect to the next flight` without two current bindings and a non-expired
  same-airport operational pair renders `Connection pending route confirmation`;
  a confirmed different-airport pair instead prompts `Stay or surface transfer?`.
  Conflicting intent cannot calculate a window, show transfer guidance, query
  MCT, or emit timing notifications.
- Ask booking and baggage questions contextually when the user opens a confirmed
  direct connection, not during initial creation.
- A locally failed create/reorder/archive/restore/delete keeps the draft or last
  committed graph, announces a specific error, emits no success message, and
  offers an idempotent retry. Once a local commit succeeds, deferred refresh,
  cancellation, or cleanup is shown as pending reconciliation rather than
  rolling back user intent.

### 7.5 Group existing flights

Provide a selection screen over ungrouped active flights:

- Check two or more rows.
- Default the draft order from user-confirmed departure-local dates, then
  creation order. Phase 2 does not read provider snapshots for ordering.
- Let the user reorder before save.
- Show continuity warnings, not hard errors.
- Move selected rows into one itinerary without creating duplicate tracked
  flights or network calls.
- Ask what each selected transition means; do not infer that a later return
  flight is a connection.
- Route null-date and legacy/unknown-date rows through the composer and require
  an explicit departure-local date before local save. Provider-selected dates do
  not silently become portable user intent.

After occurrence binding and expiry-aware reads land in Phase 3, Blipbird may
surface a suggestion card when:

- The first arrival and second departure normalize to the same airport.
- The second scheduled `OUT` is after the first scheduled `IN`.
- The gap is no more than 24 hours.

The 24-hour value is only a prompt heuristic. It is not an MCT, a legal
definition, a grouping limit, or permission to write the transition type. The
user must confirm `Direct connection`; a long same-airport gap normally suggests
`Destination stay`, and a different-airport pair always asks what the user
intended.

### 7.6 Itinerary detail

The detail screen is a vertical journey spine:

```text
<  September in Japan                    [Edit] [...]
   GVA -> FRA -> HND                     18-19 Sep

   LX 2801                                             1
   GVA 07:10 -> FRA 08:25                  LANDED 08:19
        [Open flight dossier]
          |
          |  DIRECT CONNECTION AT FRA
          |  Latest scheduled   2 h 45 min
          |  Latest calculated  2 h 51 min
          |  6 min longer than scheduled
          |  Actual arrival -> scheduled departure
          |  Inbound fetched 4 min ago
          |  Onward fetched 7 min ago
          |
          |  Booked together: Not sure        [Answer]
          |  Checked bags: Not sure            [Answer]
          |
          |  Inbound reported: Terminal 1, Gate A18
          |  Next flight reported: Terminal 1, Gate B42
          |  Confirm on airport or airline displays.
          |  [FRA transfer guide, browser]
          |  [FRA terminal map, browser]
          |
   NH 204                                              2
   FRA 11:10 -> HND 06:35 +1                  ON TIME
```

Screen behavior by journey phase:

- Before travel: show all legs and latest scheduled windows for confirmed direct
  connections; collapse detailed facts.
- Inbound in progress: expand the next confirmed direct connection without
  stealing accessibility focus.
- Treat the traveler as at the connection airport only after actual inbound
  gate `IN` is known. `LANDED`/runway `ON` is not enough.
- After actual `IN`, show time until onward `OUT` with `Boarding or gate closing
  may be earlier` directly beside it. Keep the total calculated window below.
- Complete the transition normally only when both actual inbound gate `IN` and
  actual onward gate `OUT` exist and `OUT >= IN`. If actual `OUT` exists without
  actual `IN`, show the missing milestone rather than combining actual departure
  with a scheduled/estimated arrival. A provider phase such as `DEPARTED` is not
  a gate-milestone substitute.
- A destination stay shows a calm `Break before next flight` duration and no
  transfer checklist, MCT, or erosion alert.
- After all legs, show cached actual/latest-scheduled summaries only while
  provider retention permits. After prune, keep user data and show `Live flight
  details expired` plus archive action.
- On cancellation/diversion: replace positive guidance with factual disruption
  copy, the existing flight dossier, and verified airport actions. Show an
  operating-airline action only when a reviewed source exists.

### 7.7 Connection card hierarchy

Use this order:

1. User-confirmed transition type and factual disruption state.
2. Latest scheduled and calculated window.
3. Endpoint basis and the age of both inputs.
4. Booking/bag uncertainty that can change the transfer process.
5. Reported arrival and onward locations, with confirmation reminder.
6. Publisher-named official guide/map/accessibility actions.
7. Caveat or licensed MCT comparison.

Avoid placing disclaimers before the useful facts. Put a concise caveat beside
the interpretation, with a more complete explanation in an info sheet.

### 7.8 "Where to go" states

| Available facts | Primary copy | Actions |
|---|---|---|
| Both terminals and gates | `Inbound reported at T1 A18. Next flight reported at T1 B42. Confirm on airport or airline displays.` | Publisher-named transfer guide/map |
| Terminals only | `Inbound reported at Terminal 1. Next flight reported at Terminal 2; gate not reported.` | Publisher-named terminal-transfer guide |
| Onward gate only | `Next flight reported at Gate B42; terminal not reported.` | Publisher-named terminal map |
| Onward terminal only | `Next flight reported at Terminal 2; gate not reported. Confirm on airport or airline displays.` | Publisher-named terminal guide |
| Same airport, no terminal/gate | `Onward location not reported. Check airport or airline displays and the official transfer guide.` | Publisher-named transfer guide |
| Confirmed surface transfer | `Surface transfer recorded: Heathrow to Gatwick. Travel time is not included.` | Reviewed inter-airport source in a later pair-aware registry |
| Airport unknown | `Connection airport unresolved` | Specific no-key/not-found/rate-limit/retry state |
| Explicit provider diversion | `Inbound is reported as diverted to Manchester; the flights no longer meet at one airport.` | Open flight dossier; show an airline link only if a later reviewed source provides one |
| Onward cancelled | `Onward flight cancelled` | Open flight dossier; suppress gate guidance; no verified airline link in initial scope |

These are reported facts for the app user, who may be watching someone else's
trip. They are not commands to the traveler. Every terminal/gate line includes
the older retrieval age or exposes both `Fetched ... ago` values in details.

### 7.9 Disruption and unresolved content

| State | Primary copy | Behavior/action |
|---|---|---|
| Inbound cancelled | `Inbound flight cancelled` | Keep times in details; open flight dossier; show no unverified airline handoff |
| Onward cancelled | `Onward flight cancelled` | Suppress normal transfer guidance |
| Explicit diversion | `Inbound is reported as diverted to MAN` | Show current airports; no normal window headline |
| Onward actual `OUT` before inbound actual `IN` | `Onward flight left before inbound gate arrival` | Show factual actual times and flight dossier; never mark normally completed |
| Onward actual `OUT`, inbound actual `IN` missing | `Onward flight departed; inbound gate arrival not reported` | Suppress normal completion/window history |
| Departure time passed, no actual/status confirmation | `Onward departure time passed; status unconfirmed` | Mark stale/unknown; offer refresh when eligible |
| Zero/negative planned window | `Flight times overlap` | Require route/occurrence review |
| Airports do not match, transition unknown | `Flights are reported at different airports` | Ask `Stay or transfer?`; no travel-time assumption |
| No approved provider in this build | `Live flight details are unavailable in this build` | Keep local itinerary; no futile Settings action |
| Approved provider not configured | `Connect an approved status source for live details` | Open data-source settings |
| Credentials rejected | `Status-source credentials were rejected` | Open the exact provider credential setting |
| Provider budget exhausted | `Status budget reached until ...` | Show quota period/reset; no repeated retry |
| Outside provider date window | `Live details will be available closer to departure` | Show earliest eligible date; do not say not found |
| Eligible lookup returned no result | `Flight not found for this departure-airport date` | Edit date/designator or retry when policy permits |
| Rate limited/backing off | `Status source asked Blipbird to wait` | Show exact next eligible time |
| Provider/network unavailable | `Status source unavailable` | Show last known facts and retry eligibility |
| Cancellation/diversion later clears | `Latest provider status changed` | Show both current status and age; do not claim operational recovery |

### 7.10 User fact prompts

Ask per confirmed direct connection, not once per itinerary, because a
multi-leg trip can combine different purchases and baggage arrangements.

**Booking arrangement**

- Booked together
- Booked separately
- Not sure

Helper text: `Booked together does not prove one ticket, airline protection, or
baggage transfer.`

**Checked baggage**

- No checked bag
- Airline says checked through to onward destination
- Collect and check again
- Unknown

These values influence questions and official-link emphasis only in the first
release. They do not create an unsupported risk score. Keep them `Not sure`
without interrupting creation; ask only when the connection is imminent, opened,
or the user elects to add details.

### 7.11 Edit and reorder

- Editing uses the same composer with persisted rows.
- A persisted designator or departure-local date is occurrence identity, not an
  in-place text edit. `Replace flight` inserts a new tracked row and swaps the
  membership in one User DB transaction, rebuilds both adjacent transitions with
  answers reset, and queues cleanup for the old flight. Live facts remain
  suppressed until the replacement occurrence is reviewed. Alias/name edits may
  remain in place.
- Reorder does not edit flight times.
- A changed adjacency gets a new transition edge with intent and answers reset
  to unknown.
- If moving a leg destroys an answered edge, confirm before discarding those
  answers.
- Removing a leg offers `Keep as separate flight` as the default and
  `Delete tracked flight` as a destructive alternative.
- Adding a middle leg splits one transition into two new unknown transitions.
- Removing a middle leg joins its neighbors with one new unknown transition.
- Removing the sole remaining pair deletes the itinerary and leaves its flights
  ungrouped after confirmation; persisted one-leg itineraries are not allowed.

### 7.12 Archive, restore, and delete

Recommended lifecycle invariant:

- Archiving an itinerary atomically archives its member tracked flights in the
  User DB; aggregate archive state is derived from those member flags.
- Restoring an itinerary atomically restores its member tracked flights.
- A member inside an itinerary is not individually swipe-archived from Home.
- To archive or delete one member, remove it from the itinerary first.
- Completed member legs may collapse visually but remain active records until
  the itinerary is archived. The refresh coordinator must stop scheduling work
  when no member is refresh-eligible, rather than merely relying on each fetch
  loop to skip terminal flights.
- Archive/delete routes through one membership-aware lifecycle coordinator,
  closes any open member detail, cancels reminders and visible ongoing/
  transition notifications, and retains dedup state so restore does not replay.

Itinerary deletion dialog:

- `Delete itinerary, keep flights` - recommended default.
- `Delete itinerary and all flights` - destructive confirmation, no casual
  swipe action.

An itinerary card may support swipe-to-archive with Undo. Do not use a left
swipe to delete an entire multi-leg aggregate.

The archive screen becomes `Past trips and flights`, with itinerary and
ungrouped-flight sections. Deleting an archived itinerary while keeping its
flights leaves those flights archived; the user can restore them separately.

### 7.13 Notifications UX

Direct-connection timing notifications should describe a material fact:

- `Reported Frankfurt gate-time window is 18 min shorter than latest scheduled`
- `Latest calculated: 1 h 08 min (latest scheduled 1 h 26 min)`
- `Flights now report different airports for the London connection`

Avoid:

- `You will miss your connection`
- `Connection at risk` without an approved assessment model
- Repeating the onward flight's existing gate-change alert with no added value

Notification settings should add one itinerary category, **off by default** in
the first beta. Timing events require a compatible direct connection, confirmed
occurrences, a current matching pair, and an imminent/active phase. A continuity
event instead requires unchanged confirmed bindings and a previously matching
pair that recently changed:

- Meaningful connection-window changes
- Current airport continuity no longer matches

Existing gate assignment/change and cancellation/diversion remain per-flight
events; do not emit duplicate transition alerts for the same fact. A terminal-
change alert is out of scope until the leg planner owns that event explicitly.

### 7.14 Offline UX

- Itinerary name, order, dates, and user answers remain fully available.
- Last normalized snapshots may be displayed only for their permitted TTL.
- Compact calculated-window surfaces show the older relevant retrieval age and
  stale/offline state; detail exposes both input ages. Terminal/gate facts use
  the same treatment.
- Do not continue confident live copy after connectivity is lost.
- Official links remain focusable when offline; activating one explains that a
  network connection is required instead of exposing an inaccessible disabled
  control.
- Do not advertise offline walking directions merely because a basemap tile is
  cached.

### 7.15 Accessibility

- The itinerary card exposes a concise summary node plus separate visible action
  nodes; expansion state is announced.
- Each leg and connection is a heading/focus stop, not every decorative line.
- The decorative spine is hidden from accessibility services.
- Connection status uses text and icon in addition to color.
- At 200% text, composer rows reflow vertically; flight number, departure-airport
  date, validation, and move controls never remain squeezed into one row.
- TalkBack speaks full airport names, explicit local dates, and `arrives next
  day`; it does not rely on arrows or `+1`.
- Durations use tabular figures and do not truncate at 200% font scale.
- Reorder exposes the composer's visible `Move earlier`/`Move later` controls
  as semantic actions too; drag is never the only mechanism.
- Swipe actions have equivalent menus.
- Visible `Move earlier`, `Move later`, `Refresh`, `Expand`, archive, and delete
  controls support keyboard focus and at least 48 dp targets.
- Official-map actions name publisher, destination, format, and `opens browser`.
- A connection map, when added, has a complete text alternative.
- Reduced motion (the system setting or Blipbird's in-app toggle) disables
  timeline travel animations without hiding state.
- Large touch targets and switch-access traversal are verified.
- Automatic connection expansion preserves current focus and announces only a
  material disruption, never every countdown tick.
- Do not require a user to disclose a disability. Offer `Accessibility guide`
  and accessible routing when the source supports it.

### 7.16 Adaptive layouts and visual language

The first release extends the current adaptive contract instead of regressing it.
Home and `Past trips and flights` retain the shared
`GridCells.Adaptive(380.dp)`, 16 dp outer padding, and 12 dp cell gaps; itinerary
cards are bounded grid cells while section headings/actions span all columns.
Editor, grouping, dialogs, link sheets, and the vertical itinerary detail remain
hinge-safe single panes with maximum content width 840 dp, centered in their
available region, with at least 16 dp compact, 24 dp medium, and 32 dp expanded
horizontal gutters.

A separating fold/hinge is unavailable space: no grid card or single pane spans
it. For editor/detail, use the focused field's viable region when it is at least
320 dp wide, otherwise the larger viable region. Rotation, freeform resize,
posture changes, and IME appearance preserve route, stable-field focus, scroll
position, and editor state. Keyboard order follows visual order, and 200% text is
tested on every surface, not only editor/detail.

An itinerary timeline/detail or editor/preview two-pane layout is a later
enhancement after the single-pane accessibility model is stable, even though
flight detail already has its own wide-screen treatment. If added, it must define
default selection, pane-to-pane focus traversal, resize persistence, hinge
avoidance, and collapse back to one pane when scaled text leaves insufficient
width. Do not show every full flight dossier simultaneously.

Preserve Blipbird's visual language:

- Reuse existing 22/26 dp rounded shapes, airline monograms, `StatusWord`, Inter
  typography, and tabular numeric treatment.
- Use the current leg's sky palette as an accent/header, not as a full nested
  flight card for every leg.
- Give Cockpit the same information hierarchy with its existing dark avionics
  palette.
- High Contrast uses solid surfaces, visible borders, text/icon state labels,
  and no low-alpha-only separation.

## 8. Transition and connection semantics

### 8.1 Timing realism: gate assignment windows

Gates and terminals are typically assigned 24–48 hours before departure. A
direct connection whose onward leg is more than two days away will normally show
`Gate not yet assigned` for that leg. The connection-window arithmetic is still
correct from scheduled or estimated times, but location guidance (terminal, gate)
is temporally limited.

The product must not use the absence of a gate to imply a problem. When the
onward departure is more than the typical gate-assignment horizon, show
`Onward gate not yet assigned — check closer to departure` rather than
`Onward location not reported`.

This is a product-copy distinction, not a new data source. The existing
missing-gate states already handle `null` gracefully.

### 8.2 Use gate milestones

For a user-confirmed direct connection, the connection window is:

```text
outbound gate departure (OUT) - inbound gate arrival (IN)
```

In the normalized model:

- Inbound `IN` is `inbound.arrTimes`.
- Outbound `OUT` is `outbound.depTimes`.
- Runway `ON/OFF` values live in `bestRunway` and must not be substituted into
  the gate calculation.

If gate times are absent but runway times exist, either show no connection
window or show a separately labeled `Runway-to-runway gap`. The MVP should
prefer no window to a misleading mixed-family duration.

Current adapter capability matters:

| Provider adapter | Gate scheduled/estimated `OUT/IN` | Gate actual `OUT/IN` | Runway `OFF/ON` | Consequence |
|---|---|---|---|---|
| FlightAware AeroAPI | Mapped when supplied | Current adapter maps `actual_out`/`actual_in` | Current adapter maps `actual_off`/`actual_on` | Can support actual gate history when fields exist |
| AeroDataBox | Provider schema does not let the current `revisedTime ?: predictedTime -> gate estimated` mapping safely classify the selected value | Current adapter does not populate actual gate `OUT/IN` | `runwayTime` may itself be actual or estimated, but the adapter currently maps it as actual | Disable all connection-window output for this adapter until family/certainty mapping is corrected or contractually confirmed |

AeroDataBox documents `revisedTime` as potentially actual or estimated and, in
some responses, gate or runway unless a distinct runway value disambiguates it;
`runwayTime` may also be actual or estimated. Its experimental `predictedTime`
does not establish the gate/runway family needed here. The current adapter's
`revisedTime ?: predictedTime -> gate estimated` and `runwayTime -> runway
actual` mapping is therefore not a safe basis for this feature. Exclude
predicted-only values and add revised-, predicted-, and runway-only fixtures
until normalized family/certainty is contractually confirmed.

Verbatim OpenAPI schema text (checked 2026-07-24): `revisedTime` — "Actual
/estimated time of arrival or departure the flight. If `RunwayTime` is
specified and not equal to this field, this field stands for the time of
departure/arrival to the gate. Otherwise, it may either be time at the gate or
on the runway."; `runwayTime` — "Actual / estimated time on the runway:
landing time for arriving flight; take-off time for the departing flight, if
known."; `predictedTime` — "Predicted time based on historical data
(experimental)." The gate/runway family is therefore partially documented: a
distinct, unequal `runwayTime` disambiguates `revisedTime` to the gate. But
actual-versus-estimated certainty is not documented for either field, and the
current adapter additionally maps `runwayTime` to runway actual only, which
the schema does not support.

Do not infer gate arrival from `FlightStatus.LANDED`; that can mean runway `ON`.
Do not infer gate departure from `DEPARTED` or `EN_ROUTE`; those phases can come
from runway times, provider status, or clock inference.

### 8.3 Latest scheduled window

```kotlin
scheduledWindow =
    outbound.depTimes.scheduled - inbound.arrTimes.scheduled
```

Both values are `Instant`. Never subtract local clock strings or fixed UTC
offsets. Convert to airport-local zones only for display. The existing
`AirportRef.tz` field carries an IANA zone ID when the provider supplies one;
when it is null, fall back to UTC for display rather than guessing from
coordinates.

`scheduled` here means the scheduled values in the latest permitted snapshot.
The current schema does not preserve a distinct first-observed or booked
schedule. Label it `Latest scheduled`, and do not imply that it is the original
schedule if a provider has revised that field.

### 8.4 Latest calculated window

Select each endpoint independently:

```kotlin
currentInboundIn =
    inbound.arrTimes.actual
        ?: inbound.arrTimes.estimated
        ?: inbound.arrTimes.scheduled

currentOutboundOut =
    outbound.depTimes.actual
        ?: outbound.depTimes.estimated
        ?: outbound.depTimes.scheduled

latestCalculatedWindow = currentOutboundOut - currentInboundIn
```

Return endpoint certainty, source, and retrieval age, not just a duration.
Examples:

- `Estimated gate arrival, inbound fetched 4 min ago; scheduled gate departure,
  onward fetched 7 min ago`
- `Actual gate arrival; estimated gate departure`
- `Actual gate arrival; actual gate departure` for permitted history

The headline label is `Latest calculated time between reported gate arrival and
gate departure`, shortened to `Latest calculated` only when nearby copy defines
the measurement. `Current` alone is too easy to read as live, equally fresh, or
guaranteed.

### 8.5 Remaining time after arrival

Once actual inbound `IN` is known and onward `OUT` has not occurred, also show:

```text
timeUntilOnwardDeparture = currentOutboundOut - now
```

Label it `Until onward departure`, not `Time to make connection`. Boarding or
gate closure can occur earlier, and the current model has no universal cutoff.
Put `Boarding or gate closing may be earlier` directly beside this countdown.

### 8.6 Window change

```kotlin
windowChange = latestCalculatedWindow - latestScheduledWindow
```

Copy examples:

- `18 min shorter than latest scheduled`
- `12 min longer than latest scheduled`
- `No change from latest scheduled`

This is a useful, factual signal that avoids an invented feasibility threshold.
It is relative to the latest scheduled values, not necessarily the schedule the
traveler originally booked.

### 8.7 Airport continuity

Normalize airport identity as sets of known codes:

1. Uppercase provider IATA/ICAO codes with `Locale.ROOT`.
2. Enrich missing counterpart codes through `ReferenceDao`.
3. Match if either normalized IATA or ICAO code is equal.
4. Never match on city, display name, or unstable reference-table numeric ID.

Outcome:

```kotlin
enum class Continuity {
    SAME_AIRPORT,
    DIFFERENT_AIRPORTS,
    UNKNOWN,
}
```

Different airports in one city are not equivalent without a reviewed metro-
airport dataset and transfer plan. `LHR -> LGW` may be a surface transfer or may
simply separate a London stay from a later flight; adjacency alone cannot decide.

### 8.8 Transition intent and suggestion

```kotlin
enum class TransitionIntent {
    DIRECT_CONNECTION,
    DESTINATION_STAY,
    SURFACE_TRANSFER,
    UNKNOWN,
}
```

`TransitionIntent` is user-authored. A pure suggestion policy may recommend:

- Same airport, positive gap, and no more than 24 hours: suggest
  `DIRECT_CONNECTION` for confirmation.
- Long gap: suggest `DESTINATION_STAY` for confirmation.
- Different airports: ask `Destination stay or airport transfer?`; never choose
  from straight-line geography.
- Missing airports/times: leave `UNKNOWN`.

The 24-hour value is only a prompt heuristic. It is not persisted as truth, an
MCT, or a validity rule. `INVALID_OVERLAP`, airport mismatch, stale data, and
disruption are assessment issues, not transition types.

One known failure mode of the 24-hour heuristic: a same-airport short-haul
connection with a scheduled gap below 24 hours may be suggested as a direct
connection, while a same-airport international-to-international connection with
an overnight gap above 24 hours (e.g., a 28-hour FRA stopover on a long-haul
itinerary) would be suggested as a destination stay even though the user intended
a connection. The heuristic is a convenience, not a classification rule. The user
must confirm intent explicitly and may override the suggestion.

User confirmation persists only transition intent. Provider-derived airport
codes do not become rights-free user data merely because the user taps Confirm.
Derive a normalized airport-pair fingerprint from two confirmed occurrence
bindings and keep it in Ops storage under the provider-approved TTL. Live
windows/guidance require a non-null, non-expired same-airport operational pair
and current occurrence-bound snapshots that match it. A replacement/expired
binding produces `Connection pending route confirmation` and no change alert.
With the same bindings, recently fetched endpoints that stop matching produce a
continuity disruption, suppress timing/guidance, and may emit the narrowly scoped
continuity event before asking for route review. A future independently user-
entered route would be separate user-authored data with explicit fields.

### 8.9 Disruption precedence

For a direct connection, apply factual states before timing interpretation:

1. Outbound cancelled.
2. Inbound cancelled.
3. Explicit provider diversion.
4. Outbound actual `OUT` exists but inbound actual `IN` is missing.
5. Outbound actual `OUT` before inbound actual `IN`.
6. Current inbound-arrival and onward-departure airports do not match the active
   occurrence bindings' operational airport-pair fingerprint.
7. Departure time passed with no actual/status confirmation.
8. Missing or stale times.
9. Zero/negative planned window or likely wrong occurrence.
10. Normal latest calculated window.

When a higher-priority disruption applies, retain raw times in details but do
not lead with a reassuring connection duration.

### 8.10 Freshness

The current model's `fetchedAt` is the device's retrieval time, not a provider
field-update timestamp. A newly fetched response may still contain old upstream
values. Use a separate pure retrieval-recency policy. It accepts `now`,
`fetchedAt`, expected cadence, and an absolute fallback threshold and returns:

- Inbound retrieval age.
- Outbound retrieval age.
- `recently fetched`, `stale fetch`, `last known`, or `unknown` per endpoint.
- Provider-reported field age only when a future provenance model supplies it.

Suggested policy:

- UI says `Fetched ... ago`, never `Updated ... ago`, until provider field-level
  provenance exists. Retrieval recency must not be described as data freshness.
- UI can show old arithmetic as `Last known` with a clear label.
- Show the older relevant input age in compact UI and both ages in details.
- Connection-change notifications require both endpoints to be recently
  fetched and any available provider field-recency checks to pass. Two
  expected cadence intervals can inform the policy, but an absolute fallback is
  required when cadence is null or WorkManager is delayed.
- Suppress change notifications when provider/source or certainty regresses
  unless equivalence is established; a provider switch is not a real time change.
- Cancellation/diversion notifications retain their existing provider event
  behavior; the itinerary layer does not manufacture them from silence.
- Treat a non-expired actual gate milestone as final observed rather than
  cadence-stale; still show its fetch time and enforce `expiresAt`. Scheduled and
  estimated endpoints need the normal recency checks.

The exact thresholds belong in a tested policy object and should use
`CadencePolicy` as one input, not be copied across ViewModels. Passing `now`
explicitly is sufficient; this feature need not block on the broader injectable-
clock refactor.

### 8.11 Pure domain API

```kotlin
data class ConfirmedOccurrencePair(
    val inboundBindingId: Long,
    val outboundBindingId: Long,
    /**
     * Computed from the confirmed bindings’ airport identities at time of
     * confirmation; not independently stored. Two pairs with matching binding
     * IDs but differing fingerprints indicate a data-model corruption or stale
     * reference data, not a legitimate transition reassessment.
     */
    val airportPairFingerprint: String,
    val continuityAtConfirmation: Continuity,
    val expiresAt: Instant,
)

data class BoundStatusSnapshot(
    val occurrenceBindingId: Long,
    val snapshot: StatusSnapshot,
    val expiresAt: Instant,
    /** Approved provider, normalized-time-family, and adapter-version identity. */
    val capabilityFingerprint: String,
)

data class SelectedGateEndpoint(
    val instant: Instant,
    val certainty: TimeCertainty,
    val provider: String,
    val occurrenceBindingId: Long,
    val fetchedAt: Instant,
    val providerFieldUpdatedAt: Instant?,
    val expiresAt: Instant,
    val sourceFingerprint: String,
)

data class TransitionInput(
    val transitionId: Long,
    val inboundLegId: Long,
    val outboundLegId: Long,
    val intent: TransitionIntent,
    val confirmedOccurrencePair: ConfirmedOccurrencePair?,
    val inbound: BoundStatusSnapshot?,
    val outbound: BoundStatusSnapshot?,
    val bookingArrangement: BookingArrangement,
    val baggagePlan: BaggagePlan,
    val inboundRecency: EndpointRetrievalState,
    val outboundRecency: EndpointRetrievalState,
    val now: Instant,
    val mct: MinimumConnectionTime? = null,
)

data class TransitionAssessment(
    val intent: TransitionIntent,
    val latestScheduledWindow: Duration?,
    val latestCalculatedWindow: Duration?,
    val remainingUntilOutbound: Duration?,
    val changeFromScheduled: Duration?,
    val selectedInboundIn: SelectedGateEndpoint?,
    val selectedOutboundOut: SelectedGateEndpoint?,
    val continuity: Continuity,
    val currentAirportPairFingerprint: String?,
    val disruption: ConnectionDisruption?,
    val recency: ConnectionRecency,
    val mctComparison: MctComparison?,
    /** Intent, binding generations, confirmed pair, and equivalent source facts. */
    val eligibilityFingerprint: String?,
)

object TransitionEngine {
    fun evaluate(input: TransitionInput): TransitionAssessment
}
```

Keep wording out of the engine. It returns typed facts; resources and UI state
produce localized copy. The engine is a pure function over the input; null,
expired, or ineligible fields in `TransitionInput` map to null or disrupted
fields in `TransitionAssessment` — the engine never throws or returns a sentinel
for a well-formed input. The caller (repository or ViewModel) is responsible for
omitting transitions that have not yet been confirmed or whose legs lack the
required data.

For `DESTINATION_STAY` and `UNKNOWN`, it may return a
plain break duration but no connection assessment, checklist, MCT, or erosion
event. For `SURFACE_TRANSFER`, the gross break explicitly excludes travel time.
For `DIRECT_CONNECTION`, null/expired/replaced bindings or a confirmed pair that
was not same-airport return `PENDING_ROUTE_CONFIRMATION` before any window,
location guidance, MCT lookup, or timing notification is allowed. A current pair
mismatch under the same bindings returns `AIRPORT_PAIR_NO_LONGER_MATCHES`; it
suppresses those outputs but remains eligible for only the continuity event.
`SURFACE_TRANSFER` likewise requires a confirmed different-airport pair before
showing pair-specific transfer facts.

### 8.12 Optional MCT model

```kotlin
data class MatchedRuleDimension(
    val name: String,
    val values: List<String>,
)

enum class MctApplicability {
    APPLICABLE_TO_DECLARED_ARRANGEMENT,
    NOT_APPLICABLE,
    UNKNOWN,
}

data class MctRequest(
    val occurrencePair: ConfirmedOccurrencePair,
    val bookingArrangement: BookingArrangement,
    /** Carrier, terminal, flight, connection-type, and date dimensions. */
    val dimensions: List<MatchedRuleDimension>,
)

data class MinimumConnectionTime(
    val duration: Duration,
    val source: String,
    val ruleId: String,
    val effectiveFrom: LocalDate?,
    val effectiveUntil: LocalDate?,
    /** Every provider-returned match dimension permitted for retention. */
    val matchedDimensions: List<MatchedRuleDimension>,
    val declaredBookingArrangement: BookingArrangement,
    val applicability: MctApplicability,
    val fetchedAt: Instant,
    val expiresAt: Instant,
)

interface MinimumConnectionTimeProvider {
    suspend fun lookup(request: MctRequest): MctResult
}
```

MCT output:

```text
Filed minimum: 60 min
Latest scheduled window: 90 min, 30 min above the filed minimum
Latest calculated window: 78 min, now 18 min above that minimum
Rule matched for international -> international, Terminal 1 -> Terminal 2
MCT is a schedule-validity threshold, not a guarantee.
```

The calculated margin is operational context, not a new ticket-validity decision
or guarantee. If the match is ambiguous, required dimensions are missing, or
applicability to the declared booking arrangement is not explicit, return
`Unknown`. In particular, suppress a filed-minimum comparison for
`BOOKED_SEPARATELY` unless the provider explicitly confirms that the matched rule
applies to self-transfer/virtual-interline journeys. Do not fall back to an
unexplained airport-wide number.

## 9. Wayfinding and transfer guidance

### 9.1 Guidance layers

Use progressively stronger capability labels:

| Layer | Output | MVP |
|---|---|---|
| Destination facts | Reported arrival and onward airport/terminal/gate, with age | Yes |
| Context questions | Booking, bags, documents, airport procedure | Yes |
| Official links | Airport connection guide/map/accessibility/waits | Yes, registered airports |
| Official structured procedure | Airport-specific steps with version/verification date | Later, rights-cleared |
| Indoor route | Floor-aware path, modes, duration, closures | Later, per airport |
| Indoor positioning | Live blue dot and rerouting | Later, per airport |

### 9.2 Official airport guide registry

Create a small validated checked-in asset rather than hardcoding URLs in
composables. A generated YAML-to-JSON pipeline is unnecessary for the initial
registry and would add tooling/CI drift without product value:

```json
{
  "airportIata": "LHR",
  "locale": "en",
  "topic": "CONNECTIONS",
  "title": "Connecting flights",
  "url": "https://www.heathrow.com/connecting-flights",
  "publisher": "Heathrow Airport",
  "format": "HTML",
  "accessibilityNote": null,
  "verifiedAt": "2026-07-23"
}
```

Topics:

- `CONNECTIONS`
- `TERMINAL_MAP`
- `TERMINAL_TRANSFER`
- `ACCESSIBILITY`
- `SECURITY_WAIT`
- `IMMIGRATION_WAIT`

The initial registry is airport-scoped. Airline contacts need an airline key;
inter-airport transfers need an ordered airport-pair key. Add separate typed
registries later rather than forcing those subjects into one airport-only schema.

Registry requirements:

- Official publisher only for the `Official` badge.
- HTTPS except a documented unavoidable government source.
- Locale recorded.
- Language, format (`HTML`, `PDF`, app link), review date, and known
  accessibility limitations recorded and available from the link info sheet.
- Tests validate schema, duplicate keys, URL shape, supported topics, and age.
- Scheduled CI can report broken URLs but must not silently rewrite them.
- Human review at least every six months and before releases touching guidance.
- Link labels name publisher and destination, for example
  `Frankfurt Airport transfer guide, opens browser`.
- Link out through the browser/custom tab; avoid an in-app WebView.
- Copy only stable titles and URLs, not volatile airport procedures.
- `Official` identifies the publisher, not guaranteed freshness, completeness,
  accessibility, or relevance to a specific traveler.

### 9.3 Generic checklist language

Safe generic questions and handoffs:

- `Confirm the latest onward terminal and gate on airport or airline displays.`
- `Check the airport's official transfer guide for the applicable path.`
- `Bag handling is not known; confirm with the airline.`
- `Check transit document requirements for this traveler and route.`
- `Airport accessibility and assistance information is available from the
  publisher.`

Do not assert that security, immigration, customs, or baggage reclaim is always
or never required.

### 9.4 Surface transfers

For different airports:

- Show both airport codes and the gross latest scheduled/calculated gap.
- Require explicit `This itinerary includes a surface transfer` confirmation.
- Do not call it a connection window without the `Surface transfer` qualifier.
- Link to an official inter-airport transfer page only after a pair-aware source
  registry exists. An airport-only link entry is insufficient.
- A future GTFS integration may show public transport only when an official feed
  exists and its data rights, coverage, and realtime behavior are reviewed.
- Never infer travel duration from straight-line airport distance.

### 9.5 Indoor route provider contract

If indoor routing is later added, isolate it:

```kotlin
interface AirportWayfindingProvider {
    fun supports(airport: AirportIdentity): Boolean
    suspend fun route(request: AirportRouteRequest): AirportRouteResult
}
```

The result must preserve:

- Provider and venue-data version.
- Generated/fetched time and expiration.
- Start/end node confidence.
- Distance and duration source.
- Floors and transfer modes.
- Accessibility constraints used.
- Closures included or explicitly unavailable.
- Offline availability.

No provider implementation should enter a release build until mobile credential,
display, caching, attribution, and venue-data rights are recorded in an ADR.

## 10. Data model

### 10.1 User database entities

Add three tables and one provenance column to `blipbird-user.db`, then bump
`UserDatabase` from version 1 to version 2. Existing `tracked_flight.dateLocal`
is ambiguous: current provider resolution can pin it even when the user entered
`next occurrence`. Add `dateIntentSource`; migrate null dates to
`NEXT_OCCURRENCE`, non-null dates to `LEGACY_UNKNOWN`, and let only an explicit
editor confirmation set `USER_CONFIRMED`. `NEXT_OCCURRENCE` remains valid for an
ungrouped operational lookup but must be resolved to user-confirmed intent before
joining a local itinerary. In the final Phase 3 model, provider resolution writes
its date to the Ops binding, never back into this field. Phase 1 must not remove
the current pin before that replacement exists: interim provider pins are marked
`PROVIDER_RESOLVED_LEGACY`, remain ineligible as user-confirmed itinerary dates,
and stop in the same Phase 3 change that enables Ops binding. In the existing
entity this is one new non-null field:

```kotlin
val dateIntentSource: String = DateIntentSource.NEXT_OCCURRENCE.name
```

```kotlin
@Entity(
    tableName = "itinerary",
    indices = [Index(value = ["creationRequestId"], unique = true)],
)
data class ItineraryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Serialized draft/request UUID for idempotent create retries. */
    val creationRequestId: String,
    val name: String?,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "itinerary_leg",
    foreignKeys = [
        ForeignKey(
            entity = ItineraryEntity::class,
            parentColumns = ["id"],
            childColumns = ["itineraryId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = TrackedFlightEntity::class,
            parentColumns = ["id"],
            childColumns = ["trackedFlightId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["itineraryId", "ordinal"], unique = true),
        // Parent key for the composite transition foreign keys below.
        Index(value = ["itineraryId", "id"], unique = true),
        Index(value = ["trackedFlightId"], unique = true),
    ],
)
data class ItineraryLegEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val itineraryId: Long,
    val trackedFlightId: Long,
    val ordinal: Int,
)

@Entity(
    tableName = "itinerary_transition",
    foreignKeys = [
        ForeignKey(
            entity = ItineraryLegEntity::class,
            parentColumns = ["itineraryId", "id"],
            childColumns = ["itineraryId", "inboundLegId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ItineraryLegEntity::class,
            parentColumns = ["itineraryId", "id"],
            childColumns = ["itineraryId", "outboundLegId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        // One successor and one predecessor per leg: no branches or joins.
        Index(value = ["itineraryId", "inboundLegId"], unique = true),
        Index(value = ["itineraryId", "outboundLegId"], unique = true),
    ],
)
data class ItineraryTransitionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val itineraryId: Long,
    val inboundLegId: Long,
    val outboundLegId: Long,
    val intent: String = TransitionIntent.UNKNOWN.name,
    val bookingArrangement: String = BookingArrangement.UNKNOWN.name,
    val baggagePlan: String = BaggagePlan.UNKNOWN.name,
    val updatedAt: Long,
)
```

Why an explicit neutral transition row:

- It gives user answers a stable home.
- It provides a stable deep-link and notification identity.
- It makes edge replacement on reorder explicit.
- It distinguishes a connection from a destination stay instead of interpreting
  every adjacency as a transfer.
- It avoids pretending that transition metadata belongs to only the inbound or
  outbound flight.
- It can later reference a transient MCT lookup without changing leg ownership.

The foreign keys enforce that both legs belong to the transition's itinerary,
and unique predecessor/successor indices prevent branching. Room cannot enforce
that transitions are exactly the ordinal-adjacent pairs, that there are exactly
`n - 1`, or that an itinerary has at least two legs. Those are transaction-level
repository invariants covered by tests. `RESTRICT` prevents the existing direct
flight delete path from silently tearing a hole in an itinerary; every mutation
must go through the membership-aware lifecycle gateway.

### 10.2 Enums

```kotlin
enum class BookingArrangement {
    BOOKED_TOGETHER,
    BOOKED_SEPARATELY,
    UNKNOWN,
}

enum class Continuity {
    SAME_AIRPORT,
    DIFFERENT_AIRPORTS,
    UNKNOWN,
}

enum class DateIntentSource {
    USER_CONFIRMED,
    NEXT_OCCURRENCE,
    PROVIDER_RESOLVED_LEGACY,
    LEGACY_UNKNOWN,
}

enum class TransitionIntent {
    DIRECT_CONNECTION,
    DESTINATION_STAY,
    SURFACE_TRANSFER,
    UNKNOWN,
}

enum class BaggagePlan {
    NO_CHECKED_BAG,
    THROUGH_CHECKED,
    COLLECT_AND_RECHECK,
    UNKNOWN,
}
```

Store `TransitionIntent`, `BookingArrangement`, and `BaggagePlan` names
defensively. Unknown future values must decode to `UNKNOWN` rather than crash.
Treat an unknown future date-intent value as `LEGACY_UNKNOWN` and require review,
not as user confirmation.

Update `ItineraryEntity.updatedAt` for name, membership/order, transition intent,
or user-answer changes, but not for operational snapshot refreshes. The unique
membership index prevents using the same tracked row twice. Until stable
occurrence identity exists, warn on identical normalized designator/date rows and
require explicit candidate review; once bindings exist, reject the same physical
occurrence twice while allowing genuinely distinct multi-leg occurrences.

**Codeshare handling.** A codeshare adds complexity in an itinerary context
because the operating designator and marketing designator may differ between
legs. Track by operating identity at the occurrence-binding level while
preserving the user-entered marketing designator on `TrackedFlightEntity`.
Codeshare resolution follows the existing `IdentityResolver` path. The new
concern is that two adjacent legs with the same operating carrier may not share
one ticket, just as two legs with different marketing carriers may be booked
together. The booking-arrangement question must ask per transition regardless of
carrier alignment.

### 10.3 Why not put `itineraryId` and `ordinal` on `tracked_flight`

A junction entity is slightly more code but gives cleaner behavior:

- Membership has its own stable identity for transition edges.
- Reorder does not mutate the flight's operational identity.
- Ungrouping does not rewrite unrelated flight fields.
- Future non-flight itinerary items can use a separate member type without
  bloating `TrackedFlightEntity`.
- Foreign-key and uniqueness constraints are explicit.

### 10.4 Archive authority and mutation gateway

Do not duplicate archive state on `ItineraryEntity`. Member
`TrackedFlightEntity.archived` flags remain authoritative so the existing worker
contract has one source. Aggregate state is derived: all members active means an
active itinerary; all archived means an archived itinerary.

Route every archive, restore, delete, and permanent-delete entry point through a
membership-aware lifecycle coordinator. The old direct `TrackedFlightDao`
mutation methods become internal primitives. Both active and archived screens
must use grouped/ungrouped queries before the feature is exposed.

An archive/restore transaction updates every member flag together. Do not add a
startup "repair" that guesses which contradictory flag represented user intent.
Prevent bypasses, assert graph/archive invariants in tests, and surface any
impossible state in local diagnostics. Removing the last transition deletes the
itinerary and keeps the remaining flight ungrouped after confirmation.

As defense in depth, alarm receivers and refresh/position entry points re-read
the tracked row and require `!archived`; cancellation can race or fail. An open
member detail observes archive/delete and navigates back. After archive commit,
batch-cancel every member notification/alarm surface through the durable cleanup
outbox. After restore, call `StatusRefreshCoordinator.reconcileSchedule()` once
and reconcile every member reminder. App startup runs the same schedule and
outbox reconciliation; it does not unconditionally enqueue periodic work.

### 10.5 DAO surface

Create a dedicated `ItineraryDao` with graph queries and User DB transaction
primitives. Do not mirror every public use case as a DAO method:

- `observeActiveItineraries()`
- `observeArchivedItineraries()`
- `observeItinerary(id)`
- `itinerary(id)`
- `insertGraph(...)`
- `replaceOrderAndTransitions(...)`
- `removeGraphKeepingFlights(...)`
- `setMemberArchiveState(...)`
- `updateTransition(...)`
- `assertGraphShape(...)` for debug/test diagnostics

Higher-level create/remove/delete/archive behavior belongs in the lifecycle
coordinator because a Room transaction cannot include Ops DB cleanup, alarms,
WorkManager, or notification cancellation.

Add an ungrouped query to `TrackedFlightDao`:

```sql
SELECT tracked_flight.*
FROM tracked_flight
LEFT JOIN itinerary_leg
  ON itinerary_leg.trackedFlightId = tracked_flight.id
WHERE tracked_flight.archived = 0
  AND itinerary_leg.id IS NULL
ORDER BY tracked_flight.createdAt DESC
```

### 10.6 Reorder transaction

The unique `(itineraryId, ordinal)` index means a direct swap can conflict.
Perform reorder in one transaction using temporary ordinals outside the normal
range, then assign dense `0..n-1` values. Rebuild adjacency transitions that
changed. Preserve a transition row only when its inbound and outbound leg IDs
remain adjacent in the same direction.

### 10.7 User DB migration 1 -> 2

Migration behavior:

- Create `itinerary`, `itinerary_leg`, and `itinerary_transition` with foreign
  keys and indices.
- Add non-null `tracked_flight.dateIntentSource`; map null dates to
  `NEXT_OCCURRENCE` and non-null dates to `LEGACY_UNKNOWN`. Do not guess whether
  an existing non-null date was user-entered or provider-pinned. SQLite
  mechanics: `ALTER TABLE tracked_flight ADD COLUMN dateIntentSource TEXT NOT
  NULL DEFAULT 'NEXT_OCCURRENCE'` followed by `UPDATE tracked_flight SET
  dateIntentSource = 'LEGACY_UNKNOWN' WHERE dateLocal IS NOT NULL`. Declare the
  matching `@ColumnInfo(defaultValue = "NEXT_OCCURRENCE")` on the entity so the
  exported schema's column default equals the migration's `ALTER`; the migration
  test helper compares defaults. Every insert/update path sets the column
  explicitly, so the default exists only to make the additive migration
  possible.
- Do not auto-group existing flights.
- Preserve every existing tracked-flight value unchanged apart from the new
  provenance column. Until Phase 3, any current provider pin writes
  `PROVIDER_RESOLVED_LEGACY` in the same User DB transaction. Phase 3 removes
  provider-driven writes only when its Ops replacement is active; only an
  explicit user action may change provenance to `USER_CONFIRMED`.
- Export and commit `UserDatabase/2.json`.
- Add an instrumentation migration test starting from the committed version 1
  schema with active and archived rows.
- Register `MIGRATION_1_2` in `UserDatabase.build()`.
- Add Room migration-test/schema assets, an `androidTest` source set, and CI
  managed-device/emulator execution; current CI does not run instrumentation.

There is no reason for destructive fallback. Because Room does not support
downgrade, a botched v2 migration on a production install cannot be rolled back
to v1 without clearing user data; test the migration against the committed v1
schema on the emulator before any release channel ships the bump.

### 10.8 Durable platform cleanup outbox

Lifecycle cancellation crosses Room, AlarmManager, WorkManager, and Android's
notification service. Idempotent calls alone are not crash-safe once the User DB
row containing an ID has been deleted. Bump `OpsDatabase` from version 2 to
version 3 in Phase 1 (the current schema is v2; if a concurrent change bumps it
first, use the actual current version plus one rather than hard-failing on
version constants) and add a small operational outbox:

```kotlin
@Entity(
    tableName = "platform_cleanup_task",
    indices = [Index(value = ["mutationId", "targetKind", "targetId"], unique = true)],
)
data class PlatformCleanupTaskEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val mutationId: String,
    val targetKind: String, // FLIGHT or TRANSITION
    val targetId: Long,
    val expectedTargetGeneration: String,
    val desiredUserState: String, // ABSENT or ARCHIVED
    val state: String, // PREPARED or READY
    val createdAt: Long,
)
```

For delete/archive, prepare all tasks in one Ops transaction, commit the User DB
mutation, then mark matching tasks ready and process them. On startup, reconcile
a stranded `PREPARED` task against the User DB: promote it only when the desired
state committed and, for a surviving archived target, its recorded flight-created/
transition-updated generation still matches; otherwise discard it because a
crashed process cannot later
resume that transaction. If prepare fails, do not mutate user data; if the User
DB transaction fails in-process, discard its prepared tasks. Retain `READY` until
platform cancellation completes.
The processor exposes one `cancelAllForFlight` surface covering reminders,
critical/status events, ongoing cards, position work, and legacy identities, plus
one transition cancellation surface. Restore and active-startup reconciliation
recreate only currently eligible schedules; they do not replay notifications.
Commit and test `OpsDatabase/3.json` and `MIGRATION_2_3`.

### 10.9 Operational notification state and ledger

When transition notifications ship after occurrence bindings, bump `OpsDatabase`
from version 4 to version 5 (relative to the then-current schema, not a fixed
constant) and add state, emitted-event, and ordered delivery-command rows:

```kotlin
@Entity(
    tableName = "transition_notification_state",
)
data class TransitionNotificationStateEntity(
    @PrimaryKey val transitionId: Long,
    val latestScheduledMinutes: Long?,
    val lastObservedCalculatedMinutes: Long?,
    val lastNotifiedErosionBucket: Long?,
    val hadShorteningAlert: Boolean,
    val episode: Long,
    val eligibilityFingerprint: String?,
    val lastContinuity: String,
    val lastSourceAndCertaintyFingerprint: String?,
    val updatedAt: Long,
    val expiresAt: Long,
)

@Entity(
    tableName = "emitted_transition_event",
    indices = [Index(value = ["transitionId", "fingerprint"], unique = true)],
)
data class EmittedTransitionEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val transitionId: Long,
    val eventType: String,
    val fingerprint: String,
    val emittedAt: Long,
    val expiresAt: Long,
)

@Entity(
    tableName = "notification_command",
    indices = [
        Index(value = ["state", "id"]),
        Index(value = ["targetKind", "targetId", "semanticSlot", "id"]),
    ],
)
data class NotificationCommandEntity(
    /** Monotonic ordering within the Ops DB. */
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val targetKind: String, // FLIGHT or TRANSITION
    val targetId: Long,
    val semanticSlot: String,
    val action: String, // POST or CANCEL
    val eligibilityEpisode: Long?,
    val expectedEligibilityFingerprint: String?,
    val eventType: String?,
    val invocationToken: String?,
    /** Versioned typed event payload, never an already-localized sentence. */
    val payload: ByteArray?,
    val state: String, // PENDING or DELIVERED
    val createdAt: Long,
    val expiresAt: Long,
)
```

Update planner state, dedup rows, and any `POST`/`CANCEL` commands in one Ops DB
transaction. The first
assessment after creation, restore, backup restore, Ops prune, intent change,
binding expiry/replacement, source-equivalence change, or re-entry into
eligibility starts a new episode and seeds a baseline without alerting. The
eligibility fingerprint includes transition intent, both binding IDs/generations,
the confirmed airport pair, and equivalent source/certainty identities. Keep
state/dedup through archive so restore does not replay; prune on expiry and queue
slot cancellations when transitions disappear.

A shared `PlatformSurfaceCoordinator` serializes lifecycle and planner operations
by flight/transition key and drains commands in ID order. Before a `POST`, it
re-reads User DB active state and requires the exact current eligibility episode/
fingerprint and latest desired command for that slot. Conditional `CANCEL` uses
the same check; lifecycle deletion/archive cancellation instead requires the
recorded target generation and current absent/archived state. Stale commands are
delivered as no-ops. Mark a command delivered only after the platform call; a
crash after `notify()` but before marking safely retries the same tag/ID, payload,
and immutable invocation token. This queue owns per-slot window/continuity
changes; the Phase 1 cleanup outbox remains the coarse lifecycle tombstone for
alarms, work, and all flight/transition surfaces.

Do not overload `EmittedEventEntity.trackedFlightId` with a transition ID. They
are different namespaces and have different cleanup behavior. Commit and test
`OpsDatabase/5.json` and `MIGRATION_4_5`.

### 10.10 Provider instance identity and bound snapshots

Local grouping does not require a flight-identity rewrite. Reliable live route,
time, location, and transfer guidance for newly added legs does.

The current provider layer returns candidate snapshots and discards provider
instance IDs such as FlightAware's `fa_flight_id`. Before live itinerary
guidance ships, an identity increment
should bump `OpsDatabase` from version 3 to version 4 (relative to the
Phase 1 schema) and persist, under provider-approved TTL,
an operational binding such as:

```kotlin
@Entity(
    tableName = "occurrence_binding",
    indices = [Index(value = ["trackedFlightId"], unique = true)],
)
data class OccurrenceBindingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val trackedFlightId: Long,
    val operatingDesignator: String,
    val originIata: String?,
    val originIcao: String?,
    val destinationIata: String?,
    val destinationIcao: String?,
    val resolvedDepartureLocalDate: String,
    val originalScheduledOut: Long,
    val provider: String,
    val providerInstanceId: String?,
    val reviewedAt: Long,
    val expiresAt: Long,
)

@Entity(
    tableName = "confirmed_transition_pair",
    foreignKeys = [
        ForeignKey(
            entity = OccurrenceBindingEntity::class,
            parentColumns = ["id"],
            childColumns = ["inboundBindingId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = OccurrenceBindingEntity::class,
            parentColumns = ["id"],
            childColumns = ["outboundBindingId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("inboundBindingId"), Index("outboundBindingId")],
)
data class ConfirmedTransitionPairEntity(
    @PrimaryKey val transitionId: Long,
    val inboundBindingId: Long,
    val outboundBindingId: Long,
    val airportPairFingerprint: String,
    val continuityAtConfirmation: String,
    val reviewedAt: Long,
    val expiresAt: Long,
)
```

The provider boundary must return candidates rather than discard identity:

```kotlin
data class StatusCandidate(
    val providerInstanceId: String?,
    val snapshot: StatusSnapshot,
    val capabilityFingerprint: String,
)
```

The same migration adds `confirmed_transition_pair` plus nullable, indexed
`occurrenceBindingId` and an adapter-capability fingerprint to `status_snapshot`.
Existing snapshots migrate as unbound and are never eligible for connection
calculations. Candidate review writes the binding and its reviewed snapshot
atomically; transition review then writes the pair from those binding IDs. Every
later status write must retain provider instance identity, prove that it belongs
to the active binding, and store that binding ID. A rebind creates a new binding
generation and cannot reuse old snapshots. Make all binding references same-Ops-
DB foreign keys with `ON DELETE CASCADE`; rebind atomically removes the old
binding, bound snapshots, and affected pair before inserting the new generation
plus reviewed snapshot. The User DB transition ID cannot have a cross-database
foreign key, so lifecycle cleanup and orphan pruning remain mandatory.

Commit and test `OpsDatabase/4.json` plus `MIGRATION_3_4`. The operational model
contains:

- Tracked flight ID.
- Operating designator.
- Origin and destination codes.
- Resolved departure-local date, separate from portable user date intent.
- Original scheduled `OUT` instant.
- Provider and provider instance ID.
- Expiration and provenance.
- Transition-to-binding pair fingerprint and confirmation continuity under the
  minimum provider-approved TTL.

This matches `PLAN.md`'s canonical occurrence direction and prevents revised
times from creating a new identity. Add a quota-aware candidate resolver that
can retain an allowed draft result long enough to avoid an immediate duplicate
post-save lookup. Grouping can ship locally first, but connection guidance and
notifications remain suppressed until both legs have confirmed occurrence
bindings. Existing tracked rows require a route/time review before they are
treated as confirmed for this purpose.

Replace the current unqualified `latest(trackedFlightId)` read for connection
use with a query requiring the active `occurrenceBindingId`, `expiresAt >= now`,
and an approved gate-time capability fingerprint. A newer snapshot from an
ineligible provider must not hide an older-but-current eligible snapshot. General
flight UI may use a different explicitly named projection.

### 10.11 Backup and export

The three new user tables are automatically eligible for the current include-
only backup because the whole `blipbird-user.db` file is included. Android does
not guarantee that a user's device/provider will perform a backup. Update privacy
copy and future export allowlists to include:

- Itinerary name.
- Leg order and tracked-flight references.
- Transition intent and booking arrangement.
- Baggage plan.

Do not export operational snapshots, provider IDs, MCT cache rows, notification
fingerprints, keys, or official-link fetch cache.

Export is still a roadmap item, not an existing mitigation. Before itinerary
release, provide at least a clear local delete-all-itinerary path and update
README/in-app backup disclosures. When export lands, apply the allowlist above.

## 11. Architecture and state flow

### 11.1 Components

```text
Compose
  FlightListScreen
  ItineraryEditorScreen
  ItineraryDetailScreen
  TransitionCard / DirectConnectionCard
        |
ViewModels
  FlightListViewModel (home projection)
  ItineraryEditorViewModel
  ItineraryDetailViewModel
        |
Domain
  TransitionEngine
  TransitionNotificationPlanner
  ItinerarySuggestionPolicy
  ItineraryProgressPolicy
  ConnectionRecencyPolicy
        |
Use-case coordination
  FlightLifecycleCoordinator
  StatusRefreshCoordinator
  ProviderCapabilityRouter
  PlatformSurfaceCoordinator
        |
Data
  ItineraryRepository ---------------- UserDatabase
        |                                  itinerary
        |                                  itinerary_leg
        |                                  itinerary_transition
        |
        +------------------------------- FlightRepository / OpsDatabase
                                           occurrence-bound snapshots/fixes/tracks
                                           capability router, quota, cleanup outbox
        |
        +------------------------------- AirportGuideRegistry
        |
        +-- optional later ------------ MinimumConnectionTimeProvider
        +-- optional later ------------ AirportWayfindingProvider
```

### 11.2 Dedicated repository

Create `ItineraryRepository` rather than growing the already large
`FlightRepository`.

`ItineraryRepository` responsibilities:

- User DB transactions and aggregate invariants.
- Ordered membership and transition-edge maintenance.
- Combining membership with per-flight snapshot flows.
- Airport normalization through `ReferenceDao`.
- Calling the pure transition engine for foreground read models.
- Exposing stable aggregate/transition IDs.

Do not make this repository own Ops cleanup, alarms, WorkManager, or notification
cancellation. Extract internal `TrackedFlightStore` and
`OperationalFlightCleanup` primitives from the large `FlightRepository`, then
put cross-database/platform sequencing in a higher `FlightLifecycleCoordinator`.
Only User DB changes can be atomic.

For destructive aggregate deletion, collect affected flight/transition IDs,
prepare durable cleanup tasks and delete ordinary Ops rows in one Ops transaction,
commit the User DB delete, mark the tasks ready, then cancel platform surfaces.
Startup resolves every prepared/ready task against current User DB state. A crash
may leave tracked intent that can refetch, but must not strand permanent Ops rows,
alarms, or posted notifications. Archive retains normal Ops data but uses the same
prepared-task protocol for platform cancellation; restore runs active-state and
schedule reconciliation.

### 11.3 Creation flow

1. Parse and identity-complete every draft designator in memory; retain the
   serialized `draftId` as the creation request ID.
2. Reject/annotate invalid rows before write.
3. In one `userDb.withTransaction` block:
   - Return the existing graph if that unique creation request ID already exists;
     otherwise insert `ItineraryEntity`.
   - Insert any new `TrackedFlightEntity` rows.
   - Insert ordered `ItineraryLegEntity` rows.
   - Insert one `ItineraryTransitionEntity` per adjacent pair with the user's
     confirmed intent or `UNKNOWN`.
4. Commit.
5. Ask `StatusRefreshCoordinator.reconcileSchedule()` to choose periodic,
   dormant one-time, or no network work.
6. Submit approved, occurrence-aware per-leg work through one
   `StatusRefreshCoordinator`, sequentially or at a small concurrency limit.
7. Recompute the foreground UI locally as snapshots arrive. Suppress live
   connection guidance until occurrence bindings are confirmed.
8. Request notification permission through the existing flow after successful
   creation, not before.

If the process dies after commit, retrying Save returns the same graph and startup
schedule reconciliation supplies any missed post-commit work. If refresh fails,
the itinerary remains and each leg exposes its lookup problem.

### 11.4 Observation flow

`ItineraryDetailViewModel` observes:

- One itinerary graph from User DB.
- Active occurrence binding and latest non-expired, capability-eligible bound
  snapshot for each member flight (plus the separately named general-flight
  projection where needed).
- Latest lookup attempt for each member flight.
- One shared clock/heartbeat.
- Optional guide links from the bundled registry.

It maps the small set of `n - 1` transitions through `TransitionEngine`. It
should not persist derived windows. Recomputing every transition when one
snapshot emits is simple and cheap for real itineraries; do not add memoization
until profiling justifies it.

Avoid one independent timer per row/connection. Reuse one screen-level heartbeat
and inject a clock into domain policies as planned in `PLAN.md`.

### 11.5 Home projection

Replace the single flat `rows` projection with typed items:

```kotlin
sealed interface HomeItem {
    data class Itinerary(val row: ItineraryHomeRow) : HomeItem
    data class Flight(val row: FlightRow) : HomeItem
}
```

Alternatively expose two lists in `ListUiState` to keep section rendering
explicit. Two lists are simpler and recommended:

```kotlin
data class ListUiState(
    val itineraries: List<ItineraryHomeRow> = emptyList(),
    val ungroupedFlights: List<FlightRow> = emptyList(),
    // existing fields...
)
```

These are two state collections, not two nested scroll containers. Emit both into
the existing single adaptive grid with full-span section items.

Do not flatten itinerary legs into the existing global sort.

Sort active resolved itineraries by next incomplete event. Sort unresolved
itineraries by earliest user-entered departure-local date, then creation time.
Place fully completed itineraries after active ones until archived. Never let a
moving estimate reorder legs inside an itinerary.

### 11.6 Navigation

Extend routes:

```kotlin
sealed interface Screen {
    data object List : Screen
    data class FlightDetail(val flightId: Long) : Screen
    data class ItineraryDetail(
        val itineraryId: Long,
        val focusTransitionId: Long? = null,
    ) : Screen
    data class ItineraryEditor(
        val draftId: String,
        val itineraryId: Long?,
    ) : Screen
    data object Settings : Screen
    data object Archived : Screen
}
```

Before adding positive itinerary IDs, replace the untagged `Long` saver with a
tagged representation. A simple versioned string token is sufficient:

```text
v1:list
v1:flight:42
v1:itinerary:7
v1:itinerary:7:transition:19
v1:itinerary-editor:draft-uuid:new
v1:settings
v1:archived
```

Keep the current `Screen`-keyed ViewModel stores and adopt a strict navigation
policy that an equal route is replaced/focused rather than pushed twice. This is
smaller than adding `NavEntry` identity; if unique entries are introduced later,
their IDs and next-ID source must also be serialized or configuration recreation
will lose stores and leak old ones. Decode the existing legacy `Long` format as
well as new tagged tokens, validate IDs, and fall back to Home for malformed or
future tokens. This does not force a Navigation 3 migration.

An activity-level `ItineraryDraftStoreViewModel` owns draft content in its
`SavedStateHandle`, keyed by the serialized `draftId`. Per-screen editor
ViewModels read/write through that store. This is intentionally separate from
the current route-scoped `ViewModelStore`, which survives configuration change
but does not by itself serialize a per-entry SavedStateRegistry bundle for
process death.

### 11.7 Flight-detail context

Keep `FlightDetailViewModel` keyed only by flight ID. The back stack already
retains the itinerary route, and unique flight membership lets a lightweight
navigation query derive itinerary/previous/next context from `flightId`. This
permits previous/next leg actions without putting the entire aggregate into the
operational flight state or relying on unpersisted "outside" context.

### 11.8 Airport guide registry implementation

Recommended repository layout:

```text
app/src/main/assets/reference/airport_guides.json
scripts/validate_airport_guides.py
```

The checked-in JSON is human-reviewed and consumed through a small
`AirportGuideRegistry` interface. Include source URL, publisher, locale, format,
topic, accessibility note, and verification date. A validation script/test runs
in CI. Do not fetch a mutable global registry at runtime for the MVP.

### 11.9 Itinerary progress policy

Do not reuse `FlightPhaseMachine` as proof that the traveler reached a gate. Add
a pure `ItineraryProgressPolicy` that returns separate facts:

- **Leg display progress:** upcoming, active, landed-not-at-gate,
  arrived-at-gate, terminal-unconfirmed, cancelled, or unknown.
- **Airport occupancy:** confirmed only by actual gate `IN`; runway `ON` or
  provider `LANDED` can label the flight but cannot establish occupancy.
- **Transition phase:** future, imminent, at-connection-airport,
  onward-departed, normally-completed, disrupted, or unresolved.
- **Whole-itinerary progress:** active, terminal-with-unconfirmed-arrival, or
  completed from the final leg's actual gate `IN`.

`Imminent` begins under one tested configurable policy when the inbound leg is
active or falls inside the existing near-term cadence horizon; it ends on onward
actual `OUT`, cancellation, binding expiry, or loss of eligible data. Normal
transition completion still requires actual inbound `IN` and onward `OUT` with
`OUT >= IN`. If gate actuals never arrive, terminal provider state plus a passed
time/grace may move Home to the next actionable leg or a
`terminal-unconfirmed` summary, but copy must retain `Gate arrival not reported`
and never claim normal completion or airport occupancy. A later leg's actual
`OUT` may make that leg current while the preceding transition remains an
exception. Cancellation/diversion produces terminal/disrupted progress rather
than a successful connection.

## 12. Refresh, quota, and offline behavior

### 12.1 Reuse existing per-flight refresh

An itinerary does not need a second poller. The current worker already loops
over active tracked flights, but the existing refresh entry points are not yet a
safe coordinator: worker/manual/detail calls can overlap, backoff is checked only
when no snapshot exists, and terminal unarchived flights keep the periodic
worker alive even after all fetches are skipped.

Add one `StatusRefreshCoordinator` used by worker, manual refresh, detail,
composer, and post-create work. It must:

- Coalesce concurrent work by tracked occurrence.
- Accept a set-based `refreshBatch(flightIds, reason)` and assign a generation.
  Serialize overlapping connected-flight batches, or defer transition evaluation
  until every in-flight generation touching either bound leg settles. Evaluate
  from one committed snapshot-version vector and recheck it in the planner-state
  transaction so a worker/manual race cannot compare one new side with one old
  side.
- Check lookup backoff for missing and existing snapshots.
- Separate an explicit user retry from normal cadence without bypassing provider
  `Retry-After`, disabled credentials, or spend stops.
- Classify authentication/rate-limit failures before provider failover according
  to approved policy; the current chain must not route around forbidden states.
- Bound new-itinerary concurrency.
- Reconcile reminders after successful writes.
- Return a scheduling state per flight: `DUE`, `DORMANT_UNTIL(Instant)`, or
  `TERMINAL`. A Boolean eligible/ineligible result is insufficient because a
  distant flight and a permanently finished flight both currently produce no
  cadence.
- Keep/rearm periodic work while something is due. If only dormant/backed-off
  flights remain, cancel the periodic wake and enqueue one unique one-time wake
  for the earliest future cadence/backoff boundary. Cancel network work only when
  every flight is terminal.

Rules:

- Never create one worker per itinerary or connection.
- Never refetch status merely to redraw a connection card.
- Recompute connection windows from snapshots already written for each leg.
- Refresh each side according to its own phase, due time, and backoff.
- A visible `Refresh` action delegates to the coordinator; pull-to-refresh is not
  the only path.
- Grouping existing flights causes zero immediate network calls unless the user
  explicitly refreshes or data is absent/due.
- Run retention pruning from app startup and network-unconstrained maintenance;
  the current network-constrained refresh worker must not be the only pruner.
- Carry operational `expiresAt` through repository read envelopes and enforce it
  in every UI/notification assessment even if physical cleanup is delayed. A
  one-time maintenance wake at the earliest relevant expiry can trigger cleanup.

The current ordered provider chain returns on the first `Found`, and
`InstanceSelector.select()` then picks one candidate (by operational priority)
before persistence — only one snapshot row is written per refresh. The DAO
`latest(flightId)` query only resolves which historical row is newest across
past refreshes. That pipeline is incompatible with a connection-only capability
gate: a later general-status refresh can overwrite a previously eligible row.
Introduce a `ProviderCapabilityRouter` before
dispatch:

- Each adapter declares rights-approved use cases, gate/runway families,
  certainty support, provider-instance identity support, and an adapter contract
  version.
- Choose one approved source for the requested use case before issuing a billable
  request. Do not fetch AeroDataBox, discard it as connection-ineligible, and
  then bill a fallback.
- Preserve provider candidate instance IDs through selection and persistence.
- For connection evaluation, read only the active binding and a compatible
  capability fingerprint; a later general-status write cannot replace that
  projection.
- A provider/capability switch starts a new notification eligibility episode and
  silently seeds state unless equivalence is explicitly tested.

`BlipbirdApp` startup, every lifecycle mutation, and every refresh completion call
`reconcileSchedule()` rather than the old unconditional `RefreshWorker.schedule()`
(`BlipbirdApp.onCreate` → `RefreshWorker.schedule(this)`).

### 12.2 Connection-aware priority

A later optimization may prioritize, but not multiply, work:

- When an inbound flight is active and an onward flight in a confirmed direct
  connection is imminent, process
  those due legs before distant itinerary legs.
- After actual inbound gate `IN`, allow one due check of the onward leg for terminal/
  gate changes, still respecting provider quota and backoff.
- Do not bypass `Retry-After`, authentication disablement, or spend stops.
- Coalesce codeshares only after canonical physical-instance identity exists.

### 12.3 Quota impact

Connection arithmetic is free, but tracking more legs increases status requests
roughly in proportion to the number of legs. The UI should not hide that:

- The quota ledger remains provider-wide.
- The composer can show `3 flights will be tracked` before save.
- Do not issue billable candidate previews on every keystroke.
- Parse locally; resolve only on an explicit review action, then reuse a permitted
  draft result rather than immediately billing a duplicate post-save lookup.
- Cache allowed results under existing retention terms.
- Anchor the spend model on public rate cards (checked 2026-07-24, volatile):
  FlightAware AeroAPI includes $5 of queries free per month ($10 for ADS-B
  feeders), then per-result-set fees (for example `GET /flights/{ident}` at
  $0.005 per 15-record result set), and its Standard tier — the first tier with
  B2C derivative-work rights — carries a $100/month minimum. A BYO key is
  therefore a real monthly commitment, not a free-tier gesture; spend stops and
  visible leg counts are not optional.

### 12.4 Offline state machine

| State | Behavior |
|---|---|
| User data available, no snapshots | Show itinerary shell and the specific provider/legal/configuration state |
| Online, recently fetched snapshots | Show latest calculated windows with both retrieval ages |
| Known offline, recently fetched snapshots | Show `Offline; showing data fetched at ...` plus both ages |
| Last refresh failed, cached snapshots remain | Show `Refresh failed; showing data fetched at ...` plus outcome |
| Old cached snapshots | Show arithmetic with `Last known`/`Stale fetch`; suppress change alerts |
| Snapshots expired/pruned | Keep itinerary; live transition detail becomes unresolved until refresh |
| Official link offline | Keep action focusable; activation explains network requirement |
| Licensed offline map unavailable | Never imply route availability |

Connectivity/last-refresh outcome is orthogonal to retrieval age. A recently
fetched snapshot does not justify online/live wording after a known offline or
failed request; clear that banner only after a successful refresh.

### 12.5 Historical summaries

The first release does not need to persist provider-derived connection history.
If a future flight log wants actual connection windows after snapshot TTL, add a
rights-cleared summary policy first. User-authored itinerary structure can remain
indefinitely; provider-derived actual times cannot be copied into the backup-safe
DB merely for convenience.

An actual gate `IN` or `OUT` is a final observed milestone, so cadence age alone
does not make the value semantically stale. It remains usable, with source and
fetch time shown, until its operational retention expiry. Estimates/schedules
still require the retrieval/provider-recency policy. This lets an inbound actual
`IN` pair with a recently fetched onward estimate without pretending an old
estimate is current.

After Android restore, Ops snapshots, transition-notification state, and the
quota ledger are absent. Seed notification baselines silently, refresh restored
multi-leg itineraries conservatively because local quota estimates reset on a
new device, and never interpret absent Ops data as a new disruption. The
connection-alert setting lives in backed-up settings and may restore enabled, so
silent baseline seeding is mandatory rather than optional.

## 13. Notifications and Android surfaces

### 13.1 Background evaluation and pure planner

An `ItineraryDetailViewModel` is not a background detector. After the refresh
coordinator finishes a worker, manual, detail, editor, or post-create batch, it
finds transitions affected by those flight IDs, waits for every overlapping batch
that touches either bound leg, evaluates each pair once from one committed bound-
snapshot version vector, and updates notification state in Ops DB. Foreground
calls may suppress heads-up emission, but must still advance planner state. This
avoids a transient erosion alert when both legs change in the same or overlapping
batches.

Add a planner separate from `NotificationPlanner`:

```kotlin
object TransitionNotificationPlanner {
    fun diff(
        state: TransitionNotificationState,
        current: TransitionAssessment,
    ): NotificationDecision
}
```

Potential event types:

- `WINDOW_SHORTENED`
- `WINDOW_RECOVERED`
- `AIRPORT_PAIR_NO_LONGER_MATCHES`

Timing events require direct-connection intent, two non-expired confirmed
bindings, a confirmed same-airport pair, current eligible snapshots matching that
pair, and an imminent/active phase. The continuity event instead requires the
same unchanged bindings and previously matching pair plus two recently fetched
current endpoints that no longer match. Binding replacement/expiry is pending
confirmation, not a continuity event. Explicit cancellation/diversion wins and
suppresses the transition duplicate. Terminal assignment/change is not promised
here; existing gate changes and cancellation/diversion remain flight events.

### 13.2 Material-change policy

Recommended beta policy:

- The first recently fetched assessment seeds state and emits nothing.
- First window-shortened alert at 15 minutes of erosion relative to the latest
  scheduled baseline.
- Further alerts in 15-minute buckets.
- Recovery alert only after at least one shortened alert and a full bucket of
  improvement.
- Airport-pair mismatch alert only when both occurrence bindings and their
  non-expired operational pair were previously confirmed and recently fetched
  status now reports a different pair.
- Intent, binding generation, confirmed-pair, or source-equivalence changes start
  a new episode, cancel superseded visible slots, and seed silently after
  eligibility returns.
- No alert when required data is stale or one side is unresolved.
- No alert for small schedule jitter.
- No alert on provider/source or certainty regression.
- No timing alert unless the transfer is imminent/active under a tested phase
  policy; destination stays and unknown transitions never alert.
- Fingerprints include transition ID, eligibility episode, event type, and new
  bucket/state.
- Fingerprints include an episode/generation so recovery followed by renewed
  erosion into a previously seen bucket can alert again.
- Planner state records baseline, last observed value, last notified bucket, and
  whether a shortening alert occurred; an emitted-event ledger alone cannot
  implement recovery correctly.

The 15-minute bucket is a notification-noise policy, not a claim about whether
15 minutes matters operationally.

### 13.3 Deep links

Add typed extras or a versioned route URI:

```text
blipbird://itinerary/7/transition/19?event=<immutable-ledger-id-or-token>
```

Use this URI as `Intent.data` on the explicit internal PendingIntent so Android
identity includes the destination; extras alone do not. A manifest intent filter
is unnecessary unless external invocation is intentionally supported.

Cold and warm launch validate that the transition still belongs to the
itinerary, open it, and scroll/focus the transition. On cold launch or a clean
warm stack, reset navigation explicitly to `[Home, target]`. If a warm launch
finds a dirty editor, keep `[Home, editor, target]` so Back resumes the draft;
the activity-level draft store also exposes `Resume draft` if navigation must be
rebuilt. Save the full immutable notification invocation token across process
death, not only the route target: a later notification for the same transition
must not be mistaken for process-death redelivery.

### 13.4 Notification identity

The current `NotificationEmitter` calls `notify(stableId(flightId, channel), …)`
— a single Int ID namespace with no tag. Migration to tagged slots is a real
change, not a renaming.

Use `NotificationManagerCompat.notify(tag, id, ...)` with fixed semantic slots:
`transition:<id>:window` and `transition:<id>:continuity`, each with a fixed ID.
Do not depend on a hash being collision-free across flight and transition
namespaces, and do not use immutable ledger IDs as notification slots. Recovery
replaces the window slot. A continuity mismatch cancels/supersedes the timing
slot; restored continuity cancels its slot and silently reseeds timing. Give each
PendingIntent the unique data URI above, including its immutable event token, and
a stable, update-safe policy.

Register a dedicated Android notification channel for transition events,
alongside the existing `critical`, `status`, `reminders`, and `ongoing`
channels, so the OS-level importance control and the in-app itinerary category
toggle cannot drift apart.

Deleting or reordering an itinerary must collect removed transition IDs before
foreign-key cascades, prepare durable outbox tasks, cancel posted semantic slots,
and delete their ordinary operational rows. Loss of eligibility, completion, or
reclassification also cancels superseded slots. Archiving cancels visible
notifications but retains state/dedup until expiry so restore does not replay old
events. A tap on a stale/removed transition falls back to Home with an announced
`Connection no longer available` message.

As part of Phase 1 identity hardening, give reminder PendingIntents versioned per-
flight/per-kind data URIs and move flight notifications to
`flight:<id>:<semantic-slot>` tags with fixed IDs. Every flight notification
content PendingIntent uses `Intent.data` such as
`blipbird://flight/<id>/<slot>?event=<immutable-ledger-id-or-token>`; request-code
hashes or extras alone are not identity. Activity redelivery state persists and
compares the full immutable invocation token, so a later event for the same
flight remains actionable. Cancel legacy hashed notifications and PendingIntents
for known rows and reconcile active posted notifications during migration; alarm
receivers still re-read the User DB as defense in depth.

### 13.5 Ongoing notification and Live Update

Keep the existing per-flight ongoing card in the first itinerary release. It is
leg-scoped and avoids a broad notification migration; its `LANDED` state must not
be reused as evidence of connection-airport occupancy.

A later itinerary-level Live Update can replace it only when:

- The journey is imminent or active, satisfying Android relevance guidance.
- It can transition from current flight to connection to next flight without
  duplicate ongoing cards.
- The migration explicitly cancels old per-flight IDs.
- API 36 `ProgressStyle` degrades cleanly below API 36.

Candidate connection-phase copy:

```text
Frankfurt connection
1 h 08 min until reported onward departure
Next flight reported at Terminal 1, Gate B42
Boarding or gate closing may be earlier
```

Do not request promoted ongoing treatment days before travel.

### 13.6 Reminders

Do not add exact alarms for calculated connection risk in the MVP. Existing
boarding and landing-soon reminders remain leg-scoped. A future local reminder
such as `Leave lounge by...` would need an explicit user time and should be
clearly separate from provider-derived feasibility.

## 14. Privacy, security, licensing, and retention

### 14.1 Privacy posture

The feature preserves Blipbird's no-account/no-backend architecture in its first
three tiers.

Stored user data:

- Itinerary name.
- Flight designators and dates.
- Leg order.
- Transition intent and booking-arrangement answer.
- Baggage plan answer.

Provider-derived occurrence endpoints and their airport-pair fingerprint stay in
the excluded Ops DB for the approved TTL; they are not user data or backup data.

Do not collect:

- PNR or ticket number.
- Passenger name.
- Email confirmations.
- Passport/nationality/document scans.
- Precise indoor position.

If Android backup is enabled, user-authored itinerary facts can leave the device
through the OS backup provider just like current tracked flights/settings. Update
the privacy disclosure accordingly.

### 14.2 Network disclosure

After explicit occurrence review, creating or resolving an itinerary can cause
several per-leg status lookups. The existing disclosure should clarify that each
configured provider receives each leg's designator/date. Opening official
airport, government, airline, or map links sends ordinary browser request
metadata to those publishers.

### 14.3 Commercial credentials

Secret-bearing MCT, booking, or indoor-map APIs cannot be made secret by placing
credentials in a public APK. Acceptable paths are:

- Provider-approved public/mobile token with restricted scope.
- User-supplied credentials under terms that explicitly permit this client.
- A Blipbird backend with a new threat model, privacy policy, operations budget,
  account/rate-limit design, and explicit project decision.
- No integration.

Do not introduce a backend implicitly as an SDK implementation detail.

### 14.4 Provider rights gates

For every current or new provider, record in an ADR before live itinerary display
or derived notifications:

- Direct mobile and open-source distribution permission.
- Display and normalization rights.
- Cache/retention TTL.
- Derived-result and notification rights.
- Offline and backup restrictions.
- Test-fixture permission.
- Attribution location and wording.
- Pricing, hard/soft stops, and aggregate traffic limits.
- Personal/noncommercial restrictions.
- Intended-use and prohibited high-consequence/safety-critical-use scope for
  connection guidance and alerts; an app disclaimer does not grant permission.
- Every incorporated distributor, marketplace, and plan-specific agreement,
  including the actual delivery path used by the app.
- Termination and data-deletion requirements.

In particular, AeroDataBox's public terms restrict permanent copies,
modification/derivatives, third-party display/distribution, and credential
disclosure unless another agreement permits them. A BYO key does not resolve
that conflict. Its incorporated distributor/marketplace terms and intended-use
limits must also be cleared for connection guidance and alerts. Obtain separate
written permission plus approval of the actual delivery path used, or keep the
integration and dependent live itinerary features disabled in release builds.
Note that the terms (Appendix A, November 4, 2025 revision) name **two**
authorized distributors — Nokia–RapidAPI and API.Market — each incorporating
its own marketplace agreements, while the site additionally advertises access
paths not listed in Appendix A; the approval must cover the specific path
Blipbird ships. The same terms prohibit safety-critical use (Article 5.2.a,
including real-world navigation and flight-operations applications), describe
the service as enthusiast-driven and best-effort (Article 4.2), and state the
data "must not be used as a substitute for official aviation data sources"
(Article 4.4) — Blipbird's factual, confirm-on-official-displays posture is
compatible with that framing, but it is not a substitute for written intended-
use confirmation. Apply the same specific review to FlightAware and every
fallback.

### 14.5 MCT rights

Buying SSIM documentation is not equivalent to licensing current MCT data.
Buying an MCT feed is not automatically permission to redistribute it in an APK.
Prefer an operational lookup/cache model unless the contract explicitly permits
bundling. Keep source rules out of diagnostics/export and persist only the
minimum matched result for the allowed TTL.

### 14.6 Map rights

- MapLibre's license covers rendering code, not venue data or tiles.
- OSM data requires attribution and ODbL analysis for derived databases.
- The standard `tile.openstreetmap.org` service does not permit offline prefetch;
  that policy does not automatically govern every OSM-derived/self-hosted
  provider.
- OSM's separate vector tile service (`vector.openstreetmap.org`) has its own
  usage policy, and it likewise prohibits bulk downloading and offline packs.
  Offline airport maps require self-hosted tiles or a provider whose terms
  explicitly permit offline use.
- Official airport map pages may be linked; copying map images requires separate
  permission.
- Commercial indoor maps require venue and SDK terms, not just an API key.

### 14.7 Safety/trust language

Keep the app-wide `not for navigation or operational use` disclosure, but do not
use it as permission to make unsupported claims. Product wording itself must be
accurate.

Before release, update README and in-app disclosures and provide a clear local
delete-all-itineraries action. Export remains a future feature until its
allowlisted implementation exists.

## 15. Implementation plan

This should ship as reviewable vertical slices, not one large branch.

### Phase 0: product/legal decision and test infrastructure

Deliverables:

- Accept this proposal or record changes.
- Create an ADR for neutral transitions and factual connection windows without
  MCT risk labels. Use the existing `docs/decisions/TEMPLATE.md` format; this
  and the provider-rights ADRs below are the first entries in `docs/decisions/`.
- Decide the initial official-airport registry scope.
- Update the glossary with itinerary, leg, transition, destination stay,
  connection window, MCT, self-transfer, and surface transfer.
- Replace the `PLAN.md` bundled-MCT assumption.
- Close or explicitly fail the existing status-provider rights gates for display,
  normalization, retention, intended use, incorporated marketplace terms, and
  derived connection output/alerts. Local grouping can proceed if live rights
  fail; live windows cannot.
- Add Room migration-test and Compose/instrumentation dependencies, schema
  assets, an `androidTest` tree, and CI managed-device/emulator execution.
  This is a non-trivial CI addition: the current `.github/workflows/ci.yml`
  has no emulator job, so an API-level matrix, shard count, startup timeout,
  and `runs-on` selection must all be added before instrumentation tests can
  gate merges.
- Run a small copy/comprehension prototype before fixing the visual hierarchy.

Exit criteria:

- Product copy and data/legal boundary are approved.
- No implementation depends on an unlicensed MCT source.
- Live scope names exactly which status provider/fields have approved rights.
- User DB migration tests can run in CI before a schema is changed.

### Phase 1: persistence, lifecycle, and navigation foundation

This phase is large. Ship it as at least two reviewable slices:

- **1a — Schema and lifecycle:** entities, DAO, enums, User DB migration,
  repository, lifecycle coordinator, cleanup outbox, and their tests.
- **1b — Notification identity and navigation:** versioned data URIs, semantic
  tags, legacy cleanup, tagged route saver, and navigation tests.

1b can proceed in parallel with 1a once the cleanup-outbox interface is fixed,
since it touches different files (`NotificationEmitter`, `ReminderScheduler`,
`MainActivity`).

Deliverables:

- `ItineraryEntity`, `ItineraryLegEntity`, and
  `ItineraryTransitionEntity` with composite graph constraints and a unique
  creation request ID.
- `ItineraryDao`, tracked-flight date-intent provenance, and User DB migration
  1 -> 2.
- `TransitionIntent`, `BookingArrangement`, `BaggagePlan`, and
  `DateIntentSource` enums.
- `ItineraryRepository` User DB graph transactions.
- `TrackedFlightStore`, `OperationalFlightCleanup`, and
  `FlightLifecycleCoordinator` mutation gateway.
- A minimal `StatusRefreshCoordinator` facade with `reconcileSchedule()` used by
  startup/lifecycle/create paths; initially it preserves current refresh behavior
  while removing unconditional scheduling call sites.
- Group-aware active/archive/delete/reminder/ongoing-notification lifecycle.
- Ops DB 2 -> 3 durable platform-cleanup outbox, startup processing, and one
  complete `cancelAllForFlight` surface.
- Versioned reminder and flight-notification content data URIs, immutable flight-
  event invocation tokens, semantic flight notification tags, and legacy hash-
  identity cleanup.
- Tagged route saver with legacy decode and duplicate-route replacement policy.
- Migration, graph, crash-ordering, archive, and navigation tests.

Exit criteria:

- Existing installs migrate with all flights unchanged.
- Dateless ungrouped tracking retains current occurrence-pinning behavior until
  Phase 3 replaces it, with interim pins explicitly marked provider-resolved.
- Ordered aggregates and transitions survive restart with exactly `n - 1`
  non-branching edges.
- Existing direct flight mutation paths cannot delete/archive a member behind the
  aggregate gateway.
- A crash at every delete/archive boundary leaves either unchanged user intent or
  a durable, retryable cleanup task; no alarm or posted-notification ID is lost.
- Navigation restores itinerary/editor routes after process death and existing
  flight deep links still work.

### Phase 2: minimal local itinerary vertical slice

Deliverables:

- Full-screen editor with at least two legs, one departure-airport date per leg,
  transition intent, paste-to-draft, and visible accessible move controls.
- Activity-level, `draftId`-keyed saved-state restoration and Back confirmation.
- Bounded draft/paste sizes and idempotent create retries keyed by `draftId`.
- Group existing flights without network calls.
- One-transaction local save.
- Home itinerary/ungrouped sections and a basic journey-spine detail that shows
  only user-owned designators, dates, order, and transition intent.
- `Past trips and flights` grouped archive.
- Archive Undo and explicit keep/delete-flights confirmation.

Exit criteria:

- A two-leg overnight itinerary can be created and displayed end to end without
  a provider.
- Invalid rows cannot cause a partially persisted itinerary.
- A week-long destination stay and a direct connection render differently.
- No local-only screen promises route, arrival date, timing, or terminal facts it
  does not have.
- Null/legacy dates require explicit user confirmation, and Phase 2 ordering uses
  no provider-derived snapshots.
- Local mutation failures preserve the draft/last graph, announce failure, and
  never show false success.

### Phase 3: occurrence identity and refresh coordination

Deliverables:

- Stable occurrence binding with provider IDs where allowed and a canonical
  operating-designator/origin/destination/original-OUT fallback.
- Ops DB 3 -> 4 migration and committed schema for `occurrence_binding`,
  `confirmed_transition_pair`, and binding/capability columns on status snapshots;
  migrated snapshots are unbound.
- Switch provider-resolved date pinning from User DB to the Ops occurrence
  binding in this same release; no intermediate build removes pinning without a
  replacement.
- Quota-aware candidate resolver and route/time review for existing and new
  rows; ambiguous same-day/multi-leg results require user choice.
- Expand the Phase 1 `StatusRefreshCoordinator` so worker, manual refresh, detail,
  editor, and post-create work all use it.
- `ProviderCapabilityRouter` that selects one rights-approved, gate-capable source
  before dispatch and retains provider instance identity.
- Set-based batch generations, overlapping-pair evaluation barriers, coalescing,
  complete backoff enforcement, bounded concurrency, provider-error routing
  policy, due/dormant/terminal scheduling with one-time wakeups, network-
  independent pruning, `expiresAt` read enforcement, and terminal-worker
  shutdown.
- Provider capability/contract fixtures for gate `IN/OUT` versus runway
  `ON/OFF` and source/certainty changes.

Exit criteria:

- A leg cannot silently drift to another occurrence after confirmation.
- Every connection snapshot is bound to the active occurrence generation; a
  newer ineligible/unbound provider row cannot replace it.
- An unresolved or ambiguous leg keeps the local itinerary but suppresses live
  transfer guidance.
- Missing/expired/replaced occurrence bindings or a missing confirmed pair
  produce `Connection pending route confirmation`. A current mismatch under
  unchanged bindings produces the distinct continuity disruption.
- Concurrent refresh entry points issue one allowed request per occurrence and
  respect provider `Retry-After`, auth disablement, and spend stops; overlapping
  two-leg batches cannot evaluate mixed generations.

### Phase 4: live transition semantics and UI

Deliverables:

- Pure `TransitionEngine`, `ItineraryProgressPolicy`, and structured retrieval-
  recency policy.
- Compact itinerary card with current leg/next confirmed transfer focus.
- Direct-connection cards with latest scheduled/calculated windows, endpoint
  basis/source/ages, booking/bag questions, and neutral reported-location copy.
- Destination-stay, surface-transfer, unknown, overlap, stale, provider-error,
  cancellation, explicit-diversion, and already-departed states.
- Flight-detail membership context.
- Existing-style adaptive Home/archive grids, responsive single-pane editor/
  detail, 200% text reflow, TalkBack semantics, visible keyboard/switch actions,
  and High Contrast/Cockpit variants.
- Provider capability gates: AeroDataBox windows stay disabled until its
  revised/predicted/runway family and actual/estimated mapping is corrected and
  contractually confirmed.

Exit criteria:

- User order remains stable under simulated delays.
- Current leg/next confirmed transfer are visually and semantically primary.
- Actual airport-arrival/departure states require actual gate `IN/OUT`, not phase
  guesses.
- Direct connections require same-airport compatibility; surface transfers
  require different-airport compatibility; conflicts suppress interpreted output.
- No available state uses unsupported safe/tight language.
- Comprehension tests show that users can explain the duration, input ages,
  non-guarantee, and reported-versus-routed location distinction.

### Phase 5: official guidance registry

Deliverables:

- Reviewed airport-scoped JSON source and validation script/test.
- Registry parser and validation tests.
- Initial small set of major airports with official connection/map/
  accessibility links.
- A declared coverage list used by live-release copy and screenshots.
- Broken/stale-link maintenance process.

Exit criteria:

- Every `Official` action resolves to a reviewed publisher URL.
- Labels name publisher, destination, format, and browser handoff.
- Missing coverage degrades without a fake official label; unreviewed search is
  visually and semantically separate.

### Phase 6: transition notifications

Deliverables:

- Coordinator-integrated transition evaluation after worker and foreground
  refresh batches.
- Pure `TransitionNotificationPlanner`.
- Ops DB 4 -> 5 migration with eligibility-keyed notification state, emitted-
  event ledger, and ordered `POST`/`CANCEL` command outbox.
- Shared keyed `PlatformSurfaceCoordinator`, generation revalidation, startup
  draining, and idempotent delivery-state handling.
- Fixed semantic Android notification slots, unique PendingIntent data URIs,
  validated itinerary
  deep links, cold/clean `[Home, target]` reset, and dirty-draft preservation on
  warm launch.
- Off-by-default Settings toggle, imminent/active phase gate, baseline seeding,
  and duplicate/source-regression suppression.
- Eligibility fingerprint/episode reset on intent, binding, pair, or source
  changes.
- Durable cleanup on reorder/delete/archive, eligibility loss, and completion.

Exit criteria:

- Material erosion emits once per bucket.
- Stale data never emits a new timing alert.
- Existing per-flight notifications do not duplicate connection copy.
- Backup restore and Ops prune seed silently rather than treating old state as a
  new change.
- A crash before/after `notify()` or `cancel()` converges to the latest desired
  semantic slot without losing an event or cancelling a newer episode.
- Airport mismatch can emit only under unchanged confirmed bindings; binding loss
  cannot emit it, and explicit flight disruption suppresses duplication.

### Phase 7: optional licensed and advanced enhancements

Independent go/no-go tracks:

- MCT provider and explanatory comparison.
- Timatic/Travel Centre link or licensed widget evaluation.
- Structured airport wait-time pilots.
- Mappedin/HERE/OSM indoor-routing pilot.
- GTFS surface-transfer pilot.
- Drag reordering after visible move actions are proven.
- Expanded two-pane itinerary/editor layouts after the single-pane accessibility
  model is stable.

None blocks the useful local itinerary release. MCT and venue routing do not
block the factual live-connection release either, but that release still requires
the Phase 0 status rights/capability gate and Phase 5 declared guide coverage.

### 15.1 Suggested file/package map

```text
core/database/ItineraryEntities.kt
core/database/ItineraryDao.kt
core/database/OccurrenceBindingEntity.kt (or existing Ops entities file)
core/database/PlatformCleanupTaskEntity.kt (or existing Ops entities file)
core/data/ItineraryRepository.kt
core/data/TrackedFlightStore.kt
core/data/OperationalFlightCleanup.kt
core/data/StatusRefreshCoordinator.kt
core/data/ProviderCapabilityRouter.kt
core/data/FlightLifecycleCoordinator.kt
platform/PlatformSurfaceCoordinator.kt
core/model/ItineraryModels.kt
domain/TransitionEngine.kt
domain/ConnectionRecencyPolicy.kt
domain/TransitionNotificationPlanner.kt
domain/ItinerarySuggestionPolicy.kt
domain/ItineraryProgressPolicy.kt
ui/itinerary/ItineraryEditorScreen.kt
ui/itinerary/ItineraryEditorViewModel.kt
ui/itinerary/ItineraryDraftStoreViewModel.kt
ui/itinerary/ItineraryDetailScreen.kt
ui/itinerary/ItineraryDetailViewModel.kt
ui/itinerary/TransitionCard.kt
core/data/AirportGuideRegistry.kt
platform/TransitionNotificationEmitter.kt (or typed extension of emitter)
platform/RetentionPruneWorker.kt
```

Keep files consolidated where types are small; this map describes boundaries,
not a requirement to create one file per noun.

Existing files necessarily affected include `FlightRepository.kt`,
`StatusProviders.kt`, both status network adapters, `CadencePolicy.kt`,
`RefreshWorker.kt`, `ReminderScheduler.kt`, `NotificationEmitter.kt`,
`MainActivity.kt`, `NavEntryScoping.kt`, list/archive screens and ViewModels,
Settings repository/screen/ViewModel, `BlipbirdApp.kt`, `AppModule.kt`, manifest,
strings, Gradle version/dependency files, CI, and `app/schemas/`.

### 15.2 Rough dependency order

```text
language/legal/test infrastructure
    -> schema + lifecycle + navigation
    -> minimal local editor/Home/detail/archive
    -> occurrence identity + refresh coordinator
    -> live transition engine/UI
    -> official links
    -> worker-integrated notifications
    -> MCT/wayfinding/adaptive enhancements
```

## 16. Testing strategy

### 16.1 Transition engine unit matrix

Intent/suggestion:

- Direct same-airport connection confirmed by the user.
- Week-long same-airport destination stay, with no transfer prompts/alerts.
- Arrival at HND and return from NRT after a stay, not a surface transfer.
- Confirmed LHR -> LGW surface transfer with gross break explicitly excluding
  travel time.
- Unknown transition with no connection interpretation.
- Heuristic suggestions never mutate persisted intent without confirmation.
- Operational occurrence binding expires/replaces and returns pending route
  confirmation; a current endpoint mismatch under unchanged bindings returns the
  distinct continuity disruption.
- Direct intent plus confirmed different-airport pair and surface-transfer intent
  plus confirmed same-airport pair both require reclassification and suppress
  interpreted output.
- Unbound, expired, wrong-generation, and connection-ineligible snapshots cannot
  supply endpoints.

Time selection:

- Scheduled/scheduled.
- Estimated/scheduled.
- Estimated/estimated.
- Actual/estimated.
- Actual/actual.
- Missing inbound `IN`.
- Missing outbound `OUT`.
- Runway times present but gate times absent; no silent fallback.
- Negative and zero duration.
- Very long break rendered as a destination stay when user-confirmed.

Calendar/timezone:

- Overnight connection.
- International date line in both directions.
- DST spring gap and autumn overlap.
- Device timezone unrelated to either airport.
- Unknown airport timezone while Instants remain valid.

Continuity:

- Same IATA.
- Same ICAO.
- IATA on one side, ICAO on the other through reference normalization.
- Different airports in one city.
- Missing airport identity.
- Explicit provider diversion.
- Current airport pair no longer matches the confirmed transition pair.

Status/disruption:

- Inbound cancelled.
- Outbound cancelled.
- Inbound diverted.
- Onward already departed.
- Onward actual `OUT` before inbound actual `IN`.
- Departure time passed with status unconfirmed.
- Both legs complete.
- Stale inbound only.
- Stale outbound only.
- Both stale.

Itinerary progress:

- Runway `ON`/`LANDED` yields landed-not-at-gate, never confirmed airport
  occupancy.
- Actual gate `IN` establishes occupancy; both gate actuals establish normal
  transition completion.
- Missing gate actuals can become terminal-unconfirmed after tested grace without
  blocking the next actionable leg forever.
- A later leg actual `OUT` becomes current while the prior transition remains an
  exception.
- Cancellation, diversion, expired data, and local-only legs produce distinct
  progress outcomes.

MCT extension:

- Exact applicable rule.
- Missing terminal dimension.
- Ambiguous rules.
- Expired rule.
- Latest scheduled window above/equal/below MCT.
- Latest calculated margin shown separately from scheduled validity.
- Separate-ticket MCT explicitly applicable to self-transfer is shown with
  non-guaranteeing copy.
- Separate-ticket rule with unknown/not-applicable arrangement is suppressed.

### 16.2 Notification planner tests

- First assessment seeds baseline without alert.
- First 15-minute erosion bucket on an imminent confirmed direct connection.
- Re-observed same values deduped.
- Further bucket emits once.
- Recovery after erosion.
- Small jitter ignored.
- Stale assessment suppressed.
- Cancellation/diversion remains owned by the flight planner, not duplicated.
- Destination stay and unknown transition never alert.
- Provider/source or certainty regression never alerts.
- Direct -> stay -> direct, expired -> reconfirmed, binding replacement, and
  provider/capability switch each start a new episode and seed silently.
- Current endpoint mismatch under unchanged bindings emits continuity once;
  binding loss/replacement does not, and cancellation/diversion takes precedence.
- Transition deletion/recreation receives distinct identity.
- Reorder and eligibility loss cancel fixed semantic slots and Ops state;
  recovery replaces rather than stacks the window slot.
- Planner state, ledger, and slot commands commit atomically. Crashes before and
  after `notify()`/`cancel()` retry idempotently with the same invocation token.
- Delayed commands from an older episode, archive, or reclassification become
  conditional no-ops after restore/reconfirmation; window cancel plus continuity
  post preserves command order.
- Existing gate event does not double-fire as connection event.

### 16.3 Room and migration tests

- User DB 1 -> 2 preserves every prior active/archived flight value, marks null
  dates `NEXT_OCCURRENCE`, and marks non-null dates `LEGACY_UNKNOWN`.
- Interim pre-Phase-3 provider pins atomically set
  `PROVIDER_RESOLVED_LEGACY` and cannot satisfy itinerary date confirmation.
- Empty itinerary tables after migration.
- Unique creation request ID rejects duplicate graph creation.
- Composite foreign keys reject cross-itinerary transition legs.
- Unique predecessor/successor indices reject branches and joins.
- Tracked-flight `RESTRICT` rejects a direct delete behind membership.
- Itinerary/leg cascades remove appropriate transitions.
- Unique tracked-flight membership.
- Unique ordinals.
- Dense reorder under uniqueness constraint.
- Exactly `n - 1` ordinal-adjacent transitions after every mutation.
- Insert middle leg splits transition.
- Remove middle leg joins neighbors with unknown intent/answers.
- Unchanged transition preserves answers.
- Changed transition discards answers only after confirmation.
- Removing the last pair deletes the itinerary and keeps the flight(s)
  ungrouped.
- Archive/restore updates every member atomically and derived aggregate state is
  correct.
- Delete-keeping-flights leaves ungrouped rows.
- Delete-with-flights leaves no user rows.
- Ops 2 -> 3 cleanup-outbox migration and prepared/ready reconciliation.
- Ops 3 -> 4 occurrence binding/confirmed-pair plus nullable snapshot-binding/
  capability migration; all legacy snapshots remain unbound and binding deletion
  cascades through snapshots/pairs.
- Ops 4 -> 5 transition state/ledger/notification-command migration when that
  phase lands.
- Impossible-state diagnostics do not silently rewrite user archive intent.

### 16.4 Repository tests

- New itinerary creation is all-or-nothing.
- Retrying the same creation request after every post-commit crash point returns
  the same graph, clears the restored draft safely, and startup reconciles work.
- Invalid token causes no partial write.
- Schedule reconciliation runs after aggregate commit and on startup; it chooses
  periodic, one-time dormant, or no network work without unconditional enqueue.
- The Phase 1 coordinator facade preserves dateless ungrouped tracking; Phase 3
  switches date pinning to Ops binding without an identity-regression build.
- Approved refresh starts after commit through the coordinator.
- Provider failure retains local intent.
- Grouping existing flights triggers no duplicate rows/calls.
- Replacing a leg identity swaps rows atomically, resets affected transitions,
  invalidates live bindings, and queues old-flight cleanup.
- Recomputing all `n - 1` transitions yields stable values after one leg update.
- Controlled Android backup/restore preserves user graph when the platform
  performs backup; snapshots/state remain absent and first notification baseline
  is silent.
- Coordinator coalesces worker/manual/detail/editor calls, honors backoff with an
  existing snapshot, bounds concurrency, schedules the earliest one-time wake
  for dormant flights, and stops all network work only when every flight is
  terminal.
- Overlapping worker/manual two-leg batches evaluate one settled binding-matched
  snapshot generation, never a transient old/new pair.
- Capability routing chooses one approved source before dispatch; an ineligible
  later snapshot cannot hide a usable bound snapshot or trigger fallback billing.
- Expired rows are rejected by every repository/UI/notification read before
  physical pruning; networkless maintenance prunes them.
- Every delete/archive crash boundary leaves recoverable user intent or a durable
  outbox task; startup eventually cancels reminders, critical/status events,
  ongoing cards, transition slots, and legacy identities.

### 16.5 Navigation tests

- Tagged serialization round trip for every route.
- Unknown future route token falls back safely to Home.
- Process death on itinerary detail.
- Process death restores the `draftId`-keyed activity draft; Save/Discard clears
  it.
- Maximum-size allowed draft restores after process death; over-limit name,
  paste, leg count, or serialized state is rejected accessibly before save.
- Deep link to connection on cold/warm launch.
- A warm notification tap preserves a dirty editor and Back resumes it.
- The same invocation token does not refire after process death, while a later
  event token for the same transition does navigate.
- Flight notification content intents likewise use full per-slot event tokens: a
  redelivery is ignored and a later event for the same flight still navigates.
- Equal routes are focused/replaced rather than pushed twice under the documented
  `Screen`-keyed store policy.
- Predictive back clears popped stores only after transition settles.

### 16.6 Compose/UI tests

- Local-only unresolved itinerary.
- Latest scheduled/calculated direct-connection states.
- Destination-stay, surface-transfer, and unknown-transition states.
- Direct intent with null/expired/replaced occurrence binding shows pending route
  confirmation. A current mismatch under unchanged bindings shows continuity
  disruption. Neither shows a live window or timing guidance.
- Every `where to go` missing-data combination.
- Cancellation/diversion/surface-transfer states.
- Actual onward `OUT` with missing inbound actual `IN` never renders normal
  completion.
- Distinct no-provider, unconfigured, rejected-credential, budget-stop,
  provider-window, not-found, rate-limit, network-failure, and stale-cache copy/
  actions.
- Archive/delete confirmations.
- Failed create/reorder/archive/restore/delete preserves draft or committed graph,
  announces failure, shows no false success, and retries idempotently; pending
  post-commit reconciliation is represented separately.
- Reorder by visible buttons, keyboard, switch access, and semantic action; drag
  is optional.
- TalkBack focus order and merged summaries.
- 200% font scale.
- RTL.
- Reduce motion.
- High contrast and non-color cues.
- Compact layout and 200% reflow automated for every new surface. Manual release
  matrix covers 600/840/1200 dp widths, 380 dp adaptive index cells, maximum 840
  dp editor/detail panes, separating vertical/horizontal hinges, rotation,
  freeform resize, IME, and preservation of focus/scroll/editor state until
  adaptive automation lands.
- Reduced motion, 48 dp targets, switch traversal, and automatic-expansion focus
  preservation/announcement pass on the full Home/editor/detail/archive flow.
- Official-link attribution/labels.
- Offline/stale treatment.

### 16.7 Provider and data tests

- Synthetic/redacted status fixtures with terminal/gate omissions.
- AeroDataBox revised/predicted/runway ambiguity keeps connection windows
  disabled; revised-only, predicted-only, and runway-only fixtures must prove
  family and certainty before enablement.
- Multi-leg and same-day duplicate candidate fixtures.
- Official guide registry schema, duplicate, URL, locale, and age checks.
- No network live tests in shared CI without provider permission.
- MCT matching tests use licensed/synthetic rules, never an unauthorized copied
  dataset.
- Indoor/GTFS pilots have airport-specific contract fixtures and coverage tests.

### 16.8 Manual journey protocol

Before release, test at least:

- Same-terminal domestic connection.
- Cross-terminal international connection.
- Separate-ticket self-transfer.
- Different-airport surface transfer.
- Week-long destination stay followed by a return flight.
- Overnight/date-line itinerary.
- Missing gates.
- Inbound delay that shortens the window.
- Outbound delay that lengthens the window.
- Cancellation and diversion.
- Offline interval and stale recovery.
- TalkBack flow from Home through official guidance.

Record provider cost, battery behavior, data age, and defects. Do not record a
traveler's exact real itinerary in public issue artifacts.

### 16.9 Pre-release comprehension study

Before enabling live guidance, test the prototype with travelers, including
TalkBack and low-vision participants. A participant should be able to explain:

- What the displayed duration measures (`IN -> OUT`).
- That it is not a feasibility/protection guarantee.
- Which endpoint is scheduled/estimated/actual and how old each input is.
- That terminal/gate values are reported facts to confirm on official displays,
  not a Blipbird route instruction.
- Which actions open an external official publisher.
- Why a destination stay is not receiving connection alerts.

Revise hierarchy/copy before release if those distinctions are not understood;
do not wait for post-release confusion.

## 17. Acceptance criteria

The local and live releases have separate completion gates. The local release
must satisfy sections 17.1, 17.3, 17.4, and the applicable local portions of
17.5. The product may claim live connection guidance only after every section
below passes with at least one enabled production data path.

### 17.1 Local itinerary release

- A user can create two or more legs with independently confirmed departure-
  local dates; null/legacy date intent is reviewed rather than guessed.
- Creation is atomic and idempotent by unique request ID. A crash after commit
  cannot duplicate an itinerary or permanently miss schedule reconciliation.
- Draft size limits and process-death restoration work at their maximum allowed
  values.
- Existing flights can be grouped without duplication, provider-snapshot reads,
  or network calls.
- Explicit order survives restart and process death. A controlled Android backup/
  restore test preserves it when the platform performs backup; product copy does
  not guarantee backup execution.
- Every adjacent pair has a stable transition ID and user-confirmed/unknown
  intent; date/designator replacement resets affected transitions and live state.
- Direct connection, destination stay, surface transfer, and unknown render
  distinctly without claiming route, arrival date, timing, or terminal data.
- Archive/restore/delete preserve graph invariants and durable cleanup across
  every simulated process-death boundary.
- Failed local mutations retain the draft or last committed graph, announce the
  error, show no false success, and retry idempotently.

### 17.2 Additional live-connection release

- At least one production status path has approved direct-mobile, display,
  normalization, retention, derived-output, intended-use, marketplace, and alert
  rights, and exposes contract-tested gate-time capabilities.
- Every live endpoint comes from a non-expired snapshot bound to the active
  occurrence generation; provider instance identity survives selection/write,
  and an unbound/ineligible newer row cannot replace it.
- `DIRECT_CONNECTION` requires a confirmed same-airport pair and
  `SURFACE_TRANSFER` requires a confirmed different-airport pair. Conflicts
  suppress timing/guidance and prompt reclassification.
- Latest scheduled/calculated connection windows use only gate `IN/OUT` times.
  Selected endpoint instant, certainty, source, binding, fetch time, expiry, and
  any provider field age drive both UI and notification planning.
- Missing/expired/replaced bindings produce pending confirmation. Current airport
  mismatch under unchanged bindings produces a distinct continuity disruption;
  it may notify under its event-specific predicate but cannot show a normal
  window, guidance, MCT comparison, or timing alert.
- Direct connection, destination stay, surface transfer, unknown, overlap,
  cancellation, diversion, mismatch, and missing-milestone states remain
  distinct under the tested itinerary-progress policy.
- Home prioritizes the current actionable leg/transition without treating runway
  `ON` as airport occupancy or leaving missing gate actuals active forever.
- Direct-connection detail answers time, endpoint uncertainty/ages, and reported
  locations before secondary detail. Compact UI shows the older relevant age;
  detail shows both.
- Official guidance is advertised only for the declared reviewed airport
  coverage; every action has publisher and verification metadata.
- Offline/failed-refresh state is shown independently from retrieval age, and
  disruptions suppress misleading normal guidance.
- Overlapping refresh batches cannot evaluate a transition from mixed snapshot
  generations. Provider/capability changes silently seed a new alert episode.
- Fixed semantic notification slots, dedup, eligibility reset, durable cleanup,
  and validated deep links survive process death when Phase 6 alerts are enabled.

### 17.3 Trust

- No generic safe/tight/at-risk claim ships without approved assessment data.
- No guessed walking time or route, baggage transfer, ticket protection, or visa
  inference ships.
- An MCT comparison appears only when an exact rule explicitly applies to the
  declared booking arrangement; separate-ticket applicability is never assumed.
- Location copy reports provider facts and tells the user to confirm; it never
  says `Go`/`Stay` without rights-cleared routing/procedure data.
- New provider/data rights are documented in ADRs, and live store copy names only
  capabilities actually enabled in the release build.
- Pre-release comprehension criteria pass before live guidance is enabled.

### 17.4 Engineering quality

- User and Ops schema migrations are tested and committed; no destructive
  migration fallback is added.
- One worker remains responsible for flight status. One refresh coordinator owns
  every entry point, set-based generation barriers, complete backoff, dormant
  one-time wakeups, expiry enforcement, and terminal-only network shutdown while
  networkless pruning continues.
- A capability router chooses an approved source before dispatch, and connection
  arithmetic itself adds no provider request.
- Durable cleanup outbox processing and complete flight/transition cancellation
  leave no orphaned alarm or posted-notification surface after restart.
- Lint, unit tests, migration tests, Compose tests, debug assembly, and the
  required managed-device test job pass.

### 17.5 Accessibility and adaptive UI

- Full creation, reorder, detail, archive, and delete flows work without drag or
  swipe and with keyboard and switch traversal.
- TalkBack communicates itinerary, leg, connection, duration basis, source/
  retrieval age, errors, expansion, and actions in a useful order.
- 200% font scale vertically reflows Home, editor, grouping, archive, detail,
  dialogs, and link sheets without clipping critical facts or controls.
- Color is never the only state cue; reduced motion removes timeline animation
  without hiding state; every visible action target is at least 48 dp.
- Automatic connection expansion preserves focus and announces only material
  disruption. Map guidance has a complete textual alternative.
- Home/archive retain 380 dp adaptive grid cells; editor/detail/link surfaces
  honor the 840 dp maximum pane width. All honor documented gutters, the 320 dp
  viable hinge region, and separating-hinge exclusion across 600/840/1200 dp,
  rotation, freeform resize, and IME tests while preserving focus, scroll, and
  editor state. Itinerary two-pane behavior is not required for the first release.

## 18. Risks and mitigations

| Risk | Likelihood/impact | Mitigation |
|---|---|---|
| A later return flight is mistaken for a connection | High | Persist neutral transitions; require user intent; destination stays get no transfer prompts/alerts |
| Users interpret raw duration as a guarantee | High | Use `connection window`, show basis/source/retrieval ages, avoid safe language, explain limits near interpretation |
| MCT dataset is unavailable or unaffordable | High | Ship factual windows independently; keep provider optional |
| Status provider lacks or ambiguously classifies gate `IN/OUT` | Common | Show unresolved window; never mix milestones; keep AeroDataBox revised/predicted/runway values disabled until adapter semantics are corrected/confirmed |
| Gate/terminal values are missing or wrong | Common | Neutral `reported at` copy, independent ages/placeholders, confirmation on official displays |
| Flights booked together are mistaken for one protected ticket | High | Ask per transition; state that the answer proves neither one ticket nor protection |
| Baggage guidance is wrong | High | User assertion plus existing dossier/official transfer guidance; no unverified airline link and no Resolution 753 API assumption |
| Immigration/security rules become stale | High | Curate official links, not copied global rules |
| Airport link rots | Medium | Validated checked-in registry, verification date, CI report, periodic review |
| Indoor map coverage is marketed as global | High | Capability check per airport and source; textual fallback |
| Commercial SDK key leaks from APK | Certain if treated as secret | Require approved public token/backend or do not integrate |
| More legs exhaust quota | Medium/high | Explicit candidate resolution, bounded/coalesced refresh coordinator, spend stops, visible leg count/ledger |
| Global sort breaks itinerary order | High | Separate itinerary projection and explicit ordinal |
| Reorder carries baggage/ticket answers to wrong pair | High | Transition identity by ordered pair; reset changed transitions with confirmation |
| Delete/Undo changes flight ID and loses membership | High | Aggregate-aware repository operations; keep-flight delete default |
| Cross-database crash leaves inconsistent cleanup | Medium | Prepared/ready Ops outbox, User DB state reconciliation, complete deterministic cancellation, and diagnostics rather than guessed user-state repair |
| Provider-derived airport pair leaks into user backup | High/legal | Keep occurrence endpoints/pair in TTL-bound Ops DB; persist only user transition intent |
| Dormant future flights are stranded when periodic work stops | Medium | Due/dormant/terminal scheduler plus earliest one-time wake |
| Expired provider rows remain physically present or visible offline | High/legal | Enforce `expiresAt` on every read and run networkless pruning/expiry wakes |
| Navigation ID collision/restore ambiguity | High | Tagged versioned routes, legacy decoder, and no duplicate equal routes under current store policy |
| Notification tap discards an editor draft or suppresses a later same-target event | High | Activity-level draft store, warm-stack preservation, and immutable event token in URI/consumption state |
| Old/upstream-stale data emits alarming notification | High | Retrieval/source/certainty gate, provider field age when available, persisted planner state and dedup; fetch time is not update time |
| Provider instance drifts to another occurrence | Existing risk | Stable occurrence binding, bound/capability-filtered snapshots, and candidate picker before live guidance |
| Provider-pinned date is mistaken for portable user intent | High/data boundary | Date provenance migration; explicit confirmation; resolved occurrence date stays in Ops |
| Save retry duplicates a committed itinerary | Medium | Unique creation request ID and idempotent repository create/get operation |
| Overlapping refreshes assess one new and one old leg | High | Set-based batch generations, pair barrier, and snapshot-version validation in planner transaction |
| Provider terms prohibit caching/derived alerts | Release blocking | ADR gate and implementation disabled until written approval |
| User-authored booking/bag facts are eligible for OS backup | Medium privacy | Clear disclosure, local erase path, minimal fields, no PNR/passport; export only when implemented |

## 19. Decisions and review triggers

### 19.1 Recommended decisions to accept now

1. `Itinerary` is the canonical feature/domain term.
2. One tracked flight belongs to at most one itinerary in v1.
3. User order is authoritative.
4. Every adjacency is a neutral persisted transition; direct connection,
   destination stay, surface transfer, or unknown is user intent.
5. Only a same-airport, occurrence-confirmed direct connection gets a connection
   window, using gate `IN -> OUT` only.
6. No generic feasibility score ships without licensed rules and sufficient
   context.
7. Booking and baggage facts are optional user assertions per direct connection;
   `booked together` is not proof of one ticket/protection.
8. No passport, nationality, PNR, or inbox access in the MVP.
9. Official links precede structured/indoor guidance.
10. Existing per-flight worker and ongoing notification remain, with one new
    refresh coordinator before live itinerary features.
11. Local itinerary grouping and connection arithmetic require no new backend.
12. Live route/timing waits for approved provider rights, capability-aware source
    selection, and occurrence-bound snapshots.
13. The local itinerary release and live-connection release have separate,
    testable acceptance gates.

### 19.2 Decisions to make during implementation

- Initial airport list and maintenance owner for official links.
- Exact retrieval-recency grace relative to `CadencePolicy` and absolute
  fallback thresholds.
- Whether local user-entered route/time fields should later improve zero-key
  itineraries.

### 19.3 Review triggers

Revisit the staged decision when:

- An MCT provider offers acceptable open-source mobile display/cache rights.
- Blipbird accepts a backend and its operational/privacy cost.
- An airport or airline offers an AIDX/ACRIS feed and written integration rights.
- A venue provider offers rights-cleared airport geometry and public mobile
  credentials.
- A reliable open MCT dataset with clear redistribution rights appears.
- User research shows factual windows are consistently misunderstood despite
  copy and hierarchy.
- The itinerary feature expands beyond flights to rail/hotel/ground items.

## 20. Research source register

All links were accessed on 2026-07-23. Pricing, quotas, terms, schemas, product
coverage, and URLs are volatile and must be rechecked before implementation and
release. `Contract required` means public documentation does not establish the
rights needed by a distributed open-source Android client.

### 20.1 MCT, airline, airport, and document standards

| Source | What it establishes | Access/fit |
|---|---|---|
| [OAG Minimum Connection Times](https://www.oag.com/minimum-connection-times) | Provider advertises 157,000+ MCT standards/exceptions, airport/carrier/terminal/codeshare dimensions, up-to-daily updates, files/Snowflake delivery; OAG also sells a separate Global Flight Connections dataset (200M+ route combinations, schedule-connection search rather than filed rules) | Commercial; contract and redistribution/cache review required |
| [OAG Flight Info API](https://www.oag.com/flight-info-api) and [developer portal](https://developers.oag.com/) | Commercial schedules/status/connections data products | Commercial; direct-mobile, retention, and derived-use rights require contract |
| [IATA Standard Schedules Information Manual](https://www.iata.org/en/publications/manuals/standard-schedules-information/) | SSIM standardizes schedule/coordination/MCT exchange; Chapter 8 covers MCT data | Commercial annual manual, not a live dataset or redistribution license |
| [IATA AIDX](https://www.iata.org/en/publications/info-data-exchange/) | XML messaging standard for operational flight data among airlines, airports, and third parties | Standard/schema, not a public feed |
| [IATA NDC](https://www.iata.org/en/programs/airline-distribution/retailing/ndc/) | Open airline retailing/distribution messaging standard | The standard is available; actual airline offers/orders require an authorized airline/aggregator endpoint |
| [IATA ONE Order](https://www.iata.org/en/programs/airline-distribution/retailing/one-order/) | Open order-centric retailing/fulfillment standard | The standard does not expose passenger orders; operational access requires an authorized endpoint |
| [IATA AIDM](https://developer.iata.org/en/aidm/) | Shared industry data-model vocabulary | Modeling reference, not an operational feed |
| [IATA Timatic](https://www.iata.org/en/services/compliance/timatic/) | Commercial real-time passport, visa, health, customs, and related requirements | Traveler-specific and commercial; contract required |
| [IATA Travel Centre](https://www.iatatravelcentre.com/) | IATA's public traveler-facing personalized document-requirement surface | External-link candidate; do not copy decisions locally |
| [IATA baggage tracking](https://www.iata.org/en/programs/ops-infra/baggage/baggage-tracking/) | Resolution 753 custody-tracking responsibilities | Not a public passenger baggage-transfer API |
| [ACI ACRIS Best Practice](https://store.aci.aero/product/acris-best-practice/) | Airport-system interoperability guidance | Free publication, not data/feed |
| [ACI standards overview](https://aci-standards.atlassian.net/wiki/spaces/AS/overview) and [Data Dictionary portal](https://aci-standards.atlassian.net/wiki/spaces/ACIDD/overview) | Standard airport concepts and terminology | Modeling/naming aid, not live data |

### 20.2 Flight status and order platforms

| Source | What it establishes | Access/fit |
|---|---|---|
| [AeroDataBox documentation](https://doc.aerodatabox.com/), [OpenAPI schema](https://doc.aerodatabox.com/docs/openapi-rapidapi-v1.json), [coverage](https://aerodatabox.com/data-coverage/), [pricing](https://aerodatabox.com/pricing/), and [terms](https://aerodatabox.com/terms/) | Flight status/schedule/airport/airline interfaces, ambiguous revised/predicted/runway certainty/family semantics, and marketplace access through two authorized distributors (Nokia–RapidAPI and API.Market per Terms Appendix A, November 4, 2025 revision), each incorporating its own marketplace agreements | Public terms restrict permanent storage, modification/derivatives, third-party display/distribution, credential disclosure, and safety-critical/intended uses while incorporating distributor terms; written approval of the use and the specific delivery path is required or the integration stays disabled |
| [FlightAware AeroAPI](https://www.flightaware.com/commercial/aeroapi/) | Status, schedule, estimate, position/track, alert, historical product, and public tiered usage plans; derivative-work storage/distribution rights are tiered (Personal: personal/academic only; Standard: business/B2C; Premium adds B2B); $5 free queries per month ($10 for ADS-B feeders), Standard $100/month minimum, per-result-set fees (checked 2026-07-24, volatile) | Raw-data display/retention, public distributed-client credentials, and exact selected-plan rights still require written confirmation |
| [Cirium FlightStats developer center](https://developer.flightstats.com/api-docs/) and [signup](https://developer.flightstats.com/signup) | Legacy Flex docs plus trial/Developer Studio and reviewed PAYG/commercial application paths; Flex also exposes a Connections API (v3, connecting-flight search between airports) and Flight Status fields for terminal, gate, and baggage carousel | Current production access, SLA, mobile credentials, retention, and derived use require provider review/contract; do not call it simply unavailable |
| [Aviationstack product](https://aviationstack.com/product), [pricing](https://aviationstack.com/pricing), and [APILayer legal portal](https://www.ideracorp.com/legal/APILayer#tabs-2) | Keyed real-time/historical aviation APIs and plan limits | Does not solve MCT/booking/wayfinding; identify the applicable SaaS terms and review direct-mobile/retention rights |
| [AirLabs](https://airlabs.co/) and [API docs](https://airlabs.co/docs/) | Status, schedule, delay, alert, and reference APIs | Does not solve MCT/booking/wayfinding; current pricing/rights require confirmation |
| [OpenSky REST API](https://openskynetwork.github.io/opensky-api/rest.html) and [terms](https://opensky-network.org/about/terms-of-use) | ADS-B states/tracks and operational-use restrictions | Position source only; terms require agreement for operational integration |
| [Duffel Flights](https://duffel.com/flights), [offer requests](https://duffel.com/docs/api/offer-requests/create-offer-request), and [pricing](https://duffel.com/pricing) | Commercial multi-slice shopping/order platform with self-service/test entry | Not arbitrary flight import; production booking responsibilities apply, and a shared secret in a public APK normally needs a backend or approved mobile credential model |
| [Travelport developer portal](https://developer.travelport.com/) and [TripServices](https://www.travelport.com/products/tripservices) | Commercial search/book/ticket/service platform with trial/commercial onboarding | Not arbitrary public flight import; production terms and credential architecture require approval |

### 20.3 Airport/reference/timezone data

| Source | What it establishes | Access/fit |
|---|---|---|
| [OurAirports data](https://ourairports.com/data/) and [dictionary](https://ourairports.com/help/data-dictionary.html) | Nightly airport CSVs; no IANA timezone field | Public domain, no fitness warranty; already used by Blipbird |
| [IANA Time Zone Database](https://www.iana.org/time-zones) | Authoritative timezone rules and identifiers | Public domain; does not map coordinates to zones |
| [Timezone Boundary Builder](https://github.com/evansiroky/timezone-boundary-builder) | OSM-derived geographic timezone boundaries | ODbL obligations; suitable build-time candidate after license review |
| [GeoNames export](https://www.geonames.org/export/) | Downloadable geographic/timezone-related datasets | CC BY 4.0; attribution/accuracy/size review required |

### 20.4 Mapping, indoor routing, and transit

| Source | What it establishes | Access/fit |
|---|---|---|
| [MapLibre Native docs](https://maplibre.org/maplibre-native/docs/book/) and [license](https://raw.githubusercontent.com/maplibre/maplibre-native/main/LICENSE.md) | Open-source map renderer | Renderer only; no venue data, routing, or tiles |
| [OpenStreetMap copyright](https://www.openstreetmap.org/copyright) | OSM data is ODbL with attribution/share-alike conditions | Data-license analysis required for derived/bundled indoor databases |
| [OSM tile usage policy](https://operations.osmfoundation.org/policies/tiles/) | Standard `tile.openstreetmap.org` raster service is best effort/no SLA; bulk download, prefetch, and offline use prohibited; the separate `vector.openstreetmap.org` policy also prohibits bulk download and offline packs | Not an offline airport-map backend; other OSM-derived/self-hosted services have their own terms |
| [OSM Simple Indoor Tagging](https://wiki.openstreetmap.org/wiki/Simple_Indoor_Tagging) | Community model for levels, rooms, corridors, doors, and indoor features | Coverage/routability vary; not a global airport dataset |
| [Mappedin airports](https://www.mappedin.com/industries/airports/), [pricing](https://www.mappedin.com/pricing/), [Android SDK](https://developer.mappedin.com/android-sdk/getting-started), [SDK license](https://info.mappedin.com/terms/sdk), and [subscription terms](https://info.mappedin.com/terms) | Public docs cover mobile SDK credentials, multi-floor/accessibility routing, caching/offline MVF loading, airport demos/deployments, and Free plus Pro tiers; Pro is `$165/map/month` and is the first tier with SDK & API access and data export — Free covers the editor/viewer only, and both target venue owners publishing their own maps | Strong pilot candidate; production real-airport content, safe public-client credentials, intended-use pricing, and retention/redistribution rights still require confirmation |
| [HERE Indoor Map documentation](https://docs.here.com/indoor-map/docs/indoor-map-readme) and [terms](https://legal.here.com/en-gb/terms) | Commercial indoor-map platform with documented mobile/web libraries and GeoJSON map access | Desired real-airport coverage, price, public-client credentials, and content rights require enterprise evaluation |
| [Google Maps IndoorBuilding reference](https://developers.google.com/android/reference/com/google/android/gms/maps/model/IndoorBuilding), [indoor help](https://support.google.com/maps/answer/2803784), and [billing](https://developers.google.com/maps/documentation/android-sdk/usage-and-billing) | Supported indoor buildings can expose levels in Google Maps | No documented universal airport indoor-routing API; billing/terms and F-Droid fit differ |
| [Mapbox offline maps](https://docs.mapbox.com/android/maps/guides/offline/), [pricing](https://www.mapbox.com/pricing), and [terms](https://www.mapbox.com/legal/tos) | Android offline map-region support | Does not supply airport floor plans/routes; separate venue rights required |
| [Apple IMDF resources](https://register.apple.com/resources/imdf/) and [OGC IMDF 1.0](https://docs.ogc.org/cs/20-094/index.html) | Indoor mapping data format | Format, not venue dataset; each map needs rights |
| [OGC IndoorGML](https://www.ogc.org/publications/standard/indoorgml/) | Indoor spatial/navigation data model and XML schema capable of modeling supplied geometry/topology | Supplies no venue dataset, positioning, closures, or routing service |
| [GTFS Schedule reference](https://gtfs.org/documentation/schedule/reference/), [Realtime reference](https://gtfs.org/documentation/realtime/reference/), and [Pathways](https://gtfs.org/documentation/schedule/examples/pathways/) | Ground-transit schedule/realtime and station circulation/accessibility model | Useful only where a publisher supplies a rights-cleared feed; not a flight/airport-map source |

### 20.5 Official procedure and wait-time examples

| Source | What it establishes | Product treatment |
|---|---|---|
| [Heathrow Connecting flights](https://www.heathrow.com/connecting-flights) and [Passenger updates](https://www.heathrow.com/passenger-updates) | Airport-specific transfer, security, immigration, baggage, terminal, wait, and separate-booking/MCT applicability information | Official deep links; do not generalize or copy indefinitely |
| [Frankfurt Airport transfer guide](https://www.frankfurt-airport.com/en/flights-and-transfer/transferring-at-fra.html) | Frankfurt-specific transfer instructions | Official deep link |
| [CATSA current wait times](https://www.catsa-acsta.gc.ca/en/current-wait-times) | Official Canadian security-screening wait estimates for supported checkpoints | Airport/region-specific link or reviewed adapter |
| [DFW security](https://www.dfwairport.com/security/) | DFW checkpoint and wait/security information | Airport-specific official link |
| [UK transit visa](https://www.gov.uk/transit-visa) | Transit requirements depend on traveler, route, documents, and border path | Government deep link; evidence against flight-number-only decisions |
| [EU air passenger rights](https://europa.eu/youreurope/citizens/travel/passenger-rights/air/index_en.htm) | Missed-connection rights depend on conditions including a single reservation | Informational link; never infer protection from flights alone |

### 20.6 Product and accessibility references

| Source | What it establishes | Design implication |
|---|---|---|
| [Flighty Connection Assistant](https://www.flighty.com/help/connection-assistant) | Product says it combines MCT with traveler, airport, bag, seat, gate, and live inputs | Do not imitate the score without comparable inputs |
| [Flighty flight management](https://www.flighty.com/help/managing-my-flights), [email forwarding](https://www.flighty.com/help/email-forwarding), and [offline mode](https://www.flighty.com/help/offline-mode) | Multiple add/import paths and explicit cached/offline estimates | Add local reviewable imports later; label offline estimation |
| [TripIt adding plans](https://help.tripit.com/en/support/solutions/articles/103000063275-adding-travel-plans-to-tripit) and [reordering](https://help.tripit.com/en/support/solutions/articles/103000063326-reorder-trip-items) | Manual/import paths and chronological itinerary presentation | Keep order explicit; do not mutate timestamps to reorder |
| [Google flight and booking options](https://support.google.com/travel/answer/11583641?hl=en) | Codeshare/interline/self-transfer distinctions and separate-ticket baggage/check-in implications | Ask, do not infer booking relationship |
| [Delta mobile app](https://www.delta.com/us/en/delta-digital/mobile) | Airline app groups flight-day status, gate, bags, map, and recovery actions | Prioritize current action and airline handoff |
| [United Android app](https://play.google.com/store/apps/details?id=com.united.mobile.android&hl=en_US) | Airline app describes transfer time, gate/terminal navigation, and disruption tools | Current/next leg focus, without pretending Blipbird can rebook |
| [Android Live Updates](https://developer.android.com/develop/ui/views/notifications/live-update) | Promoted ongoing notifications are for active, user-relevant progress | Do not promote distant itineraries |
| [Android accessibility principles](https://developer.android.com/guide/topics/ui/accessibility/principles) | Labels, equivalent actions, and non-color cues | Text/map alternatives and non-gesture management |
| [Wayfindr mobile functionality guidelines](https://www.wayfindr.net/open-standard/guidelines/mobile-app-development/guidelines-for-mobile-app-functionality) | Route preview/replay, text equivalents, and accessibility preferences | Indoor guidance cannot be visual-only |

### 20.7 Verification gaps

- FlightAware's selected plan, raw display/retention, and distributed-client
  credential rights still require direct confirmation before updating Blipbird's
  cost/legal model. The AeroAPI developer portal (`flightaware.com/aeroapi/portal/
  documentation`) renders field-level schema client-side and could not be fetched
  non-interactively; field names `fa_flight_id` and `actual_out` are confirmed via
  official sample code, while `actual_in`, `actual_off`, and `actual_on` follow the
  documented naming convention but should be confirmed in a browser session before
  the Phase 3 capability router relies on them.
- Cirium's public Flex documentation is legacy; trial/application paths exist,
  while current production terms remain provider-reviewed/non-public.
- Some marketplace plan details for AeroDataBox require an authenticated account.
- AirLabs' current exact pricing was not independently verified.
- Public pages document meaningful Mappedin/HERE SDK capabilities but do not
  establish the exact production airport content, safe public-client credential
  model, or redistribution rights Blipbird needs.
- No verified open, global, current MCT dataset with APK redistribution rights
  was found.
- No verified universal public API was found for baggage transfer, immigration/
  customs steps, airport security waits, or gate-to-gate indoor routes.

These gaps are product constraints, not tasks to paper over with estimates.

## Appendix A: independent verification log (2026-07-24)

An independent reviewer re-checked this document on 2026-07-24 against the
live codebase (code identical between `7c95802` and `63f5042`; only this
document changed) and by re-fetching the cited sources. "Accurate"
below means the claim was confirmed and needed no change. Corrections and
additions made by that review are listed at the end. The review was rebased
over the merged review edits of PRs #81 and #82; section references above
follow the post-#82 numbering (their new section 8.1 shifted the original
8.1–8.11 to 8.2–8.12).

| Claim | Result |
|---|---|
| Section 3 baseline: `observeActive()`, per-flight row flows sorted by next event, single unique refresh worker, per-flight reminder/notification identity, leg-scoped detail/map/weather/track, archive screen, shared 380 dp adaptive grid, saved/predictive-back navigation, User DB v1 (`tracked_flight` only), shared batch date/alias behavior, non-atomic batch add, unprovenanced provider date pinning, gate/runway separation in `MovementTimes`, AeroAPI and AeroDataBox adapter mappings, airports/airlines-only reference data, untagged `Long` route saver with negative sentinels, `Screen`-keyed ViewModel stores, hash-folded notification IDs and extra-only deep links, arrival+3-day Ops retention, Ops DB v2, include-only backup rules | Accurate — all confirmed in code |
| Section 12.1 critiques: uncoalesced refresh entry points, backoff consulted only when no snapshot exists, terminal unarchived flights keeping the periodic worker alive, network-constrained worker as the only pruner, first-`Found` failover plus cross-provider newest-row reads | Accurate |
| `REVIEW.md` F10/F11/F15/F16 and `PLAN.md` v2 trip-grouping references | Accurate |
| `PLAN.md` "bundled minimum-connect times" assumption (Delighters) | Accurate; the Phase 0 removal stands |
| OAG MCT: 157,000+ standards/exceptions, files/Snowflake delivery, up-to-daily updates, no public price or redistribution right | Accurate |
| IATA SSIM: annual commercial manual; Chapter 8 covers MCT exchange | Accurate (36th edition, 2026) |
| OSM raster tile policy: best effort, no SLA, bulk/prefetch/offline prohibited | Accurate; vector-service policy now also cited (§14.6, §20.4) |
| Mappedin: Free/Pro tiers, `$165/map/month`, Android SDK credentials, MVF offline caching, airport deployments | Accurate; Free-tier SDK/export exclusions added (§5.5, §20.4) |
| Flighty Connection Assistant inputs (MCT, nationality, bags, seat, passport control, security, terminal transfer, predicted gates, live conditions) | Accurate (page published 2026-07-08) |
| AeroDataBox terms: permanent-copy, derivative, redistribution, credential, and intended-use restrictions | Accurate; second authorized distributor and Articles 4.2/4.4/5.2.a added (§14.4, §20.2) |
| AeroDataBox `revisedTime`/`predictedTime`/`runwayTime` semantics | Accurate per the OpenAPI schema; verbatim quotes added (§8.1) |
| FlightAware AeroAPI: tiered derivative-work rights with B2C on Standard | Accurate; public price anchors added (§12.3, §20.2) |
| Google: virtual-interline/self-transfer separate check-in and baggage recheck | Accurate |
| Heathrow: security for all connecting passengers; separate bookings treated as arrival plus departure; published MCT inapplicable to them | Accurate |
| IATA Resolution 753: custody tracking at four handover points; not a passenger API | Accurate |
| UK transit-visa dependence on traveler, route, documents, and border-control path | Accurate |
| EU missed-connection rights require a single reservation | Accurate |
| GTFS Pathways: station circulation/accessibility model only | Accurate |
| Cirium FlightStats developer center: docs live, free evaluation signup | Accurate; Flex Connections API and terminal/gate/baggage fields noted (§20.2) |
| OurAirports CSV: no IANA timezone field (Blipbird's `tz` column comes from mwgg/Airports, MIT) | Accurate |
| Timezone Boundary Builder data (ODbL), GeoNames export (CC BY 4.0) | Accurate |
| CATSA and DFW official wait-time pages; Frankfurt official transfer guide URL | Accurate |
| Android Live Updates relevance guidance; `Notification.ProgressStyle` is API 36 | Accurate |
| Duffel/Travelport: booking platforms, not flight-number import shortcuts | Accurate |
| Wayfindr mobile functionality guidelines | Accurate |
| Mappedin/HERE/Mapbox/Google indoor-mapping capability framing | Accurate |

Corrections and additions applied by that review:

- §5.5, §20.4 — Mappedin Free tier excludes SDK & API access and export;
  self-serve tiers are venue-owner-oriented.
- §7.15 — unified move-control terminology on `Move earlier`/`Move later`
  (§7.4 previously conflicted with `Move before`/`Move after`); reduced-motion
  requirement now names both the system setting and the in-app toggle added by
  PR #80 after the stated code baseline.
- §8.2 — verbatim AeroDataBox OpenAPI descriptions for `revisedTime`,
  `runwayTime`, and `predictedTime`, including the documented gate/runway
  disambiguation rule.
- §10.7 — `dateIntentSource` migration mechanics: `ADD COLUMN ... DEFAULT
  'NEXT_OCCURRENCE'` plus a conditional `UPDATE`, with the matching
  `@ColumnInfo(defaultValue = ...)` so the exported schema default matches the
  migration.
- §12.3, §20.2 — FlightAware public price anchors ($5/$10 free monthly
  allowance, $100/month Standard minimum, per-result-set fees).
- §13.4 — dedicated Android notification channel for transition events so the
  OS-level control and the in-app itinerary category cannot drift apart.
- §14.4, §20.2 — AeroDataBox has two authorized distributors (Nokia–RapidAPI,
  API.Market) per Terms Appendix A; approval must name the actual delivery
  path; safety-critical-use prohibition and best-effort/substitute language
  cited.
- §14.6, §20.4 — OSM's vector tile service policy also prohibits bulk
  download/offline packs.
- §20.1 — OAG's separate Global Flight Connections dataset noted.
- §20.2 — Cirium Flex Connections API (v3) and terminal/gate/baggage fields
  noted.
- Header — baseline note records that PR #80 merged after the research date.

No factual errors were found in the original research; the changes above are
precision corrections, newly available specifics, and one terminology
consistency fix.
