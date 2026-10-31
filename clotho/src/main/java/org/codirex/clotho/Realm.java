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
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * A lifetime scope that owns jobs. Every job belongs to exactly one realm for its whole life.
 *
 * <p>A realm is {@code OPEN}, then {@code CLOSING} (new work is rejected, running work continues),
 * then {@code CLOSED} (all work finished). See {@code docs/SEMANTICS.md} for the exact rules.
 */
public final class Realm implements AutoCloseable {

  private static final long CLOSING = 1L << 62;

  private final String name;
  private final RealmPolicy policy;
  private final UncaughtHandler handler;
  private final Cadence cadence;

  /** Bit 62 is the closing flag; the low bits count outstanding jobs and callbacks. */
  private final AtomicLong state = new AtomicLong();

  private final Set<JobImpl<?>> jobs = ConcurrentHashMap.newKeySet();
  private final CountDownLatch closed = new CountDownLatch(1);
  private final ConcurrentLinkedQueue<ClosedCallback> closedCallbacks =
      new ConcurrentLinkedQueue<>();
  private final Closing closing = new Closing();
  private final AtomicReference<Throwable> failure = new AtomicReference<>();
  private volatile boolean cancelAll;
  private volatile boolean cancelAllInterrupt;

  private Realm(String name, RealmPolicy policy, UncaughtHandler handler, Cadence cadence) {
    this.name = name;
    this.policy = policy;
    this.handler = handler;
    this.cadence = cadence;
  }

  /**
   * Opens a {@link RealmPolicy#SUPERVISED} realm with the default uncaught handler.
   *
   * @param name a name used in diagnostics
   * @return the new realm
   */
  public static Realm open(String name) {
    return builder(name).open();
  }

  /**
   * Starts building a realm.
   *
   * @param name a name used in diagnostics
   * @return the builder
   */
  public static Builder builder(String name) {
    return new Builder(name);
  }

  /** Builds a {@link Realm}. */
  public static final class Builder {
    private final String name;
    private RealmPolicy policy = RealmPolicy.SUPERVISED;
    private UncaughtHandler handler;
    private Cadence cadence;

    private Builder(String name) {
      this.name = Objects.requireNonNull(name, "name");
    }

    /**
     * Sets the failure policy. The default is {@link RealmPolicy#SUPERVISED}.
     *
     * @param policy the policy
     * @return this builder
     */
    public Builder policy(RealmPolicy policy) {
      this.policy = Objects.requireNonNull(policy, "policy");
      return this;
    }

    /**
     * Sets the handler for this realm. By default {@link Clotho#defaultUncaughtHandler()} is used,
     * looked up each time an error is reported.
     *
     * @param handler the handler
     * @return this builder
     */
    public Builder uncaughtHandler(UncaughtHandler handler) {
      this.handler = Objects.requireNonNull(handler, "handler");
      return this;
    }

    /**
     * Sets the timer service used by {@code after}, {@code every} and {@code atRate}. By default
     * the cadence of {@link Engine#global()} is used.
     *
     * @param cadence the cadence, usually {@link Engine#cadence()} of a private engine
     * @return this builder
     */
    public Builder cadence(Cadence cadence) {
      this.cadence = Objects.requireNonNull(cadence, "cadence");
      return this;
    }

    /**
     * Opens the realm.
     *
     * @return the new realm
     */
    public Realm open() {
      return new Realm(name, policy, handler, cadence);
    }
  }

  /**
   * Returns the realm name.
   *
   * @return the name
   */
  public String name() {
    return name;
  }

  /**
   * Returns the failure policy.
   *
   * @return the policy
   */
  public RealmPolicy policy() {
    return policy;
  }

  /**
   * Returns the failure that triggered a {@link RealmPolicy#FAIL_FAST} realm.
   *
   * @return the first root failure, or {@code null}
   */
  public Throwable failure() {
    return failure.get();
  }

