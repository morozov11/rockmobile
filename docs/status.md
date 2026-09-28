# RockMobile status

## RM-012-C — live two-device acceptance round (2026-09-28 evening)

The first real RockMobile↔RockCast sync run exposed the RM-007-A quarantine as the single root
cause of both reported defects. "Радио Ваня" had been a favourite on the phone until
2026-09-18, when `reconcile` quarantined it (`unresolved`, shown as "Station unavailable")
because its radio-browser id is not in the 41-station bundled catalog — so sync never saw it
as a record and RockCast could never receive it. Worse: the favourites synced from RockCast
(rb-*/legacy-*) would be quarantined on the next app restart, after which the sync diff would
have pushed tombstones and deleted them account-wide.

Fix, ported one-to-one from RockCast's own `restore-and-remap` (quarantine is disabled there
already): nothing is quarantined anymore — a station id the catalog cannot resolve stays in
the profile (listed, plays nothing until discoverable); known legacy ids are still rewritten
(with an `updatedAt` advance so the rewrite wins server LWW); every previously quarantined
reference returned to the live collections with its original station id, `referenceId` as
record id and `firstSeenAt` as timestamps. Tombstones now only ever originate from explicit
user deletions (`toggleFavourite` off, `clearHistory`), which is what the diff always assumed.

A second, older defect surfaced during verification: Room rejects the prebuilt extended
catalog with `IllegalStateException: Pre-packaged database has an invalid schema`, so the
extended fallback and any Room-based lookup have been silently failing (the app fell back to
the bundled list; `runCatching` hid it). Personal-data name/stream resolution now uses a
Room-free read-only SQLite connection over the verified database copy
(`ExtendedCatalogStationSource.stationsById`), which needs no schema validation.

Verified live on the connected phone and the desktop: the restore brought the phone from 9 to
11 favourites (Радио Ваня Туапсе, RadioBOB) with names, pushed them (cursor 384→489), and
RockCast pulled both within minutes; the phone's Favourites/History dialogs now render all
synced records with names, tags and playable streams; copy says "Synced with your Rock
account" while connected. Full checks green (`:app:testDebugUnitTest` incl. rewritten
quarantine tests, `:app:lintDebug`, `:app:assembleDebug`, `git diff --check`). Note: on this
phone the app's own logcat tags produce no output (OEM suppression), so acceptance was
verified from device data — prefs, sync state, UI dumps and the RockCast log/profile.

## RM-012-C — client favourites/history sync with RockServer (implemented locally, 2026-09-28)

RockMobile now syncs the RM-007-A personal profile with `POST /api/v1/sync` on
`https://rockplatform.win` (RockCast RM-012-B experience ported, not reinvented).
The cycle is event-driven without WorkManager: startup, a ~10 s debounced push after local
edits, a ~5 min periodic pull, and a cycle on foreground return / opening the account screen.
Cycles never touch the main thread and never block the UI; offline radio keeps working
exactly as before when the server is unreachable.

Key mechanics, mirroring RockCast where the contract is shared:

- Wire DTOs live in `personalsync/PersonalSyncDtos.kt` as strict snake_case kotlinx types
  (the `DirectoryJson` pattern); the local profile stays camelCase org.json.
- `HistoryEntry` gained `updatedAt`. Schema v1→v2 migration backfills
  `updatedAt = lastPlayedAt` through the existing backup-plus-journal mechanism
  (`profile.pre-migration`, `profile.migration-journal`); `recordPlay`/coalescing keep the
  stamp current, and a station-id rewrite in `reconcile` now advances `updatedAt` so the
  rewritten record can win server-side LWW instead of tying forever.
- Per-device state (cursor + acknowledged base of exact pushed record versions) is persisted
  in the profile SharedPreferences; a new pairing (`deviceId` change) or a recreated profile
  (`profileId` change) resets it to `since_revision=0` (full snapshot) plus a full local push.
- Push batches are chunked to ≤300 per collection, and each chunk response's
  `server_revision` becomes the next chunk's `since_revision`, so later chunks pull deltas.
  The acknowledged base is rebuilt from the pushed snapshot plus the response echoes:
  records created mid-cycle are not marked acknowledged (still pushed next cycle), records
  deleted mid-cycle keep their snapshot version (tombstoned next cycle).
- Applying the response goes through `PersonalDataStore.applySyncRecords` → `update()`:
  tombstones delete by `record_id`, strict-newer `updated_at` replaces (ties keep local),
  malformed records (bad UUID, unknown station-id shape, unparsable time) are skipped at the
  boundary, and re-applying the same response is a no-op. Losing-push echoes leave the local
  winner untouched.
