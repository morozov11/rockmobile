# RockMobile status

## RM-011-09 — Wave 9 A4 secure pairing handoff (complete locally, 2026-08-29)

Pairing QR and the same-phone browser action now use `?code=<code>#secret=<proof>`, keeping the
approval secret out of request queries. The Android return handler accepts only an `ACTION_VIEW`
HTTPS URI with host `alex.vault57.ru`, path `/return/rockmobile`, and no query or fragment. It
resumes existing polling only; it cannot create a request or accept credentials from the URI.

The manifest already declares that exact auto-verified path and does not capture the ordinary
pairing URL. Production verification is deliberately not claimed: no `assetlinks.json` exists in
the checked-out server deployment assets, and the release signer is configured only by the private,
untracked `keystore.properties` file. The domain owner/release signer must publish and verify the
association separately.

Verified locally with `./gradlew.bat testDebugUnitTest --console=plain`, `lintDebug`, and
`assembleDebug` (using the mandated process-local `JAVA_TOOL_OPTIONS`), plus `git diff --check`.
No push, deploy, staging mutation or physical-device flow occurred.