  /**
   * Tells whether the realm started closing.
   *
   * @return {@code true} once {@code close} was called, also after it is closed
   */
  public boolean isClosing() {
    return (state.get() & CLOSING) != 0;
  }

  /**
   * Tells whether the realm is closed, meaning closing and no job or callback is outstanding.
   *
   * @return {@code true} when closed
   */
  public boolean isClosed() {
    return closed.getCount() == 0;
  }

  /**
   * Returns the number of outstanding jobs and completion callbacks.
   *
   * @return the count
   */
  public int outstanding() {
    return (int) (state.get() & ~CLOSING);
  }

  /**
   * Starts a job running {@code work} on {@code lane}.
   *
   * @param lane where the work runs
   * @param work the work
   * @param <T> the value type
   * @return the job
   * @throws RealmClosedException if the realm is closing or closed
   */
  public <T> Job<T> launch(Lane lane, Work<T> work) {
    return launch(lane, Priority.NORMAL, work);
  }

  /**
   * Starts a job running {@code work} on {@code lane} with a scheduling priority.
   *
   * <p>Jobs derived from this one ({@code then}, {@code recover}) run with the same priority; the
   * combiner of a {@link #zip zip} runs with the higher priority of its inputs.
   *
   * @param lane where the work runs
   * @param priority the scheduling priority; lanes without priorities ignore it
   * @param work the work
   * @param <T> the value type
   * @return the job
   * @throws RealmClosedException if the realm is closing or closed
   */
  public <T> Job<T> launch(Lane lane, Priority priority, Work<T> work) {
    Objects.requireNonNull(lane, "lane");
    Objects.requireNonNull(priority, "priority");
    Objects.requireNonNull(work, "work");
    JobImpl<T> job = JobImpl.create(this, true, priority);
    job.start(lane, work);
    return job;
  }

  /**
   * Returns the timer service this realm uses.
   *
   * @return the cadence given to the builder, or the one of the global engine
   */
  public Cadence cadence() {
    Cadence c = cadence;
    return c != null ? c : Engine.global().cadence();
  }

  /**
   * Runs {@code work} on {@code lane} once, after a delay.
   *
   * <p>The job is pending while it waits, and it belongs to the realm: {@link #close()} waits for
   * it to run, {@link #closeAsync()}, {@link #cancel()} and the timeout of {@link #close(long,
   * TimeUnit)} cancel it and remove its timer before they return (see {@code docs/CADENCE.md} for
   * the one race, with the creation of the same timer by another thread). A delay of zero or less
   * is the same as {@link #launch(Lane, Work) launch}. The work never starts before the delay
   * elapsed; it starts after it, late by up to one tick of the cadence plus the time the lane needs
   * (see {@code docs/CADENCE.md}: this is not real-time).
   *
   * <p>If the cadence was shut down the job ends at once as {@link Outcome.Failure} with a {@link
   * LaneClosedException}.
   *
   * @param lane where the work runs; a lane that runs tasks inline (such as {@link Lane#direct()})
   *     is refused
   * @param delay the delay
   * @param unit its unit
   * @param work the work
   * @param <T> the value type
   * @return the job
   * @throws IllegalArgumentException if the lane runs tasks inline
   * @throws RealmClosedException if the realm is closing or closed
   */
  public <T> Job<T> after(Lane lane, long delay, TimeUnit unit, Work<T> work) {
    return after(lane, Priority.NORMAL, delay, unit, work);
  }

