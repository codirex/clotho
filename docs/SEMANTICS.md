# Semantics (phase 2)

These rules are the contract. Every rule below has at least one test (`clotho/src/test`).

## 1. Job states and the single decision point

A job is `PENDING` (not started), `RUNNING`, `CANCEL_REQUESTED`, or done with an `Outcome`
(`Success`, `Failure`, `Cancelled`). The state is one atomic reference; the compare-and-set that
stores the `Outcome` is the **only** place where the result is decided. After that the outcome is
immutable and every observer (`await`, `outcomeOrNull`, callbacks) gets the same instance.

| Event | Result |
|---|---|
| `cancel()` while `PENDING` | `Cancelled` at once; the work never runs |
| `cancel()` while `RUNNING` | `CANCEL_REQUESTED`; the job is **not done** until the work returns |
| work returns a value, cancel not requested | `Success` |
| work returns a value, cancel requested first | `Cancelled`, value dropped |
| work throws, cancel not requested | `Failure` (including a stray `CancelledException`) |
| work throws, cancel requested | `Cancelled`, exception kept in `lateFailure()`, **not** reported |
| `cancel()` after the outcome is decided | returns `false`, outcome unchanged |

`cancel()` returns `true` only when that call made the job cancelled or cancel-requested. Work that
ignores `Checkpoint` stays running; Java cannot stop it (see 6).

## 2. Callbacks and `await`

- `onComplete` callbacks run exactly once, on the lane given, in registration order for the same
  lane behaviour. Registering after completion still runs the callback once.
- `await()` may return **before** callbacks have run. A callback is outstanding work of the realm,
  so `Realm.close()` waits for it.
- An exception thrown by a callback goes to the `UncaughtHandler`; other callbacks still run.
- If a lane refuses a task (`execute` throws), the job fails with that exception.

## 3. `then` and `recover`

- They create a new job in the same realm. They never cancel the parent: a dependent does not own
  its parent.
- Parent cancelled: dependents end `Cancelled` without running. Parent failed: `then` dependents
  fail with the same cause; `recover` runs its step.
- Cancelling a dependent only affects that dependent and its own dependents.

## 4. Ownership versus realm membership

- **Realm membership:** every job (also composites and the losers of a race) belongs to exactly one
  realm for its whole life. The realm waits for it on close and cancels it on `cancel()`/timeout.
  Nothing can outlive its realm silently.
- **Ownership** (`Ownership`) only answers: may a composite cancel its inputs?
  - `OWNED`: when the composite finishes early or is cancelled, it cancels inputs still running.
    Passing a shared job with `OWNED` can cancel work other code depends on. That is the choice you
    made by passing it.
  - `BORROWED`: the composite only observes. It never cancels inputs; they keep running under their
    realm and their results are ignored.
- There is no default: the caller must pass the policy.
- Inputs must belong to the realm the composite is created on (`IllegalArgumentException` otherwise).

## 5. `zip` and `race`

`zip(a, b)`:
- both succeed: the combiner runs on its lane. The combiner failing makes the result `Failure`.
- an input fails: the result fails with that exact cause. If both fail at the same moment, which
  one wins is unspecified. (Suppressed exceptions are not attached to avoid mutating exceptions that
  are shared with the input jobs.)
- an input is cancelled: the result is `Cancelled`.
- `OWNED`: the other input is cancelled when the result is decided early. Cancelling the result
  before the combiner starts cancels both inputs. Cancelling it while the combiner runs only
  cancels the result.

`race(inputs)`:
- the winner is the **first `Success`**. A failure or cancellation of a single input does not end
  the race.
- no success and at least one failure: `Failure(NoWinnerException)`; cause is the first failed input
  in **list order**, the other failures are suppressed in list order. Cancelled inputs are ignored
  when at least one input failed.
- all inputs cancelled: `Cancelled`.
- success arriving together with `race.cancel()`: whichever wins the single decision step. If the
  cancel wins, the value is dropped and `Cancelled` is final; if the success wins, `cancel()`
  returns `false`.
