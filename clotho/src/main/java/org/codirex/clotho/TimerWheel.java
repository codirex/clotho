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
import java.util.Comparator;
import java.util.List;

/**
 * A hashed timer wheel. It has no thread and no clock: the caller passes the time in, so it is
 * testable with a fake clock. Not part of the public API.
 *
 * <p>Time is cut into ticks of {@code tickNanos} counted from {@code startNanos}. A timer with
 * deadline {@code d} belongs to tick {@code ceil((d - start) / tick)}, so it never fires before its
 * deadline, and to the bucket {@code tick mod wheelSize}. Buckets are doubly linked lists. All
 * state is guarded by one lock.
 *
 * <p>Costs, as implemented: adding is constant work. Cancelling unlinks the entry under the lock,
 * also constant work, and the entry is gone from the wheel when {@code cancel} returns (no deferred
 * cleanup). Advancing time scans at most {@code wheelSize} buckets, however long the gap was. A
 * timer far in the future stays in its bucket and is skipped, not fired, when that bucket is
 * visited before its round.
 *
 * <p>Each bucket remembers the earliest tick among its entries. Removing the earliest entry only
 * marks the bucket dirty; the value is recomputed (cost: the size of that bucket) the next time the
 * timer thread asks when to wake up. That question scans the {@code wheelSize} buckets, so the
 * thread sleeps until the tick of the earliest pending timer exactly, never waking for a bucket
 * whose timers are not due.
 */
final class TimerWheel {

  /** Returned by {@link #prepareSleep()} when the thread must exit. */
  static final long EXIT = Long.MIN_VALUE;

  /** The result of {@link #schedule}. */
  static final class Scheduled {
    final TimerEntry entry;
    boolean startThread;
    boolean wakeThread;

    Scheduled(TimerEntry entry) {
      this.entry = entry;
    }
  }

  private static final Comparator<TimerEntry> BY_DEADLINE =
      (a, b) -> a.tick != b.tick ? Long.compare(a.tick, b.tick) : Long.compare(a.seq, b.seq);

  private final long startNanos;
  private final long tickNanos;
  private final int mask;
  private final TimerEntry[] heads;
  private final long[] minTick;
  private final boolean[] dirty;
  private final Object lock = new Object();

  private long currentTick;
  private int size;
  private boolean closed;
  private boolean threadRunning;
  private long sleepingUntilTick = Long.MIN_VALUE;
  private long seq;
  private volatile Runnable waker;

  TimerWheel(long startNanos, long tickNanos, int wheelSize) {
    if (tickNanos <= 0) {
      throw new IllegalArgumentException("tick must be positive");
    }
    if (wheelSize < 2 || Integer.bitCount(wheelSize) != 1) {
      throw new IllegalArgumentException("wheel size must be a power of two, at least 2");
    }
    this.startNanos = startNanos;
    this.tickNanos = tickNanos;
    this.mask = wheelSize - 1;
    this.heads = new TimerEntry[wheelSize];
    this.minTick = new long[wheelSize];
    this.dirty = new boolean[wheelSize];
  }

  long tickNanos() {
    return tickNanos;
  }

  /**
   * Sets what to call, outside the lock, when the last timer was cancelled while the timer thread
   * sleeps, so that the thread can end at once instead of sleeping until a deadline that no longer
   * exists.
   */
  void setWaker(Runnable waker) {
    this.waker = waker;
  }

  /** Adds a timer. Returns null if the wheel is closed. */
  Scheduled schedule(long deadlineNanos, Timeout timeout) {
    synchronized (lock) {
      if (closed) {
        return null;
      }
      long delta = deadlineNanos - startNanos;
      long tick = delta <= 0 ? 0 : (delta - 1) / tickNanos + 1;
      if (tick <= currentTick) {
        tick = currentTick + 1;
      }
      TimerEntry entry = new TimerEntry(this, timeout, tick, ++seq);
      timeout.scheduled(entry);
      link(entry);
      size++;
      Scheduled result = new Scheduled(entry);
      if (!threadRunning) {
        threadRunning = true;
        result.startThread = true;
      } else if (tick < sleepingUntilTick) {
        result.wakeThread = true;
      }
      return result;
    }
  }

  boolean cancel(TimerEntry entry) {
    boolean wake;
    synchronized (lock) {
      if (entry.state != TimerEntry.SCHEDULED) {
        return false;
      }
      unlink(entry);
      size--;
      entry.state = TimerEntry.CANCELLED;
      wake = size == 0 && threadRunning && sleepingUntilTick != Long.MIN_VALUE;
    }
    if (wake) {
      Runnable w = waker;
      if (w != null) {
        w.run();
      }
    }
    return true;
  }

  /**
   * Moves time to {@code nowNanos} and returns the timers that are due, in deadline order (then in
   * the order they were added). The caller fires them outside the lock.
   */
  List<TimerEntry> advanceTo(long nowNanos) {
    synchronized (lock) {
      long target = Math.floorDiv(nowNanos - startNanos, tickNanos);
      if (target <= currentTick) {
        return Collections.emptyList();
      }
      List<TimerEntry> due = new ArrayList<>();
      if (target - currentTick >= heads.length) {
        for (int i = 0; i < heads.length; i++) {
          collect(i, target, due);
        }
      } else {
        for (long t = currentTick + 1; t <= target; t++) {
          collect((int) (t & mask), target, due);
        }
      }
      currentTick = target;
      if (due.size() > 1) {
        Collections.sort(due, BY_DEADLINE);
      }
      return due;
    }
  }