  /**
   * Like {@link #after(Lane, long, TimeUnit, Work)} with a scheduling priority for the work.
   *
   * @param lane where the work runs
   * @param priority the scheduling priority
   * @param delay the delay
   * @param unit its unit
   * @param work the work
   * @param <T> the value type
   * @return the job
   * @throws IllegalArgumentException if the lane runs tasks inline
   * @throws RealmClosedException if the realm is closing or closed
   */
  public <T> Job<T> after(Lane lane, Priority priority, long delay, TimeUnit unit, Work<T> work) {
    Objects.requireNonNull(priority, "priority");
    Objects.requireNonNull(unit, "unit");
    Objects.requireNonNull(work, "work");
    checkTimerLane(lane);
    JobImpl<T> job = JobImpl.create(this, true, priority);
    long delayNanos = unit.toNanos(delay);
    if (delayNanos <= 0) {
      job.start(lane, work);
      return job;
    }
    Cadence c = cadence();
    TimerEntry entry =
        c.schedule(
            CadenceMath.deadline(c.nanoTime(), delayNanos),
            new Timeout() {
              @Override
              public void fire() {
                job.start(lane, work);
              }

              @Override
              public void discard(Throwable reason) {
                job.completeIfPending(Outcome.failure(reason));
              }
            });
    if (entry == null) {
      job.completeIfPending(
          Outcome.failure(
              new LaneClosedException("the cadence of " + c.engine() + " is shut down")));
      return job;
    }
    job.onCancelBeforeStart(entry::cancel);
    // A cancel (for example closeAsync) that came before the hook was set found nothing to remove.
    if (job.isDone()) {
      entry.cancel();
    }
    return job;
  }

  /**
   * Runs {@code action} on {@code lane} again and again with a fixed delay: each run starts one
   * {@code period} after the previous run <em>ended</em>. Failures stop the job ({@link
   * RepeatFailure#STOP}).
   *
   * @param lane where the action runs; a lane that runs tasks inline is refused
   * @param initialDelay the delay before the first run; zero or less starts it at once
   * @param period the delay between the end of a run and the start of the next, positive
   * @param unit the unit of both delays
   * @param action the action
   * @return the periodic job; see {@link #every(Lane, long, long, TimeUnit, RepeatFailure, Action)}
   *     for how it ends
   * @throws IllegalArgumentException if {@code period} is not positive or the lane runs tasks
   *     inline
   * @throws RealmClosedException if the realm is closing or closed
   */
  public Job<Void> every(Lane lane, long initialDelay, long period, TimeUnit unit, Action action) {
    return every(lane, initialDelay, period, unit, RepeatFailure.STOP, action);
  }

  /**
   * Runs {@code action} on {@code lane} again and again with a fixed delay.
   *
   * <p>Rules (details in {@code docs/CADENCE.md}):
   *
   * <ul>
   *   <li>Two runs of the same job never overlap: the next one is planned only when the current one
   *       has ended.
   *   <li>The job never succeeds. It ends as {@link Outcome.Cancelled} when cancelled, or as {@link
   *       Outcome.Failure} (see {@link RepeatFailure}).
   *   <li><b>Cancelling:</b> once {@link Job#cancel()} returned {@code true}, no run that had not
   *       started will start, even one whose tick was already queued on the lane. A run that
   *       already started continues until it returns or notices its {@link Checkpoint}; the job is
   *       not done before that.
   *   <li>{@link #close()}, {@link #closeAsync()} and {@link #close(long, TimeUnit)} cancel
   *       periodic jobs of the realm automatically (a running run is not interrupted by the first
   *       two).
   * </ul>
   *
   * @param lane where the action runs; a lane that runs tasks inline is refused
   * @param initialDelay the delay before the first run; zero or less starts it at once
   * @param period the delay between the end of a run and the start of the next, positive
   * @param unit the unit of both delays
   * @param onFailure what to do when a run fails
   * @param action the action
   * @return the periodic job
   * @throws IllegalArgumentException if {@code period} is not positive or the lane runs tasks
   *     inline
   * @throws RealmClosedException if the realm is closing or closed
   */
  public Job<Void> every(
      Lane lane,
      long initialDelay,
      long period,
      TimeUnit unit,
      RepeatFailure onFailure,
      Action action) {
    return periodic(lane, initialDelay, period, unit, onFailure, action, false);
  }

