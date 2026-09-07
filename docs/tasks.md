# RockMobile task log

## DC-016 E2E debug endpoint and controller registration — 2026-09-07

- Scope: stop publishing a fabricated controller runtime snapshot and add a build-time,
  HTTPS-only debug endpoint override. Release builds retain the fixed production URL; the value is
  not persisted, displayed, logged or accepted over HTTP.
- Result: the controller starts heartbeat after registration without asserting nonexistent player
  state. A debug APK may be built with `-ProckmobileDevServerUrl=https://…`; blank, HTTP and all
  release values resolve to the production endpoint.
- Checks: `:app:testDebugUnitTest` passed. RockServer lifecycle verification is recorded in its
  repository; no APK install, deployment, pairing or hardware E2E was performed.
- Status: local implementation complete; DC-016 still awaits live acceptance.

## DC-016 — 2026-09-07 — capability-driven remote-player controls (local implementation; live E2E pending)

- Scope: typed device-only commands over the existing controller WSS lifecycle, selected-target
  validation, capability-derived Compose controls and command correlation. Local phone playback,
  pairing, account inventory and DC-015 selection behaviour remain separate.
- Result: playback, volume/mute, Chromecast discovery/connect/disconnect and relay actions/modes
  are rendered only when the current selected fresh online player advertises them and the directory
  grants `media.control`. Unsupported/unknown capabilities stay invisible. Every dispatched frame
  has one selected `device_id`, UUID command id and a 10-second deadline; no identity fields or
  broad targets are sent.
- Lifecycle: pending/received/accepted never imply success. Terminal success waits for a refreshed
  authoritative directory snapshot; failed, expired, target removal and WSS send loss remain
  visible errors. Equivalent in-flight commands are disabled and never auto-retried with a new id.
  Chromecast receivers are typed ephemeral handles filtered by `expires_at`; relay modes are the
  advertised allowlist.
- Checks: `:app:testDebugUnitTest`, `:app:lintDebug` (0 errors; pre-existing warnings) and
  `:app:assembleDebug` passed; `git diff --check` passed. Fakes cover capability mapping, unknown
  capabilities, explicit/scope-gated dispatch, target removal, lifecycle and duplicate dispatch.
- Status: local acceptance evidence exists, but DC-016 is **not marked complete** until a live
  Rockmobile → RockServer → RockCast run confirms the canonical directory state refresh and
  physical command result. DC-017+ remains ESP32-only and outside this repository change.

## DC-015 — 2026-09-06 — target selector (complete locally)

- Scope: typed account-owned directory REST/WSS consumption, revision-safe selector and explicit
  target persistence only; normal inventory/pairing/revoke/radio behaviour remains unchanged.
- Result: unknown capabilities/messages are ignored safely at the DTO boundary; malformed known
  payloads are rejected. Revision gaps, resync close and WSS loss reload the directory. No target
  is inferred or broadcast; unavailable/revoked/missing selections are cleared visibly.
- Exclusion: DC-016 command dispatch, controls and lifecycle UI are not implemented.
- Checks: `:app:testDebugUnitTest`, `:app:lintDebug`, `:app:assembleDebug` and `git diff --check`
  passed with the mandated process-local `JAVA_TOOL_OPTIONS`.
- Status: DC-015 complete locally. DC-016 remains command/control UI work.

## RM-011 — 2026-08-30 — durable device-secret client sessions

- Goal: make a paired phone survive access-token expiry and transient session-issuance failures.
- Scope: persist `device_id` and `device_secret` through Android Keystore, replace refresh calls
  with `/v1/auth/device-session`, and revoke the device for explicit disconnect.
- Result: only `device_credential_invalid` clears the protected local binding; an unavailable
  server leaves it available for retry.
- Checks: `testDebugUnitTest`, `lintDebug`, and `assembleRelease` passed.
- Status: local client implementation complete; RockServer endpoint work remains before E2E.

