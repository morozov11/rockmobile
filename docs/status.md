# RockMobile status

## DC-016 E2E controller registration and debug endpoint (local implementation, 2026-09-07)

RockMobile no longer submits an empty device runtime snapshot: it is a controller, not a player.
The corresponding RockServer lifecycle gate now permits controller-only heartbeats while retaining
the full-state requirement for player and hybrid roles. Debug APKs alone may receive an HTTPS
RockServer URL at build time through `rockmobileDevServerUrl`; release builds and invalid/HTTP
values always use the production endpoint. No URL, token or credential is stored or logged. This
removes the local transport blockers but does not claim deployment, pairing or hardware E2E.

## DC-016 capability-driven remote controls (local implementation, 2026-09-07)

The account dialog now exposes remote-player controls only after the DC-015 explicit selection is
fresh, online, player-scoped and authorized by `media.control`. Typed capability variants govern
the visible playback, volume/mute, Chromecast and relay controls; unknown variants do not leave
the API boundary. Commands use only the selected device target, a generated command UUID and a
bounded deadline through the existing controller WSS connection. The client tracks received,
accepted and terminal frames by command UUID, disables equivalent in-flight actions, never
auto-retries under a new UUID, and waits for a refreshed directory snapshot before reporting a
successful result. Receiver identifiers stay player-local and expire at their server deadline.

Local fake transport tests, debug unit tests, lint (0 errors) and a debug APK build pass. A live
Rockmobile → RockServer → RockCast server/hardware environment was not available, so end-to-end
command execution and factual playback/volume/output state have not been claimed. DC-016 remains
pending that acceptance; DC-017+ is not part of RockMobile.

## DC-015 target selector (complete locally, 2026-09-06)

RockMobile now has an owner-scoped device-control directory selector inside the existing account
dialog. It uses the existing native session for typed `GET /api/v1/device-control/directory` and
the controller WebSocket registration; no pairing, device-list, revoke, credential store or
ordinary radio flow was replaced. Strict DTO decoding stays at the `devicecontrol` API boundary.
The repository maps only domain targets into Compose, applies monotonic directory revisions,
reloads on gaps/resync/loss, bounds WSS frames and retries without a busy loop.

Only a target tapped by the user is persisted by account/controller-device context. Missing,
revoked, offline, stale or unknown targets are invalidated visibly, never substituted. The
selector presents RockCast truthfully through type/player role, online and freshness state, but
contains no playback/volume/relay/Chromecast controls or command dispatch. DC-016 remains the
separate command/control UI task.

Verified with `:app:testDebugUnitTest` (including five fake typed REST/WSS directory cases),
`:app:lintDebug`, `:app:assembleDebug` and `git diff --check`. DC-016 remains explicitly separate:
there is still no command dispatch or control UI.

## RM-011 durable device-secret sessions (implemented locally, 2026-08-30)

RockMobile now keeps a `device_id` and persistent `device_secret` in its Android Keystore-protected
credential blob, alongside the replaceable access token. A protected-request `401` obtains a new
token through `POST /v1/auth/device-session`; the binding is cleared only for
`device_credential_invalid`. The old rotating refresh-token and native logout endpoints are no
longer used. Server support for the new endpoint is required before staging E2E.
Verified: `testDebugUnitTest`, `lintDebug`, and `assembleRelease` passed locally.

## RM-011 App Link return recovery (physical defect fixed locally, 2026-08-30)

The exact, credential-free App Link return route now uses Android `singleTask` launch behavior.
`singleTop` passed an explicit-emulator-intent check but does not guarantee reuse when Chrome opens
an App Link from its own task: live staging evidence showed approved requests never reached native
completion. `singleTask` returns that external intent to the existing `MainActivity`, where the
in-memory pairing can continue polling. It does not broaden the intent filter or persist pairing
secrets. The release version is now `0.1.5`/`versionCode=6`.

The prior `0.1.4`/`versionCode=5` build passed unit/lint/release checks and an explicit-emulator
return check, but staging browser approval left two requests approved but unconsumed. The exact
`singleTask` replacement passed `testDebugUnitTest`, `lintDebug`, and `assembleRelease` as
`0.1.5`/`versionCode=6`. Physical verified-App-Link completion remains pending. Browser passkey
approval and final physical native credentials remain unverified and are not claimed.

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
