# RockMobile task log

## RM-012-C — 2026-09-28 — live acceptance fixes: quarantine removal port

- Goal: close the two-device acceptance defects reported live — the phone's favourites list
  looked purely local, and "Радио Ваня" favourited on the phone never reached RockCast.
- Scope: port RockCast's `restore-and-remap` to `resolvePersonalData` (quarantine removed
  entirely; unresolved references restored as live records with original ids; known legacy
  rewrites still advance `updatedAt`); Room-free `stationsById` read-only lookup in
  `ExtendedCatalogStationSource` for names/streams of stations outside the loaded catalogue
  (Room's pre-packaged identity check rejects the asset — pre-existing silent failure);
  Favourites/History dialogs render resolved stations and show synced-storage copy while an
  account is connected; `MainActivity` resolves personal station ids against the extended
  catalog off the main thread.
- Result: root cause of both complaints was the RM-007-A quarantine (vanya quarantined on the
  phone since 2026-09-18; synced rb-* favourites would have been tombstoned account-wide on
  the next restart). After the port, live evidence: phone 9→11 favourites (Радио Ваня Туапсе,
  RadioBOB restored with names), pushed at cursor 384→489, RockCast pulled both; the phone's
  Favourites dialog lists all 11 records with names, tags and playable streams; history
  converges on both sides.
- Checks: `:app:testDebugUnitTest` (rewritten quarantine tests: unresolvable ids stay,
  references restore), `:app:lintDebug`, `:app:assembleDebug`, `git diff --check` — green.
  Live two-device evidence collected from prefs/sync-state/UI dumps and the RockCast log
  (app logcat tags produce no output on this phone).
- Status: two-device acceptance for add/restore→arrival and convergence performed live with
  real data; explicit deletion propagation covered by unit tests, not re-verified live.

## RM-012-C — 2026-09-28 — client favourites/history sync with RockServer

- Goal: converge RockMobile favourites and playback history with the account on
  `https://rockplatform.win` through `POST /api/v1/sync` (RM-012-A contract), porting the
  RockCast RM-012-B experience instead of reinventing it.
- Scope: strict snake_case kotlinx wire DTOs (`personalsync/PersonalSyncDtos.kt`) kept apart
  from the camelCase org.json profile; `HistoryEntry.updatedAt` with a schema v1→v2
  backfill (`updatedAt = lastPlayedAt`) through the existing backup-plus-journal migration;
  per-device sync state (cursor + acknowledged base) in the profile SharedPreferences with a
  reset on new pairing or recreated profile; batch chunking ≤300 per collection with cursor
  threading between chunks; response application via `PersonalDataStore.applySyncRecords`
  (tombstones, strict LWW, boundary validation, idempotency); event-driven triggers
  (startup, ~10 s edit debounce, ~5 min pull, foreground/account-dialog) without
  WorkManager and off the main thread; 401→renew→resend once, 429/503 backoff, 422 surfacing;
  session refresh extracted into `account/NativeSessionManager.kt` shared with
  `AccountViewModel`; one sync status line in the account dialog; phase/counter-only logging.
- Result: a paired phone pulls the full account snapshot on first sync and pushes its local
  profile; local edits and deletions reach the server as upserts and tombstones, remote
  changes apply through the ordinary profile write path, and the cursor advances only after
  a durably applied response. Offline radio behaviour is unchanged. Known v1 limitation kept:
  the same listening session recorded by two devices stays as two history records.
- Checks: `:app:testDebugUnitTest` (186 tests, 0 failed; new suites: `PersonalSyncApplyTest`,
  `PersonalSyncContractTest`, `PersonalSyncStateTest`, `PersonalSyncEngineTest`,
  `PersonalSyncCoordinatorTest`, migration tests in `PersonalDataTest`, status-line test in
  `AccountSessionTest`), `:app:lintDebug`, `:app:assembleDebug`, `git diff --check` — all
  green with the mandated process-local `JAVA_TOOL_OPTIONS`.
- Status: complete locally. The final two-device acceptance (favourite added on one device
  appears on the other, deletion arrives as a tombstone, history converges after a couple of
  syncs — the open RM-012-B item) requires physical RockCast + RockMobile devices and is not
  claimed here.

## Remote output, station name and track metadata (2026-09-26)

- Follow-up: a non-empty search field replaces its trailing microphone with clear and search icons. Clear restores the catalogue and microphone; search submits the typed query without recording audio. Typing keeps the current list visible, while the button closes the keyboard and shows progress or a failure reason. Tests cover explicit submission and preservation of server-ranked results.
- Live check: on the connected phone, `reggae` kept the 41-station starter list until search was tapped, then displayed 20 RockServer stations with the keyboard closed.

- Goal: keep RockCast selected during remote control and show the station and current track on the phone.
- Scope: exact server station lookup with extended SQLite fallback; optional runtime `track_title` presentation; preserve selected target through temporary offline/stale state; route voice station results and catalogue taps to the current output.
- Checks: `:app:testDebugUnitTest :app:assembleDebug` passed; installed on the connected phone. Two live catalogue switches reached RockCast and showed distinct track titles. Spoken microphone command remains unverified after the fix.
- Status: implemented and installed on the phone.

## RM-4 — Station-First state-driven live playback UI (implemented locally, 2026-09-17)

- Goal: render confirmed station/playback/volume from the directory `runtime_state`
  projection (RS-8) with a Station-First navigation revision (2026-09-17), keeping
  pending intent, volume gesture and catalogue presentation strictly separate.
- Scope: `runtime_state` DTO/domain (`DirectoryDtos.kt`, `DirectoryModels.kt`,
  absence → `Unknown`); pure `LivePlaybackReducer` + shared `LivePlaybackStore`
  (§4.4 matrix, external override, drag isolation, single commit-on-release);
  `StationPlayerScreen` (hero, output-device selector with reasons, on-air badge
  without seek/timeline, capability-driven transport, volume card);
  `LiveMiniPlayer`; catalogue rows without per-device buttons; dead `PlayerScreen`
  removed; `MainActivity` wiring incl. override-driven station-screen expansion
  and manual-retry snackbar.
- Result: a station is never labelled playing from a tap or `succeeded` result
  alone; only a fresh matching `station_id` with buffering/playing state confirms.
  Absent state degrades to `Unknown`, missing catalogue entries to `Станция <id>`.
- Checks: `compileDebugKotlin`; `testDebugUnitTest` — 129 tests, 0 failed
  (incl. 15 `LivePlaybackReducerTest` cases: §4.4 matrix rows, override, stop,
  failure/retry, drag isolation/commit/echo, absence, stale revision, reconnect
  snapshot, mini-player auto-select, catalogue fallback); `lintDebug`;
  `git diff --check`. All with the mandated process-local `JAVA_TOOL_OPTIONS`.
- Canonical design and dependency gates:
  [`rockmobile-rockcast-live-control.md`](../../rockserver/docs/roadmap/rockmobile-rockcast-live-control.md).
- Status: **implemented locally; physical USB acceptance (Phase 4) pending.**
  Relay/Chromecast controls and raw stream URLs are out of scope.

## RC-3 — live RockCast control accepted (2026-09-17)

- Root cause: RockCast sent a changed manifest with an already-stored revision 2;
  RockServer correctly returned `registration_rejected`. The target was present in
  inventory but could not become online.
- Fix and safety: manifest revision is now 4. The UI exposes `Остановить` when
  `media.playback.stop` is advertised. It no longer receives premature relay/Cast
  controls, whose server routing remains pending in RS-7.
- Live acceptance: with the debug APK installed on the USB-connected phone, the
  refreshed RockCast target was online, explicitly selected, and one `playback.stop`
  completed as `Состояние плеера подтверждено.`

## RM-1 — play catalog stations on a device-control target (2026-09-09)

- Scope: play catalog stations on an explicitly selected usable device-control target (player role,
  online presence, fresh state, media.control scope granted) advertising media.station with
  rockserver_catalog capability. Gated from station list and player screen without auto-retry.
- Result: "Играть на устройстве" is integrated into StationRow and PlayerScreen; TargetDirectoryRepository
  dispatches station.play_station using the existing 10s deadline and command lifecycle. Target
  unavailability displays clear reasons (offline, unselected, stale, missing scope). Late Succeeded
  arriving after Expired is accepted and shown as "Выполнено после истечения ожидания". On command failure,
  a Snackbar offers manual retry.
- Checks: `:app:testDebugUnitTest`, `:app:assembleDebug`, and `git diff --check` passed. Unit tests
  cover RemoteCommand.PlayStation construction, direct_stream rejection, capability gating, and
  the Expired -> late Succeeded lifecycle.
- Status: local implementation and module tests complete. End-to-end live verification on physical
  ESP32 hardware is deferred pending RE-11 provisioning.


## DC-016 — controller socket refresh after dialog re-entry (2026-09-07)

- Fixed an E2E timeout where the account dialog retained an object for an already-dead controller
  WebSocket. Re-entering the dialog now moves the directory into loading and obtains a fresh
  REST/WSS snapshot before exposing controls; command frames are not retried or broadened.
- Live result: after the companion RockCast idle wake-up fix, an explicitly selected `Stop`
  completed in staging as `succeeded` in under one second.
- Checks: `:app:testDebugUnitTest`, `:app:assembleDebug` and `git diff --check` passed.
- Status: physical command E2E recovered; the connected debug APK contains the fix.

## DC-016 E2E controller registration and debug endpoint — 2026-09-07

- Scope: stop publishing a fabricated controller runtime snapshot and add a build-time,
  HTTPS-only debug endpoint override. Release builds retain the fixed production URL; the value is
  not persisted, displayed, logged or accepted over HTTP.
- Result: the controller starts heartbeat after registration without asserting nonexistent player
  state. A debug APK may be built with `-ProckmobileDevServerUrl=https://…`; blank, HTTP and all
  release values resolve to the production endpoint.
- Live result: after a debug APK install with the HTTPS-only build-time override, the paired
  Android controller and paired RockCast appeared online in the authoritative directory. An
  explicitly selected RockCast accepted a physical `Stop` command; the phone reported terminal
  state confirmation and the staging command lifecycle was recorded as `succeeded`.
- Blockers fixed during the run: directory REST was moved off the main thread; a controller whose
  initial directory scope is granted only by WSS registration now bootstraps that socket; command
  envelopes retain required protocol defaults; and the sealed command serializer is registered.
  Socket-heartbeat interruption is handled as normal shutdown.
- Checks: `:app:testDebugUnitTest` (102 tests), `:app:assembleDebug` and `git diff --check`
  passed.
- Status: live acceptance complete.

## DC-016 — 2026-09-07 — capability-driven remote-player controls (live E2E complete)

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
- Status: a live RockMobile → RockServer → RockCast run confirmed directory refresh and a
  terminal physical command result. DC-017+ remains ESP32-only and outside this repository
  change.

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
