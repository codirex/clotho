/*
 * Copyright 2026 The Clotho Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.codirex.clotho;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The only {@link Job} implementation. Not part of the public API.
 *
 * <p>The whole life of a job is one {@link AtomicReference} holding either a {@link Phase} marker
 * or the final {@link Outcome}. The compare-and-set that stores the outcome is the single point
 * where the result is decided.
 */
final class JobImpl<T> implements Job<T> {

  /** Non-final states. */
  private enum Phase {
    PENDING,
    RUNNING,
    CANCEL_REQUESTED
  }

  /** Inline listeners run on the thread that decided the outcome. */
  @FunctionalInterface
  interface Inline<T> {
    void done(Outcome<T> outcome);
  }

  private static final class Node<T> {
    final Inline<T> listener;
    Node<T> next;

    Node(Inline<T> listener) {
      this.listener = listener;
    }
  }

  @SuppressWarnings("rawtypes")
  private static final Node DONE = new Node<>(null);

  private final Realm realm;
  private final boolean root;
  private final Priority priority;
  private final boolean periodic;
  private final AtomicReference<Object> state = new AtomicReference<>(Phase.PENDING);
  private final CountDownLatch done = new CountDownLatch(1);
  private final AtomicReference<Node<T>> listeners = new AtomicReference<>();
  private final AtomicBoolean leakReported = new AtomicBoolean();
  private final Checkpoint checkpoint = new JobCheckpoint();
  private final Object lock = new Object();

  private volatile Work<T> work;
  private volatile Runnable cancelHook;
  private volatile PeriodicDriver driver;

  // guarded by lock
  private Thread runner;
  private boolean interruptRequested;

  private JobImpl(Realm realm, boolean root, Priority priority, boolean periodic) {
    this.realm = realm;
    this.root = root;
    this.priority = priority;
    this.periodic = periodic;
  }

  /** Registers a new job in the realm. Throws if the realm is closing. */
  static <T> JobImpl<T> create(Realm realm, boolean root, Priority priority) {
    return create(realm, root, priority, false);
  }

  /** A periodic job is marked at creation, so a realm that is closing never misses it. */
  static <T> JobImpl<T> create(Realm realm, boolean root, Priority priority, boolean periodic) {
    realm.register();
    JobImpl<T> job = new JobImpl<>(realm, root, priority, periodic);
    realm.track(job);
    return job;
  }

  // ---- Job API ----

  @Override
  public Realm realm() {
    return realm;
  }

  @Override
  public boolean isDone() {
    return state.get() instanceof Outcome<?>;
  }

  @Override
  public boolean isCancellationRequested() {
    Object s = state.get();
    return s == Phase.CANCEL_REQUESTED || s instanceof Outcome.Cancelled<?>;
  }

  @Override
  public Outcome<T> outcomeOrNull() {
    Object s = state.get();
    return s instanceof Outcome<?> ? outcomeOf(s) : null;
  }

  @Override
  public Outcome<T> await() throws InterruptedException {
    LaneContext.checkMayBlock();
    done.await();
    return outcomeOf(state.get());
  }

  @Override
  public Outcome<T> await(long timeout, TimeUnit unit)
      throws InterruptedException, TimeoutException {
    LaneContext.checkMayBlock();
    if (!done.await(timeout, unit)) {
      throw new TimeoutException("job not done after " + timeout + " " + unit);
    }
    return outcomeOf(state.get());
  }

  @Override
  public boolean cancel() {
    return cancel(false);
  }

  @Override
  public boolean cancel(boolean interrupt) {
    for (; ; ) {
      Object s = state.get();
      if (s instanceof Outcome<?>) {
        return false;
      }
      if (s == Phase.PENDING) {
        Outcome<T> cancelled = Outcome.cancelled();
        if (state.compareAndSet(Phase.PENDING, cancelled)) {
          finish(cancelled);
          Runnable hook = cancelHook;
          if (hook != null) {
            hook.run();
          }
          return true;
        }
      } else if (s == Phase.RUNNING) {
        if (state.compareAndSet(Phase.RUNNING, Phase.CANCEL_REQUESTED)) {
          if (interrupt) {
            interruptRunner();
          }
          return true;
        }
      } else {
        // CANCEL_REQUESTED: escalate to an interrupt if asked, but the request was not new.
        if (interrupt) {
          interruptRunner();
        }
        return false;
      }
    }
  }

  @Override
  public <R> Job<R> then(Lane lane, Step<? super T, ? extends R> step) {
    Objects.requireNonNull(lane, "lane");
    Objects.requireNonNull(step, "step");
    JobImpl<R> next = create(realm, false, priority);
    addInline(
        outcome -> {
          if (outcome instanceof Outcome.Success<T> success) {
            T value = success.value();
            next.start(lane, cp -> step.apply(value, cp));
          } else if (outcome instanceof Outcome.Failure<T> failure) {
            next.completeIfPending(Outcome.failure(failure.cause()));
          } else {
            next.completeIfPending(Outcome.cancelled());
          }
        });
    return next;
  }

