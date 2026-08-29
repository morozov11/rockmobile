# RM-011 Wave 2 result

## Scope completed

Implemented RockMobile client scope A3 and A5 only.

### A3: safe structured API errors

- Added `ApiError(status, code, requestId)`. Its parser retains only those three fields from an error response and does not retain or log response bodies, URLs, query parameters, headers, proofs, tokens, QR content, or account/device/session identifiers.
- Routed account and pairing non-success responses through `ApiError`, preserving canonical server codes and `request_id` to the account layer.
- Added canonical handling for `pairing_pending`, `pairing_rejected`, `pairing_expired`, `device_limit_reached`, `client_upgrade_required`, and `pairing_unavailable`.
- Polling retries only the explicit `pairing_pending` code. Other errors use narrow status fallbacks: device limit is 409, expired/unavailable pairing is 410, and server availability is 5xx. Unknown codes do not collapse HTTP 404–410 into one pairing result.
- The parser tolerates malformed and legacy error bodies, retaining whichever of status, code, and request_id are present without inventing values.

### A5: raw device names

- Device pairing defaults now use the raw Android model label, with `Android device` fallback.
- `device_display_name` is sent as the raw label; no data migration was performed.
- Centralized byte-length validation remains unchanged in `validateDeviceDisplayName`.
- Presentation adds the product prefix exactly once and normalizes either legacy canonical prefix.

## Changed files

- `app/src/main/java/com/rockmobile/data/api/RockserverApi.kt`
- `app/src/main/java/com/rockmobile/account/AccountGateway.kt`
- `app/src/main/java/com/rockmobile/account/AccountModels.kt`
- `app/src/main/java/com/rockmobile/account/AccountViewModel.kt`
- `app/src/main/java/com/rockmobile/account/AccountScreen.kt`
- `app/src/test/java/com/rockmobile/account/AccountSessionTest.kt`
- `app/src/test/java/com/rockmobile/data/stations/StationSourcesTest.kt`

## Verification

All Gradle commands were run from `C:\repos\rockmobile` with process-local `JAVA_TOOL_OPTIONS=-Xmx8G -Xms512m -Duser.home=C:\Users\alex`.

| Command | Result |
| --- | --- |
| `./gradlew.bat test --console=plain` | Passed: 75 unit tests |
| `./gradlew.bat lint --console=plain` | Passed: 0 errors, 35 pre-existing warnings |
| `./gradlew.bat assembleDebug --console=plain` | Passed |

## Commits

- Implementation: `a1d7acbcd49d47911dabc46bd92c747a31408838`
- This report: recorded in the separate documentation commit following this file.

## Known limitations

- The authoritative Windows plan copies at `C:\repos\rockserver\docs\rm-011-client-ux-fix-plan.md` and `C:\repos\rockserver\docs\rm-011-g7-staging-diagnostic-plan.md` were used.
- No RockServer, OpenAPI, RockCast, staging, deployment, or M3+ state-machine changes were made.
