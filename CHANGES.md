# Changes

All notable changes are recorded here. Every public API change must be listed.
Format follows [Keep a Changelog](https://keepachangelog.com/); versioning follows SemVer.

## [Unreleased]

### Added (phase 7)
- New module `clotho-bench` (JMH; opt-in with `-PwithBench`, not part of `build`): round trip
  (one and many producers, mean and percentiles), batch throughput, three-stage chain, ordered
  lanes, never-firing timers (schedule + cancel) and realm scope cost, each against
  `ThreadPoolExecutor`, `ForkJoinPool`, `CompletableFuture` or `ScheduledThreadPoolExecutor`
  as it fits; allocation per operation via `-prof gc`. `scripts/jmh_to_md.py` builds the report.
- GitHub Actions: `ci.yml` (JVM on JDK 17/21, Android, publishing dry run), `jcstress.yml`,
  `bench.yml`, `release.yml` (tag `vX.Y.Z` publishes to Maven Central), `dependabot.yml`.
- Maven Central publishing for `clotho`, `clotho-android`, `clotho-test` through
  `com.vanniktech.maven.publish` (convention plugin `clotho.publish`), with signing, POM metadata
  and sources/javadoc jars.

### Changed (phase 7)
- Javadoc (`-Xdoclint:all`) is now part of `check` explicitly; the sources and javadoc jars are
  attached by the publishing plugin instead of `java { withSourcesJar() }`.
- Removed `PLAN.md` (internal working notes).

### Added (phase 6)
- New module `clotho-test`: `TestClock`, `DeterministicLane`, `ManualMainThread`,
  `RecordingUncaughtHandler`, `TestScheduler`.
- Core: `Engine.Builder.manualTimers()`, `Cadence.isManual()`, `Cadence.runDue()`,
  `Cadence.nextDueNanos()` (timers without a thread, driven by the engine clock).
- New module `clotho-jcstress` (ten jcstress scenarios; not part of `build`, include with
  `-PwithJcstress`).
- Instrumented tests for `clotho-android` (`androidTest`); catalog entries for androidx.test and jcstress.
- `docs/TESTING.md`.

### Fixed (phase 6)
- `atRate`: a slot could be run twice when its run ended at the very instant of the slot (found with
  the controlled clock). The driver now tracks the slot it served and the next one is always after it.

### Added (phase 5)
- Timers: `Realm.after`, `Realm.every`, `Realm.atRate` (+ `Priority`/`RepeatFailure` overloads),
  `Realm.Builder.cadence(...)`, `Realm.cadence()`, `Action`, `RepeatFailure`.
- `Cadence` (one hashed timer wheel and one thread per engine): `Engine.cadence()`,
  `Engine.Builder.timerTick(...)`, `timerWheelSize(...)`.
- `Cell<T>`: `get`, `set`, `getAndSet`, `compareAndSet`, `compareAndSetSame`, `update`,
  `updateAndGet`, `getAndUpdate`.
- `LaneClosedException`; `DroppedException.Reason` (`QUEUE_OVERFLOW`, `SHUTDOWN`) and `reason()`.
- `docs/CADENCE.md`.

### Changed (phase 5)
- A lane that is shut down (engine) or whose host stopped now rejects with `LaneClosedException`
  (a `RejectedExecutionException`), and discards caused by `shutdownNow` carry
  `DroppedException.Reason.SHUTDOWN`. Code catching `RejectedExecutionException` is unaffected.
- `Realm.close()` / `close(timeout)` / `closeAsync()` cancel periodic jobs of the realm.
- `Engine.shutdown()` / `shutdownNow()` discard pending timers; `awaitTermination` also waits for the
  timer thread.
- FAIL_FAST ordering fix: a failed root job records `Realm.failure()` and cancels the realm
  **before** `await()` waiters wake up (previously `await()` could return first).

### Added (phase 4)
- Core: `MainLane`, `MainThreadHost`, `MainLane.of(...)`, `Lane.main()`, `Clotho.install(MainLane)`,
  `Clotho.installedMainLane()`, `Clotho.uninstallMainLane()`, `MainLaneNotInstalledException`;
  `Realm.closeAsync()` with `Realm.Closing` (`isClosed`, `onClosed`, `await`).
- `ServiceLoader` discovery of a single `MainThreadHost`; `META-INF/proguard/clotho.pro` in the core jar.
- New module `clotho-android`: `AndroidMainLane`, `ClothoInitializer` (androidx.startup),
  `AndroidRealms.forLifecycle(...)`, manifest entry, `consumer-rules.pro`.
- Version catalog: androidx.startup 1.2.0, androidx.lifecycle 2.10.0, Robolectric 4.17, JUnit 4.13.2.
- `docs/ANDROID.md`.

### Changed (phase 4)
- With a main lane installed, blocking calls (`Job.await`, `Realm.close`, `Engine.close`, ...) are
  refused on the main thread even outside Clotho tasks.
- With a main lane installed, `CALLER_RUNS` never runs pool work on the main thread.
- The sample `app` uses `clotho-android` and shows I/O work returning to the main thread.

### Added (phase 3)
- `Engine` (owns threads; `global()`, `builder()`, `compute()`, `io()`, `serial(...)`, `single(...)`,
  `shutdown()`, `shutdownNow()`, `awaitTermination(...)`, `close()`, `close(timeout)`).
- Work-stealing pool behind `compute()` and `io()` with priorities (`Priority`) and aging.
- `QueueLimit` + `RejectionPolicy` (`REJECT`, `DROP_OLDEST`, `CALLER_RUNS`), `DroppedException`.
- `Lane.compute()`, `Lane.io()` (global engine), `Lane.execute(Runnable, Priority)`,
  `Lane.Discardable`.
- `Realm.launch(Lane, Priority, Work)`.
- `docs/ENGINE.md`.

### Changed (phase 3)
- `Realm.close()` and `Realm.close(long, TimeUnit)` now throw `BlockingNotAllowedException` on a lane
  that forbids blocking (like `Job.await`).
- A task a lane discards now ends its job as `Failure(DroppedException)`; a discarded completion
  callback is reported to the `UncaughtHandler` and released.

### Added (phase 2)
- Core API: `Work`, `Step`, `Combiner`, `Completion`, `Checkpoint`, `Job`, `Outcome`
  (`Success`/`Failure`/`Cancelled`), `Realm` (`launch`, `zip`, `race`, `cancel`, `close`),
  `Ownership`, `RealmPolicy`, `UncaughtHandler`, `Lane` (+ `Lane.direct()`), `BlockingPolicy`.
- Exceptions: `CancelledException`, `RealmClosedException`, `BlockingNotAllowedException`,
  `JobFailedException`, `NoWinnerException`, `LeakedJobException`.
- `Clotho.defaultUncaughtHandler()` / `setDefaultUncaughtHandler(...)`.
- `docs/SEMANTICS.md`: the full behavioural contract.

### Added
- Phase 1: Gradle build (Gradle 9.6.1), `build-logic` convention plugins, version catalog.
- Quality gates: Spotless (google-java-format + license header), animal-sniffer against
  Android API 24, `-Xlint:all -Werror`, strict Javadoc (`-Xdoclint:all`), JaCoCo.
- `org.codirex.clotho.Clotho` entry point exposing `version()`.
- Android `app` sample application (minSdk 24, targetSdk 37), GitHub Actions CI (JDK 17 and 21), initial `docs/`.
