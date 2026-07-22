# Security Policy

## Reporting a vulnerability

Blipbird has **no application backend** — there is no server, API, or database
operated by the project. Reportable vulnerabilities are therefore limited to
the on-device Android client and the bundled/generated data pipeline.

**Please do not open a public GitHub issue for security reports.** Instead,
report them privately:

- Open a **private security advisory** via GitHub's
  [Report a vulnerability](https://github.com/L-K-M/Blipbird/security/advisories/new)
  tab, or
- email the maintainer (see the GitHub profile).

Please include: affected version/commit, a reproduction, and your assessment
of impact. We will acknowledge receipt within a reasonable window and
coordinate a fix and disclosure timeline with you.

## Scope

In scope:

- The Android client (`app/` once it exists) — crash/ANR causes, data leakage
  in logs/exports/diagnostics, secrets (provider API keys) handled contrary to
  `PLAN.md` §16 (they must be Keystore-encrypted, excluded from backup/export/
  logs, and redacted from diagnostics).
- The bundled-data generators — anything that pulls in data beyond its
  documented license, or embeds a secret.

Out of scope:

- Vulnerabilities in third-party data providers (report to the provider).
- Findings from automated scanners that cannot be reproduced.
- Best-effort background notification latency (a known platform limitation,
  see `PLAN.md` §12.2, not a security issue).

## Privacy posture

There is no account, analytics SDK, or Blipbird-operated data backend. Enabled
providers receive the flight designator, date/range, airports/registration/hex,
IP address, and a custom User-Agent; these queries can reveal travel interests
and are disclosed per-provider in onboarding and the privacy screen
(`PLAN.md` §16). CrashRouter never auto-uploads anything; diagnostics are
local and aggressively redacted and the user chooses to share.