  @Override
  public Job<T> recover(Lane lane, Step<? super Throwable, ? extends T> step) {
    Objects.requireNonNull(lane, "lane");
    Objects.requireNonNull(step, "step");
    JobImpl<T> next = create(realm, false, priority);
    addInline(
        outcome -> {
          if (outcome instanceof Outcome.Success<T> success) {
            next.completeIfPending(Outcome.success(success.value()));
          } else if (outcome instanceof Outcome.Failure<T> failure) {
            Throwable cause = failure.cause();
            next.start(lane, cp -> step.apply(cause, cp));
          } else {
            next.completeIfPending(Outcome.cancelled());
          }
        });
    return next;
  }

  @Override
  public void onComplete(Lane lane, Completion<? super T> completion) {
    Objects.requireNonNull(lane, "lane");
    Objects.requireNonNull(completion, "completion");
    realm.register();
    AtomicBoolean released = new AtomicBoolean();
    Runnable release =
        () -> {
          if (released.compareAndSet(false, true)) {
            realm.release();
          }
        };
    addInline(
        outcome -> {
          try {
            lane.execute(new CallbackTask(lane, completion, outcome, release), Priority.NORMAL);
          } catch (Throwable t) {
            realm.report(t);
            release.run();
          }
        });
  }

  // ---- internals used by Realm and composites ----

  /** Called while building a composite, before any listener is attached. */
  void onCancelBeforeStart(Runnable hook) {
    this.cancelHook = hook;
  }

  /** Marks the leak as reported. Returns true only for the first caller. */
  boolean markLeakReported() {
    return leakReported.compareAndSet(false, true);
  }

  boolean isRoot() {
    return root;
  }

  boolean isPeriodic() {
    return periodic;
  }

  /** Attaches the driver of a periodic job and makes cancelling remove its pending timer. */
  void attachDriver(PeriodicDriver periodicDriver) {
    this.driver = periodicDriver;
    onCancelBeforeStart(periodicDriver::cancelCurrent);
  }

  Priority priority() {
    return priority;
  }

  /** Decides the outcome if the job has not started. Returns true if this call decided it. */
  boolean completeIfPending(Outcome<T> outcome) {
    if (state.compareAndSet(Phase.PENDING, outcome)) {
      finish(outcome);
      return true;
    }
    return false;
  }

  /** Schedules the work on the lane unless the job was already decided. */
  void start(Lane lane, Work<T> w) {
    if (state.get() != Phase.PENDING) {
      return;
    }
    this.work = w;
    try {
      lane.execute(new JobTask(lane), priority);
    } catch (Throwable rejected) {
      PeriodicDriver d = driver;
      if (d != null) {
        d.tickRejected(rejected);
      } else {
        completeIfPending(Outcome.failure(rejected));
      }
    }
  }

  void addInline(Inline<T> listener) {
    Node<T> node = new Node<>(listener);
    for (; ; ) {
      Node<T> head = listeners.get();
      if (head == DONE) {
        runListener(listener, outcomeOf(state.get()));
        return;
      }
      node.next = head;
      if (listeners.compareAndSet(head, node)) {
        return;
      }
    }
  }

  // ---- implementation ----

  private void runWork() {
    if (!state.compareAndSet(Phase.PENDING, Phase.RUNNING)) {
      return;
    }
    Work<T> w = work;
    work = null;
    Thread self = Thread.currentThread();
    synchronized (lock) {
      runner = self;
      if (interruptRequested) {
        self.interrupt();
      }
    }
    T value = null;
    Throwable error = null;
    try {
      value = w.run(checkpoint);
    } catch (Throwable t) {
      error = t;
    }
    synchronized (lock) {
      runner = null;
      if (interruptRequested) {
        // Clear the interrupt we may have delivered so it does not leak to the next task.
        Thread.interrupted();
      }
    }
    if (driver != null) {
      settlePeriodic(error);
    } else {
      settle(value, error);
    }
  }

