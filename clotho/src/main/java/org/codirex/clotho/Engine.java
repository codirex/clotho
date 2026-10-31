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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;

/**
 * Owns the threads behind a set of {@link Lane}s.
 *
 * <p>An engine provides {@link #compute()} and {@link #io()} (work-stealing pools) and creates
 * {@link #serial serial} and {@link #single single} lanes on demand. Private engines are created
 * with {@link #builder()} and must be shut down by their owner. {@link #global()} is shared by the
 * whole process and can never be shut down. The exact rules (shutdown, priorities, aging, bounded
 * queues, blocking) are in {@code docs/ENGINE.md}.
 */
public final class Engine implements AutoCloseable {

  private static final class Holder {
    static final Engine GLOBAL = new Builder().name("clotho-global").buildInternal(true);
  }

  private final String name;
  private final boolean global;
  private final LongSupplier ticker;
  private final long agingStepNanos;
  private final boolean daemon;
  private final Thread.UncaughtExceptionHandler errorHandler;
  private final PoolLane compute;
  private final PoolLane io;
  private final Cadence cadence;
  private final List<ManagedLane> managed = new CopyOnWriteArrayList<>();
  private final AtomicInteger inFlight = new AtomicInteger();
  private final AtomicInteger live = new AtomicInteger();
  private final Set<Thread> liveThreads = ConcurrentHashMap.newKeySet();
  private final ReentrantLock termLock = new ReentrantLock();
  private final Condition termCond = termLock.newCondition();
  private volatile boolean shutdown;

  private Engine(Builder b, boolean global) {
    this.name = b.name;
    this.global = global;
    this.ticker = b.ticker;
    this.agingStepNanos = b.agingStepNanos;
    this.daemon = b.daemon;
    this.errorHandler = b.errorHandler;
    this.compute =
        new PoolLane(
            this,
            "compute",
            BlockingPolicy.FORBIDDEN,
            b.computeThreads,
            b.computeKeepAliveNanos,
            b.computeLimit);
    this.io =
        new PoolLane(
            this, "io", BlockingPolicy.ALLOWED, b.ioMaxThreads, b.ioKeepAliveNanos, b.ioLimit);
    this.cadence = new Cadence(this, b.timerTickNanos, b.timerWheelSize, b.manualTimers);
    managed.add(compute);
    managed.add(io);
  }

  /**
   * Returns the process-wide engine used by {@link Lane#compute()} and {@link Lane#io()}.
   *
   * <p>It uses daemon threads that retire when idle, and it cannot be shut down: {@link
   * #shutdown()}, {@link #shutdownNow()}, {@link #close()} and {@link #single} throw {@link
   * IllegalStateException}, so a library cannot close it for everybody by mistake.
   *
   * @return the global engine
   */
  public static Engine global() {
    return Holder.GLOBAL;
  }

  /**
   * Starts building a private engine.
   *
   * @return the builder
   */
  public static Builder builder() {
    return new Builder();
  }

  /** Builds an {@link Engine}. */
  public static final class Builder {
    private String name = "clotho";
    private int computeThreads = Math.max(1, Runtime.getRuntime().availableProcessors());
    private int ioMaxThreads = 128;
    private long computeKeepAliveNanos = TimeUnit.SECONDS.toNanos(60);
    private long ioKeepAliveNanos = TimeUnit.SECONDS.toNanos(30);
    private QueueLimit computeLimit = QueueLimit.unbounded();
    private QueueLimit ioLimit = QueueLimit.unbounded();
    private long agingStepNanos = TimeUnit.MILLISECONDS.toNanos(100);
    private LongSupplier ticker = System::nanoTime;
    private long timerTickNanos = TimeUnit.MILLISECONDS.toNanos(10);
    private int timerWheelSize = 512;
    private boolean manualTimers;
    private boolean daemon = true;
    private Thread.UncaughtExceptionHandler errorHandler = Builder::printError;

    private Builder() {}

    private static void printError(Thread thread, Throwable error) {
      System.err.println("Clotho: uncaught error in a raw lane task");
      error.printStackTrace();
    }

