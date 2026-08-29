# RockMobile task log

## RM-011-R3 — 2026-08-30 — App Link return lifecycle recovery

- Goal: retain the existing pending pairing when the browser opens the narrow credential-free
  RockMobile return App Link.
- Scope: `MainActivity` only uses Android `singleTop` launch behavior; release metadata advances
  monotonically to `0.1.4`/`versionCode=5`.
- Result: a return intent is delivered to the foreground activity's existing `onNewIntent` path,
  allowing its in-memory pending pairing to continue without persisting its secret. The route and
  URI validation remain unchanged.
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
