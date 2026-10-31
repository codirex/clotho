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

import java.util.List;
import java.util.Objects;
import java.util.concurrent.locks.LockSupport;

/**
 * The timer service of an {@link Engine}: one hashed timer wheel and one thread.
 *
 * <p>Realms use it for {@link Realm#after after}, {@link Realm#every every} and {@link Realm#atRate
 * atRate}. A realm uses the cadence of the global engine unless it was built with {@link
 * Realm.Builder#cadence(Cadence)}. Details and limits (not real-time, no device wake-up) are in
 * {@code docs/CADENCE.md}.
 *
 * <p>The thread is started with the first pending timer and ends when none is left. It never runs
 * user code: when a deadline comes it only hands the work to the lane chosen by the user. A lane
 * that runs tasks inline ({@link BlockingPolicy#INHERIT}, such as {@link Lane#direct()}) is
 * therefore refused. Time comes from the engine clock, which {@link Engine.Builder#ticker}
 * replaces.
 */
public final class Cadence {

  private final Engine engine;
  private final TimerWheel wheel;
  private final boolean manual;
  private volatile Thread thread;

  Cadence(Engine engine, long tickNanos, int wheelSize, boolean manual) {
    this.engine = engine;
    this.manual = manual;
    this.wheel = new TimerWheel(engine.nanoTime(), tickNanos, wheelSize);
    this.wheel.setWaker(
        () -> {
          Thread t = thread;
          if (t != null) {
            LockSupport.unpark(t);
          }
        });
  }

  /**
   * Returns the engine that owns this cadence.
   *
   * @return the engine
   */
  public Engine engine() {
    return engine;
  }

  /**
   * Tells whether this cadence is manual: it has no thread and fires timers only when {@link
   * #runDue()} is called. See {@link Engine.Builder#manualTimers()}.
   *
   * @return {@code true} for a manual cadence
   */
  public boolean isManual() {
    return manual;
  }

  /**
   * Fires, on the calling thread, every timer whose deadline is not after the engine clock, and
   * returns how many fired. Firing only hands the work to the lane chosen by the user.
   *
   * <p>Meant for tests and simulations with a manual cadence and a controlled clock; see the {@code
   * clotho-test} module.
   *
   * @return the number of timers fired
   * @throws IllegalStateException if this cadence is not manual
   */
  public int runDue() {
    if (!manual) {
      throw new IllegalStateException(
          "runDue() needs a manual cadence (Engine.Builder.manualTimers())");
    }
    List<TimerEntry> due = wheel.advanceTo(engine.nanoTime());
    for (TimerEntry entry : due) {
      try {
        entry.timeout.fire();
      } catch (Throwable t) {
        engine.reportTaskError(t);
      }
    }
    return due.size();
  }

  /**
   * Returns the engine-clock time at which the earliest pending timer fires: its deadline rounded
   * up to a tick boundary.
   *
   * @return the time in nanoseconds of the engine clock, or empty if no timer is pending
   */
  public java.util.OptionalLong nextDueNanos() {
    long next = wheel.peekNextWake();
    return next == TimerWheel.EXIT
        ? java.util.OptionalLong.empty()
        : java.util.OptionalLong.of(next);
  }

  /**
   * Returns the number of timers waiting for their deadline. A cancelled or fired timer is not
   * counted: cancelling removes it from the wheel at once.
   *
   * @return the number of pending timers
   */
  public int pending() {
    return wheel.size();
  }

  long nanoTime() {
    return engine.nanoTime();
  }

  /** Whether the cadence was shut down with its engine. */
  boolean isShutdown() {
    return wheel.isClosed();
  }

  /**
   * Schedules a timer. Returns null if the cadence is shut down or its thread cannot be started.
   */
  TimerEntry schedule(long deadlineNanos, Timeout timeout) {
    Objects.requireNonNull(timeout, "timeout");
    TimerWheel.Scheduled scheduled = wheel.schedule(deadlineNanos, timeout);
    if (scheduled == null) {
      return null;
    }
    if (manual) {
      return scheduled.entry;
    }
    if (scheduled.startThread) {
      try {
        startThread();
      } catch (Throwable e) {
        scheduled.entry.cancel();
        wheel.threadFailedToStart();
        engine.reportTaskError(e);
        return null;
      }
    } else if (scheduled.wakeThread) {
      Thread t = thread;
      if (t != null) {
        LockSupport.unpark(t);
      }
    }
    return scheduled.entry;
  }

  private void startThread() {
    Thread t = engine.newThread(this::loop, engine.name() + "-cadence");
    thread = t;
    engine.threadStarting(t);
    try {
      t.start();
    } catch (Throwable e) {
      engine.threadEnded(t);
      throw e;
    }
  }

  /** Discards every pending timer; their owners are told why. Used by the engine's shutdown. */
  void shutdown(Throwable reason) {
    List<TimerEntry> drained = wheel.closeAndDrain();
    for (TimerEntry entry : drained) {
      try {
        entry.timeout.discard(reason);
      } catch (Throwable t) {
        engine.reportTaskError(t);
      }
    }
    Thread t = thread;
    if (t != null) {
      LockSupport.unpark(t);
    }
  }

  private void loop() {
    try {
      for (; ; ) {
        List<TimerEntry> due = wheel.advanceTo(engine.nanoTime());
        for (TimerEntry entry : due) {
          try {
            entry.timeout.fire();
          } catch (Throwable t) {
            engine.reportTaskError(t);
          }
        }
        long wakeAt = wheel.prepareSleep();
        if (wakeAt == TimerWheel.EXIT) {
          return;
        }
        long sleep = wakeAt - engine.nanoTime();
        if (sleep > 0) {
          LockSupport.parkNanos(this, sleep);
        }
        wheel.awake();
        Thread.interrupted();
      }
    } finally {
      engine.threadEnded(Thread.currentThread());
    }
  }

  @Override
  public String toString() {
    return "Cadence(" + engine.name() + ", pending=" + pending() + ")";
  }
}