  /**
   * Runs {@code action} on {@code lane} at a fixed rate with {@link RepeatFailure#STOP}.
   *
   * @param lane where the action runs; a lane that runs tasks inline is refused
   * @param initialDelay the delay before the first run; zero or less starts it at once
   * @param period the time between the planned starts, positive
   * @param unit the unit of both delays
   * @param action the action
   * @return the periodic job; see {@link #atRate(Lane, long, long, TimeUnit, RepeatFailure,
   *     Action)}
   * @throws IllegalArgumentException if {@code period} is not positive or the lane runs tasks
   *     inline
   * @throws RealmClosedException if the realm is closing or closed
   */
  public Job<Void> atRate(Lane lane, long initialDelay, long period, TimeUnit unit, Action action) {
    return atRate(lane, initialDelay, period, unit, RepeatFailure.STOP, action);
  }

  /**
   * Runs {@code action} on {@code lane} at a fixed rate: runs are planned at {@code first + k *
   * period} on the original schedule. When a run (or the lane) is late and slots passed, those
   * slots are skipped: the next run is the next slot that is not in the past, and there is never a
   * burst of catch-up runs. Everything else is as in {@link #every(Lane, long, long, TimeUnit,
   * RepeatFailure, Action)}, including that two runs never overlap.
   *
   * @param lane where the action runs; a lane that runs tasks inline is refused
   * @param initialDelay the delay before the first run; zero or less starts it at once
   * @param period the time between the planned starts, positive
   * @param unit the unit of both delays
   * @param onFailure what to do when a run fails
   * @param action the action
   * @return the periodic job
   * @throws IllegalArgumentException if {@code period} is not positive or the lane runs tasks
   *     inline
   * @throws RealmClosedException if the realm is closing or closed
   */
  public Job<Void> atRate(
      Lane lane,
      long initialDelay,
      long period,
      TimeUnit unit,
      RepeatFailure onFailure,
      Action action) {
    return periodic(lane, initialDelay, period, unit, onFailure, action, true);
  }

  private Job<Void> periodic(
      Lane lane,
      long initialDelay,
      long period,
      TimeUnit unit,
      RepeatFailure onFailure,
      Action action,
      boolean fixedRate) {
    Objects.requireNonNull(unit, "unit");
    Objects.requireNonNull(onFailure, "onFailure");
    Objects.requireNonNull(action, "action");
    checkTimerLane(lane);
    long periodNanos = unit.toNanos(period);
    if (periodNanos <= 0) {
      throw new IllegalArgumentException("period must be positive");
    }
    JobImpl<Void> job = JobImpl.create(this, true, Priority.NORMAL, true);
    PeriodicDriver driver =
        new PeriodicDriver(job, cadence(), lane, action, periodNanos, fixedRate, onFailure);
    job.attachDriver(driver);
    driver.begin(unit.toNanos(initialDelay));
    return job;
  }

  private static void checkTimerLane(Lane lane) {
    Objects.requireNonNull(lane, "lane");
    if (lane.blockingPolicy() == BlockingPolicy.INHERIT) {
      throw new IllegalArgumentException(
          "timers need a lane that does not run tasks inline: the cadence thread must never run "
              + "user code ("
              + lane
              + ")");
    }
  }