    /**
     * Sets the name used in thread names and diagnostics. Default {@code clotho}.
     *
     * @param name the name
     * @return this builder
     */
    public Builder name(String name) {
      this.name = Objects.requireNonNull(name, "name");
      return this;
    }

    /**
     * Sets the maximum number of compute threads. Default: the number of processors.
     *
     * @param threads at least 1
     * @return this builder
     */
    public Builder computeThreads(int threads) {
      if (threads < 1) {
        throw new IllegalArgumentException("computeThreads must be at least 1");
      }
      this.computeThreads = threads;
      return this;
    }

    /**
     * Sets the maximum number of I/O threads. Default 128.
     *
     * @param threads at least 1
     * @return this builder
     */
    public Builder ioMaxThreads(int threads) {
      if (threads < 1) {
        throw new IllegalArgumentException("ioMaxThreads must be at least 1");
      }
      this.ioMaxThreads = threads;
      return this;
    }

    /**
     * Sets how long an idle compute thread lives. Default 60 seconds.
     *
     * @param time a positive time
     * @param unit its unit
     * @return this builder
     */
    public Builder computeKeepAlive(long time, TimeUnit unit) {
      this.computeKeepAliveNanos = positive(time, unit, "computeKeepAlive");
      return this;
    }

    /**
     * Sets how long an idle I/O thread lives. Default 30 seconds.
     *
     * @param time a positive time
     * @param unit its unit
     * @return this builder
     */
    public Builder ioKeepAlive(long time, TimeUnit unit) {
      this.ioKeepAliveNanos = positive(time, unit, "ioKeepAlive");
      return this;
    }

    /**
     * Bounds the compute queue. Default unbounded.
     *
     * @param limit the limit
     * @return this builder
     */
    public Builder computeQueue(QueueLimit limit) {
      this.computeLimit = Objects.requireNonNull(limit, "limit");
      return this;
    }

    /**
     * Bounds the I/O queue. Default unbounded.
     *
     * @param limit the limit
     * @return this builder
     */
    public Builder ioQueue(QueueLimit limit) {
      this.ioLimit = Objects.requireNonNull(limit, "limit");
      return this;
    }

    /**
     * Sets the aging step: a waiting task gains one priority level per step. Default 100 ms. Zero
     * disables aging, which allows starvation of low priority work and is only meant for tests.
     *
     * @param time zero or a positive time
     * @param unit its unit
     * @return this builder
     */
    public Builder agingStep(long time, TimeUnit unit) {
      if (time < 0) {
        throw new IllegalArgumentException("agingStep must not be negative");
      }
      this.agingStepNanos = unit.toNanos(time);
      return this;
    }

    /**
     * Replaces the monotonic clock used for aging, so tests can control time. Keep-alive uses the
     * real clock.
     *
     * @param nanoTime a monotonic time source in nanoseconds
     * @return this builder
     */
    public Builder ticker(LongSupplier nanoTime) {
      this.ticker = Objects.requireNonNull(nanoTime, "nanoTime");
      return this;
    }

    /**
     * Sets the tick of the timer wheel: timers fire on tick boundaries, so a timer can be late by
     * up to one tick. Default 10 ms. The timer thread wakes only for ticks that have timers.
     *
     * @param time a positive time
     * @param unit its unit
     * @return this builder
     */
    public Builder timerTick(long time, TimeUnit unit) {
      this.timerTickNanos = positive(time, unit, "timerTick");
      return this;
    }

    /**
     * Sets the number of buckets of the timer wheel (a power of two, at least 2). Default 512. It
     * does not change when timers fire; it only spreads them over more buckets.
     *
     * @param size a power of two
     * @return this builder
     */
    public Builder timerWheelSize(int size) {
      if (size < 2 || Integer.bitCount(size) != 1) {
        throw new IllegalArgumentException("timerWheelSize must be a power of two, at least 2");
      }
      this.timerWheelSize = size;
      return this;
    }

    /**
     * Makes the timer service manual: it never starts a thread, and timers fire only when {@link
     * Cadence#runDue()} is called, using the engine clock ({@link #ticker}). For tests and
     * simulations (see the {@code clotho-test} module). Not available for the global engine.
     *
     * @return this builder
     */
    public Builder manualTimers() {
      this.manualTimers = true;
      return this;
    }