## RM-011-R4 — 2026-08-30 — cross-task App Link return recovery

- Goal: ensure Chrome's verified external App Link returns to the original pending RockMobile
  activity so the native completion request is sent.
- Scope: replace task-local `singleTop` with `singleTask`; release metadata advances to
  `0.1.5`/`versionCode=6`.
- Evidence: the browser approval endpoint succeeded, while a read-only staging aggregate found
  recent requests approved but not consumed; this excludes browser approval and identifies native
  completion not resuming after the external return.
- Checks: `testDebugUnitTest`, `lintDebug`, and `assembleRelease` passed for the signed
  `0.1.5`/`versionCode=6` package. Signed package update and physical verified-App-Link completion
  remain pending.
- Status: local verification complete; physical verification pending.

## RM-011-R3 — 2026-08-30 — App Link return lifecycle recovery

- Goal: retain the existing pending pairing when the browser opens the narrow credential-free
  RockMobile return App Link.
- Scope: `MainActivity` initially used Android `singleTop` launch behavior; release metadata advanced
  monotonically to `0.1.4`/`versionCode=5`.
- Result: explicit component delivery on an emulator retained the pending pairing without persisting
  its secret. Live Chrome delivery exposed that `singleTop` was insufficient across tasks; this is
  superseded by RM-011-R4. The route and URI validation remain unchanged.
- Checks: `testDebugUnitTest`, `lintDebug`, and `assembleRelease` passed; the signed package
  installed over the physical-device prior release with application data retained. A clean
  disposable emulator created a staging pairing and, after the exact credential-free return URI,
  retained the pairing and rendered its browser-return state rather than Connect. The physical
  device reports the return host as verified.
- Status: verified through the disposable lifecycle check. Browser passkey approval and final
  physical native credentials remain pending and are not claimed.

## RM-011-R2 — 2026-08-30 — stale endpoint recovery

- Goal: eliminate the client-side condition that can retain an obsolete RockServer endpoint and
  surface a misleading account-service-unavailable state.
- Scope: fixed official endpoint selection, stale-setting removal, actual pairing app-version
  reporting, and monotonic release metadata `0.1.3`/`versionCode=4`.
- Result: official builds now ignore/remove all persisted endpoint overrides; pairing sends
  `BuildConfig.VERSION_NAME`. A clean disposable emulator created a staging pairing request and
  rendered the confirmation state without an availability error.
- Checks: `testDebugUnitTest`, `lintDebug`, `assembleRelease`; public APK metadata/certificate
  verification; install on a disposable emulator.
- Status: verified locally. Passkey-dependent completion remains pending and is not claimed.

## RM-011-FINAL — 2026-08-29 — disposable signed Android release

- Goal: make the current RM-011 Android sources installable over a mismatched disposable package
  and verify the real release signer against the deployed App Link association.
- Scope: monotonic version metadata only (`0.1.3`/`versionCode=4`), a signed local release build,
  signature verification, and user-authorized removal/reinstallation of the disposable package.
- Result: in progress. The prior installed package had a signer mismatch with the published
  association, so no App Link or E2E success is claimed before the new package is built and checked.
- Status: pending local build, installation and physical passkey flow.

## RM-011-09 — 2026-08-29 — Wave 9 A4 secure pairing handoff

- Goal: construct the agreed fragment-based pairing URL and lock Android return handling to a
  credential-free resume endpoint.
- Scope: existing account models/activity and JVM account tests; no new dependency, router,
  request lifecycle or credential persistence.
- Result: QR/open links share one fragment URL helper; the exact return target rejects all query,
  fragment, wrong-host and ordinary-pairing inputs in deterministic JVM tests. Activity startup and
  new intents only resume existing pairing polling.
- Checks: `testDebugUnitTest`, `lintDebug`, `assembleDebug` and `git diff --check` passed.
- Status: complete locally. Production App Link verification requires externally managed
  `assetlinks.json` plus the private release certificate fingerprint; no deployment occurred.