  /**
   * Combines two jobs of this realm once both succeed.
   *
   * <p>If an input fails, the result fails with that failure; if an input is cancelled, the result
   * is cancelled. Which input counts when both end badly at the same moment is not specified. With
   * {@link Ownership#OWNED} the other input is then cancelled; with {@link Ownership#BORROWED} it
   * is left alone. Cancelling the result before the combiner starts does the same for both inputs.
   *
   * @param first the first input, in this realm
   * @param second the second input, in this realm
   * @param ownership whether the result owns (may cancel) the inputs
   * @param lane where the combiner runs
   * @param combiner combines the two values
   * @param <A> first value type
   * @param <B> second value type
   * @param <R> result type
   * @return the combined job
   * @throws IllegalArgumentException if an input belongs to another realm
   * @throws RealmClosedException if the realm is closing or closed
   */
  public <A, B, R> Job<R> zip(
      Job<A> first,
      Job<B> second,
      Ownership ownership,
      Lane lane,
      Combiner<? super A, ? super B, ? extends R> combiner) {
    Objects.requireNonNull(ownership, "ownership");
    Objects.requireNonNull(lane, "lane");
    Objects.requireNonNull(combiner, "combiner");
    JobImpl<A> a = adopt(first);
    JobImpl<B> b = adopt(second);
    boolean owned = ownership == Ownership.OWNED;
    Priority priority = a.priority().level() >= b.priority().level() ? a.priority() : b.priority();
    JobImpl<R> zip = JobImpl.create(this, false, priority);
    if (owned) {
      zip.onCancelBeforeStart(
          () -> {
            a.cancel(false);
            b.cancel(false);
          });
    }
    AtomicReference<Outcome.Success<A>> sa = new AtomicReference<>();
    AtomicReference<Outcome.Success<B>> sb = new AtomicReference<>();
    AtomicInteger arrived = new AtomicInteger();
    Runnable combine =
        () -> zip.start(lane, cp -> combiner.combine(sa.get().value(), sb.get().value(), cp));
    a.addInline(
        o -> {
          if (o instanceof Outcome.Success<A> s) {
            sa.set(s);
            if (arrived.incrementAndGet() == 2) {
              combine.run();
            }
          } else {
            settleEarly(zip, o, owned ? b : null);
          }
        });
    b.addInline(
        o -> {
          if (o instanceof Outcome.Success<B> s) {
            sb.set(s);
            if (arrived.incrementAndGet() == 2) {
              combine.run();
            }
          } else {
            settleEarly(zip, o, owned ? a : null);
          }
        });
    return zip;
  }

  private static <R> void settleEarly(JobImpl<R> zip, Outcome<?> o, JobImpl<?> other) {
    boolean won;
    if (o instanceof Outcome.Failure<?> f) {
      won = zip.completeIfPending(Outcome.failure(f.cause()));
    } else {
      won = zip.completeIfPending(Outcome.cancelled());
    }
    if (won && other != null) {
      other.cancel(false);
    }
  }

  /**
   * Completes with the first input that succeeds.
   *
   * <p>Failures and cancellations of single inputs do not end the race. When every input ended
   * without a success, the result is {@link Outcome.Failure} with a {@link NoWinnerException} if at
   * least one input failed (cancelled inputs do not matter then), or {@link Outcome.Cancelled} if
   * all were cancelled. A success that arrives together with a cancellation of the race is decided
   * by the single atomic step of the result job: whichever happens first wins, and if the
   * cancellation wins the value is dropped. With {@link Ownership#OWNED} the other inputs are
   * cancelled as soon as the race is decided; with {@link Ownership#BORROWED} they keep running.
   *
   * @param inputs the participants, in this realm, not empty
   * @param ownership whether the race owns (may cancel) the inputs
   * @param <T> the value type
   * @return the race job
   * @throws IllegalArgumentException if {@code inputs} is empty or a job belongs to another realm
   * @throws RealmClosedException if the realm is closing or closed
   */
  public <T> Job<T> race(List<? extends Job<? extends T>> inputs, Ownership ownership) {
    Objects.requireNonNull(inputs, "inputs");
    Objects.requireNonNull(ownership, "ownership");
    if (inputs.isEmpty()) {
      throw new IllegalArgumentException("race needs at least one job");
    }
    List<JobImpl<? extends T>> impls = new ArrayList<>(inputs.size());
    for (Job<? extends T> job : inputs) {
      impls.add(adopt(job));
    }
    boolean owned = ownership == Ownership.OWNED;
    JobImpl<T> race = JobImpl.create(this, false, Priority.NORMAL);
    if (owned) {
      race.onCancelBeforeStart(() -> cancelExcept(impls, -1));
    }
    AtomicReferenceArray<Outcome<?>> results = new AtomicReferenceArray<>(impls.size());
    AtomicInteger remaining = new AtomicInteger(impls.size());
    for (int i = 0; i < impls.size(); i++) {
      watch(race, impls, impls.get(i), i, owned, results, remaining);
    }
    return race;
  }

