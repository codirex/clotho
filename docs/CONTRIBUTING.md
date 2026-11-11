# Contributing

1. `./gradlew spotlessApply` before committing.
2. `./gradlew build` must pass: tests, Spotless, animal-sniffer (Android API 24), Javadoc.
3. New public API: write Javadoc and tests first, then implement.
4. Record every public API change in `CHANGES.md`.

## Releasing to Maven Central

One-time setup:

- The namespace `org.codirex` must be verified in the Central Portal.
- GitHub repository secrets: `MAVEN_CENTRAL_USERNAME`, `MAVEN_CENTRAL_PASSWORD` (Central Portal
  user token, not the account password), `SIGNING_KEY_ID`, `SIGNING_PASSWORD`, and
  `SIGNING_SECRET_KEY_RING_FILE_BASE64` (`base64 -w0 ~/.gnupg/secring.gpg`, the file that
  `signing.secretKeyRingFile` points to). The public key must be on a keyserver
  (`keys.openpgp.org` or `keyserver.ubuntu.com`).
- Optional repository variable `MAVEN_CENTRAL_AUTO_RELEASE=true` to release without pressing
  "Publish" in the Portal. Leave it unset until you trust the pipeline.

Per release:

1. Set `VERSION_NAME` in `gradle.properties` to the release version (never `-SNAPSHOT`: the
   workflow refuses it), move the `[Unreleased]` entries of `CHANGES.md` under a heading for that
   version, and merge to `main` with CI green.
2. Tag and push: `git tag v<version> && git push origin v<version>`. The `Release` workflow checks
   that the tag (without the `v`) equals `VERSION_NAME`, builds and tests `clotho`, `clotho-test`
   and `clotho-android`, builds `clotho-bom`, signs and uploads all four artifacts to Maven Central, then creates the GitHub release
   (marked as a pre-release when the version contains a `-`).
3. Unless `MAVEN_CENTRAL_AUTO_RELEASE` is true, press **Publish** in the Central Portal.

A version published to Maven Central can never be changed or replaced: a fix needs a new version.

Locally, the same properties go in `~/.gradle/gradle.properties` (`mavenCentralUsername`,
`mavenCentralPassword`, `signing.keyId`, `signing.password`, `signing.secretKeyRingFile`) and
`./gradlew publishToMavenCentral` does the upload. `./gradlew publishToMavenLocal` needs none of them.
