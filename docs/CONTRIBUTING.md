# Contributing

1. `./gradlew spotlessApply` before committing.
2. `./gradlew build` must pass: tests, Spotless, animal-sniffer (Android API 24), Javadoc.
3. New public API: write Javadoc and tests first, then implement.
4. Record every public API change in `CHANGES.md`.
5. Bumping the version: change `VERSION_NAME` in `gradle.properties` **and** `Clotho.VERSION`
   (a test fails if they differ).

## Releasing to Maven Central

One-time setup:

- The namespace `org.codirex` must be verified in the Central Portal (done for the domain).
- GitHub repository secrets: `MAVEN_CENTRAL_USERNAME`, `MAVEN_CENTRAL_PASSWORD` (Central Portal
  user token, not the account password), `SIGNING_KEY_ID`, `SIGNING_PASSWORD`, and
  `SIGNING_SECRET_KEY_RING_FILE_BASE64` (`base64 -w0 ~/.gnupg/secring.gpg`, the file that
  `signing.secretKeyRingFile` points to). The public key must be on a keyserver
  (`keys.openpgp.org` or `keyserver.ubuntu.com`).
- Optional repository variable `MAVEN_CENTRAL_AUTO_RELEASE=true` to release without pressing
  "Publish" in the Portal. Leave it unset for the first release.

Per release:

1. Set `VERSION_NAME` and `Clotho.VERSION` to the release version (no `-SNAPSHOT`), update
   `CHANGES.md`, merge to `main` with CI green.
2. `git tag v0.1.0 && git push origin v0.1.0`. The `Release` workflow checks that the tag, the
   property and the constant agree, builds, signs, uploads and creates the GitHub release.
3. Bump to the next `-SNAPSHOT`.

Locally, the same properties go in `~/.gradle/gradle.properties` (`mavenCentralUsername`,
`mavenCentralPassword`, `signing.keyId`, `signing.password`, `signing.secretKeyRingFile`) and
`./gradlew publishToMavenCentral` does the upload. `./gradlew publishToMavenLocal` needs none of them.
