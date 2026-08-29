# RM-011-09 — RockMobile Wave 9 A4 result

## Result

RockMobile now constructs the secure pairing URL as `?code=<code>#secret=<proof>`. The link is
used by the current QR and same-phone browser actions and the secret remains memory-only.

The activity handles only the exact credential-free return App Link: HTTPS,
`alex.vault57.ru`, `/return/rockmobile`, no query and no fragment. It resumes an existing pairing
poll; it never creates a pairing request or consumes credential parameters. The normal pairing URL
does not match the intent filter.

## Verification

- `./gradlew.bat testDebugUnitTest --console=plain` — passed.
- `./gradlew.bat lintDebug --console=plain` — passed.
- `./gradlew.bat assembleDebug --console=plain` — passed.
- `git diff --check` — passed after this documentation commit.

The process-local JVM setting was `-Xmx8G -Xms512m -Duser.home=C:\Users\alex`.

## App Link blocker

The manifest can request verification but cannot establish it. RockServer has no checked-in
`assetlinks.json`, and the release signing configuration reads private, untracked
`keystore.properties`; this task did not read it, deploy the association or claim production
readiness.

## Commits

- Implementation: `710c6950fa1f3178a4b4885f3fb4acb6e545ebce`
- Documentation: this commit
