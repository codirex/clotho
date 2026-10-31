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

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * A clock that only moves when the test moves it. Pass it to {@code Engine.Builder.ticker(...)} so
 * aging and timers follow it.
 */
public final class TestClock implements LongSupplier {

  private final AtomicLong nanos;

  /** Creates a clock that starts at zero. */
  public TestClock() {
    this(0);
  }

  /**
   * Creates a clock.
   *
   * @param startNanos the first value, in nanoseconds
   */
  public TestClock(long startNanos) {
    this.nanos = new AtomicLong(startNanos);
  }

  /**
   * Returns the current time.
   *
   * @return nanoseconds
   */
  public long nanos() {
    return nanos.get();
  }

  @Override
  public long getAsLong() {
    return nanos.get();
  }

  /**
   * Moves the clock forward.
   *
   * @param amount how much, not negative
   * @param unit its unit
   * @throws IllegalArgumentException if {@code amount} is negative
   */
  public void advance(long amount, TimeUnit unit) {
    if (amount < 0) {
      throw new IllegalArgumentException("a clock does not go backwards");
    }
    nanos.addAndGet(unit.toNanos(amount));
  }

  /**
   * Sets the clock to a later time.
   *
   * @param newNanos the new time, not before the current one
   * @throws IllegalArgumentException if it would move the clock backwards
   */
  public void set(long newNanos) {
    for (; ; ) {
      long current = nanos.get();
      if (newNanos < current) {
        throw new IllegalArgumentException("a clock does not go backwards");
      }
      if (nanos.compareAndSet(current, newNanos)) {
        return;
      }
    }
  }

  @Override
  public String toString() {
    return "TestClock(" + nanos.get() + " ns)";
  }
}