    /**
     * Chooses daemon or non-daemon threads. Default daemon.
     *
     * @param daemon whether threads are daemons
     * @return this builder
     */
    public Builder daemon(boolean daemon) {
      this.daemon = daemon;
      return this;
    }

    /**
     * Sets the handler for errors thrown by raw {@link Runnable}s run on lanes, and for raw tasks
     * that were dropped. Tasks of jobs never reach it: their errors become outcomes. The default
     * prints to the standard error stream.
     *
     * @param handler the handler
     * @return this builder
     */
    public Builder taskErrorHandler(Thread.UncaughtExceptionHandler handler) {
      this.errorHandler = Objects.requireNonNull(handler, "handler");
      return this;
    }

    /**
     * Creates the engine. No thread is started until work arrives.
     *
     * @return a new private engine
     */
    public Engine build() {
      return buildInternal(false);
    }

    private Engine buildInternal(boolean global) {
      return new Engine(this, global);
    }

    private static long positive(long time, TimeUnit unit, String what) {
      long nanos = unit.toNanos(time);
      if (nanos <= 0) {
        throw new IllegalArgumentException(what + " must be positive");
      }
      return nanos;
    }
  }

  /**
   * Returns the engine name.
   *
   * @return the name
   */
  public String name() {
    return name;
  }

  /**
   * Tells whether this is the global engine.
   *
   * @return {@code true} for {@link #global()}
   */
  public boolean isGlobal() {
    return global;
  }

  /**
   * Returns the timer service of this engine. Pass it to {@link Realm.Builder#cadence(Cadence)} to
   * make a realm use it. {@link #shutdown()} and {@link #shutdownNow()} discard its pending timers
   * (their jobs end as {@link Outcome.Failure} with a shutdown {@link DroppedException}) and its
   * thread ends.
   *
   * @return the cadence
   */
  public Cadence cadence() {
    return cadence;
  }

  /**
   * Returns the compute lane: at most one thread per configured compute thread, blocking calls
   * forbidden ({@link BlockingPolicy#FORBIDDEN}), priorities and aging apply.
   *
   * @return the compute lane
   */
  public Lane compute() {
    return compute;
  }

  /**
   * Returns the I/O lane: threads are created on demand up to the maximum and retire after the
   * keep-alive time, blocking allowed, priorities and aging apply.
   *
   * @return the I/O lane
   */
  public Lane io() {
    return io;
  }

  /**
   * Creates an unbounded serial lane. See {@link #serial(String, QueueLimit)}.
   *
   * @param name a name for diagnostics
   * @return the lane
   */
  public Lane serial(String name) {
    return serial(name, QueueLimit.unbounded());
  }

  /**
   * Creates a serial lane: tasks run one at a time in the exact order they were submitted, on
   * threads of the {@linkplain #io() I/O pool} (possibly a different thread each time, with a
   * happens-before edge between consecutive tasks). Priority is ignored. Blocking is allowed.
   *
   * @param name a name for diagnostics
   * @param limit the queue limit; {@link RejectionPolicy#CALLER_RUNS} is not allowed
   * @return the lane
   * @throws IllegalArgumentException if the policy is {@code CALLER_RUNS}
   * @throws IllegalStateException if the engine is shut down
   */
  public Lane serial(String name, QueueLimit limit) {
    checkOrdered(limit);
    checkNotShutdown();
    SerialLane lane = new SerialLane(this, Objects.requireNonNull(name, "name"), io, limit);
    if (!global) {
      // Registered only to discard its queue on shutdownNow(); the global engine never shuts down,
      // so it keeps no reference and creating many serial lanes there does not leak.
      managed.add(lane);
    }
    return lane;
  }

  /**
   * Creates an unbounded single lane. See {@link #single(String, QueueLimit)}.
   *
   * @param name a name for diagnostics
   * @return the lane
   */
  public Lane single(String name) {
    return single(name, QueueLimit.unbounded());
  }

