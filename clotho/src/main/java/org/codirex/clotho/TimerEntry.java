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

/** One scheduled timer. Everything but the constants is guarded by the wheel lock. */
final class TimerEntry {

  static final int SCHEDULED = 0;
  static final int FIRED = 1;
  static final int CANCELLED = 2;
  static final int DISCARDED = 3;

  final TimerWheel wheel;
  final Timeout timeout;
  final long tick;
  final long seq;

  int bucket;
  TimerEntry prev;
  TimerEntry next;
  int state = SCHEDULED;

  TimerEntry(TimerWheel wheel, Timeout timeout, long tick, long seq) {
    this.wheel = wheel;
    this.timeout = timeout;
    this.tick = tick;
    this.seq = seq;
  }

  /**
   * Removes the timer if it has not fired. The entry is unlinked from its bucket before this
   * returns, so a cancelled timer is not kept by the wheel.
   *
   * @return true if this call cancelled it; false if it already fired, was cancelled or discarded
   */
  boolean cancel() {
    return wheel.cancel(this);
  }
}
