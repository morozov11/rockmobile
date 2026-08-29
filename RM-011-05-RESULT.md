# RM-011-05 — RockMobile M4–M8

Дата: 2026-08-29

## Scope completed

Implemented only RockMobile M4–M8 on top of the canonical Wave 2 and Wave 4 commits.

- **M4:** the waiting state now prioritizes opening the existing secure browser link on the same phone, shows a live countdown and verification phrase, and keeps QR under a secondary expandable action. Cancel remains a text action.
- **M5:** added a ZXing QR renderer with explicit four-module margin and M error correction. It renders whole-pixel modules in black on white and supplies an accessibility description containing only the target and expiry. Unit coverage verifies the quiet zone, integer scaling and a synthetic link shape.
- **M6:** completion now enters `ConnectedFirstTime`; its primary action closes the dialog back to radio and its secondary action opens the ordinary account centre. The narrow `/return/rockmobile` App Link only resumes existing polling and accepts no credential parameters.
- **M7:** the connected account centre keeps the current device first, marks it as this phone, never offers its generic revoke control, and formats other-device activity locally. Device-list failure preserves the connected profile and displays a non-blocking message.
- **M8:** structured pairing codes map to the specified user text and distinct recovery action. Offline, `pairing_unavailable` and 5xx use the recoverable unavailable path; no broad 404–410 mapping was added.

No RockServer, RockCast, OpenAPI, staging, account/device data, pairing URL contract, fragment rollout, deployment, or push was changed. Pairing URLs, proofs, QR payloads, tokens, phrases and identifiers are neither persisted nor logged by this work.

## Changed files

- `app/src/main/AndroidManifest.xml`
- `app/src/main/java/com/rockmobile/MainActivity.kt`
- `app/src/main/java/com/rockmobile/account/AccountModels.kt`
- `app/src/main/java/com/rockmobile/account/AccountScreen.kt`
- `app/src/main/java/com/rockmobile/account/AccountViewModel.kt`
- `app/src/main/java/com/rockmobile/account/PairingQr.kt`
- `app/src/test/java/com/rockmobile/account/AccountSessionTest.kt`

## Verification

| Check | Result |
| --- | --- |
| `git diff --check` | Passed before the implementation commit. |
| `./gradlew.bat testDebugUnitTest --console=plain` from `C:\repos\rockmobile` | Blocked before Gradle startup: a separate process held `C:\Users\alex\.gradle\wrapper\dists\gradle-9.3.1-bin\23ovyewtku6u96viwx3xl3oks\gradle-9.3.1-bin.zip.lck`. The empty stale lock was removed after confirming no Java process, but two unrelated Java processes immediately reacquired the wrapper lock. Per repository instruction, no Gradle command was run in parallel with them. |
| `lintDebug` / `assembleDebug` | Not run for the same active-wrapper-lock blocker; not substituted with a worktree-local Gradle invocation because repository instructions require Gradle from `C:\repos\rockmobile`. |

## Limitations

- Automated Android compilation, unit test, lint and assemble gates remain to be rerun once the unrelated Gradle wrapper users have released the shared lock.
- The verified App Link requires the server-owned `assetlinks.json` deployment; this change only prepares the fixed Android path and does not assume that deployment exists.
- No browser, physical-device or staging flow was performed.

## Implementation commit

`7cb33b7f2b6c8c4f198a80f51e98d4341cdc05a9`