  /**
   * Creates a single lane: one dedicated thread owned by this engine runs every task, in the exact
   * order they were submitted. The thread starts with the first task and lives until the engine is
   * shut down. Priority is ignored. Blocking is allowed.
   *
   * @param name a name for diagnostics
   * @param limit the queue limit; {@link RejectionPolicy#CALLER_RUNS} is not allowed
   * @return the lane
   * @throws IllegalArgumentException if the policy is {@code CALLER_RUNS}
   * @throws IllegalStateException if this is the global engine (its threads could never be
   *     released) or the engine is shut down
   */
  public Lane single(String name, QueueLimit limit) {
    if (global) {
      throw new IllegalStateException("single lanes need a private engine: they own a thread");
    }
    checkOrdered(limit);
    checkNotShutdown();
    SingleLane lane = new SingleLane(this, Objects.requireNonNull(name, "name"), limit);
    managed.add(lane);
    return lane;
  }

  private static void checkOrdered(QueueLimit limit) {
    Objects.requireNonNull(limit, "limit");
    if (limit.capacity() > 0 && limit.policy() == RejectionPolicy.CALLER_RUNS) {
      throw new IllegalArgumentException("CALLER_RUNS would break the order of an ordered lane");
    }
  }

  private void checkNotShutdown() {
    if (shutdown) {
      throw new IllegalStateException("engine '" + name + "' is shut down");
    }
  }

  /**
   * Tells whether {@link #shutdown()} or {@link #shutdownNow()} was called.
   *
   * @return {@code true} once shutdown started
   */
  public boolean isShutdown() {
    return shutdown;
  }

  /**
   * Tells whether the engine is shut down and none of its threads is alive.
   *
   * @return {@code true} when terminated
   */
  public boolean isTerminated() {
    return shutdown && live.get() == 0;
  }

  /**
   * Returns the number of threads currently owned by this engine.
   *
   * @return the live thread count
   */
  public int liveThreads() {
    return live.get();
  }

  /**
   * Starts a graceful shutdown and returns immediately.
   *
   * <p>From this moment every lane rejects new tasks ({@link RejectedExecutionException}, so a job
   * submitted afterwards ends as {@link Outcome.Failure}), including tasks submitted by tasks that
   * are still running. Tasks already accepted keep running until the queues are empty, then the
   * threads exit. Calling it again has no effect.
   *
   * @throws IllegalStateException on the global engine
   */
  public void shutdown() {
    checkNotGlobal();
    beginShutdown();
  }

  /**
   * Shuts down at once and returns immediately.
   *
   * <p>Does everything {@link #shutdown()} does, then discards every queued task (a job's task ends
   * the job as {@link Outcome.Failure} with a {@link DroppedException}; raw tasks are reported to
   * the task error handler) and interrupts every thread. Tasks that are running keep running unless
   * they react to the interrupt.
   *
   * @return the number of discarded tasks
   * @throws IllegalStateException on the global engine
   */
  public int shutdownNow() {
    checkNotGlobal();
    beginShutdown();
    int discarded = 0;
    for (ManagedLane lane : managed) {
      discarded += lane.onShutdownNow();
    }
    return discarded;
  }

  private void beginShutdown() {
    shutdown = true;
    // New submissions now see the flag; wait for the ones that already passed it, so nothing is
    // enqueued or started after this point.
    while (inFlight.get() != 0) {
      Thread.yield();
    }
    cadence.shutdown(
        new DroppedException(
            "engine '" + name + "' was shut down: pending timers discarded",
            DroppedException.Reason.SHUTDOWN));
    for (ManagedLane lane : managed) {
      lane.onShutdown();
    }
    signalTermination();
  }

