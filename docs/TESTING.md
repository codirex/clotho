# Testing

Each tool below proves something different. None replaces another.

| Tool | Where | Proves | Cannot prove |
|---|---|---|---|
| Unit and behaviour tests | `clotho/src/test`, `clotho-test/src/test` | the documented contracts, with exact timing through a fake clock | absence of races |
| Real-thread tests | `Engine*Test`, `CadenceTest`, `PeriodicTest`, `MainLaneTest` | the same contracts with real threads and the real clock (tolerant: "never early", ordering, thread ends) | tight timing |
| Stress tests | `ConcurrencyStressTest`, `EngineStressTest`, `CadenceStressTest` | the invariants held in the interleavings that happened: exactly-once, no job without outcome, no leaked thread | that no other interleaving breaks them |
| jcstress | `clotho-jcstress` | for the listed small structures, which outcomes the JVM and the hardware can produce | anything about big flows (engine, realm close with threads) |
| Robolectric | `clotho-android/src/test` | the Android glue on a simulated looper | behaviour on a real device |
| Instrumented | `clotho-android/src/androidTest` | the real looper, the real manifest merge, real androidx lifecycle | other devices and versions |

CI runs the unit, real-thread, stress and Robolectric tests. jcstress and the instrumented tests are
not part of CI's normal run (jcstress has a manual workflow).

## 1. `clotho-test`: test your own code

Depend on it from your tests (`testImplementation("org.codirex.clotho:clotho-test:<version>")`). It keeps
asynchronous tests free of threads, sleeps and flakiness.

- `TestClock`: a clock that only moves when you move it (`advance`, `set`). Never goes backwards.
- `DeterministicLane`: a lane that runs nothing until you call `runNext()` / `runAll()` on your
  thread, in submission order. Also: `refuseWith(...)` (a lane that refuses tasks), `discardAll(reason)`
  (a lane that drops its queue), a configurable `BlockingPolicy`, recorded priorities.
- `ManualMainThread`: a main thread that runs posted tasks when you call `idle()`. Use with
  `MainLane.of("test", new ManualMainThread())`.
- `RecordingUncaughtHandler`: remembers every error reported to a realm.
- `TestScheduler`: all of it wired together (clock, manual timers, lane, realm).

```java
try (TestScheduler s = new TestScheduler()) {
  List<Long> starts = new ArrayList<>();
  s.realm().atRate(s.lane(), 0, 100, TimeUnit.MILLISECONDS, cp -> starts.add(s.clock().nanos()));
  s.advance(300, TimeUnit.MILLISECONDS);   // runs at 0, 100, 200, 300 ms of test time, instantly
}
```

`advance(d)` stops at every timer on the way, so each periodic run happens at its own time;
`jump(d)` moves time in one step like a device that slept (late timers fire once, fixed-rate jobs
skip missed slots). A task may advance the clock itself to simulate that it takes time.

The manual timers it uses are in the core: `Engine.Builder.manualTimers()`, `Cadence.runDue()`,
`Cadence.nextDueNanos()`. With them no timer thread exists; timers fire only when `runDue()` is called.
Because the clock is fake, these tests are exact, which is why they found a real fixed-rate bug (a
slot run twice when its run ended at the instant of the slot), since fixed. They do not test the timer
thread: `CadenceTest` and `PeriodicTest` do.

## 2. jcstress (`clotho-jcstress`)

jcstress runs a small piece of code from several threads millions of times and reports every outcome
that the JVM and the CPU produce. It is the specialised tool for the places where a lost update or a
duplicated action would hide.

Scenarios (ten), each with the outcomes allowed and everything else forbidden:

| Test | Outcomes that must be the only ones |
|---|---|
| `JobCancelVersusCompletion` | cancel true with `Cancelled`, or cancel false with `Success` |
| `JobCallbackExactlyOnce` | the callback ran once and nothing is outstanding |
| `CellGetAndUpdate` | two updates replace different values, final value 2 |
| `CellCompareAndSet` | exactly one of two compareAndSet wins |
| `TimerWheelFireVersusCancel` | a timer fires xor is cancelled, nothing left behind |
| `TimerWheelScheduleVersusAdvance` | a timer added during an advance fires exactly once |
| `RealmLaunchVersusClose` | accepted work is never left pending, the realm ends closed |
| `RaceSuccessVersusCancel` | success or cancellation, never both, as for a plain job |
| `RankedQueueTakeOnce` | one task, taken by exactly one thread |
| `PeriodicCancelVersusTick` | no timer left behind, at most one run, job ends done |

Run it (it takes minutes and wants real cores, so it is not part of `build` or `check`):

```
./gradlew -PwithJcstress :clotho-jcstress:jcstress                      # mode quick
./gradlew -PwithJcstress :clotho-jcstress:jcstress -PjcstressMode=default
```

The module is included in the build only with `-PwithJcstress` (see `settings.gradle.kts`), so it
never slows the normal build. The run writes its report under `results/`. There is also a manual CI
job (workflow dispatch).

The tests live in the package `org.codirex.clotho` (split package, classpath only) because they
touch package-private classes (`TimerWheel`, `RankedQueue`). Where the jcstress annotation `Outcome`
clashes with `org.codirex.clotho.Outcome`, the tests spell the library type in full.

## 3. Instrumented tests

```
./gradlew :clotho-android:connectedDebugAndroidTest     # a device or emulator must be connected
```

`AndroidMainLaneInstrumentedTest`: the startup initializer installed the lane without any call, tasks
run on the real main looper in order, `immediate()`, an I/O to main round trip, blocking refused on
the main thread. `AndroidRealmsInstrumentedTest`: binding and destroy with the real `LifecycleRegistry`
(which enforces the main thread itself), and a destroy that returns at once while a job is still
running.

## 4. Not covered

- Coverage gates: CI enforces at least 90% line coverage and 80% branch coverage in each JVM library module. These floors do not prove the absence of concurrency races.
- Custom lint rules and mutation testing: none.
- jcstress for the whole engine (`PoolLane`, `SerialLane`): too coarse for jcstress; covered by the
  stress tests only.
