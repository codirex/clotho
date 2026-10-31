# Clotho

Structured concurrency and async framework for the JVM and Android.
A complete, self-contained alternative to `java.util.concurrent` executors: its own vocabulary,
its own engine, no dependency on coroutines.

> **Status:** pre-release (`0.1.0-SNAPSHOT`). Core, engine, main lane, lifecycle realms, timers, `Cell` and `clotho-test` exist and are tested (unit, stress, jcstress, Robolectric, instrumented). Benchmarks against `java.util.concurrent` are in `clotho-bench` (see [docs/BENCHMARKS.md](docs/BENCHMARKS.md)). `Flow` comes after the first stable release. See [docs/ROADMAP.md](docs/ROADMAP.md).

## Vocabulary

| Concept | Meaning |
|---|---|
| `Job` | Handle to a running unit of work (replaces `Future`) |
| `Outcome` | Result of a job: `Success`, `Failure` or `Cancelled` |
| `Realm` | Lifetime scope that owns jobs; closing it cancels them |
| `Lane` | Where work runs (Compute, Io, Main, Serial, Single) |
| `Worker` | A thread owned by a lane |
| `Cadence` | Delays and repetition |
| `Flow` | Value streams (deferred until after the first stable release) |
| `Cell` | Atomically updatable protected value |

## Taste of the API

```java
try (Realm realm = Realm.open("checkout")) {
  Job<User> user = realm.launch(lane, cp -> api.fetchUser(cp));
  Job<String> name = user.then(lane, (u, cp) -> u.name())
                         .recover(lane, (error, cp) -> "guest");
  Outcome<String> out = name.await();
}
```

Behaviour is specified in [docs/SEMANTICS.md](docs/SEMANTICS.md).

## Requirements

- Java 17 (language level and bytecode)
- Android: `minSdk 24`, AGP 9+ with a JDK 17 toolchain (Android SDK 37 needed to build `app`)
- Core only uses APIs available on Android API 24 (enforced by animal-sniffer)

## Artifacts

```kotlin
dependencies {
    implementation("org.codirex.clotho:clotho:<version>")          // JVM and Android core
    implementation("org.codirex.clotho:clotho-android:<version>")  // Android: main lane, lifecycle
    testImplementation("org.codirex.clotho:clotho-test:<version>") // deterministic test tools
}
```

## Build

```
gradle wrapper --gradle-version 9.6.1   # first time only
./gradlew spotlessApply                  # format + license headers
./gradlew build
```

CI (GitHub Actions) runs the JVM modules on JDK 17 and 21, the Android module, and a publishing
dry run on every push and pull request. jcstress and the benchmarks are manual workflows.
Releasing is described in [docs/CONTRIBUTING.md](docs/CONTRIBUTING.md).

## License

Apache License 2.0. See [LICENSE](LICENSE).
