# Android and the main thread (phase 4)

Every rule has tests: `MainLaneTest`, `MainLaneRegistryTest`, `RealmCloseAsyncTest` (JVM, core) and
`AndroidMainLaneTest`, `ClothoInitializerTest`, `AndroidRealmsTest` (Robolectric,
`clotho-android`). The core stays free of Android.

## 1. Modules and verified versions

| Piece | Version | Checked against |
|---|---|---|
| Android Gradle Plugin | 9.3.1 (project) | Robolectric 4.17 was built with 9.2.1 and Gradle 9.6.1 |
| `androidx.startup:startup-runtime` | 1.2.0 (stable) | developer.android.com release page |
| `androidx.lifecycle:lifecycle-common` / `-runtime` | 2.10.0 (stable; 2.11 is alpha) | developer.android.com release page |
| Robolectric | 4.17 (API 23 to 37) | robolectric.org compatibility table |
| JUnit 4 (Robolectric runner) | 4.13.2 | unchanged for years |

- Robolectric API 35 runs on JDK 17; API 36 and 37 need JDK 21 (same table). The tests use
  `sdk=35` (`robolectric.properties`), so the project's JDK 17 is enough. CI runs JDK 17 and 21.
- Robolectric 4.16 stops at API 36, so 4.17 is required for `compileSdk 37`.
- Android 17 replaced `MessageQueue` with a lock-free one for apps that target API 37. The main
  lane only uses `Handler.post`, so it is not affected.
- `clotho-android`: `api` on `:clotho` and on startup (the initializer implements `Initializer`);
  lifecycle is `compileOnly` (section 5).
- Not verified here: that these versions resolve together in your Gradle (no network in the
  authoring environment). The first build tells.

## 2. `MainLane`

- **Resolution**: `Lane.main()` returns the lane installed with `Clotho.install(MainLane)`. If none,
  exactly one `MainThreadHost` found with `ServiceLoader` is wrapped and installed; none gives
  `MainLaneNotInstalledException` (with instructions), several give `IllegalStateException` naming
  them. Installing the same lane again is a no-op; a different one fails. `Clotho.uninstallMainLane()`
  is for tests. On Android no `ServiceLoader` is used: the initializer installs the lane.
- **Always posts**: `execute` queues even when called on the main thread: a task never runs inside
  another, order is submission order (FIFO), priority is ignored.
- **`immediate()`**: runs inline when already on the main thread, posts otherwise. It bypasses
  tasks that are already queued, so it can run before them; use it knowingly.
- **Exceptions**: a job never throws into the loop (the failure is its outcome). A raw `Runnable`
  that throws is not caught: the host loop sees it (on Android: the app's uncaught handler), as with
  `Handler.post`.
- **Cancellation**: cancelling a queued job ends it at once; its queued task stays in the host queue
  and does nothing.
- **Host stops accepting** (`post` throws): `execute` throws `RejectedExecutionException`; the job
  ends as `Failure`. **Host discards** accepted tasks: it must call `Lane.Discardable.discarded`;
  the job ends as `Failure(reason)`, a completion callback is reported and released. The Android
  main looper never stops, so this only matters for other hosts.
- **Context**: while a task runs, the thread's lane is the main lane; it is restored afterwards.

## 3. Blocking on the main thread

- Lane policy is `FORBIDDEN`.
- Once a main lane is installed, `Job.await`, `Realm.close`, `Closing.await`, `Engine.close` and
  `awaitTermination` throw `BlockingNotAllowedException` on **any thread where `isMainThread()` is
  true**, also in code that never went through Clotho (for example `onCreate`). Before installation
  nothing can know which thread is main, so nothing is refused.
- `CALLER_RUNS` never runs pool work on the main thread (not even compute work), regardless of the
  lane table in ENGINE.md.
- Still not detectable: `Thread.sleep`, locks, I/O on the main thread (see ENGINE.md section 3).

## 4. `Realm.closeAsync()`

- Does, in order: reject new work, request cancellation of every current job (no interrupt), return
  a `Realm.Closing`. Never blocks; allowed on the main thread and on forbidden lanes. Idempotent:
  the same `Closing` is returned.
- **Completion**: `Closing.isClosed()`, `Closing.onClosed(lane, callback)` (exactly once, after
  CLOSED, handed to its lane right after the realm becomes closed (not atomically with `isClosed()` turning true), scheduled at once if already closed; not outstanding work of the realm; a rejected or
  throwing callback is reported to the realm's `UncaughtHandler`), `Closing.await(t)` (blocking,
  refused on the main thread).
- **Returning proves nothing about the jobs.** A job that ignores cancellation keeps the realm
  `CLOSING`. Nothing is reported and no thread is interrupted by `closeAsync`.
- `close(timeout)` from phase 2 is unchanged and still works after `closeAsync`: on timeout it cancels
  with interrupt, reports each running job once as `LeakedJobException` and returns `false`.

## 5. `AndroidRealms.forLifecycle(owner)`

| Question | Answer |
|---|---|
| Thread | main thread only, else `IllegalStateException` |
| When is the realm closed | at `ON_DESTROY` of the owner's lifecycle, and only then. Not at stop or pause, so a realm outlives backgrounding. A configuration change destroys the activity, which closes its realm: use a realm that lives outside the activity for work that must survive rotation |
| What happens to jobs | `closeAsync()`: new work rejected, current jobs asked to cancel; with `interruptOnDestroy` also interrupted. Queued jobs end `Cancelled` at once. Running jobs that ignore cancellation keep the realm `CLOSING` until they end |
| Completion callbacks | callbacks registered before destroy still run exactly once, with the final outcome (`Cancelled` for cancelled jobs). They run on their own lane, possibly after destroy: they must not touch destroyed views |
| Duplicate registration | one binding per `Lifecycle`: `forLifecycle(owner)` again returns the same open realm (one observer). A builder call on a lifecycle that has an open realm throws. A realm the caller closed is replaced and its old observer removed |
| Leaks | the observer removes itself and the binding at destroy; the bindings map is weak and keyed by the lifecycle; the realm never references the owner. Jobs you write may capture the owner; only cancellation-aware jobs release it at destroy |
| Destroyed owner | `IllegalStateException` |
| Dependency | `compileOnly` on `lifecycle-common`: only `AndroidRealms` uses it, apps that call it have it through `ComponentActivity`/`Fragment`, apps that never call it do not load it |

## 6. Startup, opt-out, R8

- The library manifest adds the `androidx.startup` provider entry for `ClothoInitializer`.
  Opt out with `tools:node="remove"` on that meta-data and call `AndroidMainLane.install()`.
- `consumer-rules.pro` keeps `ClothoInitializer` and its constructor. The core jar carries
  `META-INF/proguard/clotho.pro` to keep `MainThreadHost` providers for JVM-style ServiceLoader use.

## 7. Timers on Android

Clotho timers use the monotonic clock and a plain thread: they do not wake a sleeping device and fire
late after sleep ([CADENCE.md](CADENCE.md) section 2). Use `AlarmManager` or `WorkManager` for exact or
wake-up scheduling.

## 8. What was and was not run when this phase was delivered

The core tests ran on a JVM; `AndroidRealmsTest` ran only against hand-written stubs of `Looper`
and `LifecycleRegistry`; Robolectric, AGP, lint and the manifest merge were not run. The delivery
notes of the phase list the details.
