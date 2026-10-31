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

import java.util.Objects;

/**
 * Drives the runs of a periodic job. Not part of the public API.
 *
 * <p>At most one timer is pending at any time, and the next one is created only when the current
 * run has ended, so two runs of the same job never overlap. A tick is a timer that came due; it is
 * handed to the lane through {@link JobImpl#start}.
 */
final class PeriodicDriver implements Timeout {

  private final JobImpl<Void> job;
  private final Cadence cadence;
  private final Lane lane;
  private final Work<Void> work;
  private final long periodNanos;
  private final boolean fixedRate;
  private final RepeatFailure onFailure;
  private long firstNanos;
  private volatile long slotIndex;
  private volatile TimerEntry current;

  PeriodicDriver(
      JobImpl<Void> job,
      Cadence cadence,
      Lane lane,
      Action action,
      long periodNanos,
      boolean fixedRate,
      RepeatFailure onFailure) {
    this.job = job;
    this.cadence = cadence;
    this.lane = lane;
    this.work =
        cp -> {
          action.run(cp);
          return null;
        };
    this.periodNanos = periodNanos;
    this.fixedRate = fixedRate;
    this.onFailure = Objects.requireNonNull(onFailure, "onFailure");
  }

  RepeatFailure onFailure() {
    return onFailure;
  }

  /** Starts the schedule: the first run is after {@code initialDelayNanos} (at once if zero). */
  void begin(long initialDelayNanos) {
    long now = cadence.nanoTime();
    firstNanos = CadenceMath.deadline(now, initialDelayNanos);
    slotIndex = 0;
    if (initialDelayNanos <= 0) {
      job.start(lane, work);
    } else {
      scheduleAt(firstNanos);
    }
  }

  /** The current run ended normally (or its failure is to be survived): plan the next one. */
  void scheduleNext() {
    long now = cadence.nanoTime();
    long next;
    if (fixedRate) {
      long k = CadenceMath.nextFixedRateIndex(firstNanos, periodNanos, slotIndex, now);
      slotIndex = k;
      next = CadenceMath.slotTime(firstNanos, periodNanos, k);
    } else {
      next = CadenceMath.nextFixedDelay(now, periodNanos);
    }
    scheduleAt(next);
  }

  private void scheduleAt(long deadlineNanos) {
    TimerEntry entry = cadence.schedule(deadlineNanos, this);
    if (entry == null) {
      job.completeIfPending(
          Outcome.failure(
              new LaneClosedException("the cadence of " + cadence.engine() + " is shut down")));
      return;
    }
    // A cancel that ran before the timer existed found nothing to remove: remove it now.
    if (job.isDone()) {
      entry.cancel();
    }
  }

  @Override
  public void scheduled(TimerEntry entry) {
    // Stored under the wheel lock, before the timer is visible: a cancel that can see the timer in
    // pending() can also find it here.
    current = entry;
  }

  /** Removes the pending timer, if any. Called when the job is cancelled between two runs. */
  void cancelCurrent() {
    TimerEntry entry = current;
    if (entry != null) {
      entry.cancel();
    }
  }

  /**
   * A tick could not be handed to the lane (it refused the task, or discarded it later). The job is
   * pending, no run started.
   */
  void tickRejected(Throwable reason) {
    if (onFailure == RepeatFailure.STOP || isTerminal(reason)) {
      job.completeIfPending(Outcome.failure(reason));
      return;
    }
    // CONTINUE and a refusal that may pass: report it once, skip this tick, go on.
    if (!job.isDone()) {
      job.realm().report(reason);
      scheduleNext();
    }
  }

  /** The lane will never accept work again: engine shutdown or a host that stopped. */
  static boolean isTerminal(Throwable reason) {
    return reason instanceof LaneClosedException
        || (reason instanceof DroppedException dropped
            && dropped.reason() == DroppedException.Reason.SHUTDOWN);
  }

  @Override
  public void fire() {
    job.start(lane, work);
  }

  @Override
  public void discard(Throwable reason) {
    job.completeIfPending(Outcome.failure(reason));
  }
}
