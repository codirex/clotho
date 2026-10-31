# Engine and lanes (phase 3)

Every rule here has tests in `clotho/src/test` (`Engine*Test`, `RankedQueueTest`). Behaviour of jobs
themselves is in [SEMANTICS.md](SEMANTICS.md).

## 1. Who owns the threads

- An `Engine` owns every thread behind its lanes. Nothing else creates threads for Clotho work.
- **Private engines** (`Engine.builder()...build()`): the creator owns the lifecycle and must shut
  them down. No thread exists until work arrives.
- **Global engine** (`Engine.global()`, used by `Lane.compute()` and `Lane.io()`): shared by the whole
  process, daemon threads that retire when idle. `shutdown()`, `shutdownNow()`, `close()`,
  `close(timeout)` and `single(...)` throw `IllegalStateException`, so a library cannot close it for
  everyone. A single lane owns a thread for its whole life, which the global engine could never
  release, so single lanes need a private engine.
- `liveThreads()` counts the threads the engine currently owns. `isTerminated()` means shut down
  **and** no owned thread alive.

## 2. Shutdown (deterministic for private engines)

The timer service ([CADENCE.md](CADENCE.md)) belongs to the engine: `shutdown()` and `shutdownNow()`
also discard its pending timers and end its thread.

| Call | Blocks | Queued tasks | Running tasks | New tasks |
|---|---|---|---|---|
| `shutdown()` | no | still run | keep running | rejected from the moment it returns, including tasks submitted by running tasks |
| `shutdownNow()` | no | discarded | interrupted | rejected |
| `awaitTermination(t)` | yes | | | |
| `close()` | yes | still run | keep running | rejected |
| `close(t)` | yes | still run until `t`, then discarded | interrupted after `t` | rejected |

- `shutdown()` sets the flag and then waits for the submissions that were already in progress, so
  after it returns nothing is enqueued and no thread is started any more.
- **After shutdown every lane rejects**: `Lane.execute` throws `RejectedExecutionException`; a job
  launched on it ends as `Failure(RejectedExecutionException)`. Creating serial/single lanes throws
  `IllegalStateException`. The lane objects stay valid, they just reject.
- **Discarded tasks** (`shutdownNow`, `DROP_OLDEST`) are never run. If the task belongs to a job, the
  job ends as `Failure(DroppedException)` (`Lane.Discardable`). A discarded completion callback is
  reported to the realm's `UncaughtHandler` and released, so `Realm.close()` cannot hang on it. A
  discarded raw `Runnable` is reported to the engine's task error handler. Nothing is dropped silently.
- `close()` called with an interrupt pending escalates to `shutdownNow()`, keeps waiting and restores
  the flag. `close(t)` returns `true` only if the engine terminated within `t`; otherwise it calls
  `shutdownNow()` once and returns `false` without waiting again.
- `awaitTermination` returns `false` if a task ignores cancellation and interrupts. Java cannot stop
  such code; the thread stays alive and is counted by `liveThreads()` until the task returns.
- Waiting calls (`awaitTermination`, `close`) throw `BlockingNotAllowedException` on a lane that forbids
  blocking, and `IllegalStateException` when called from a thread the engine owns (it would wait for
  itself).

## 3. Compute and I/O

- **Compute**: up to `computeThreads` threads (default: processors, at least 1). Policy `FORBIDDEN`.
- **I/O**: threads are created on demand up to `ioMaxThreads` (default 128) and retire after the
  keep-alive time (default 30 s; compute 60 s). Policy `ALLOWED`. Tasks beyond the maximum wait in
  the queue.
- **What `FORBIDDEN` detects**: the calls the framework knows about: `Job.await(...)`,
  `Realm.close(...)`, `Realm.Closing.await(...)`, `Engine.awaitTermination/close(...)`. They throw
  `BlockingNotAllowedException` when the current lane forbids blocking (based on the lane recorded
  for the thread, never on its name) or, once a main lane is installed, on the main thread. A task running inline through `CALLER_RUNS` or `Lane.direct()` keeps the policy of the lane it
  was meant for / the caller.
- **What it cannot detect**: `Thread.sleep`, locks, `Object.wait`, `Future.get`, blocking I/O, native
  calls. Java offers no way to forbid them. `FORBIDDEN` is a contract plus the checks above, not a
  guarantee that compute threads never block. (Test: `theFrameworkOnlyDetectsItsOwnBlockingCalls`.)

