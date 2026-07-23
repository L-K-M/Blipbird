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
cadence policy, daylight engine) are plain JVM code with unit tests.

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

- [`ci.yml`](.github/workflows/ci.yml) — push to main / PRs / manual: wrapper
  validation, tests, lint, debug APK artifact. Hardening trio: least-privilege
  token, PR-only concurrency cancellation, job timeouts.
- [`zai-code-review.yml`](.github/workflows/zai-code-review.yml) — GLM 5.2
  reviews every non-draft PR (`pull_request_target`); no-ops without the
  `ZAI_API_KEY` secret.
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
  daylight engine) stay plain JVM and unit-tested.
