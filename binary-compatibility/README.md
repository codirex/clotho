# Approved binary breaks

`approved-breaks.xml` is the version-controlled list of binary incompatibilities that a release
intentionally accepts against the published baseline (`binaryCompatibilityBaseline` in
`gradle.properties`). The rules come from `docs/BINARY_COMPATIBILITY.md`.

## How the gate uses it

For every published artifact the build runs the complete JApiCmp comparison against the unchanged
baseline (`validateBinaryCompatibilityTool`, reports under
`build/reports/binary-compatibility/validation/`), then `verifyBinaryCompatibility` reads the
unfiltered XML report and compares it with this manifest. The gate passes only if:

- every reported binary break matches exactly one entry, and
- every entry matches a reported break, and
- every entry of the artifact targets the release being built (`VERSION_NAME`).

An unmatched break, an ambiguous match, an entry for another release or an entry that no longer
matches fails the gate. The gate never edits or filters the JApiCmp reports, and it writes a
summary to `build/reports/binary-compatibility/gate/<artifact>.md` (also on failure) that contains
ready-to-edit entries for every unapproved break.

## Running it locally

`./gradlew build` runs the gate for `:clotho` and `:clotho-test` through `check`;
`./gradlew checkBinaryCompatibility` runs it for all three artifacts, including `:clotho-android`.
The published baseline is resolved from Maven Central, so these tasks need network access. The
gate's own unit tests run with `./gradlew -p build-logic test`.

## Entry format

One `<break>` per incompatibility. Wildcards and package-wide entries are rejected, as are unknown
or missing attributes. Member data describes the **baseline** declaration.

```xml
<break
    artifact="clotho"
    class="org.codirex.clotho.Example"
    kind="METHOD"
    name="run"
    parameters="java.lang.String,long"
    returnType="void"
    change="METHOD_REMOVED"
    approval="PR #123"
    release="0.1.0-alpha03">
    <rationale>Why it is accepted, its impact and the migration path.</rationale>
</break>
```

| Attribute | Meaning |
|---|---|
| `artifact` | `clotho`, `clotho-test` or `clotho-android` |
| `class` | Fully qualified class name (nested classes use `$`) |
| `kind` | `CLASS`, `SUPERCLASS`, `INTERFACE`, `CONSTRUCTOR`, `METHOD` or `FIELD` |
| `name` | Method or field name; the old superclass or the interface name for `SUPERCLASS` / `INTERFACE`; not used for `CLASS` and `CONSTRUCTOR` |
| `parameters` | Comma-separated baseline parameter types (`CONSTRUCTOR`, `METHOD`); empty for none |
| `returnType` / `type` | Baseline return type (`METHOD`) or field type (`FIELD`) |
| `change` | The JApiCmp compatibility change type, for example `METHOD_REMOVED` |
| `approval` | The approving PR or release reference |
| `release` | The release this break is accepted for; must equal `VERSION_NAME` |

Because `release` must equal `VERSION_NAME`, add entries in the release PR together with the version
bump, and remove or revise them after the approved release becomes the new baseline.