- `OWNED`: losers are cancelled when the race is decided or cancelled. `BORROWED`: they keep running.

## 6. Realm lifecycle

States: `OPEN` → `CLOSING` → `CLOSED`. One atomic counter holds the closing flag and the number of
outstanding jobs and callbacks, so registering work and starting to close are atomic with respect
to each other: every `launch`, `then`, `recover`, `zip`, `race` and `onComplete` either is accepted
and will be waited for, or throws `RealmClosedException`. A job is registered when it is created,
so chains built before closing still complete.

- `close()`: rejects new work, waits for everything, cancels nothing. If the thread is interrupted
  it calls `cancel()` (no thread interrupts), keeps waiting and restores the interrupt flag.
  Never call it from inside a job of the same realm. It never returns if a job never ends.
- `close(timeout, unit)`: on timeout it (1) cancels every remaining job and every job added later,
  (2) interrupts the threads running them, (3) reports each still-running job **once** (per job,
  also across repeated calls) as `LeakedJobException`, (4) returns `false`. The realm stays
  `CLOSING` and becomes `CLOSED` by itself when the last job ends.
  If interrupted while waiting it cancels (no thread interrupts) and throws `InterruptedException`.
- **What is not promised:** `false` does not mean the work stopped. Clotho owns no threads in this
  phase, so closing leaves no realm-owned thread behind, but code that ignores cancellation and
  interrupts keeps running. The realm can only refuse new work, deliver both signals, report it, and
  release its references when it finally ends.

## 7. Interrupt policy

- `cancel()` is cooperative only. `cancel(true)` also interrupts the thread running the work.
- The interrupt is delivered only while the work runs, under a lock that the end of the work also
  takes; the flag is cleared when the work returns. It cannot leak into the next task of a pooled
  thread. (If an unrelated interrupt hit the thread meanwhile, it is cleared too.)
- `cancel(true)` on a job that is already cancel-requested still delivers the interrupt and returns
  `false`.

## 8. Failure policy and the `UncaughtHandler`

- `SUPERVISED` (default): failures stay in their jobs.
- `FAIL_FAST` (ordering): the failure is recorded and the realm cancelled **before** waiters are woken, so after `await()` returns for such a job `Realm.failure()` is set and the other jobs are cancelled. The report to the `UncaughtHandler` may come after `await()` returns.
- `FAIL_FAST`: the failure of a job started with `launch` cancels every job of the realm and every
  job launched afterwards (they are born cancelled), is stored in `Realm.failure()` (first one only)
  and is reported once. Failures of derived jobs (`then`, `recover`, `zip`, `race`) do not trigger
  it, so `recover` only makes sense in a `SUPERVISED` realm.
- The handler receives: exceptions from callbacks, leaked jobs, the `FAIL_FAST` trigger. It never
  receives late failures of cancelled jobs. If the handler throws, the stack trace is printed to
  stderr (the only place Clotho prints).
- Default handler: `Clotho.defaultUncaughtHandler()` (prints to stderr), replaceable.

## 9. Blocking policy

- A lane declares `BlockingPolicy`: `ALLOWED`, `FORBIDDEN` or `INHERIT`.
- The framework records, per thread, the lane whose task it is running (`ThreadLocal`, restored in
  `finally`). `await` throws `BlockingNotAllowedException` when that lane is `FORBIDDEN`, even if the
  job is already done. Thread names are never inspected.
- `Lane.direct()` is `INHERIT`: work run inline keeps the caller's policy.
- `Realm.close(...)` is checked the same way. `MainLane` reports `FORBIDDEN`, and with a main lane installed the same calls are refused on the main thread (ANDROID.md).
- `Realm.closeAsync()` is the non-blocking close (ANDROID.md section 4); `close()` and `close(timeout)` keep the meaning above.

## 10. Known limits

- Thread-backed lanes, priorities and queue limits are specified in [ENGINE.md](ENGINE.md); timers and `Cell` in [CADENCE.md](CADENCE.md).
