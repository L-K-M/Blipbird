# Blipbird — agent/contributor notes

Single-module Kotlin + Jetpack Compose Android app. Design rationale and
roadmap live in [PLAN.md](PLAN.md); user-facing docs in [README.md](README.md).

## Releasing

`scripts/release.sh X.Y.Z --push` (a stub over the shared
[release-tool](https://github.com/L-K-M/release-tool) engine) bumps
`versionName`/`versionCode` in `app/build.gradle.kts` plus the version line at
the top of this README, commits, tags `vX.Y.Z`, and pushes. The tag triggers
[`release.yml`](.github/workflows/release.yml), which re-runs tests + lint,
builds the release APK, signs it when the `ANDROID_KEYSTORE_BASE64` /
`ANDROID_KEYSTORE_PASSWORD` / `ANDROID_KEY_ALIAS` / `ANDROID_KEY_PASSWORD`
secrets are all configured, verifies the APK signature and signing certificate,
generates a SHA-256 checksum, and publishes both as GitHub Release assets. If any
signing secret is absent, the workflow fails before building or publishing; it
never publishes an unsigned APK. Pull requests are additionally reviewed by GLM
5.2 via [`zai-code-review.yml`](.github/workflows/zai-code-review.yml) when the
`ZAI_API_KEY` secret is set.

## Architecture

Single-module Kotlin 2.4 + Jetpack Compose (Material 3) app, MVVM with
unidirectional data flow. Room is the source of truth, split by backup boundary:
`blipbird-user.db` (your tracked flights — backed up) vs `blipbird-ops.db`
(provider-derived snapshots/fixes + bundled reference tables — excluded,
rebuildable, TTL-pruned). Every external service sits behind a provider interface
with ordered failover; pure decision cores (phase machine, notification planner,
cadence policy, daylight engine, jetlag engine, transition engine) are plain JVM
code with unit tests.

Full design rationale, verified API research, and the roadmap live in
[`PLAN.md`](PLAN.md).

### Known deviations from PLAN.md in v0.1

- **Navigation:** hand-rolled 3-screen back stack instead of Navigation 3.
- **Modules:** single `:app` module ("Kotlin packages first", per plan §3).
- **Aliases:** stored on the tracked flight; separate `SavedFlight` entity and
  recurring rules remain roadmap.
- Provider legal gates from PLAN.md §4.6 (written permissions for aggregators,
  runtime enrichment services) remain open items for a store release; v0.1 is a
  source release.

## Toolchain

- JDK 17+ (CI uses Temurin 17), Gradle 9.6.1 via the committed wrapper.
- AGP 9.3.0 / Kotlin 2.4.10 / KSP — all pinned in `gradle/libs.versions.toml`.
- `compileSdk` is the string form `android-37.0` paired with
  `android.suppressUnsupportedCompileSdk=37` in `gradle.properties`; don't
  "fix" either side independently.
- No `kotlin-android` plugin — AGP 9's built-in Kotlin support is in use.
- Android SDK via `local.properties` or `ANDROID_HOME`; CI relies on the
  runner image's preinstalled SDK (no explicit install step).

## Build / test / lint

```bash
./gradlew testDebugUnitTest    # unit tests (pure-JVM decision cores)
./gradlew lintDebug            # Android lint — a CI gate, keep it clean
./gradlew assembleDebug        # debug APK
scripts/build.sh               # release APK staged into dist/ (--debug, --clean, --check)
scripts/install-debug.sh       # assembleDebug + adb install -r on a connected device
```

Bundled reference data and launcher icons are generated, committed artifacts:

```bash
python3 scripts/generate_reference_data.py   # pinned open datasets -> app/src/main/assets/reference/
python3 scripts/generate_icons.py            # media-sources/icon.png -> mipmaps
```

## CI/CD (family contract)

Full detail — per-job breakdown, the secrets table, local equivalents and the
failure modes — is in [CICD.md](CICD.md).

- [`ci.yml`](.github/workflows/ci.yml) — push to main / PRs / manual: wrapper
  validation, tests, lint, debug APK artifact. Hardening trio: least-privilege
  token, PR-only concurrency cancellation, job timeouts.
- [`zai-code-review.yml`](.github/workflows/zai-code-review.yml) — GLM 5.2
  reviews every non-draft PR from this repository (`pull_request_target`);
  no-ops without the `ZAI_API_KEY` secret. Fork PRs are excluded by design —
  `pull_request_target` hands secrets and a write-capable token to a workflow
  an outside contributor triggered — and the action is pinned to a commit.
- [`release.yml`](.github/workflows/release.yml) — tag push `v*`: verifies the
  tag matches the committed `versionName`, re-runs tests + lint, builds
  `assembleRelease`, signs with the keystore secrets
  (`ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`,
  `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`), verifies the signature, and
  publishes the signed APK plus its SHA-256 checksum. Missing signing secrets
  fail the workflow before the build; unsigned APKs are never published.
- Dependabot: weekly `github-actions` + `gradle` update PRs.

## Releasing

```bash
scripts/release.sh 0.2.0 --push
```

The stub delegates to the shared `lkm-release` engine
(<https://github.com/L-K-M/release-tool>, kind `gradle-android`): bumps
`versionName`, auto-increments `versionCode`, rewrites the README
`<!-- version -->` marker, commits, tags `v0.2.0`, pushes branch + tag; the
tag triggers `release.yml`. Never hand-edit `versionCode` for a release.

## Conventions

- Room schemas are exported to `app/schemas/` and committed — schema changes
  need a migration plus the regenerated schema JSON.
- Release builds minify (R8) — new reflection/serialization entry points may
  need `app/proguard-rules.pro` entries.
- Provider interfaces wrap every external service with ordered failover; pure
  decision cores (phase machine, notification planner, cadence policy,
  daylight engine, jetlag engine, transition engine) stay plain JVM and
  unit-tested.
- Dossier cards the user can hide (§9.6) must skip their *fetch*, not just their
  rendering — a hidden card that still spends a third-party request is a bug.
- Connection windows are gate-milestone arithmetic
  (`docs/ITINERARY_PROPOSAL.md` §8.2): only a provider whose adapter maps
  documented gate `OUT`/`IN` with a stated certainty may carry one, and
  `TransitionEngine.GATE_MILESTONE_PROVIDERS` is the single place that records
  which do. Widening it means correcting an adapter's time-family mapping first,
  with fixtures — never editing the set on its own.
- The back stack is hand-rolled, so every entry needs **both** scoping helpers:
  a per-entry `ViewModelStore` (`NavEntryScoping.kt`) *and* a
  `SaveableStateProvider` keyed on `encodeRoute()` (`MainActivity.kt`). Miss the
  second and `AnimatedContent` disposes the screen on navigate, silently dropping
  every `rememberSaveable` it owns — scroll positions and top-bar collapse state.

<!-- shared-rules:start -->

## Working practices

- Follow explicit task instructions over the default workflow below.
- Before editing, inspect the branch and working tree, fetch remote updates,
  and fast-forward where safe. Never overwrite existing work to update.
- Resolve ambiguity before making consequential changes. State low-risk
  assumptions; ask when scope, safety, or expected behavior is unclear.
- Keep changes focused. Do not modify unrelated code, formatting, or comments.
- Prefer surgical edits over whole-file rewrites when the result is equivalent.
- Stage only intended files. Inspect the diff before committing.

## Communication

- Be concise, factual, and direct. Preserve necessary context and uncertainty.
- Avoid praise, motivational filler, emojis, and em dashes in new prose.
- Address the reader directly in user-facing copy.
- Report what was verified and what remains unverified. Never imply that an
  unavailable check passed.

## Code design

- Prefer early returns and shallow nesting. Separate logical blocks with
  blank lines.
- Use descriptive constants or enums for meaningful or repeated values.
  Use existing standard definitions for protocol/specification constants.
  Keep obvious, one-off values inline.
- Use enums for behavioral modes that would otherwise require ambiguous
  boolean arguments.
- Default members to private. Widen visibility only for required consumers,
  and review the change as an API design decision.
- Follow the repository's declared dependency boundaries. UI and controllers
  must use application services rather than directly accessing databases,
  subprocesses, sockets, or other low-level mechanisms.
- Encapsulate low-level mechanics behind domain-oriented interfaces.
- Reuse genuinely shared logic. Avoid speculative abstractions and layers
  that only forward calls.
- Prefer pure functions for business rules and immutable data where practical.
  Isolate side effects; document non-obvious state ownership or synchronization.
- Explain non-obvious intent, constraints, and tradeoffs in comments.
  Do not narrate obvious code. Add examples or diagrams when they clarify it.

## Validation and errors

- Validate untrusted input at entry points. Where practical, represent valid
  states in types and enforce persistent invariants in database schemas.
- Represent absence and failure explicitly.
- Use assertions for internal programming invariants, not external-input
  validation or required runtime error handling.
- Prefer explicit, actionable errors over silent failure or undocumented
  fallback. Document intentional recovery behavior.
- Never report a skipped or failed operation as successful.

## Bug fixes

1. Identify the root cause and define an observable success criterion.
2. Add a regression test and observe the relevant failure before fixing it.
3. Implement the fix and observe the test passing.
4. Check surrounding behavior for regressions and architectural consistency.

If an automated regression test is impractical, document the reproduction
and verification procedure. State any inability to reproduce the failure.

## Verification

- Run relevant tests and lint after changes.
- Choose coverage by affected behavior and risk, not patch size.
- Use integration or end-to-end tests for critical workflows and boundaries;
  test isolated business rules at the lowest effective level.
- Run broader suites for cross-cutting or high-risk changes, and the full
  required release checks before releasing.
- Validate the requested command, options, platform, and configuration.
  Unrelated green CI is not proof that the reported problem is fixed.
- Recheck after the final edit. Distinguish local checks from CI results.

## Commit messages

- Use a capitalized, imperative subject without a final period.
- Target 50 characters; never exceed 72.
- Separate the subject and body with one blank line.
- Wrap body text at 72 characters.
- Explain what changed and why. Leave implementation mechanics to the code.

## Implementation and review

Unless explicitly instructed otherwise:

1. Work on a focused branch and open a PR against main.
2. Inspect CI results and completed review feedback for the latest commit.
   A successful reviewer job does not mean the review found no problems.
3. Address important findings or explain why they do not apply. Handle minor
   findings according to the stopping rules below.
4. Evaluate each fix in the surrounding project, add regression coverage,
   and rerun affected checks before pushing.
5. Repeat until a stopping criterion is met.
6. Merge without asking again once the stopping criterion is met, required
   checks pass on the latest commit, and no unresolved blockers or required
   human review requests remain.

### Automated review stopping rules

Judge findings by verified impact, not the reviewer's severity label.
Important findings concern correctness, security, data loss, broken builds,
or materially degraded behavior/performance.

Track completed review rounds and consecutive rounds without important
findings. Reruns of the same revision and integration failures do not count.

- No applicable actionable feedback: finish immediately.
- First minor-only round: optionally fix worthwhile, low-risk findings.
  Do not manufacture another push merely to obtain another review.
- Two consecutive rounds without important findings: stop responding to
  automated nitpicks, even if actionable minor suggestions remain.
  Defer worthwhile leftovers rather than continuing the cycle.
- A confirmed important finding resets the minor-only streak. Address it
  and verify the fix before continuing.

After ten completed rounds, enter stabilization:

- Stop optional cleanup, refactoring, and nitpick fixes.
- One completed review without confirmed important findings is sufficient
  to finish, even if minor suggestions remain.
- Continue only for confirmed important defects. If resolving them stalls,
  report the blockers rather than continuing indefinitely.

These limits end optional automated-feedback work. They do not waive
confirmed blockers, unresolved human review requests, or required checks.

### Reviewer integration failures

After two consecutive reviewer-integration failures, stop and report the
review gap. Do not treat failures as approval. An explicit user instruction
may waive review; report that waiver rather than claiming review passed.

## Completion checklist

- The requested behavior is implemented without unrelated changes.
- Relevant checks pass for the latest code.
- Important review findings are addressed or rejected with reasons.
- Deferred suggestions, remaining risks, and validation gaps are disclosed.
- The final response accurately states whether work is committed, pushed,
  and merged.

<!-- shared-rules:end -->