- The cursor is persisted only after the response is durably applied.
- Errors follow the contract: 401 → one session renewal and resend per request;
  429/503 → exponential per-device backoff (1…16 min); 422 → the batch is rejected
  (`details.field` surfaced in status, no backoff); the account screen shows one sync status
  line; logs carry phases/counters only and never tokens.
- Session refresh (`ensureFreshAccessToken`/`renewDeviceSession`) was extracted from
  `AccountViewModel` into the shared `NativeSessionManager` used by both account calls and
  the sync channel — no duplicated renewal code, tokens never copied anywhere.

Known v1 limitation (unchanged, by design): one listening session recorded independently by
two devices stays as two history records; coalescing is local-only, the server does not
deduplicate.

Verified with `:app:testDebugUnitTest` (186 tests, 0 failed — including LWW/tombstone/echo,
cursor threading and chunking, 401-renew-retry, 429/503 backoff, 422, first-sync full
push+snapshot, migration backfill, idempotency, pairing reset), `:app:lintDebug`,
`:app:assembleDebug`, and `git diff --check`. The final two-device acceptance run
(RockCast + RockMobile on one account) is the remaining open item of RM-012-B/C and
requires physical devices; no such run is claimed here.

## Voice control recovery (2026-09-26)

The phone restores local player volume as soon as microphone recording ends, before waiting for
RockServer's voice response. A new remote station selection can supersede an earlier station
command that is still in flight; repeated requests for the same station remain deduplicated.
All 143 Android unit tests and debug assembly passed. The updated APK was installed on the
connected phone; the user confirmed that station switching and phone audio work after a spoken
command.

## Remote output, station name and track metadata (2026-09-26)

The search field now shows clear and search icons instead of a microphone when it contains text.
Search submits the typed query immediately without voice capture, while clear restores the catalogue
and the microphone icon. Typing alone keeps the current list visible; the search button shows a
progress indicator and closes the keyboard, and an unavailable search shows a reason.
On the connected phone, typing `reggae` kept the 41-station starter list; tapping search closed the
keyboard and displayed 20 RockServer results.

The selected RockCast target now remains selected through temporary offline/stale states.
Choosing it in Devices also selects remote output; catalogue taps and voice station results use
the same current output. Voice no longer forces phone playback. A remote station missing from the
41-station starter list is resolved by exact RockServer catalog ID, then verified extended SQLite.
The remote mini-player and station screen show optional `track_title` from RockCast runtime state.
Android unit tests and debug assembly passed; the APK was installed on the connected phone.
Two successive catalog taps changed the live desktop station, and the phone displayed the station
name and changing track title. A post-fix spoken microphone command has not been verified.

## RM-12 — rockplatform.win domain and server-owned station icons (implemented locally, 2026-09-24)

RockServer production moved to `https://rockplatform.win`: the settings
production base URL, the AndroidManifest account-return deep link, the
`MainActivity` deep-link host check and their tests now use the new domain
(old-domain entries in `docs/status.md` below are historical records). The
server publishes a nullable same-origin `favicon_url` path
(`/api/v1/stations/{id}/icon`) that the pre-server icon loader would have
rejected as non-absolute.

`StationIconLoader.sourceUrl` now resolves such relative paths against the
resolved RockServer base URL (passed by `StationLogo` from
`resolvedRockserverUrl(BuildConfig.DEBUG, BuildConfig.DEBUG_ROCKSERVER_URL)`),
so RockServer stations fetch their icon from RockServer only. Protocol-relative
(`//host/...`) and non-rooted sources are rejected; an absolute favicon URL
(offline-catalog station) still wins; the homepage `/favicon.ico` fallback
without scraping remains only for stations without a server icon URL. The
bounded download/decode/cache and letter-tile placeholder are unchanged; WebP
decodes through `BitmapFactory`. Checks: `gradlew test` and `gradlew
assembleDebug` passed. Not yet exercised against the live server from a
physical device.

## RM-4 — Station-First authoritative live playback UI (implemented locally, 2026-09-17)

Navigation now starts from music, not from devices. The station catalogue
(`StationsScreen`) is a clean searchable list whose rows open one station screen;
per-device playback buttons were removed from the rows. `StationPlayerScreen`
renders the station hero card, an output-device selector (`Играть на: [ RockCast ·
В сети ▼ ]` / `Этот телефон`, with a directory chooser and per-target
online/offline + freshness reasons), a live on-air indicator without any
seek/timeline, a capability-driven transport (`|◀`, accent `▶ Play`, `⏹`, `▶|`;
Pause appears only for targets advertising the `pause` action — RockCast does
not), and the selected target's volume card with drag isolation and a single
`volume.set_volume` commit on release (`Применяем…` until the device echo).

