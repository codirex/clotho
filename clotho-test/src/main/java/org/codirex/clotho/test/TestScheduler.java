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
package org.codirex.clotho.test;

import java.util.OptionalLong;
import java.util.concurrent.TimeUnit;
import org.codirex.clotho.Cadence;
import org.codirex.clotho.Engine;
import org.codirex.clotho.Realm;

/**
 * Everything needed to test time-dependent code without threads and without waiting: a {@link
 * TestClock}, a manual timer service driven by that clock, a {@link DeterministicLane} and a {@link
 * Realm} wired to them.
 *
 * <pre>{@code
 * try (TestScheduler s = new TestScheduler()) {
 *   Job<Void> job = s.realm().every(s.lane(), 0, 1, TimeUnit.SECONDS, cp -> counter.increment());
 *   s.advance(5, TimeUnit.SECONDS);   // runs the job at 0, 1, 2, 3, 4 and 5 seconds
 * }
 * }</pre>
 *
 * <p>Time moves only through {@link #advance} and {@link #jump}. Timers of the engine fire on tick
 * boundaries of the clock, exactly as with real time, so the tests are exact instead of tolerant.
 * Use real-time tests too: they check what a fake clock cannot, such as the timer thread.
 */
public final class TestScheduler implements AutoCloseable {

  private static final int MAX_EVENTS = 5_000_000;
  private static final int MAX_SETTLE_ROUNDS = 100_000;

  private final TestClock clock = new TestClock();
  private final DeterministicLane lane = new DeterministicLane();
  private final RecordingUncaughtHandler handler = new RecordingUncaughtHandler();
  private final Engine engine;
  private final Realm realm;

  /** Creates a scheduler whose timers tick every millisecond of test time. */
  public TestScheduler() {
    this(1, TimeUnit.MILLISECONDS);
  }

  /**
   * Creates a scheduler.
   *
   * @param tick the tick of the timer wheel in test time; timers fire on its boundaries
   * @param unit the unit of {@code tick}
   */
  public TestScheduler(long tick, TimeUnit unit) {
    this.engine =
        Engine.builder()
            .name("test-scheduler")
            .manualTimers()
            .timerTick(tick, unit)
            .ticker(clock)
            .build();
    this.realm = Realm.builder("test").cadence(engine.cadence()).uncaughtHandler(handler).open();
  }

  /**
   * Returns the clock.
   *
   * @return the test clock
   */
  public TestClock clock() {
    return clock;
  }

  /**
   * Returns the lane to give to {@code launch}, {@code after}, {@code every} and {@code atRate}.
   * Tasks run when this scheduler settles.
   *
   * @return the deterministic lane
   */
  public DeterministicLane lane() {
    return lane;
  }

  /**
   * Returns the realm, already wired to the test clock and to {@link #handler()}.
   *
   * @return the realm
   */
  public Realm realm() {
    return realm;
  }

  /**
   * Returns the engine that provides the manual timer service. Its own lanes use real threads; use
   * {@link #lane()} instead.
   *
   * @return the engine
   */
  public Engine engine() {
    return engine;
  }

  /**
   * Returns the handler that receives the realm's uncaught errors.
   *
   * @return the recording handler
   */
  public RecordingUncaughtHandler handler() {
    return handler;
  }

  /**
   * Returns the timer service.
   *
   * @return the manual cadence
   */
  public Cadence cadence() {
    return engine.cadence();
  }

  /**
   * Fires the timers that are due now and runs the lane until nothing more happens at the current
   * time.
   *
   * @throws AssertionError if it does not settle (a task that keeps submitting tasks)
   */
  public void settle() {
    for (int round = 0; round < MAX_SETTLE_ROUNDS; round++) {
      int fired = cadence().runDue();
      int ran = lane.runAll();
      if (fired == 0 && ran == 0) {
        return;
      }
    }
    throw new AssertionError("the scheduler does not settle: tasks keep submitting tasks");
  }

  /**
   * Moves test time forward, stopping at every timer on the way so each fires at its own time. A
   * task that advances the clock itself (to simulate that it takes time) is allowed.
   *
   * @param amount how much time, not negative
   * @param unit its unit
   * @throws AssertionError if more than five million timer events happen (a tiny period over a very
   *     long time)
   */
  public void advance(long amount, TimeUnit unit) {
    long target = clock.nanos() + unit.toNanos(amount);
    settle();
    int events = 0;
    for (; ; ) {
      OptionalLong next = cadence().nextDueNanos();
      if (!next.isPresent() || next.getAsLong() > target) {
        break;
      }
      if (++events > MAX_EVENTS) {
        throw new AssertionError("too many timer events while advancing time");
      }
      if (next.getAsLong() > clock.nanos()) {
        clock.set(next.getAsLong());
      }
      settle();
    }
    if (clock.nanos() < target) {
      clock.set(target);
      settle();
    }
  }

  /**
   * Moves test time forward in one step, like a device that slept: every timer that became due
   * fires at once, late, and periodic jobs see only the final time.
   *
   * @param amount how much time, not negative
   * @param unit its unit
   */
  public void jump(long amount, TimeUnit unit) {
    clock.advance(amount, unit);
    settle();
  }

  /**
   * Returns the number of timers waiting.
   *
   * @return the pending timer count
   */
  public int pendingTimers() {
    return cadence().pending();
  }

  /** Cancels the realm's work and shuts the engine down. Safe to call twice. */
  @Override
  public void close() {
    realm.closeAsync();
    engine.shutdownNow();
  }
}
