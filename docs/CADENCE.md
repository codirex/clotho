# Cadence: timers and `Cell` (phase 5)

Every rule has tests: `TimerWheelTest`, `CadenceMathTest` (fake clock, no threads), `CadenceTest`,
`PeriodicTest` (real threads, real clock), `CadenceStressTest` (concurrency), `CellTest`.

## 1. API

| Call | Result |
|---|---|
| `realm.after(lane, delay, unit, work)` | `Job<T>`: runs `work` once after the delay |
| `realm.every(lane, initialDelay, period, unit, [onFailure,] action)` | `Job<Void>`: fixed **delay**, next run = end of the run + period |
| `realm.atRate(lane, initialDelay, period, unit, [onFailure,] action)` | `Job<Void>`: fixed **rate**, runs planned at `first + k * period` |
| `Realm.builder(..).cadence(engine.cadence())` | the timer service of a realm; default: the global engine's |

`Action` is work without a result. `RepeatFailure` is `STOP` (default) or `CONTINUE`. The unit
applies to both the initial delay and the period. A periodic job never succeeds: it ends
`Cancelled` or `Failure`.

## 2. Time

- The clock is the engine clock (`System.nanoTime`, monotonic; `Engine.Builder.ticker` replaces it,
  also for timers). Wall-clock changes do not matter.
- **Not real-time.** A timer never fires before its deadline. It fires at a tick boundary of the
  wheel (default 10 ms), so it can be late by up to one tick, plus the scheduling of the timer
  thread, plus the time the lane needs to start the task. No upper bound is promised.
- **Android:** `nanoTime` does not advance while the device is in deep sleep and the timer thread is
  not woken. Clotho timers therefore do **not** wake the device; use `AlarmManager` or `WorkManager`
  for that or for exact times. After the device wakes, every timer that became due fires late, all
  at once: a fixed-rate job skips the slots it missed (no burst), a fixed-delay job waits one
  period after the end of its run.

## 3. The wheel and what is (and is not) claimed

- One hashed timer wheel and one thread per engine (`Engine.cadence()`); tick and bucket count are
  configurable (`timerTick`, `timerWheelSize`). All wheel state is guarded by one lock.
- **Adding** a timer: constant work. **Cancelling**: constant work: the entry is unlinked from its
  bucket under the lock, so it is gone from the wheel (and from `Cadence.pending()`) when the
  cancelling call returns. One race exists: cancelling a timer that another thread is creating at
  the same moment (a realm closing while `every` is being called). Then the creating thread removes
  the timer before its own call returns; `pending()` can show it for that instant.