  private static <T, X extends T> void watch(
      JobImpl<T> race,
      List<JobImpl<? extends T>> all,
      JobImpl<X> input,
      int index,
      boolean owned,
      AtomicReferenceArray<Outcome<?>> results,
      AtomicInteger remaining) {
    input.addInline(
        o -> {
          if (o instanceof Outcome.Success<X> success) {
            T value = success.value();
            if (race.completeIfPending(Outcome.success(value)) && owned) {
              cancelExcept(all, index);
            }
          } else {
            results.set(index, o);
          }
          if (remaining.decrementAndGet() == 0) {
            settleRace(race, results);
          }
        });
  }

  private static <T> void settleRace(JobImpl<T> race, AtomicReferenceArray<Outcome<?>> results) {
    List<Throwable> causes = new ArrayList<>();
    int cancelled = 0;
    for (int i = 0; i < results.length(); i++) {
      Outcome<?> r = results.get(i);
      if (r instanceof Outcome.Failure<?> f) {
        causes.add(f.cause());
      } else if (r != null) {
        cancelled++;
      }
    }
    if (causes.isEmpty()) {
      race.completeIfPending(Outcome.cancelled());
      return;
    }
    NoWinnerException e =
        new NoWinnerException(
            "no race participant succeeded: "
                + causes.size()
                + " failed, "
                + cancelled
                + " cancelled",
            causes.get(0));
    for (int k = 1; k < causes.size(); k++) {
      e.addSuppressed(causes.get(k));
    }
    race.completeIfPending(Outcome.failure(e));
  }

  private static void cancelExcept(List<? extends JobImpl<?>> all, int keep) {
    for (int k = 0; k < all.size(); k++) {
      if (k != keep) {
        all.get(k).cancel(false);
      }
    }
  }

  private <X> JobImpl<X> adopt(Job<X> job) {
    Objects.requireNonNull(job, "job");
    JobImpl<X> impl = (JobImpl<X>) job;
    if (impl.realm() != this) {
      throw new IllegalArgumentException("job belongs to realm '" + impl.realm().name + "'");
    }
    return impl;
  }

  /**
   * Requests cancellation of every job currently in the realm, without interrupting threads. Jobs
   * launched afterwards are not affected.
   */
  public void cancel() {
    cancel(false);
  }

  /**
   * Requests cancellation of every job currently in the realm.
   *
   * @param interrupt whether to interrupt the threads running the jobs
   */
  public void cancel(boolean interrupt) {
    for (JobImpl<?> job : jobs) {
      job.cancel(interrupt);
    }
  }

  /**
   * Closes the realm and waits for all work to finish, without cancelling anything.
   *
   * <p>Throws {@link BlockingNotAllowedException} if called from a lane that forbids blocking. New
   * work is rejected as soon as this is called. If the calling thread is interrupted while waiting,
   * the realm is cancelled (without interrupting job threads), the wait continues, and the
   * interrupt flag is restored on return. Do not call this from inside a job of the same realm. If
   * a job never ends, this never returns; use {@link #close(long, TimeUnit)} to bound the wait.
   */
  @Override
  public void close() {
    LaneContext.checkMayBlock();
    beginClose();
    boolean interrupted = false;
    for (; ; ) {
      try {
        closed.await();
        break;
      } catch (InterruptedException e) {
        if (!interrupted) {
          interrupted = true;
          cancelAllSticky(false);
        }
      }
    }
    if (interrupted) {
      Thread.currentThread().interrupt();
    }
  }

