# Design decisions

## Philosophy
Structured concurrency: every job runs inside a `Realm`. Cancelling a realm cancels its jobs.
Failures are never swallowed: they surface as `Outcome.Failure` or reach an `UncaughtHandler`.

## Language and platform
- Core is pure Java 17, no Kotlin, no coroutines. Kotlin support comes later as `clotho-ktx`.
- JVM and Android share one entry point (`Clotho`); platform support never changes it.
- Main thread: `MainLane` is an interface. Android registers via `androidx.startup`; JVM via
  `ServiceLoader` or explicit `Clotho.install(...)`.
- No Kotlin Multiplatform.

## Core API rules (enforced where possible)
1. Only Android API 24 APIs in core (animal-sniffer). No `VarHandle`, no `java.time` in public API.
2. Time is `long` + `TimeUnit`.
3. `Outcome` is a sealed interface with final classes, not records, for binary compatibility.
4. Concurrency primitives: `Atomic*FieldUpdater`, `AtomicReferenceArray`, `LockSupport`.
5. Implementation classes are package-private (`JobImpl`, `LaneContext`, `DirectLane`, `PoolLane`, `SerialLane`, `SingleLane`, `RankedQueue`, `HostMainLane`, `MainLaneRegistry`, `TimerWheel`, `TimerEntry`, `PeriodicDriver`) or live in `org.codirex.clotho.internal` (from phase 3). Neither is API. `Job` is a sealed interface so it cannot be implemented outside the library.
6. Every public type has complete Javadoc and a test written before the implementation.
7. `-Xlint:all -Werror`; string concat compiled inline (`-XDstringConcat=inline`) because
   `StringConcatFactory` does not exist on API 24.

## API compatibility checking
After the first release, `japicmp` compares against the published baseline
(binary-compatibility-validator targets Kotlin and is not used).

## Module layout
`clotho` (core), `clotho-android` (AGP library, phase 4), `clotho-test` (phase 6), `clotho-jcstress` (phase 6, opt-in), `clotho-bench`, `clotho-bom`, `app` (sample Android application, also required so IDEs recognise the project).
Modules are added in the phase that needs them.

## Behaviour
See [SEMANTICS.md](SEMANTICS.md) (jobs, realms), [ENGINE.md](ENGINE.md) (lanes, threads) [ANDROID.md](ANDROID.md) (main thread, lifecycle) and [CADENCE.md](CADENCE.md) (timers, `Cell`).
