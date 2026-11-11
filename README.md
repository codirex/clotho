<div align="center">

# Clotho

**Structured concurrency and async for the JVM and Android.**
Pure Java 17. No coroutines. No `java.util.concurrent` executors in your code.

[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)
![Java](https://img.shields.io/badge/Java-17-orange)
![Android](https://img.shields.io/badge/Android-minSdk%2024-3DDC84)
![Status](https://img.shields.io/badge/status-pre--release-yellow)

</div>

Clotho is a self-contained concurrency framework with its own vocabulary and its own engine.
Every piece of work runs inside a **`Realm`** (a lifetime scope). Closing the realm cancels the work
it owns, so nothing can outlive its owner silently and no failure is swallowed.

```java
try (Realm realm = Realm.open("checkout")) {
  Job<User> user = realm.launch(Lane.io(), cp -> api.fetchUser(cp));

  Job<String> name = user
      .then(Lane.compute(), (u, cp) -> u.name())
      .recover(Lane.compute(), (error, cp) -> "guest");

  Outcome<String> out = name.await();   // Success, Failure or Cancelled
}
```

> **Status: pre-release (`0.1.0-alpha01`).** Core, engine, main lane, lifecycle realms, timers,
> `Cell` and the test tools exist and are tested. `Flow` (value streams) comes after the first stable
> release. The public API can still change. See [Roadmap](#roadmap).

---

## Table of contents

- [Why Clotho](#why-clotho)
- [Vocabulary](#vocabulary)
- [Installation](#installation)
- [Quick start](#quick-start)
- [Core concepts](#core-concepts)
  - [Realm](#realm)
  - [Job and Outcome](#job-and-outcome)
  - [Cancellation and Checkpoint](#cancellation-and-checkpoint)
  - [Chaining: then, recover, onComplete](#chaining-then-recover-oncomplete)
  - [Combining: zip and race](#combining-zip-and-race)
  - [Failure policy](#failure-policy)
  - [Closing a realm](#closing-a-realm)
- [Lanes and the Engine](#lanes-and-the-engine)
  - [Built-in lanes](#built-in-lanes)
  - [Priorities and aging](#priorities-and-aging)
  - [Bounded queues and rejection](#bounded-queues-and-rejection)
  - [Private engines](#private-engines)
  - [Blocking policy](#blocking-policy)
- [Timers: Cadence](#timers-cadence)
- [Cell](#cell)
- [Android](#android)
- [Testing your code: clotho-test](#testing-your-code-clotho-test)
- [Exceptions reference](#exceptions-reference)
- [Design rules](#design-rules)
- [Quality and verification](#quality-and-verification)
- [Benchmarks](#benchmarks)
- [Project layout](#project-layout)
- [Building from source](#building-from-source)
- [Roadmap](#roadmap)
- [Contributing](#contributing)
- [License](#license)

---

## Why Clotho

| Problem with raw executors and futures | What Clotho does |
|---|---|
| A `Future` can outlive the screen, request or component that started it | Every job belongs to exactly one `Realm`. Closing the realm cancels and waits for its jobs |
| Exceptions disappear inside `Future`s nobody reads | A failure is an `Outcome.Failure`, or it reaches an `UncaughtHandler`. Nothing is dropped silently |
| Cancellation is a flag nobody checks | Work receives a `Checkpoint`; cancellation has one atomic decision point with documented races |
| Thread pools are created everywhere and never shut down | An `Engine` owns every thread. Private engines shut down deterministically |
| "Which thread am I on?" bugs, blocking the UI thread | Lanes declare a `BlockingPolicy`; `await` on the main thread throws instead of freezing the app |
| Timers that leak, overlap or run twice | One hashed timer wheel per engine; periodic jobs never overlap; cancel has a hard guarantee |
| Async tests with `sleep` and flakiness | `clotho-test`: fake clock, manual lanes and a manual main thread. Tests run instantly and exactly |

Clotho is **not** a wrapper around `ExecutorService`. It has its own work-stealing pool, its own
timer wheel and its own job state machine, and it specifies the behaviour in writing
([docs/SEMANTICS.md](docs/SEMANTICS.md)) with tests for the rules.

---

## Vocabulary

| Concept | Meaning |
|---|---|
| `Job<T>` | Handle to a running unit of work (replaces `Future`) |
| `Outcome<T>` | Final result of a job: `Success`, `Failure` or `Cancelled` |
| `Realm` | Lifetime scope that owns jobs. Closing it cancels them |
| `Lane` | *Where* work runs: Compute, Io, Main, Serial, Single, Direct |
| `Engine` | Owns the threads behind the lanes and the timer service |
| `Checkpoint` | Passed to your work so it can observe cancellation |
| `Cadence` | Delays and repetition (the timer service) |
| `Cell<T>` | Atomically updatable protected value |
| `Flow` | Value streams (deferred until after the first stable release) |

---

## Installation

```kotlin
dependencies {
    implementation(platform("org.codirex.clotho:clotho-bom:0.1.0-alpha01"))
    implementation("org.codirex.clotho:clotho")           // JVM and Android core
    implementation("org.codirex.clotho:clotho-android")   // Android: main lane + lifecycle realms
    testImplementation("org.codirex.clotho:clotho-test")  // deterministic test tools
}
```

The current development version is `0.1.0-alpha01`. Artifacts are published to Maven Central under the
group `org.codirex.clotho` (see [CONTRIBUTING](docs/CONTRIBUTING.md) for the release process).

**Requirements**

- Java 17 (language level and bytecode).
- Android: `minSdk 24`, AGP 9+ with a JDK 17 toolchain. The core only uses APIs available on
  Android API 24, enforced at build time by animal-sniffer.

---

## Quick start

### 1. Run work and get a result

```java
Realm realm = Realm.open("my-feature");

Job<Integer> job = realm.launch(Lane.compute(), cp -> expensiveCalculation());

Outcome<Integer> outcome = job.await();
if (outcome instanceof Outcome.Success<Integer> ok) {
  System.out.println(ok.value());
} else if (outcome instanceof Outcome.Failure<Integer> bad) {
  bad.cause().printStackTrace();
} else {
  System.out.println("cancelled");
}

realm.close();
```

`Outcome` is a sealed interface, so you can also use `getOrThrow()`, `valueOrNull()` or
`failureOrNull()`. `getOrThrow()` returns the value, throws `JobFailedException` for a failure and
`CancelledException` for a cancellation.

### 2. Do I/O off the main thread and come back (Android)

```java
realm.launch(Lane.io(), cp -> repository.load())
     .then(Lane.main(), (data, cp) -> { render(data); return data; });
```

### 3. Run something periodically

```java
Job<Void> poll = realm.every(Lane.io(), 0, 30, TimeUnit.SECONDS, cp -> syncOnce());
// ...
poll.cancel();   // after this returns true, no new run starts
```

---

## Core concepts

### Realm

A realm is the scope that owns jobs. It is `AutoCloseable`.

```java
Realm realm = Realm.open("name");                       // defaults: SUPERVISED, stderr handler

Realm custom = Realm.builder("name")
    .policy(RealmPolicy.FAIL_FAST)                      // see Failure policy
    .uncaughtHandler((r, error) -> log.error("realm " + r.name(), error))
    .cadence(engine.cadence())                          // which timer service to use
    .open();
```

| Method | Purpose |
|---|---|
| `launch(lane, work)` / `launch(lane, priority, work)` | Start a job |
| `after(lane, delay, unit, work)` | Run once after a delay |
| `every(...)` / `atRate(...)` | Periodic jobs (fixed delay / fixed rate) |
| `zip(a, b, ownership, lane, combiner)` | Combine two jobs |
| `race(jobs, ownership)` | First success wins |
| `cancel()` / `cancel(interrupt)` | Cancel every job of the realm |
| `close()` / `close(timeout, unit)` / `closeAsync()` | Three ways to close |
| `failure()`, `isClosing()`, `isClosed()`, `outstanding()`, `name()`, `policy()`, `cadence()` | Inspection |

**Membership is total.** Every job (including the result of `then`, `zip`, `race`, and the losers of a
race) belongs to exactly one realm for its whole life. A realm is one atomic counter of outstanding
jobs and callbacks plus a closing flag, so every operation is either accepted and will be waited
for, or throws `RealmClosedException`. Nothing is lost in between.

### Job and Outcome

```java
public sealed interface Job<T> {
  Realm realm();
  boolean isDone();
  boolean isCancellationRequested();
  Outcome<T> outcomeOrNull();                    // null until done
  Outcome<T> await() throws InterruptedException;
  Outcome<T> await(long timeout, TimeUnit unit) throws InterruptedException, TimeoutException;
  boolean cancel();
  boolean cancel(boolean interrupt);
  <R> Job<R> then(Lane lane, Step<? super T, ? extends R> step);
  Job<T> recover(Lane lane, Step<? super Throwable, ? extends T> step);
  void onComplete(Lane lane, Completion<? super T> completion);
}
```

`Job` is sealed: it cannot be implemented outside the library.

**One decision point.** The state of a job is one atomic reference. The compare-and-set that stores the
`Outcome` is the only place where the result is decided; afterwards the outcome is immutable and
every observer (`await`, `outcomeOrNull`, callbacks) sees the same instance.

| Event | Result |
|---|---|
| `cancel()` while pending | `Cancelled` at once, the work never runs |
| `cancel()` while running | Cancel requested. The job is **not done** until the work returns |
| Work returns a value, no cancel requested | `Success` |
| Work returns a value, cancel requested first | `Cancelled` (value dropped) |
| Work throws, no cancel requested | `Failure` |
| Work throws, cancel requested | `Cancelled`; the exception is kept in `lateFailure()` and **not** reported |
| `cancel()` after the outcome is decided | Returns `false`, nothing changes |

### Cancellation and Checkpoint

Cancellation is **cooperative**. Your work receives a `Checkpoint`:

```java
realm.launch(Lane.compute(), cp -> {
  for (Item item : items) {
    cp.check();            // throws CancelledException if cancel was requested
    process(item);
  }
  return summary;
});
```

- `cp.isCancelled()` for polling, `cp.check()` to throw.
- `cancel(true)` also interrupts the thread running the work. The interrupt is delivered only while the
  work runs and is cleared when it returns, so it **cannot leak into the next task** of a pooled thread.
- Java cannot stop code that ignores both signals. Clotho never pretends otherwise (see
  [Closing a realm](#closing-a-realm)).

### Chaining: then, recover, onComplete

```java
Job<Order> order = realm.launch(Lane.io(), cp -> api.loadOrder(id))
    .then(Lane.compute(), (o, cp) -> validate(o))
    .recover(Lane.compute(), (error, cp) -> Order.empty());

order.onComplete(Lane.main(), outcome -> showResult(outcome));
```

- `then` and `recover` create a **new job in the same realm**. They never cancel their parent: a
  dependent does not own its parent.
- Parent cancelled → dependents end `Cancelled` without running.
- Parent failed → `then` dependents fail with the same cause; `recover` runs its step.
- Priorities are inherited from the job being followed.
- `onComplete` runs exactly once on the lane you give, even if you register after completion. A
  callback is outstanding work of the realm, so `Realm.close()` waits for it. An exception thrown by a
  callback goes to the `UncaughtHandler`; other callbacks still run.
- `await()` can return **before** callbacks ran.

### Combining: zip and race

Both require an explicit **`Ownership`**, there is no default. It answers one question: *may the composite
cancel its inputs?*

| `Ownership` | Meaning |
|---|---|
| `OWNED` | When the composite finishes early or is cancelled, it cancels inputs still running |
| `BORROWED` | The composite only observes. Inputs keep running under their realm; their results are ignored |

```java
Job<Profile> profile = realm.zip(userJob, settingsJob, Ownership.OWNED, Lane.compute(),
    (user, settings, cp) -> new Profile(user, settings));

Job<String> fastest = realm.race(List.of(mirrorA, mirrorB, mirrorC), Ownership.OWNED);
```

`zip(a, b)`: both succeed → the combiner runs; an input fails → the result fails with that exact cause;
an input is cancelled → the result is `Cancelled`.

`race(inputs)`: the winner is the **first `Success`**. A single failure does not end the race. If nothing
succeeds and something failed → `Failure(NoWinnerException)` (cause = first failed input in list
order, others suppressed). All cancelled → `Cancelled`.

Inputs must belong to the realm the composite is created on.

### Failure policy

| `RealmPolicy` | Behaviour |
|---|---|
| `SUPERVISED` (default) | Failures stay in their jobs |
| `FAIL_FAST` | A failed job started with `launch` cancels **every** job of the realm (and every job launched afterwards), stores the first failure in `Realm.failure()` and reports it once |

Under `FAIL_FAST`, failures of derived jobs (`then`, `recover`, `zip`, `race`) do not trigger it, so
`recover` only makes sense in a `SUPERVISED` realm. The realm is cancelled **before** `await()`
waiters wake up, so after `await()` returns, `Realm.failure()` is already set.

The **`UncaughtHandler`** receives: exceptions from callbacks, leaked jobs, and the `FAIL_FAST`
trigger. It never receives late failures of cancelled jobs. The default prints to stderr;
replace it globally with `Clotho.setDefaultUncaughtHandler(...)` or per realm via the builder.

### Closing a realm

States: `OPEN` → `CLOSING` → `CLOSED`.

| Call | Blocks | What it does |
|---|---|---|
| `close()` | yes | Rejects new work, **waits** for everything, cancels nothing. Never call it from inside a job of the same realm. If the thread is interrupted it cancels (without interrupting threads) and keeps waiting |
| `close(timeout, unit)` | yes | On timeout: cancels everything, **interrupts** running jobs, reports each still-running job once as `LeakedJobException`, returns `false`. The realm stays `CLOSING` and becomes `CLOSED` by itself when the last job ends |
| `closeAsync()` | **no** | Rejects new work, requests cancellation (no interrupt), returns a `Realm.Closing` at once. Idempotent. Safe on the main thread |

```java
Realm.Closing closing = realm.closeAsync();
closing.onClosed(Lane.main(), () -> log("realm fully closed"));
```

**What is not promised:** `false` from `close(timeout)` does not mean the work stopped. A realm can
refuse new work, deliver cancel and interrupt, report the leak, and release its references when the
job finally ends. That is all Java allows.

---

## Lanes and the Engine

A **lane** decides *where* a task runs. An **engine** owns the threads behind the lanes.

```java
public interface Lane {
  void execute(Runnable task);
  default void execute(Runnable task, Priority priority) { execute(task); }
  default BlockingPolicy blockingPolicy() { return BlockingPolicy.ALLOWED; }
  // ...
}
```

### Built-in lanes

| Lane | Use for | Threads | Blocking | Order |
|---|---|---|---|---|
| `Lane.compute()` | CPU-bound work | up to processors (min 1), idle keep-alive 60 s | **FORBIDDEN** | priority + aging |
| `Lane.io()` | Blocking I/O | on demand up to 128, idle keep-alive 30 s | allowed | priority + aging |
| `Lane.main()` | UI / main thread | the host's main thread | **FORBIDDEN** | FIFO, always posts |
| `engine.serial(name)` | One task at a time, in order, no dedicated thread | borrowed from the engine's I/O pool | allowed | exact submission order, happens-before between tasks |
| `engine.single(name)` | One dedicated thread | 1 owned for the lane's life | allowed | exact submission order |
| `Lane.direct()` | Run inline on the caller | caller's | inherits | immediate |

`Lane.compute()` and `Lane.io()` use the **global engine**: shared by the process, daemon threads
that retire when idle. It cannot be shut down (a library must not close it for everyone), and it
cannot create `single` lanes. Use a private engine for that.

The compute and I/O pool is a **work-stealing pool**: a shared queue for outside submissions, a local
queue per worker for tasks submitted from a worker of the same lane, stealing when both are empty.

### Priorities and aging

```java
realm.launch(Lane.compute(), Priority.HIGH, cp -> urgentWork());
```

Levels: `LOW < NORMAL < HIGH`. A waiting task's effective priority grows with its waiting time:

```
effective = min(HIGH, base + floor((now - enqueuedAt) / agingStep))     // agingStep default: 100 ms
```

Any task reaches `HIGH` after `2 * agingStep`, so there is **no starvation**: under continuous `HIGH`
load a `LOW` task waits at most `2 * agingStep` plus the time to run the tasks queued before it.
Within one queue ordering is exact; across the shared and local queues it is approximate (the
starvation bound holds per queue; strict global priority order is not promised with several threads).

Serial, single and main lanes ignore priority: their contract is exact submission order.

### Bounded queues and rejection

```java
Engine engine = Engine.builder()
    .computeQueue(QueueLimit.of(1_000, RejectionPolicy.DROP_OLDEST))
    .build();
```

| `RejectionPolicy` | When the queue is full |
|---|---|
| `REJECT` | Throws `RejectedExecutionException`; the job ends as `Failure` |
| `DROP_OLDEST` | The longest-waiting task is removed and never runs; its job ends as `Failure(DroppedException)`; the new task is queued |
| `CALLER_RUNS` | The submitting thread runs it, **only if that is safe** for blocking. Unknown callers (plain threads, UI thread) are rejected. The main thread is never used to run pool work |

Serial and single lanes accept `REJECT` and `DROP_OLDEST`. `CALLER_RUNS` would break their ordering and
is refused at creation. The capacity check is atomic: the queue never exceeds its capacity.

**Nothing is dropped silently.** A discarded task ends its job as `Failure(DroppedException)`
(with `Reason.QUEUE_OVERFLOW` or `Reason.SHUTDOWN`); a discarded completion callback is reported to the
`UncaughtHandler` and released, so `Realm.close()` can never hang on it.

### Private engines

```java
try (Engine engine = Engine.builder()
        .name("downloads")
        .computeThreads(2)
        .ioMaxThreads(16)
        .ioKeepAlive(10, TimeUnit.SECONDS)
        .agingStep(50, TimeUnit.MILLISECONDS)
        .daemon(true)
        .build()) {

  Lane serial = engine.serial("uploads");   // ordered, one at a time
  Lane single = engine.single("db");        // one dedicated thread
  // ...
}
```

No thread exists until work arrives. The creator owns the lifecycle:

| Call | Blocks | Queued tasks | Running tasks | New tasks |
|---|---|---|---|---|
| `shutdown()` | no | still run | keep running | rejected |
| `shutdownNow()` | no | discarded | interrupted | rejected |
| `awaitTermination(t)` | yes | | | |
| `close()` | yes | still run | keep running | rejected |
| `close(t)` | yes | run until `t`, then discarded | interrupted after `t` | rejected |

After shutdown every lane rejects with `LaneClosedException` (a `RejectedExecutionException`).
`isTerminated()` means shut down **and** no owned thread alive; `liveThreads()` tells you how many.

### Blocking policy

Each lane declares `ALLOWED`, `FORBIDDEN` or `INHERIT`. The framework records which lane's task each
thread is running (never by thread name). Then:

- `Job.await`, `Realm.close`, `Realm.Closing.await`, `Engine.close` and `Engine.awaitTermination`
  throw `BlockingNotAllowedException` when called from a `FORBIDDEN` lane, even if the job is
  already done.
- Once a main lane is installed, the same calls are refused on the main thread **even outside Clotho
  tasks** (for example in `onCreate`).

**What it cannot detect:** `Thread.sleep`, locks, `Object.wait`, `Future.get`, blocking I/O and
native calls. `FORBIDDEN` is a contract plus the checks above, not a guarantee that compute threads
never block.

---

## Timers: Cadence

One hashed timer wheel and one thread per engine (`engine.cadence()`). The thread starts with the first
pending timer, ends when none is left, and **never runs your code**: it only hands tasks to a lane.

```java
// run once after a delay
Job<String> later = realm.after(Lane.io(), 5, TimeUnit.SECONDS, cp -> fetch());

// fixed DELAY: next run = end of the run + period
Job<Void> a = realm.every(Lane.io(), 0, 10, TimeUnit.SECONDS, cp -> cleanup());

// fixed RATE: runs planned at first + k * period, missed slots are skipped
Job<Void> b = realm.atRate(Lane.io(), 0, 1, TimeUnit.SECONDS, RepeatFailure.CONTINUE, cp -> tick());
```

- **Never early.** A timer never fires before its deadline. It can be late by up to one wheel tick
  (default 10 ms) plus scheduling. It is **not real-time**; no upper bound is promised.
- **Monotonic clock** (`System.nanoTime`): wall-clock changes do not matter.
- **No overlap.** Two runs of one periodic job never overlap. A tick waiting in a busy lane delays the
  whole schedule instead of creating a second tick.
- **No catch-up burst.** After a long pause a fixed-rate job skips missed slots; a fixed-delay job
  waits one period after its run ends. A slot is never run twice.
- A periodic job never succeeds: it ends `Cancelled` or `Failure`.
- **Cancel guarantee:** once `cancel()` returned `true`, no run that had not yet started will start.
- `RepeatFailure.STOP` (default): a throwing run fails the job. `CONTINUE`: the failure is reported
  once to the `UncaughtHandler` and the next run is planned.
- Lanes that run tasks inline (`Lane.direct()`) are refused for timers with `IllegalArgumentException`.
- `Realm.close()` cancels periodic jobs; a pending `after` is waited for. `closeAsync()` cancels both.
  Engine `shutdown()` discards all pending timers as `Failure(DroppedException)`.

**Android:** `nanoTime` does not advance in deep sleep, so Clotho timers do **not** wake the device.
Use `AlarmManager` or `WorkManager` for exact or wake-up scheduling.

Full rules: [docs/CADENCE.md](docs/CADENCE.md).

---

## Cell

`Cell<T>` is a lock-free atomically updatable value (an `AtomicReference` underneath).

```java
Cell<Integer> counter = Cell.of(0);

counter.set(10);
int old   = counter.getAndUpdate(n -> n + 1);
int fresh = counter.updateAndGet(n -> n * 2);
boolean swapped = counter.compareAndSet(22, 0);       // compares with equals()
boolean same    = counter.compareAndSetSame(ref, ref2); // compares by reference
```

- **Update functions can run several times** if another thread changes the cell meanwhile. Keep them
  fast, pure and dependent only on their argument. If one throws, the cell is unchanged.
- No lost updates; lock-free, not wait-free.
- `null` is a legal value; the functions themselves must not be `null`.
- Observers come with `Flow` (after the first stable release).

---

## Android

Add `clotho-android`. It brings the main lane and lifecycle-bound realms.

### Main lane (zero setup)

`ClothoInitializer` (via `androidx.startup`) installs the Android main looper as the main lane
automatically. Then `Lane.main()` just works.

```java
realm.launch(Lane.io(), cp -> "computed on " + Thread.currentThread().getName())
     .then(Lane.main(), (text, cp) -> { textView.setText(text); return text; });
```

- `execute` **always posts**, even from the main thread: a task never runs inside another, order is FIFO.
- `MainLane.immediate()` runs inline when already on the main thread, posts otherwise. It can run
  before already-queued tasks: use it knowingly.
- A job never throws into the looper; its failure is its outcome.
- Opt out of auto-install with `tools:node="remove"` on the initializer's meta-data and call
  `AndroidMainLane.install()` yourself.
- R8: consumer rules keep `ClothoInitializer`; the core jar carries rules for `MainThreadHost`
  providers.

### Lifecycle-bound realms

```java
public class MyActivity extends ComponentActivity {
  private Realm realm;

  @Override protected void onCreate(Bundle b) {
    super.onCreate(b);
    realm = AndroidRealms.forLifecycle(this);          // closes itself at ON_DESTROY
    realm.launch(Lane.io(), cp -> load()).then(Lane.main(), (d, cp) -> { show(d); return d; });
  }
}
```

| | |
|---|---|
| Overloads | `forLifecycle(owner)`, `forLifecycle(owner, builder)`, `forLifecycle(owner, builder, interruptOnDestroy)` |
| Thread | Main thread only, else `IllegalStateException` |
| When it closes | At `ON_DESTROY`, and only then. **Not** on pause/stop, so a realm outlives backgrounding |
| How | `closeAsync()`: new work rejected, current jobs asked to cancel (and interrupted if `interruptOnDestroy`) |
| Rotation | A configuration change destroys the activity and its realm. Use a realm that lives outside the activity for work that must survive rotation |
| Callbacks | Registered callbacks still run exactly once, possibly after destroy. They must not touch destroyed views |
| Duplicates | One binding per `Lifecycle`: calling again returns the same open realm |
| Leaks | The realm never references the owner; the binding map is weak |

### Other hosts

For a JVM UI toolkit, implement `MainThreadHost` (`isMainThread()`, `post(Runnable)`) and either
register it with `ServiceLoader` or call `Clotho.install(MainLane.of("name", host))`.

Full rules: [docs/ANDROID.md](docs/ANDROID.md).

---

## Testing your code: clotho-test

Asynchronous tests without threads, sleeps or flakiness.

| Class | What it gives you |
|---|---|
| `TestClock` | A clock that only moves when you move it (`advance`, `set`). Never goes backwards |
| `DeterministicLane` | Runs nothing until you call `runNext()` / `runAll()`, in submission order. Also `refuseWith(...)`, `discardAll(reason)`, configurable `BlockingPolicy`, recorded priorities |
| `ManualMainThread` | A main thread that runs posted tasks when you call `idle()`. Use with `MainLane.of("test", new ManualMainThread())` |
| `RecordingUncaughtHandler` | Remembers every error reported to a realm |
| `TestScheduler` | All of it wired together: clock, manual timers, lane, realm |

```java
try (TestScheduler s = new TestScheduler()) {
  List<Long> starts = new ArrayList<>();
  s.realm().atRate(s.lane(), 0, 100, TimeUnit.MILLISECONDS, cp -> starts.add(s.clock().nanos()));

  s.advance(300, TimeUnit.MILLISECONDS);   // runs at 0, 100, 200, 300 ms of test time, instantly
}
```

- `advance(d)` stops at every timer on the way, so each periodic run happens at its own time.
- `jump(d)` moves time in one step like a device that slept (late timers fire once, fixed-rate jobs
  skip missed slots).

Under the hood the core exposes `Engine.Builder.manualTimers()`, `Cadence.runDue()` and
`Cadence.nextDueNanos()`: timers without a thread, driven by the engine clock. These exact tests already
found one real bug (a fixed-rate slot run twice) during development.

Full guide: [docs/TESTING.md](docs/TESTING.md).

---

## Exceptions reference

| Exception | Meaning |
|---|---|
| `CancelledException` | Thrown by `Checkpoint.check()` and `Outcome.getOrThrow()` on a cancelled job |
| `JobFailedException` | Wraps the cause when `getOrThrow()` is called on a failure |
| `RealmClosedException` | The realm no longer accepts work |
| `BlockingNotAllowedException` | A blocking call on a lane (or the main thread) that forbids it |
| `NoWinnerException` | A `race` ended with failures and no success |
| `LeakedJobException` | Reported when `close(timeout)` expires on a job that is still running |
| `DroppedException` | A queued task was discarded; `reason()` is `QUEUE_OVERFLOW` or `SHUTDOWN` |
| `LaneClosedException` | The lane/engine is shut down (a `RejectedExecutionException`) |
| `MainLaneNotInstalledException` | `Lane.main()` used with no main lane and no `MainThreadHost` found |

---

## Design rules

These are enforced where possible by the build.

1. Core is pure Java 17: no Kotlin, no coroutines. Kotlin extensions come later as `clotho-ktx`, optional.
2. Only Android API 24 APIs in the core (animal-sniffer). No `VarHandle`, no `java.time` in the public API.
3. Time is `long` + `TimeUnit`.
4. `Outcome` is a sealed interface with final classes (not records) for binary compatibility.
5. Concurrency primitives: `Atomic*FieldUpdater`, `AtomicReferenceArray`, `LockSupport`.
6. Implementation classes are package-private; `Job` is sealed.
7. Every public type has complete Javadoc and a test written before the implementation.
8. `-Xlint:all -Werror`; string concatenation compiled inline because `StringConcatFactory` does not
   exist on API 24.

Not set up yet: automatic binary-compatibility checking against the published baseline (planned
for the first stable release).

---

## Quality and verification

| Layer | Where | Proves |
|---|---|---|
| Unit and behaviour tests | `clotho/src/test`, `clotho-test/src/test` | The documented contracts, with exact timing through a fake clock |
| Real-thread tests | `Engine*Test`, `CadenceTest`, `PeriodicTest`, `MainLaneTest` | The same contracts with real threads (tolerant: "never early", ordering) |
| Stress tests | `ConcurrencyStressTest`, `EngineStressTest`, `CadenceStressTest` | Exactly-once, no job without an outcome, no leaked thread, in the interleavings that occurred |
| **jcstress** | `clotho-jcstress` (10 scenarios) | Which outcomes the JVM and CPU can produce for the small critical structures |
| Robolectric | `clotho-android/src/test` | The Android glue on a simulated looper |
| Instrumented | `clotho-android/src/androidTest` | The real looper, manifest merge and androidx lifecycle |

jcstress scenarios: cancel vs completion, callback exactly once, `Cell` update and CAS, timer wheel
fire vs cancel and schedule vs advance, realm launch vs close, race success vs cancel, ranked queue
take-once, periodic cancel vs tick.

Build gates: Spotless (google-java-format + license header), animal-sniffer (API 24), `-Xlint:all -Werror`,
strict Javadoc (`-Xdoclint:all`). JaCoCo produces a coverage report.

**Honest limits.** Stress tests do not prove the absence of other interleavings. Coverage gates
and a recorded benchmark run are still open. See [docs/TESTING.md](docs/TESTING.md) for what each
kind of test proves and what is not covered.

CI (GitHub Actions) runs the JVM modules on JDK 17 and 21, the Android module and a publishing dry run on
every push and pull request. jcstress and benchmarks are manual workflows.

---

## Benchmarks

`clotho-bench` (JMH) compares Clotho with `java.util.concurrent`:

| Benchmark | Compared with |
|---|---|
| Round trip, one and many producers (mean and p50/p99/p99.9) | `ThreadPoolExecutor`, `ForkJoinPool`, `CompletableFuture` |
| Batch throughput (1000 tasks per batch) | same |
| Three-stage chain | `CompletableFuture` |
| Ordered lane (1000 tasks in order) | `newSingleThreadExecutor` |
| Timers: schedule + cancel 10,000 timers | `ScheduledThreadPoolExecutor` |
| Realm scope cost | the same task on a plain pool |

```bash
./gradlew -PwithBench :clotho-bench:bench                         # full run
./gradlew -PwithBench :clotho-bench:bench -PbenchQuick            # ~2 minute smoke run
./gradlew -PwithBench :clotho-bench:bench -PbenchFilter=Timers    # one class
python3 scripts/jmh_to_md.py clotho-bench/build/results/jmh/results.json > report.md
```

**No numbers are published yet.** Recorded results will be added to
[docs/BENCHMARKS.md](docs/BENCHMARKS.md) from a quiet machine with the hardware stated. Benchmarks
Clotho loses stay in the report.

---

## Project layout

```
clotho-framework/
├── clotho/            Core (pure Java): Realm, Job, Lane, Engine, Cadence, Cell
├── clotho-android/    Android: main lane, startup initializer, lifecycle realms
├── clotho-test/       Test tools: TestClock, DeterministicLane, TestScheduler, ...
├── clotho-jcstress/   10 jcstress scenarios      (opt-in: -PwithJcstress)
├── clotho-bench/      JMH benchmarks             (opt-in: -PwithBench)
├── app/               Sample Android app
├── build-logic/       Gradle convention plugins (library, publish, spotless, java-base)
├── docs/              SEMANTICS, ENGINE, CADENCE, ANDROID, TESTING, BENCHMARKS, CONTRIBUTING
└── scripts/           jmh_to_md.py
```

| Document | Covers |
|---|---|
| [SEMANTICS.md](docs/SEMANTICS.md) | Jobs, realms, cancellation, zip/race, failure policy: the full behavioural contract |
| [ENGINE.md](docs/ENGINE.md) | Threads, lanes, priorities, queues, shutdown |
| [CADENCE.md](docs/CADENCE.md) | Timers and `Cell` |
| [ANDROID.md](docs/ANDROID.md) | Main thread, lifecycle, startup, R8 |
| [TESTING.md](docs/TESTING.md) | Test tools and what each kind of test proves |
| [BENCHMARKS.md](docs/BENCHMARKS.md) | How to run and read the benchmarks |
| [CONTRIBUTING.md](docs/CONTRIBUTING.md) | Contribution rules and the release process |
| [CHANGES.md](CHANGES.md) | Changelog (every public API change is listed) |

---

## Building from source

```bash
./gradlew spotlessApply                  # format + license headers
./gradlew build                          # tests, Spotless, animal-sniffer, Javadoc
```

Building the `app` sample needs Android SDK 37. Optional modules:

```bash
./gradlew -PwithJcstress :clotho-jcstress:jcstress
./gradlew :clotho-android:connectedDebugAndroidTest     # device or emulator required
```

---

## Roadmap

**Done:** core (`Realm`, `Job`, `Outcome`), engine (work-stealing pool, lanes, priorities, bounded
queues), Android (main lane, lifecycle realms), timers (`Cadence`) and `Cell`, test tools
(`clotho-test`), stress tests and jcstress scenarios, JMH benchmarks, CI and Maven Central publishing.

**Still open before a stable release:** a recorded benchmark run, coverage gates, a BOM.

**Later:** `Flow` (value streams, after the first stable release); `clotho-ktx` (optional Kotlin
extensions).

---

## Contributing

1. `./gradlew spotlessApply` before committing.
2. `./gradlew build` must pass.
3. New public API: write Javadoc and tests first, then implement.
4. Record every public API change in [CHANGES.md](CHANGES.md).

Details and the release process: [docs/CONTRIBUTING.md](docs/CONTRIBUTING.md).

---

## License

Apache License 2.0. See [LICENSE](LICENSE).