- **Advancing** time scans at most `wheelSize` buckets, however long the gap.
- **Next wake-up**: each bucket remembers its earliest tick; the thread sleeps until the earliest
  pending deadline. The question costs a scan of the buckets, plus recomputing a bucket whose earliest
  entry was removed (cost: that bucket's size). Cancelling the earliest timer does not wake the
  thread; cancelling the **last** timer does, so the thread ends at once.
- **The thread** starts with the first pending timer and ends when none is left. It never runs user
  code: it only hands the task to the lane. A lane that runs tasks inline
  (`BlockingPolicy.INHERIT`, such as `Lane.direct()`) is refused with `IllegalArgumentException`.
  A custom lane that runs tasks inline but declares another policy cannot be detected; do not pass
  one. Engine lanes and the main lane post, so they are fine. `CALLER_RUNS` never runs on the timer
  thread (it is not a known caller).
- Not claimed: constant-time next-wake in all cases, lock-free operation, exactness of the delay.

## 4. `after`

- Zero or negative delay is a `launch` (no timer). Otherwise the job is pending in the wheel.
- The job belongs to the realm (see section 7). Cancelling it removes the timer.
- If the cadence is shut down the job ends at once as `Failure(LaneClosedException)`.

## 5. `every` and `atRate`

- **Non-overlap**: at most one timer is pending for a job, and the next one is planned only when the
  current run has ended, so two runs of one job never overlap. A tick waiting in a busy lane's queue
  delays the whole schedule (no second tick is created).
- **Fixed delay (`every`)**: next run = end of the run + period.
- **Fixed rate (`atRate`)**: the original schedule `first + k * period` (first = creation time +
  initial delay; slot 0 is the first run). After a run that served slot `j`, the next run is the first
  slot after `j` that is not in the past. Slots that passed meanwhile are skipped; there is no
  catch-up burst, and a slot is never run twice, even if its run ends at the very instant of the
  slot. Exact arithmetic: `CadenceMathTest`, and exact scenarios with a controlled clock:
  `TestSchedulerTest`.
- **Initial delay** of zero or less runs the first run at once; later runs always go through the
  wheel (so a lane that runs inline cannot cause recursion).
- Periodic runs use `Priority.NORMAL`.

## 6. Cancellation of periodic jobs

| Moment | What `cancel()` does |
|---|---|
| waiting in the wheel | job `Cancelled` at once; the timer is removed |
| tick already queued on the lane | job `Cancelled` at once; the queued tick finds the job finished and does nothing |
| a run is executing | cancellation is requested (`Checkpoint`); the job is not done until the run returns; then `Cancelled`; no further run |
| between two runs | as "waiting" (the next timer is removed) |

**Guarantee**: once `cancel()` returned `true`, no run that had not yet started will start. The only
run that can still be executing is one that started before the call. The decision is the single
atomic step of the job state, so a run and a cancel racing each other have exactly one winner.
`cancel()` returns `false` if the job is already done or cancellation was already requested. An
exception thrown after cancellation was requested is a late failure: not reported.

## 7. Failures

| Event | `STOP` | `CONTINUE` |
|---|---|---|
| user code throws | job `Failure(cause)` | reported once to the `UncaughtHandler`, next run planned |
| lane refuses the tick, queue full (`RejectedExecutionException`) | `Failure(cause)` | reported once, tick skipped, next planned |
| tick dropped later (`DroppedException`, `QUEUE_OVERFLOW`) | `Failure` | reported once, next planned |
| lane closed (`LaneClosedException`) or tick discarded by shutdown (`DroppedException`, `SHUTDOWN`) | `Failure` | `Failure` (final, not reported as a survived failure) |
| cadence shut down with the engine | `Failure(DroppedException SHUTDOWN)` | same |
| cancelled | `Cancelled` | `Cancelled` |

A `CONTINUE` report is made once per failed run, only if cancellation had not been requested yet. In
a `FAIL_FAST` realm only the final `Failure` of the job fails the realm, reported failures do not.
No job is left pending: every path above ends the job or plans the next run.

## 8. Realm closing

| Call | `after` (pending) | `every`/`atRate` | running execution |
|---|---|---|---|
| `close()` | waited for: it runs at its time (can take as long as its delay) | cancelled automatically | waited for, not interrupted |
| `closeAsync()` | cancelled, timer removed | cancelled | cancel requested, not interrupted; returns at once |
| `close(timeout)` | cancelled (with the cancel of the timeout) | cancelled at the start | interrupted after the timeout, leaks reported as in phase 2 |

`isClosed()`/`Closing.onClosed` are true only when no job (including a pending `after` and a running
execution) and no callback is outstanding: a pending timer does not count as closed, a cancelled one
(removed) does. Closing does not wait for the cadence thread.

## 9. Engine shutdown

`shutdown()` and `shutdownNow()` discard every pending timer: their jobs end as
`Failure(DroppedException)` with reason `SHUTDOWN`, the thread ends, and later `after`/`every`/`atRate`
on that cadence return jobs that already failed with `LaneClosedException`. The global engine's
cadence is never shut down. `awaitTermination` also waits for the timer thread.

## 10. `Cell<T>`

- Lock-free `AtomicReference` underneath: `get` (volatile read), `set`, `getAndSet`, `compareAndSet`,
  `compareAndSetSame`, `update`, `updateAndGet`, `getAndUpdate`.
- **Update functions can run several times**: if another thread changes the cell meanwhile, the
  result is discarded and the function is called again with the newer value. Keep it fast, pure and
  dependent only on its argument. No lock is held; if it throws the cell is unchanged.
- **No lost updates**: each completed update applies exactly once to the value held at that moment.
  Lock-free, not wait-free.
- **`null`** is a legal value everywhere; functions themselves must not be `null`.
- **`compareAndSet`** compares with `equals` (works for boxed numbers); `compareAndSetSame` compares
  by reference like `AtomicReference`.
- Observers are deferred to `Flow` (phase 8).

## 11. What the tests prove and what they do not

- The wheel is tested with a fake clock and no threads: tick boundaries, ordering, wrap-around,
  long jumps, past deadlines, cancel, wake-up computation, draining.
- Real-clock tests are tolerant: they assert "never early", non-overlap, order of events and
  ends of threads, not tight lateness. They are repeated under CPU load. The fake-clock tests are
  not a substitute for them, and neither replaces the other.
- Stress tests (thousands of timers with concurrent cancellation, engine shutdown during timer
  creation, cancel races, hundreds of periodic jobs, wheel fire-versus-cancel) show that the
  invariants held in the interleavings that occurred. They do not prove absence of other races;
  jcstress (phase 6) is the specialised tool. A single-core machine exercises fewer parallel
  interleavings.

## 12. Testing time-dependent code

Use `TestScheduler` from `clotho-test` ([TESTING.md](TESTING.md)): a controlled clock and manual timers
make periodic behaviour exact and instant.

## 13. Known limits

One lock for the whole wheel; periodic runs have fixed priority; one unit for delay and period;
no wall-clock (calendar) schedules; no count of skipped slots is exposed; a custom inline lane is
not detected.
