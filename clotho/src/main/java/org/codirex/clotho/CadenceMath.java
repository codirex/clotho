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

/** Time arithmetic for timers: saturating, so huge delays never overflow. Not public API. */
final class CadenceMath {

  /** Longest delay accepted; keeps deadline arithmetic far from overflow (about 73 years). */
  static final long MAX_DELAY_NANOS = Long.MAX_VALUE / 4;

  private CadenceMath() {}

  static long saturatedAdd(long a, long b) {
    long r = a + b;
    if (((a ^ r) & (b ^ r)) < 0) {
      return a < 0 ? Long.MIN_VALUE : Long.MAX_VALUE;
    }
    return r;
  }

  static long saturatedMultiply(long a, long b) {
    try {
      return Math.multiplyExact(a, b);
    } catch (ArithmeticException overflow) {
      return (a < 0) == (b < 0) ? Long.MAX_VALUE : Long.MIN_VALUE;
    }
  }

  /** The deadline for a delay, with the delay clamped to {@link #MAX_DELAY_NANOS}. */
  static long deadline(long now, long delayNanos) {
    return saturatedAdd(now, Math.min(Math.max(delayNanos, 0), MAX_DELAY_NANOS));
  }

  /** Fixed delay: the next run is one period after the end of the previous one. */
  static long nextFixedDelay(long endNanos, long periodNanos) {
    return saturatedAdd(endNanos, periodNanos);
  }

  /**
   * Fixed rate: the index {@code k} of the slot {@code first + k * period} to run next, after the
   * slot {@code lastIndex} was served. It is the first slot after {@code lastIndex} that is not in
   * the past ({@code slot >= now}). Slots that passed meanwhile are skipped, so there is never a
   * burst of catch-up runs, and a slot is never run twice even if its run ends at the very instant
   * of the slot.
   */
  static long nextFixedRateIndex(long firstNanos, long periodNanos, long lastIndex, long nowNanos) {
    long due = 0;
    if (nowNanos > firstNanos) {
      long elapsed = nowNanos - firstNanos;
      due = elapsed / periodNanos + (elapsed % periodNanos == 0 ? 0 : 1);
    }
    return Math.max(lastIndex + 1, due);
  }

  /** The time of slot {@code index} of a fixed-rate schedule. */
  static long slotTime(long firstNanos, long periodNanos, long index) {
    return saturatedAdd(firstNanos, saturatedMultiply(index, periodNanos));
  }
}
