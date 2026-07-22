# Blipbird — agent/contributor notes

Single-module Kotlin + Jetpack Compose Android app. Design rationale and
roadmap live in [PLAN.md](PLAN.md); user-facing docs in [README.md](README.md).

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
  `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`) or attaches the unsigned APK
  with a warning, then publishes the GitHub Release.
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
