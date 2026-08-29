# RockMobile status

## RM-011 App Link return recovery (verified locally, 2026-08-30)

The exact, credential-free App Link return route now reuses the foreground `MainActivity` with
Android `singleTop` launch behavior. That delivers the return to `onNewIntent`, where the existing
in-memory pairing can continue polling; it does not broaden the intent filter or persist pairing
secrets. This fixes the observed post-browser return that otherwise created a new activity and
rendered the disconnected Connect screen. The release version is now `0.1.4`/`versionCode=5`.

Verified: `testDebugUnitTest`, `lintDebug`, and `assembleRelease` passed. The signed `0.1.4`/
`versionCode=5` package installed over the physical-device prior release without removing its app
data. On a clean disposable emulator, a staging pairing was created and the exact credential-free
return URI preserved the pending pairing, rendered the browser-return state, and did not show the
disconnected Connect screen. The physical device reports the return host as App Link `verified`.
Browser passkey approval and final physical native credentials remain unverified and are not
claimed.

## RM-011 endpoint recovery release (verified locally, 2026-08-30)

The official mobile runtime now always uses the fixed public RockServer URL and removes every stale
stored endpoint override. The old setting had no supported UI but could survive earlier development
installs and make the account flow report the server as unavailable. Pairing creation also reports
the actual APK version rather than a stale literal. The release version is now `0.1.3`/
`versionCode=4`; no signing secret is recorded in source or this document.

Verified: `testDebugUnitTest`, `lintDebug`, and `assembleRelease` passed. The APK reports
`0.1.3`/`versionCode=4`, its public signing SHA-256 matches the deployed App Link association, and
it installed on the disposable emulator. A clean emulator reached the pairing confirmation state
against staging without an availability error. Browser passkey approval and a real native session
remain unverified; no E2E success is claimed.

## RM-011-09 — Wave 9 A4 secure pairing handoff (complete locally, 2026-08-29)

Pairing QR and the same-phone browser action now use `?code=<code>#secret=<proof>`, keeping the
approval secret out of request queries. The Android return handler accepts only an `ACTION_VIEW`
HTTPS URI with host `alex.vault57.ru`, path `/return/rockmobile`, and no query or fragment. It
resumes existing polling only; it cannot create a request or accept credentials from the URI.

The manifest already declares that exact auto-verified path and does not capture the ordinary
pairing URL. This historical local result predates the deployed server association; its then-current
external publication blocker is superseded by the final-integration status above.

Verified locally with `./gradlew.bat testDebugUnitTest --console=plain`, `lintDebug`, and
`assembleDebug` (using the mandated process-local `JAVA_TOOL_OPTIONS`), plus `git diff --check`.
No push, deploy, staging mutation or physical-device flow occurred.