  /**
   * The end of one run of a periodic job. The job is cancelled if cancellation was requested, ends
   * as a failure under STOP, and otherwise goes back to waiting for its next tick (a failure is
   * reported once under CONTINUE, unless it came after cancellation was requested).
   */
  private void settlePeriodic(Throwable error) {
    PeriodicDriver d = driver;
    boolean reported = false;
    for (; ; ) {
      Object s = state.get();
      if (s instanceof Outcome<?>) {
        return;
      }
      if (s == Phase.CANCEL_REQUESTED) {
        Throwable late = error instanceof CancelledException ? null : error;
        Outcome<T> cancelled = new Outcome.Cancelled<>(late);
        if (state.compareAndSet(s, cancelled)) {
          finish(cancelled);
          return;
        }
        continue;
      }
      if (error != null) {
        if (d.onFailure() == RepeatFailure.STOP) {
          Outcome<T> failed = Outcome.failure(error);
          if (state.compareAndSet(s, failed)) {
            finish(failed);
            return;
          }
          continue;
        }
        if (!reported) {
          reported = true;
          realm.report(error);
        }
      }
      if (state.compareAndSet(Phase.RUNNING, Phase.PENDING)) {
        d.scheduleNext();
        return;
      }
    }
  }

  private void settle(T value, Throwable error) {
    for (; ; ) {
      Object s = state.get();
      if (s instanceof Outcome<?>) {
        return;
      }
      Outcome<T> outcome;
      if (s == Phase.CANCEL_REQUESTED) {
        Throwable late = error instanceof CancelledException ? null : error;
        outcome = new Outcome.Cancelled<>(late);
      } else if (error != null) {
        outcome = Outcome.failure(error);
      } else {
        outcome = Outcome.success(value);
      }
      if (state.compareAndSet(s, outcome)) {
        finish(outcome);
        return;
      }
    }
  }

  private void interruptRunner() {
    synchronized (lock) {
      interruptRequested = true;
      if (runner != null) {
        runner.interrupt();
      }
    }
  }

  private void finish(Outcome<T> outcome) {
    // A FAIL_FAST realm records the failure and cancels its jobs before anybody is woken up, so a
    // thread that returns from await() sees realm.failure() set. Only the report to the
    // UncaughtHandler (user code) comes after the wake-up.
    Throwable reportable = null;
    if (root
        && outcome instanceof Outcome.Failure<T> failure
        && realm.recordRootFailure(failure.cause())) {
      reportable = failure.cause();
    }
    done.countDown();
    if (reportable != null) {
      realm.reportRootFailure(reportable);
    }
    @SuppressWarnings("unchecked")
    Node<T> head = listeners.getAndSet(DONE);
    List<Inline<T>> ordered = new ArrayList<>();
    for (Node<T> n = head; n != null; n = n.next) {
      ordered.add(n.listener);
    }
    Collections.reverse(ordered);
    for (Inline<T> listener : ordered) {
      runListener(listener, outcome);
    }
    realm.untrack(this);
  }

  private void runListener(Inline<T> listener, Outcome<T> outcome) {
    try {
      listener.done(outcome);
    } catch (Throwable t) {
      realm.report(t);
    }
  }

  @SuppressWarnings("unchecked")
  private static <T> Outcome<T> outcomeOf(Object state) {
    return (Outcome<T>) state;
  }

  @Override
  public String toString() {
    Object s = state.get();
    return "Job(" + (s instanceof Outcome<?> ? s : ((Phase) s).name()) + ")";
  }

  /** The task a lane runs for this job. If the lane discards it, the job fails with the reason. */
  private final class JobTask implements Runnable, Lane.Discardable {
    private final Lane lane;

    JobTask(Lane lane) {
      this.lane = lane;
    }

    @Override
    public void run() {
      LaneContext.runIn(lane, JobImpl.this::runWork);
    }

    @Override
    public void discarded(Throwable reason) {
      PeriodicDriver d = driver;
      if (d != null) {
        d.tickRejected(reason);
      } else {
        completeIfPending(Outcome.failure(reason));
      }
    }
  }

  /** A completion callback on a lane. If the lane discards it, the loss is reported. */
  private final class CallbackTask implements Runnable, Lane.Discardable {
    private final Lane lane;
    private final Completion<? super T> completion;
    private final Outcome<T> outcome;
    private final Runnable release;

    CallbackTask(
        Lane lane, Completion<? super T> completion, Outcome<T> outcome, Runnable release) {
      this.lane = lane;
      this.completion = completion;
      this.outcome = outcome;
      this.release = release;
    }

    @Override
    public void run() {
      LaneContext.runIn(
          lane,
          () -> {
            try {
              completion.accept(outcome);
            } catch (Throwable t) {
              realm.report(t);
            } finally {
              release.run();
            }
          });
    }

    @Override
    public void discarded(Throwable reason) {
      realm.report(reason);
      release.run();
    }
  }

  private final class JobCheckpoint implements Checkpoint {
    @Override
    public boolean isCancelled() {
      return isCancellationRequested();
    }

    @Override
    public void check() {
      if (isCancellationRequested()) {
        throw new CancelledException();
      }
    }
  }
}
