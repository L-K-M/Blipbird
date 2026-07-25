# CI/CD

Blipbird is a single-module Kotlin + Jetpack Compose Android app. CI tests, lints and
assembles a debug APK on every change; the release workflow builds, signs and publishes a
release APK with a SHA-256 checksum when a `v*` tag is pushed.

[AGENTS.md](AGENTS.md) has the one-paragraph summary. This file is the detail: what each
job does, which secrets it needs, how to reproduce it locally, and what the failure modes
mean.

## Workflows

| Workflow | Trigger | Purpose |
| --- | --- | --- |
| [`ci.yml`](.github/workflows/ci.yml) | Pull requests, pushes to `main`, manual dispatch | Wrapper validation, unit tests, Android lint, debug APK, and instrumentation tests on two API levels. |
| [`release.yml`](.github/workflows/release.yml) | Pushing a `v*` tag (e.g. `v1.2.0`) | Re-prove the tagged commit, assert tag/version agreement, build, sign, verify and publish. |
| [`zai-code-review.yml`](.github/workflows/zai-code-review.yml) | Non-draft PRs from this repository | GLM 5.2 review when `ZAI_API_KEY` is configured. |

All three carry the family hardening trio: least-privilege `permissions`, a `concurrency`
group, and `timeout-minutes` on every job. `ci.yml` gates `cancel-in-progress` on
`github.event_name == 'pull_request'`, so a superseded PR run is cancelled but an
in-progress `main` run never is — a permanently "cancelled" main commit masks breakage and
puts holes in CI-status bisection. `release.yml` sets `cancel-in-progress: false`: a
half-cancelled publish is worse than a slow one.

## Continuous integration (`ci.yml`)

Two jobs.

**`android`** (`ubuntu-latest`, 45 min):

1. `gradle/actions/wrapper-validation@v5` — a supply-chain gate that rejects a tampered
   `gradle-wrapper.jar`. It runs before anything else executes the wrapper.
2. Temurin JDK 17, then `gradle/actions/setup-gradle@v5` for the dependency and build
   cache.
3. `./gradlew testDebugUnitTest lintDebug assembleDebug`.
4. Uploads the debug APK as `blipbird-debug-<sha>` (14-day retention).

There is deliberately **no Android SDK install step**: the ubuntu runner image ships one,
and AGP resolves `compileSdk android-37.0` against it (paired with
`android.suppressUnsupportedCompileSdk=37` in `gradle.properties` — don't "fix" either
side independently).

**`instrumentation`** (matrix over API 26 and 35, 35 min, `fail-fast: false`): enables KVM
via a udev rule, then runs `connectedDebugAndroidTest` under
`ReactiveCircus/android-emulator-runner@v2` with a 600-second boot timeout. API 26 is the
`minSdk` floor and API 35 a current release, so a change that only works on one of them
fails here rather than on a user's phone. The two API levels run independently — one
flaking doesn't hide the other's result.

### Running CI checks locally

```sh
./gradlew testDebugUnitTest lintDebug assembleDebug   # the `android` job
./gradlew connectedDebugAndroidTest                   # the `instrumentation` job (needs a device/emulator)
```

`scripts/build.sh` is the friendlier local build: it stages a version-named APK into
`dist/` and reveals it in the Finder on macOS. `scripts/install-debug.sh` builds and
`adb install`s onto a connected phone.

## Releases (`release.yml`)

Cut a release with the helper:

```sh
scripts/release.sh 1.2.0 --push
```

