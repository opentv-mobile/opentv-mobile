# Releasing

Releases are cut by pushing a version tag. GitHub Actions runs the tests, builds with the existing
production signing key and attaches the APKs to a GitHub release.

## Prepare 1.1.0

- `app/build.gradle.kts`: `versionName = "1.1.0"`, `versionCode = 101`.
- Review `CHANGELOG.md` and the validation status in
  [`MANUAL_TEST_ROADMAP.md`](MANUAL_TEST_ROADMAP.md). General manual testing was approved by the user;
  this does not certify every exhaustive checklist case.
- Run `./gradlew test lintDebug assembleDebug --stacktrace` and `git diff --check`.
- Review and commit the intended changes before tagging. Preparation alone does not publish anything.

Only after authorization and a successful commit/push, cut the release:

```bash
git tag v1.1.0
git push origin v1.1.0
```

`.github/workflows/release.yml` publishes only on a push whose ref starts with `refs/tags/v`.
Tagged releases retain both asset names: `opentv-mobile-v1.1.0.apk` (for this version) and
`opentv-mobile.apk` (the stable download name). Release notes come from the matching changelog section.

Running **Release → Run workflow** manually builds a signed candidate and uploads the
`opentv-mobile-signed-candidate` workflow artifact, containing `opentv-mobile-candidate-<run>.apk`
and `opentv-mobile.apk`. It does not create a tag or publish a GitHub release, even when run on a tag.
The separate Build workflow produces debug artifacts, not production releases.

## Signing

Production signing already worked for v1.0.0. Reuse the existing repository secrets and the same
keystore so Android can upgrade existing installations without losing their data. Do not generate
a replacement key for 1.1.0 or print/export secret values in logs or release notes.

The Release workflow requires all four Actions secrets:

- `KEYSTORE_BASE64`
- `KEYSTORE_PASSWORD`
- `KEY_ALIAS`
- `KEY_PASSWORD`

It fails if any is absent, decodes the keystore into runner temporary storage, then supplies
`KEYSTORE_PATH` and the signing credentials to `assembleRelease`. Invalid signing material also
fails the build; there is no CI fallback to publishing a debug-signed APK.

Locally, `app/build.gradle.kts` still falls back to a debug key for release-variant testing when
`KEYSTORE_PATH` is absent. Such a build is **not production-signed and must not be distributed as a
release**. The normal debug build uses `app.opentvmobile.debug`, separate from the production package
`app.opentvmobile`; it installs alongside production and does not validate a production upgrade.
Android requires both the same package and the same signing key for an in-place update.

Back up the existing production keystore securely and ensure trusted maintainers can recover it.
Losing that key prevents compatible updates to existing sideloaded installations.

## Version numbers

Bump `versionCode` and `versionName` in `app/build.gradle.kts`. `versionCode` must increase on
every release; Android uses it, not `versionName`, to decide what counts as an upgrade.