## 4. Priorities and aging

- Levels: `LOW < NORMAL < HIGH`, set at `Realm.launch(lane, priority, work)`. `then`/`recover` jobs
  inherit the priority of the job they follow; a `zip` combiner uses the higher of its inputs.
- **Effective priority** of a waiting task:

  `effective = min(HIGH, base + floor((now - enqueuedAt) / agingStep))`

  `agingStep` defaults to 100 ms. It changes at every multiple of `agingStep` spent waiting, and any
  task reaches `HIGH` after `2 * agingStep`. The task keeps its `base` for everything else (drop
  decisions use waiting time only). `agingStep(0)` disables aging (tests only).
- **Order**: highest effective priority first; ties go to the task queued first (then the lower
  submission number). Tasks of one level are FIFO, so only the head of each level is compared.
- **No starvation**: once a task is effectively `HIGH` it competes by queueing time only, so it waits
  for the tasks queued before it and nothing queued after it. Under continuous `HIGH` load a `LOW`
  task waits at most `2 * agingStep` plus the time to run the tasks queued before it.
  (Tests with a fake clock check the exact boundaries, a chain of `HIGH` tasks that always keeps one
  waiting, and a real-clock flood.)
- **Scope**: priority and aging apply to compute and I/O. Each worker has a local queue and there is a
  shared queue, so ordering is exact inside one queue and approximate across queues: the starvation
  bound holds per queue, strict global priority order is not promised with several threads.
- **Serial and single lanes ignore priority.** Their contract is stronger: tasks run in exact
  submission order. `serial` also guarantees one task at a time with a happens-before edge between
  consecutive tasks (the thread may change); `single` guarantees the same dedicated thread.

## 5. Bounded queues and rejection

`QueueLimit.of(capacity, policy)` limits the tasks waiting in a lane (running tasks do not count).

- `REJECT`: `RejectedExecutionException`; the job ends as `Failure`.
- `DROP_OLDEST`: the task that has waited longest, regardless of priority, is removed and the new
  one is queued. The removed task never runs; its job ends at once as `Failure(DroppedException)`.
  In a `FAIL_FAST` realm that failure fails the realm like any other failure of a root job.
- `CALLER_RUNS`: the submitting thread runs the task, but only if it is safe for blocking:

  | caller (lane recorded for the thread) | target `ALLOWED` | target `FORBIDDEN` |
  |---|---|---|
  | unknown (plain thread, UI thread) | rejected | rejected |
  | on a `FORBIDDEN` lane | rejected | runs inline |
  | on an `ALLOWED` lane | runs inline | runs inline |

  Unknown callers are never trusted. Once a main lane is installed (phase 4) the main thread is
  known: it is **never** used to run pool work inline, whichever lane it came from (see ANDROID.md).
  The inline run uses the policy of the target lane. Rejection throws like `REJECT`.
- Serial and single lanes accept `REJECT` and `DROP_OLDEST`; `CALLER_RUNS` would break their order and
  is refused at creation.
- The capacity check is atomic, so the queue never holds more than its capacity.

## 6. Work-stealing pool

Lock-protected queues (not lock-free): a shared queue for outside submissions, a local queue per
worker for tasks submitted from a worker of the same lane, stealing from other workers when both are
empty. Idle workers park on a stack; a submitter claims one and unparks it, or starts a worker below
the maximum, so a wake-up cannot be lost. Replacing the queues with lock-free deques is a later
optimisation to be justified by the benchmarks (phase 7) and jcstress (phase 6).

## 7. What the stress tests prove, and what they do not

They check, under heavy concurrency with random lanes, priorities, bounded queues, cancellation and a
`shutdownNow` in the middle of the load, that: a job that succeeded ran exactly once; a job that was
rejected or dropped never ran; no job is left without a final outcome; queues are empty and the realm
has nothing outstanding afterwards; no engine-owned thread survives termination.

They show that these invariants held in the interleavings that occurred during the runs, on the
machines that ran them. They do not prove that no other interleaving breaks them, and a single-core
machine exercises fewer parallel interleavings. Proof-level checks of the critical structures belong
to jcstress (phase 6); the stress tests do not replace them.
