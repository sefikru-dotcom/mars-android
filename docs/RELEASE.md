# Release checks

Releases are built and published **from CI** (`.github/workflows/ci.yml`, job
`release-publish`). The manual host-side procedure still exists, but it is the
**emergency fallback only**.

## Standard path (CI)

Triggered by a push to `main` whose `android` job is green:

1. `android` builds `:app-mobile:assembleRelease` and `:app-tv:assembleRelease`
   with the production keystore supplied from CI secrets, and uploads the APKs
   as the `android-build-<sha>` artifact.
2. `Verify signing contract with apksigner` checks, for both modules, that
   apksigner reports `Verifies` and that the **SHA-256 fingerprint of the
   signing certificate** equals the workflow-level pin
   `MARS_EXPECTED_CERT_SHA256`.
3. `Release APK contract` enforces R8 minification and `v3-only` signing.
4. `release-publish` downloads the artifact, re-runs both contract checks on the
   **downloaded** files, then copies each APK to the distribution host and
   verifies it over HTTPS (size + SHA-256).

If `MARS_PUBLISH_SSH_KEY` is not configured, `release-publish` prints
`publish skipped` and exits 0: the build is green, nothing is published.

### Why the certificate fingerprint, not the DN

The production Mars key **is** an Android debug certificate — DN
`C=US, O=Android, CN=Android Debug`, the same certificate for mobile and TV:

```
SHA-256 15da3b0219edc2b82a7ae20c73b18497d9034e4c745d842390451b0cb7f3d311
```

A text check for `androiddebugkey` / `CN=Android Debug` therefore rejected every
real release (that is why releases were published by hand). The fingerprint
separates our key from any other debug key — including the ephemeral key of the
CI runner — while the DN cannot.

**Never regenerate the key.** Android refuses to update an installed app whose
APK is signed with a different certificate; a new key would force every user to
uninstall and lose local data.

The pin is public information (a certificate hash, not a secret) and lives in
`env.MARS_EXPECTED_CERT_SHA256` in the workflow. Changing the key means changing
the pin — and shipping a migration, not just a new build.

## Required checks before merging to `main`

1. The target commit is reviewed and CI is green.
2. Gradle wrapper validation passed.
3. `python3 tests/test_apk_contract_check.py` is green (includes the
   certificate-pin tests, both positive and negative).
4. `python3 -c "import yaml; yaml.safe_load(open('.github/workflows/ci.yml'))"`
   parses.
5. The APK is signed with the pinned certificate and uses `v3-only`
   (`python3 scripts/apk-cert-pin-check.py <apk>` and
   `python3 scripts/apk-contract-check.py <apk> --signing-scheme v3-only`).

## Emergency manual path

Only when CI publishing is unavailable (host down, secret lost, runner outage).
Run from the host that owns the distribution directory:

1. Build the release locally with the production keystore
   (`MARS_RELEASE_KEYSTORE*` in `~/.gradle/gradle.properties`, never in git).
2. Verify the certificate pin and the contract:
   `python3 scripts/apk-cert-pin-check.py <apk> --apksigner <apksigner>` and
   `python3 scripts/apk-contract-check.py <apk> --signing-scheme v3-only`.
3. Publish with `python3 scripts/apk-publish-ci.py --apk <apk> --name
   mars-mobile.apk --remote-user root --remote-host marscore.ru --remote-dir
   /opt/hermes-webui/static/apk --ssh-key <key>`; it copies over `scp`, renames
   atomically, and verifies the published file over HTTPS.
   Use `--dry-run` first to see the plan without touching the distribution.
4. Record the source commit, artifact names, SHA-256 values and the previously
   published SHA-256 (the script prints it) in the release record.

## Release safety

- Do not commit signing keys, keystores, passwords, tokens, SSH keys, or chat
  credentials. The publish key lives only in the `MARS_PUBLISH_SSH_KEY` secret.
- Never echo secret values in CI logs or in review comments; the workflows only
  test for presence.
- Publishing is restricted to `push` on `main`. Branches (`run/**`, `wave/**`)
  and pull requests build but never publish.
- Do not treat a successful debug build as proof that native device credential
  or native chat functionality exists; both are explicitly blocked in
  [the API contract](API-CONTRACT.md).

## Android SDK baseline

Both application modules compile and target SDK 34. The mobile shell targets SDK 34
to match its compile SDK and the Android Gradle Plugin release-lint requirement
(target SDK 33 or newer); the TV module targets SDK 34 as well. Both keep
`minSdk` 28, so API 28 devices stay supported — raising `targetSdk` changes
platform behavior on newer Android versions but does not drop older devices.
