# Binary compatibility

## Decision

Use **JApiCmp through the Gradle plugin** as the first binary-compatibility gate, with the
published `0.1.0-alpha02` artifacts as the immutable baseline.

Candidate evaluated:

| Tool | Strengths | Fit for Clotho |
|---|---|---|
| JApiCmp (`me.champeau.gradle.japicmp`) | Compares compiled archives; can report only binary-incompatible modifications; accepts explicit old/new archive and classpath inputs | Best fit for a narrowly defined JVM/Android ABI gate. The Android AAR can be normalized to its `classes.jar` before comparison. |
| Revapi | Broader API analysis, including source and semantic compatibility, extensible checks, and API-surface/dependency analysis | Valuable if Clotho later wants source/semantic API policy. Its Gradle integrations are primarily documented for Java projects; Android Gradle Plugin/AAR handling would still need separate integration. |

JApiCmp's Gradle plugin documents explicit `oldArchives`/`newArchives` and classpath inputs,
binary-incompatible-only reporting, and failure/report configuration:
https://github.com/melix/japicmp-gradle-plugin

Revapi's Java checker distinguishes source, binary, and semantic compatibility and supports
dependency/API-surface analysis:
https://github.com/revapi/revapi

The decision is deliberately scoped: this phase establishes **binary compatibility**, not a
complete source-compatibility or semantic-compatibility policy. Revisit Revapi if that broader
policy becomes a release requirement.

## Baseline

Pin the baseline to the published release, not to a moving branch or the current source tree:

- Version: `0.1.0-alpha02`
- Maven group: `org.codirex.clotho`
- Core artifact: `org.codirex.clotho:clotho:0.1.0-alpha02`
- Android artifact: `org.codirex.clotho:clotho-android:0.1.0-alpha02`
- Test artifact: `org.codirex.clotho:clotho-test:0.1.0-alpha02`
- BOM: `org.codirex.clotho:clotho-bom:0.1.0-alpha02` (metadata/platform only; no class archive to compare)
- Release tag: `v0.1.0-alpha02`
- Release commit: `545602a04155686e4de610a2365675401a4c3b0a`

The baseline version must be a single Gradle property (for example,
`binaryCompatibilityBaseline`) so intentional baseline updates are reviewable and do not require
editing task logic.

## Comparison scope

Compare the published class archives for all three code-bearing published artifacts:

1. `clotho`: compare the published baseline JAR with the current `jar` output.
2. `clotho-test`: compare its published baseline JAR with the current `jar` output.
3. `clotho-android`: resolve the published baseline AAR, extract its `classes.jar`, and compare
   it with `classes.jar` extracted from the current release AAR. Do not compare the AAR ZIP
   container itself.

The BOM is excluded because it has no classes. The Android module's dependency on core does not
replace the separate core comparison: each artifact's own classes are checked once.

Use the public access level for the published API. Supply the relevant old/new dependency
classpaths for symbol resolution rather than globally suppressing missing-class diagnostics.
In particular, Android's public references to AndroidX types must be resolvable during comparison.

## Gate behavior

- Fail only on binary-incompatible changes. Adding a new public class or method is not, by itself,
  a binary break and must not fail this gate.
- Produce a machine-readable report (XML) and a human-readable report (Markdown or HTML) per
  artifact under `build/reports/binary-compatibility/`.
- Expose one root task, `checkBinaryCompatibility`, and make the published modules' `check`
  tasks depend on their respective comparison tasks so the existing `./gradlew build`/CI path
  enforces the gate.
- A missing or unresolvable baseline is a hard failure. Never silently skip a module.
- Do not blanket-ignore missing classes. Resolve the published API's dependencies; any narrowly
  justified exclusions must be documented with the affected symbol and reason.

## Updating the baseline

A baseline update is a release-management action, not a routine cleanup:

1. Publish and validate the new release first.
2. Change the single baseline version property to that exact published version.
3. Include the generated compatibility reports in the PR evidence.
4. Explain every accepted binary break in the PR and release notes, including the migration path.
5. Require explicit review before merge. Do not auto-accept all changes or rewrite the old baseline.

## Validation still required before enabling the gate

- Verify the selected JApiCmp plugin version works with this repository's Gradle wrapper and
  configuration-cache/build conventions.
- Prove that Maven Central resolves all three `0.1.0-alpha02` artifacts in CI.
- Verify the Android AAR extraction path against both the published AAR and the current AGP
  release AAR.
- Run the comparison against the current `main` artifacts and manually inspect the initial report.
  The first run is expected to establish the baseline, not to hide unexplained incompatibilities.
- Only after those checks pass should the task be wired into the required CI `check` path.
