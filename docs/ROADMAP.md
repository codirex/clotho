# Roadmap

Each phase closes only when `./gradlew build` is green (tests, Spotless, animal-sniffer,
Javadoc) and `docs/` plus `CHANGES.md` are updated.

| # | Phase | Scope |
|---|---|---|
| 1 | Foundation | Build, quality gates, CI, docs skeleton, `Clotho` class |
| 2 | Core (done, see CHANGES) | `Work`, `Checkpoint`, `Job`, `Outcome`, `Realm`, cancellation, FailFast/Supervised, `UncaughtHandler`, `then/recover/zip/race/await` |
| 3 | Engine (delivered, see CHANGES) | Work-stealing `Worker` pool, `Lane` (Compute/Io/Serial/Single), priorities + aging, bounded queues and rejection policies |
| 4 | Android (delivered, see CHANGES) | `MainLane` interface, `androidx.startup` initializer, ServiceLoader on JVM, `Clotho.install(...)`, lifecycle, consumer rules, lifecycle bindings, app wiring |
| 5 | Cadence (delivered, see CHANGES) | Wheel timer, `delay/every`, Realm integration, `Cell` |
| 6 | Testing (delivered, see CHANGES) | `clotho-test` (DeterministicLane, TestClock), stress tests, jcstress, instrumented tests |
| 7 | Benchmarks and docs | `clotho-bench` JMH vs Executors (delivered, results pending a recorded run), CI and Maven Central publishing (delivered); still open: coverage gates, BOM |
| 8 | Flow | After the first stable release |
| later | `clotho-ktx` | Optional Kotlin extensions, never affects core |