  private void collect(int bucket, long targetTick, List<TimerEntry> due) {
    long remainingMin = Long.MAX_VALUE;
    TimerEntry e = heads[bucket];
    while (e != null) {
      TimerEntry next = e.next;
      if (e.tick <= targetTick) {
        unlink(e);
        size--;
        e.state = TimerEntry.FIRED;
        due.add(e);
      } else if (e.tick < remainingMin) {
        remainingMin = e.tick;
      }
      e = next;
    }
    if (heads[bucket] != null) {
      minTick[bucket] = remainingMin;
      dirty[bucket] = false;
    }
  }

  /**
   * Called by the timer thread when it has nothing due. Returns the time to wake up at (the tick of
   * the earliest pending timer), or {@link #EXIT} if the wheel is empty, in which case the thread
   * is considered stopped and the next {@link #schedule} asks for a new one.
   */
  long prepareSleep() {
    synchronized (lock) {
      if (size == 0) {
        threadRunning = false;
        sleepingUntilTick = Long.MIN_VALUE;
        return EXIT;
      }
      long best = Long.MAX_VALUE;
      for (int b = 0; b < heads.length; b++) {
        if (heads[b] == null) {
          continue;
        }
        if (dirty[b]) {
          recompute(b);
        }
        if (minTick[b] < best) {
          best = minTick[b];
        }
      }
      sleepingUntilTick = best;
      return startNanos + best * tickNanos;
    }
  }

  private void recompute(int bucket) {
    long min = Long.MAX_VALUE;
    for (TimerEntry e = heads[bucket]; e != null; e = e.next) {
      if (e.tick < min) {
        min = e.tick;
      }
    }
    minTick[bucket] = min;
    dirty[bucket] = false;
  }

  /**
   * The time of the earliest pending timer's tick, or {@link #EXIT} if none, without changing any
   * state. Used by manual timers, which have no thread.
   */
  long peekNextWake() {
    synchronized (lock) {
      if (size == 0) {
        return EXIT;
      }
      long best = Long.MAX_VALUE;
      for (int b = 0; b < heads.length; b++) {
        if (heads[b] == null) {
          continue;
        }
        if (dirty[b]) {
          recompute(b);
        }
        if (minTick[b] < best) {
          best = minTick[b];
        }
      }
      return startNanos + best * tickNanos;
    }
  }

  /** The timer thread is awake again. */
  void awake() {
    synchronized (lock) {
      sleepingUntilTick = Long.MIN_VALUE;
    }
  }

  /** Called when a thread that {@link #schedule} asked for could not be started. */
  void threadFailedToStart() {
    synchronized (lock) {
      threadRunning = false;
    }
  }

  /** Closes the wheel and removes every timer. Later {@link #schedule} calls return null. */
  List<TimerEntry> closeAndDrain() {
    synchronized (lock) {
      closed = true;
      List<TimerEntry> all = new ArrayList<>();
      for (int i = 0; i < heads.length; i++) {
        TimerEntry e = heads[i];
        while (e != null) {
          TimerEntry next = e.next;
          unlink(e);
          e.state = TimerEntry.DISCARDED;
          all.add(e);
          e = next;
        }
      }
      size = 0;
      java.util.Arrays.fill(dirty, false);
      Collections.sort(all, BY_DEADLINE);
      return all;
    }
  }

  int size() {
    synchronized (lock) {
      return size;
    }
  }

  boolean isClosed() {
    synchronized (lock) {
      return closed;
    }
  }

  int state(TimerEntry entry) {
    synchronized (lock) {
      return entry.state;
    }
  }

  int nonEmptyBuckets() {
    synchronized (lock) {
      int n = 0;
      for (TimerEntry head : heads) {
        if (head != null) {
          n++;
        }
      }
      return n;
    }
  }

  private void link(TimerEntry e) {
    int b = (int) (e.tick & mask);
    e.bucket = b;
    e.prev = null;
    e.next = heads[b];
    if (heads[b] != null) {
      heads[b].prev = e;
      if (!dirty[b] && e.tick < minTick[b]) {
        minTick[b] = e.tick;
      }
    } else {
      minTick[b] = e.tick;
      dirty[b] = false;
    }
    heads[b] = e;
  }

  private void unlink(TimerEntry e) {
    if (e.prev != null) {
      e.prev.next = e.next;
    } else {
      heads[e.bucket] = e.next;
    }
    if (e.next != null) {
      e.next.prev = e.prev;
    }
    e.prev = null;
    e.next = null;
    if (heads[e.bucket] == null) {
      dirty[e.bucket] = false;
    } else if (!dirty[e.bucket] && e.tick == minTick[e.bucket]) {
      dirty[e.bucket] = true;
    }
  }
}