  /**
   * Waits until the engine is terminated: shut down and no thread alive.
   *
   * @param timeout the maximum time to wait
   * @param unit its unit
   * @return {@code true} if terminated, {@code false} if the time ran out (threads running tasks
   *     that ignore cancellation and interrupts stay alive; Java cannot stop them)
   * @throws InterruptedException if interrupted while waiting
   * @throws BlockingNotAllowedException if the current lane forbids blocking
   * @throws IllegalStateException if called from a thread owned by this engine
   */
  public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
    checkMayWait();
    long nanos = unit.toNanos(timeout);
    termLock.lock();
    try {
      while (!isTerminated()) {
        if (nanos <= 0) {
          return false;
        }
        nanos = termCond.awaitNanos(nanos);
      }
      return true;
    } finally {
      termLock.unlock();
    }
  }

  /**
   * Shuts down gracefully and waits, without a time limit, until all threads ended.
   *
   * <p>If the calling thread is interrupted, it escalates to {@link #shutdownNow()}, keeps waiting
   * and restores the interrupt flag. If a task never ends this never returns; use {@link
   * #close(long, TimeUnit)} to bound the wait.
   *
   * @throws BlockingNotAllowedException if the current lane forbids blocking
   * @throws IllegalStateException on the global engine, or from a thread owned by this engine
   */
  @Override
  public void close() {
    checkNotGlobal();
    checkMayWait();
    beginShutdown();
    boolean interrupted = false;
    for (; ; ) {
      try {
        if (awaitTermination(1, TimeUnit.HOURS)) {
          break;
        }
      } catch (InterruptedException e) {
        if (!interrupted) {
          interrupted = true;
          shutdownNow();
        }
      }
    }
    if (interrupted) {
      Thread.currentThread().interrupt();
    }
  }

  /**
   * Shuts down gracefully and waits at most {@code timeout}; if threads are still alive then, it
   * calls {@link #shutdownNow()} once and returns {@code false} without waiting again.
   *
   * @param timeout the maximum time to wait for the graceful shutdown
   * @param unit its unit
   * @return {@code true} if the engine terminated within the time
   * @throws InterruptedException if interrupted while waiting (the engine keeps shutting down)
   * @throws BlockingNotAllowedException if the current lane forbids blocking
   * @throws IllegalStateException on the global engine, or from a thread owned by this engine
   */
  public boolean close(long timeout, TimeUnit unit) throws InterruptedException {
    checkNotGlobal();
    checkMayWait();
    beginShutdown();
    if (awaitTermination(timeout, unit)) {
      return true;
    }
    shutdownNow();
    return false;
  }

  @Override
  public String toString() {
    return "Engine(" + name + (global ? ", global" : "") + ")";
  }

  private void checkNotGlobal() {
    if (global) {
      throw new IllegalStateException("the global engine can never be shut down");
    }
  }

  private void checkMayWait() {
    LaneContext.checkMayBlock();
    if (liveThreads.contains(Thread.currentThread())) {
      throw new IllegalStateException("cannot wait for engine '" + name + "' from its own thread");
    }
  }

  // ---- internals used by the lanes ----

  long nanoTime() {
    return ticker.getAsLong();
  }

  long agingStepNanos() {
    return agingStepNanos;
  }

  /** Enters a submission; throws if the engine is shut down. Pair with {@link #exitSubmit()}. */
  void enterSubmit() {
    inFlight.incrementAndGet();
    if (shutdown) {
      inFlight.decrementAndGet();
      throw new LaneClosedException("engine '" + name + "' is shut down");
    }
  }

  void exitSubmit() {
    inFlight.decrementAndGet();
  }

  Thread newThread(Runnable body, String threadName) {
    Thread t = new Thread(body, threadName);
    t.setDaemon(daemon);
    return t;
  }

  void threadStarting(Thread t) {
    live.incrementAndGet();
    liveThreads.add(t);
  }

  void threadEnded(Thread t) {
    liveThreads.remove(t);
    if (live.decrementAndGet() == 0) {
      signalTermination();
    }
  }

  List<Thread> liveThreadSnapshot() {
    return new ArrayList<>(liveThreads);
  }

  private void signalTermination() {
    termLock.lock();
    try {
      termCond.signalAll();
    } finally {
      termLock.unlock();
    }
  }

  void reportTaskError(Throwable error) {
    try {
      errorHandler.uncaughtException(Thread.currentThread(), error);
    } catch (Throwable inner) {
      inner.addSuppressed(error);
      inner.printStackTrace();
    }
  }

  /** Tells a discarded task why, or reports the loss if the task cannot be told. */
  static void discard(Engine engine, Runnable task, Throwable reason) {
    if (task instanceof Lane.Discardable discardable) {
      try {
        discardable.discarded(reason);
      } catch (Throwable t) {
        engine.reportTaskError(t);
      }
    } else {
      engine.reportTaskError(reason);
    }
  }
}