  /**
   * Closes the realm and waits at most {@code timeout} for all work to finish.
   *
   * <p>When the time runs out this call, in order: cancels every remaining job and every job
   * launched afterwards, interrupts the threads running them (see {@link #cancel(boolean)}),
   * reports each still-running job once to the {@link UncaughtHandler} as a {@link
   * LeakedJobException}, and returns {@code false}. Returning {@code false} does <em>not</em> mean
   * the work stopped: Java cannot stop code that ignores cancellation and interrupts. The realm
   * stays {@code CLOSING} and becomes {@code CLOSED} on its own when the last job ends. Calling
   * this again re-delivers the cancellation and interrupt but never reports the same job twice.
   *
   * @param timeout the maximum time to wait
   * @param unit the unit of {@code timeout}
   * @return {@code true} if the realm is closed, {@code false} if work was still outstanding
   * @throws InterruptedException if interrupted while waiting; the realm is then cancelled (without
   *     interrupting job threads) and keeps closing
   */
  public boolean close(long timeout, TimeUnit unit) throws InterruptedException {
    LaneContext.checkMayBlock();
    beginClose();
    try {
      if (closed.await(timeout, unit)) {
        return true;
      }
    } catch (InterruptedException e) {
      cancelAllSticky(false);
      throw e;
    }
    cancelAllSticky(true);
    for (JobImpl<?> job : jobs) {
      if (!job.isDone() && job.markLeakReported()) {
        report(
            new LeakedJobException(
                "job still running after close timeout in realm '" + name + "'"));
      }
    }
    return isClosed();
  }

  /**
   * Starts closing the realm and returns at once, without waiting. Safe to call from any thread,
   * including the main thread and lanes that forbid blocking.
   *
   * <p>It does, in order: rejects new work (as {@link #close()} does), requests cancellation of
   * every job currently in the realm (as {@link #cancel()} does, no thread interrupts), and returns
   * a {@link Closing} that tells when the closing is really complete. Calling it again returns the
   * same object.
   *
   * <p>Returning says nothing about the jobs: they stop only when their work cooperates. A job that
   * ignores cancellation keeps the realm {@code CLOSING}. Nothing is reported to the {@link
   * UncaughtHandler} and no thread is interrupted; to bound and escalate the closing call {@link
   * #close(long, TimeUnit)} from a thread that may block (it keeps its phase 2 meaning, also after
   * {@code closeAsync()}), or {@link #cancel(boolean) cancel(true)}.
   *
   * @return the handle that observes the end of the closing
   */
  public Closing closeAsync() {
    beginClose();
    cancelAllSticky(false);
    return closing;
  }

  /** Observes the end of a closing started by {@link #closeAsync()} (or by {@code close}). */
  public final class Closing {

    private Closing() {}

    /**
     * Returns the realm being closed.
     *
     * @return the realm
     */
    public Realm realm() {
      return Realm.this;
    }

    /**
     * Tells whether the realm is closed: closing started and no job or callback is outstanding.
     *
     * @return {@code true} when closed
     */
    public boolean isClosed() {
      return Realm.this.isClosed();
    }

    /**
     * Calls {@code callback} on {@code lane} exactly once, after the realm is closed: it is handed
     * to the lane right after the realm becomes closed (not atomically with {@link #isClosed()}
     * turning true). If it is already closed the callback is scheduled at once. Callbacks run in
     * registration order per lane behaviour. A callback is not outstanding work of the realm (the
     * realm is closing), and one that cannot run because its lane discarded it is reported to the
     * {@link UncaughtHandler}.
     *
     * @param lane where the callback runs
     * @param callback the callback
     */
    public void onClosed(Lane lane, Runnable callback) {
      Objects.requireNonNull(lane, "lane");
      Objects.requireNonNull(callback, "callback");
      closedCallbacks.add(new ClosedCallback(lane, callback));
      if (isClosed()) {
        drainClosedCallbacks();
      }
    }

