# Blipbird — Contributor guide

Thanks for your interest in Blipbird. This is a small, opinionated project; the
bar is craft and correctness, not churn.

## Read first

1. [PLAN.md](PLAN.md) — the design & implementation plan. Every external data
   source, retention boundary, and provider gate is documented there for a
   reason. A change that quietly weakens a §4.6 provider gate or a §16 red
   line is not a change we can take.
2. [ANALYSIS.md](ANALYSIS.md) — the living review/findings document. Check
   whether your idea or bug is already recorded before opening an issue.
3. [GLOSSARY.md](GLOSSARY.md) — terminology.

## Project status

**v0.1 is a working release** (single `:app` module, hand-rolled nav, aliases stored
on the tracked flight — see "Known deviations from PLAN.md" in
[README.md](README.md)). The plan's provider legal gates (PLAN.md §4.6) remain open
items for a store release, so v0.1 is a source release. Contributions that close
those gates, build out the remaining milestones (M2–M4), or fix
[ANALYSIS.md](ANALYSIS.md) findings are welcome; follow the §3 tech-stack pins and
the §14 exit criteria.

## Code & change conventions

- **Kotlin, Jetpack Compose, Material 3** per `PLAN.md` §3. Keep plugin
  versions in the version catalog; do not pin libraries ad hoc.
- **Pure decision cores stay pure.** `FlightPhaseMachine`,
  `NotificationPlanner`, the cadence policy, and the daylight engine are plain
  JVM, injectable-`Clock`-friendly, and unit-tested. Do not add Android
  dependencies to them.
- **Provider interfaces wrap every external service.** An implementation
  enters a release build only after its §4.6 gate closes. Failover is
  centralized; never route around a license, auth error, or spend stop.
- **Retention is executable policy.** Each provider/data class maps to a
  reviewed persistence permission and TTL. Do not persist a raw provider
  payload.
- **No secrets in the repo.** No API keys, no restricted test payloads, no
  logos/photos without documented rights. Secret scanning runs on source and
  artifacts.
- **Tests are the bulk of the suite.** See `PLAN.md` §15. New behavior ships
  with JVM unit tests for the decision cores and contract fixtures for any
  provider change.
- **Commit messages** are concise and imperative (see `git log`). Do not commit
  commented-out code.

## Decision records

Any non-trivial decision — especially provider licensing/retention answers,
scope deviations, or a §4.6 gate close/open — is recorded as an Architecture
Decision Record (ADR) in [`docs/decisions/`](docs/decisions/) using the
[template](docs/decisions/TEMPLATE.md). Copy the template, increment the
number, and link it from `docs/decisions/README.md`. Archive the written
provider answers (without credentials or confidential contract text) as an ADR;
a disclaimer never replaces legal certainty (`PLAN.md` §4.6).

## Licensing

By contributing, you agree your contributions are released under the project's
[Unlicense](LICENSE) (public domain). Bundled data keeps its own per-source
license; do not relicense data by mixing it into code.
