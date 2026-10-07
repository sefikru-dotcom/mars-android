# Release checks

This repository currently produces the Android TV debug APK. Release automation must remain separate from CI and must not require credentials in this repository.

## Required checks

Before a release candidate is published:

1. Confirm the target commit is reviewed and CI is green.
2. Confirm Gradle wrapper validation passed.
3. Run `./gradlew --no-daemon :core-network:test`.
4. Run `./gradlew --no-daemon :app-tv:assembleDebug` (or the future release variant).
5. Generate SHA-256 checksums for every distributed artifact and publish them beside the artifacts.
6. Verify the checksum file against the exact downloaded artifacts before distribution.

The workflow uploads debug APK outputs and `SHA256SUMS` as short-lived CI evidence. These artifacts are not a release channel.

## Release safety

- Do not commit signing keys, keystores, passwords, tokens, pairing secrets, or chat credentials.
- Signing and publishing credentials must be supplied by the protected release environment, not by repository files or pull requests.
- Do not treat a successful debug build as proof that native device credential or native chat functionality exists; both are explicitly blocked in [the API contract](API-CONTRACT.md).
- Document the exact source commit, artifact names, and checksum values in the release record.

## Current baseline limitation

The repository now includes an `app-mobile` shell. CI builds both `app-tv` and `app-mobile`; this shell deliberately does not claim native auth or chat support.

## Android SDK baseline

Both application modules compile against SDK 34. The mobile shell targets SDK 34 as well, matching its compile SDK and the Android Gradle Plugin release-lint requirement (target SDK 33 or newer). Its minimum SDK remains 28; raising `targetSdk` changes platform behavior on newer Android versions but does not drop support for API 28 devices. The TV module's target SDK remains 28 until its device compatibility is reviewed separately.