    /**
     * Blocks until the realm is closed or the time runs out.
     *
     * @param timeout the maximum time to wait
     * @param unit its unit
     * @return {@code true} if the realm is closed, {@code false} if the time ran out
     * @throws InterruptedException if interrupted while waiting
     * @throws BlockingNotAllowedException if the current lane or thread forbids blocking
     */
    public boolean await(long timeout, TimeUnit unit) throws InterruptedException {
      LaneContext.checkMayBlock();
      return closed.await(timeout, unit);
    }
  }

  private final class ClosedCallback {
    private final Lane lane;
    private final Runnable callback;

    ClosedCallback(Lane lane, Runnable callback) {
      this.lane = lane;
      this.callback = callback;
    }

    void fire() {
      try {
        lane.execute(new ClosedTask(lane, callback), Priority.NORMAL);
      } catch (Throwable t) {
        report(t);
      }
    }
  }

  private final class ClosedTask implements Runnable, Lane.Discardable {
    private final Lane lane;
    private final Runnable callback;

    ClosedTask(Lane lane, Runnable callback) {
      this.lane = lane;
      this.callback = callback;
    }

    @Override
    public void run() {
      LaneContext.runIn(
          lane,
          () -> {
            try {
              callback.run();
            } catch (Throwable t) {
              report(t);
            }
          });
    }

    @Override
    public void discarded(Throwable reason) {
      report(reason);
    }
  }

  private void drainClosedCallbacks() {
    ClosedCallback callback;
    while ((callback = closedCallbacks.poll()) != null) {
      callback.fire();
    }
  }

  /** The realm reached CLOSED: wake waiters and run the registered close callbacks. */
  private void markClosed() {
    closed.countDown();
    drainClosedCallbacks();
  }

  @Override
  public String toString() {
    return "Realm(" + name + ")";
  }

  // ---- internals ----

  void register() {
    for (; ; ) {
      long s = state.get();
      if ((s & CLOSING) != 0) {
        throw new RealmClosedException("realm '" + name + "' is closing or closed");
      }
      if (state.compareAndSet(s, s + 1)) {
        return;
      }
    }
  }

  void release() {
    if (state.decrementAndGet() == CLOSING) {
      markClosed();
    }
  }

  void track(JobImpl<?> job) {
    jobs.add(job);
    if (cancelAll) {
      job.cancel(cancelAllInterrupt);
    }
    // A periodic job never ends by itself, so closing cancels it. This covers a job created while
    // the realm starts to close; beginClose() covers the others.
    if (job.isPeriodic() && isClosing()) {
      job.cancel(false);
    }
  }

  void untrack(JobImpl<?> job) {
    jobs.remove(job);
    release();
  }

  /**
   * Records the failure of a root job in a FAIL_FAST realm and cancels everything. Returns true for
   * the first failure only; the caller then reports it with {@link #reportRootFailure}.
   */
  boolean recordRootFailure(Throwable cause) {
    if (policy == RealmPolicy.FAIL_FAST && failure.compareAndSet(null, cause)) {
      cancelAllSticky(false);
      return true;
    }
    return false;
  }

  void reportRootFailure(Throwable cause) {
    report(cause);
  }

  void report(Throwable error) {
    UncaughtHandler h = handler != null ? handler : Clotho.defaultUncaughtHandler();
    try {
      h.uncaught(this, error);
    } catch (Throwable inner) {
      inner.addSuppressed(error);
      inner.printStackTrace();
    }
  }

  private void beginClose() {
    for (; ; ) {
      long s = state.get();
      if ((s & CLOSING) != 0) {
        return;
      }
      long next = s | CLOSING;
      if (state.compareAndSet(s, next)) {
        for (JobImpl<?> job : jobs) {
          if (job.isPeriodic()) {
            job.cancel(false);
          }
        }
        if (next == CLOSING) {
          markClosed();
        }
        return;
      }
    }
  }

  /** Cancels current jobs and every job added later. */
  private void cancelAllSticky(boolean interrupt) {
    if (interrupt) {
      cancelAllInterrupt = true;
    }
    cancelAll = true;
    cancel(interrupt);
  }
}