That bumps `versionName` in `app/build.gradle.kts`, auto-increments `versionCode` (Android
requires a new code for every release), rewrites the README `<!-- version -->` marker,
commits, creates the annotated tag `v1.2.0`, and pushes branch + tag. It is a ~25-line
stub over the shared [lkm-release](https://github.com/L-K-M/release-tool) engine, kind
`gradle-android`. **Never hand-edit `versionCode`, and never create a `v*` tag by hand.**

The tag push triggers a single `release` job (`ubuntu-latest`, 60 min):

1. **Validates all four signing secrets up front** and fails with the list of missing
   ones. Nothing is built before this passes — a tag must never publish an unsigned APK.
2. Wrapper validation, JDK 17, Gradle setup.
3. **Asserts the tag matches the committed `versionName`.** The APK is built from
   `app/build.gradle.kts` at the tagged commit, so a mismatched tag would ship a build
   labeled with a version it isn't. The gate reads the line with the same tolerant pattern
   the `lkm-release` engine uses to write it (flexible spacing, trailing comments allowed),
   so it accepts everything the bumper can produce.
4. `./gradlew testDebugUnitTest lintDebug` — a `v*` tag can land on any commit, including
   one CI never saw.
5. `./gradlew assembleRelease` (R8-minified).
6. Signs: decodes the keystore, picks the **highest** installed Build Tools version by
   `sort -V`, `zipalign -p -f 4`, then `apksigner sign`. Verifies with `apksigner verify
   --verbose --print-certs` — signing without verifying would happily publish an APK no
   device will install.
7. `sha256sum` the signed APK.
8. Publishes `blipbird-v<version>.apk` and its `.sha256` with auto-generated notes. A tag
   containing a hyphen (`v1.2.0-rc.1`) is published as a pre-release.

The keystore and the aligned intermediate are removed by a shell `trap ... EXIT`, so they
are cleaned up even when a step fails.

## Secrets

`ci.yml` needs none — it runs on forked-PR branches too.

`release.yml` requires all four, and fails closed without them:

| Secret | What it is |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | The release keystore (`.jks`), base64-encoded: `gh secret set ANDROID_KEYSTORE_BASE64 --repo L-K-M/Blipbird --body "$(base64 < release.jks \| tr -d '\n')"` — `base64 -w0` is GNU-only and errors on the maintainer's Mac |
| `ANDROID_KEYSTORE_PASSWORD` | Password for the keystore file |
| `ANDROID_KEY_ALIAS` | Alias of the signing key inside the keystore |
| `ANDROID_KEY_PASSWORD` | Password for that key |

The base64 secret is decoded through `tr -d ' \t\r\n'` first, so a 76-column wrapped paste
or a stray carriage return doesn't fail the release — only genuinely invalid base64 does.

**Losing the keystore is unrecoverable.** Android identifies an app by its signing
certificate; a differently signed APK cannot upgrade an installed Blipbird, and users have
to uninstall first (losing `blipbird-user.db`). Keep an offline backup.

`zai-code-review.yml` uses `ZAI_API_KEY` and skips itself cleanly when it is unset. It runs
on `pull_request_target`, which exposes repository secrets and a write-capable token to a
workflow triggered by an outside contributor's PR — so the job is guarded on
`github.event.pull_request.head.repo.full_name == github.repository` and never runs for
forks, and the third-party action is pinned to an immutable commit rather than a moving
branch.

## Dependabot

Weekly, for both `github-actions` and `gradle` (limit 10). One deliberate exclusion:
major-version bumps of `gradle/actions` are ignored, because v6 relicensed its caching
component to a proprietary commercial ToU. Blipbird stays on the fully-open v5 and keeps
taking v5.x minor and patch updates.

## Troubleshooting

- **`Release signing is not fully configured`** — one of the four secrets is unset. Add it
  and re-push the tag.
- **`Tag vX.Y.Z does not match versionName`** — the tag was created by hand. Delete it and
  re-cut with `scripts/release.sh`.
- **`ANDROID_KEYSTORE_BASE64 is not valid base64`** — re-create the secret with
  `base64 < release.jks | tr -d '\n'`; the workflow's error message includes the exact
  `gh secret set` command.
- **`No Android SDK Build Tools installation found`** — the runner image changed. The step
  selects the highest version present; it does not install one.
- **Instrumentation job times out booting** — the emulator occasionally fails to come up
  on a cold runner. Re-run the job; the two API levels are independent, so only the failed
  one needs re-running.
- **Lint fails on a new warning** — `lintDebug` is a hard CI gate. Fix it, or add a scoped
  suppression with a comment saying why.
