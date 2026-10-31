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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Three FIFO queues (one per {@link Priority}) with aging. Not part of the public API.
 *
 * <p>Effective priority is {@code min(HIGH, base + floor(waited / agingStep))}, so a waiting task
 * gains one level per aging step and any task reaches HIGH after two steps. The best task has the
 * highest effective priority; ties go to the task that was queued first (then to the lower sequence
 * number). Because tasks of one level are FIFO, the head of a level always dominates the rest of
 * that level, so only the three heads are compared. All methods are thread-safe.
 */
final class RankedQueue {

  private final List<ArrayDeque<EngineTask>> levels = new ArrayList<>(3);

  RankedQueue() {
    for (int i = 0; i < 3; i++) {
      levels.add(new ArrayDeque<>());
    }
  }

  /** Effective priority level (0..2) of a task at time {@code now}. */
  static int effective(EngineTask task, long now, long agingStep) {
    int base = task.priority.level();
    if (agingStep <= 0) {
      return base;
    }
    long waited = now - task.enqueueNanos;
    if (waited <= 0) {
      return base;
    }
    long boost = Math.min(2L, waited / agingStep);
    return (int) Math.min(2L, base + boost);
  }

  /** Whether {@code a} must run before {@code b}. */
  static boolean better(EngineTask a, EngineTask b, long now, long agingStep) {
    int ea = effective(a, now, agingStep);
    int eb = effective(b, now, agingStep);
    if (ea != eb) {
      return ea > eb;
    }
    return older(a, b);
  }

  /** Whether {@code a} was queued before {@code b}. */
  static boolean older(EngineTask a, EngineTask b) {
    long d = a.enqueueNanos - b.enqueueNanos;
    if (d != 0) {
      return d < 0;
    }
    return a.seq < b.seq;
  }

  synchronized void add(EngineTask task) {
    levels.get(task.priority.level()).addLast(task);
  }

  synchronized EngineTask peekBest(long now, long agingStep) {
    EngineTask best = null;
    for (ArrayDeque<EngineTask> level : levels) {
      EngineTask head = level.peekFirst();
      if (head != null && (best == null || better(head, best, now, agingStep))) {
        best = head;
      }
    }
    return best;
  }

  synchronized EngineTask pollBest(long now, long agingStep) {
    EngineTask best = peekBest(now, agingStep);
    if (best == null) {
      return null;
    }
    levels.get(best.priority.level()).pollFirst();
    return best;
  }

  synchronized EngineTask peekOldest() {
    EngineTask oldest = null;
    for (ArrayDeque<EngineTask> level : levels) {
      EngineTask head = level.peekFirst();
      if (head != null && (oldest == null || older(head, oldest))) {
        oldest = head;
      }
    }
    return oldest;
  }

  synchronized EngineTask pollOldest() {
    EngineTask oldest = peekOldest();
    if (oldest == null) {
      return null;
    }
    levels.get(oldest.priority.level()).pollFirst();
    return oldest;
  }

  /** Removes this exact task. Returns false if it is not queued. */
  synchronized boolean remove(EngineTask task) {
    ArrayDeque<EngineTask> level = levels.get(task.priority.level());
    for (java.util.Iterator<EngineTask> it = level.iterator(); it.hasNext(); ) {
      if (it.next() == task) {
        it.remove();
        return true;
      }
    }
    return false;
  }

  synchronized int drainTo(Collection<EngineTask> sink) {
    int n = 0;
    for (ArrayDeque<EngineTask> level : levels) {
      n += level.size();
      sink.addAll(level);
      level.clear();
    }
    return n;
  }

  synchronized int size() {
    int n = 0;
    for (ArrayDeque<EngineTask> level : levels) {
      n += level.size();
    }
    return n;
  }
}