Presentation truth comes from one shared `LivePlaybackStore`/`LivePlaybackReducer`
fed by the directory `runtime_state` projection (RS-8) and the existing command
lifecycle: `lastConfirmedState` and `pendingIntent` are kept separate, terminal
`succeeded` alone stays `Ожидаем подтверждения…`, a fresher state with another
`station_id` immediately cancels the pending intent as an external override, an
absent `runtime_state` degrades to `Unknown` (never fabricated `stopped`/`0%`),
and a station missing from the catalogue falls back to `Станция <id>`. The sticky
`LiveMiniPlayer` shows only confirmed remote or local phone playback and opens the
confirmed station screen; dispatch refusals (offline/stale/no scope/capability)
surface their reason with a manual retry only.

Verified locally: `compileDebugKotlin`, `testDebugUnitTest` (129 tests, 0 failed,
including 15 reducer tests covering the ТЗ §4.4 matrix), `lintDebug`, and
`git diff --check`. Physical USB acceptance (Phase 4) is still pending and is not
claimed here. Relay/Chromecast controls and raw stream URLs remain out of scope.

## RC-3 — live RockCast control accepted from USB-connected phone (2026-09-17)

The paired Windows RockCast target was initially offline because RockServer rejected
its changed manifest at the already-used revision 2 (`registration_rejected`), despite
successful native-session authentication and protocol negotiation. RockCast now
publishes manifest revision 4 and the target becomes online after a directory refresh.
The USB-connected Android phone selected the target and received `Состояние плеера
подтверждено.` after one standard `playback.stop` command. The Devices UI now exposes
that advertised Stop action. RockCast no longer advertises `relay` or `chromecast`
controls before the server implements those command families (RS-7), preventing a
controller from offering actions that cannot complete.

## RM-1 play catalog stations on a device-control target (local implementation, 2026-09-09)

RockMobile now allows dispatching `station.play_station` to an explicitly selected, usable
device-control target directly from the station catalog (`StationRow`) and the full-screen player
(`PlayerScreen`). The command follows the standard controller WSS lifecycle without creating a
secondary command path. Actions are enabled only when the target is usable (player role, online,
fresh, `media.control` scope granted) and supports `media.station` with `rockserver_catalog`.
Unusable targets show clear contextual reasons (offline, not selected, stale, missing scope).
Commands track in-flight phases with indicators; late Succeeded results arriving after the 10-second
client deadline transition to Succeeded with "Выполнено после истечения ожидания". Failures show
a Snackbar with a manual "Повторить" action (no auto-retry). Unit tests and assembleDebug pass;
physical hardware acceptance with ESP32 is deferred pending RE-11.


## DC-016 controller WebSocket recovery (live acceptance, 2026-09-07)

Re-entering the account dialog now discards its actionable directory view until it reconnects the
controller socket and reloads the authoritative directory. This prevents a locally stale WebSocket
object from accepting a send that can no longer reach RockServer; no command is retried. With the
companion RockCast idle wake-up, a physical paired-phone `Stop` completed as `succeeded` in staging
in under one second.

## DC-016 E2E controller registration and debug endpoint (live acceptance, 2026-09-07)

RockMobile no longer submits an empty device runtime snapshot: it is a controller, not a player.
The corresponding RockServer lifecycle gate now permits controller-only heartbeats while retaining
the full-state requirement for player and hybrid roles. Debug APKs alone may receive an HTTPS
RockServer URL at build time through `rockmobileDevServerUrl`; release builds and invalid/HTTP
values always use the production endpoint. No URL, token or credential is stored or logged. This
removes the local transport blockers. A debug APK was installed on a physical Android controller,
which registered through the deployed RockServer and saw its paired RockCast online. The explicit
RockCast selection then sent `Stop`; RockCast returned a terminal result, the phone showed
`Состояние плеера подтверждено.`, and staging persisted the lifecycle as `succeeded`.

## DC-016 capability-driven remote controls (live acceptance, 2026-09-07)

The account dialog now exposes remote-player controls only after the DC-015 explicit selection is
fresh, online, player-scoped and authorized by `media.control`. Typed capability variants govern
the visible playback, volume/mute, Chromecast and relay controls; unknown variants do not leave
the API boundary. Commands use only the selected device target, a generated command UUID and a
bounded deadline through the existing controller WSS connection. The client tracks received,
accepted and terminal frames by command UUID, disables equivalent in-flight actions, never
auto-retries under a new UUID, and waits for a refreshed directory snapshot before reporting a
successful result. Receiver identifiers stay player-local and expire at their server deadline.

The live run exposed and fixed four mobile transport blockers: REST directory loading on the main
thread; a WSS-registration prerequisite for the directory scope; omitted required default fields
in outgoing protocol frames; and missing sealed-command serialization. Socket heartbeat shutdown
also no longer crashes the app. `:app:testDebugUnitTest` (102 tests) and `:app:assembleDebug`
pass after those fixes. DC-016 is accepted; DC-017+ is not part of RockMobile.

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
