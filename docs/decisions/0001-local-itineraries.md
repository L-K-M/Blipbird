# 0001. Local itineraries use neutral transitions

- **Status:** Accepted
- **Date:** 2026-07-24
- **Owner:** Blipbird maintainers
- **Related plan sections:** `docs/ITINERARY_PROPOSAL.md` §§1, 4, 6, 10, 15-17

## Context

Blipbird already tracks many independent flights. A travel plan needs durable
membership, explicit order, per-leg departure-local dates, and an honest model
for what happens between adjacent flights. Adjacency does not prove that two
flights are a direct or protected connection, and the current status-provider
rights and occurrence identity are insufficient for public live connection
guidance.

## Decision

Add a backup-eligible local itinerary aggregate over existing tracked flights.
Each adjacency owns a neutral transition that the user classifies as a direct
connection, destination stay, surface transfer, or unknown. Tier 0 stores and
shows only user-authored names, order, dates, intent, and optional booking/bag
answers. It does not calculate or advertise live connection windows, MCT risk,
walking times, ticket protection, baggage transfer, or document decisions.

Tracked flights retain their existing per-leg status, map, weather, refresh,
and notification behavior. Live itinerary output remains disabled until a
separate ADR closes provider display/normalization/retention/derived-use rights
and confirmed occurrence bindings prevent instance drift.

## Alternatives considered

- Infer trips from active flights. Rejected because moving estimates would make
  membership, order, alerts, and user intent unstable.
- Add itinerary columns directly to `tracked_flight`. Rejected because stable
  leg and transition identities require explicit membership and edge rows.
- Treat every adjacency as a connection. Rejected because return flights,
  destination stays, and surface transfers are materially different.
- Wait for licensed MCT and indoor routing. Rejected because local organization
  is independently useful and has no new provider dependency.

## Consequences

- Positive: ordered multi-day travel plans work offline without an account,
  backend, status key, or duplicated flight model.
- Positive: neutral transitions provide a stable future home for rights-cleared
  connection facts and notifications.
- Negative: the first release deliberately shows unresolved live route/time
  guidance and asks users to classify transitions manually.
- Negative: aggregate lifecycle and identity replacement require migrations,
  membership-aware mutation coordination, and durable platform cleanup.
- Neutral: provider snapshots and identities remain in the excluded Ops DB.

## Provider / licensing specifics

No current provider gate is closed by this ADR. AeroDataBox and FlightAware
remain available only for their existing per-flight paths. Neither is enabled
as a live itinerary-window or transition-alert source by this decision.

## Open questions / review trigger

Revisit when a production status path has written direct-mobile, display,
normalization, retention, derived-output, intended-use, and alert permission,
and when occurrence-bound gate milestones are implemented and contract-tested.
