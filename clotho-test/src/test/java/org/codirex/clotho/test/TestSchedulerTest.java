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

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.codirex.clotho.DroppedException;
import org.codirex.clotho.Job;
import org.codirex.clotho.Outcome;
import org.codirex.clotho.RepeatFailure;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Exact, instant tests of timers with a controlled clock: no threads, no sleeping. */
class TestSchedulerTest {

  private static final long MS = TimeUnit.MILLISECONDS.toNanos(1);

  private final TestScheduler s = new TestScheduler();

  @AfterEach
  void close() {
    s.close();
  }

  private long nowMs() {
    return s.clock().nanos() / MS;
  }

  @Test
  void afterFiresExactlyAtItsDeadline() {
    Job<String> job = s.realm().after(s.lane(), 250, TimeUnit.MILLISECONDS, cp -> "done");
    s.advance(249, TimeUnit.MILLISECONDS);
    assertThat(job.isDone()).isFalse();
    assertThat(s.pendingTimers()).isEqualTo(1);
    s.advance(1, TimeUnit.MILLISECONDS);
    assertThat(job.outcomeOrNull()).isEqualTo(Outcome.success("done"));
    assertThat(s.pendingTimers()).isEqualTo(0);
    assertThat(nowMs()).isEqualTo(250);
  }

  @Test
  void everyRunsOncePerPeriodAndAdvanceVisitsEachRun() {
    List<Long> starts = new ArrayList<>();
    s.realm().every(s.lane(), 0, 1, TimeUnit.SECONDS, cp -> starts.add(nowMs()));
    s.advance(5, TimeUnit.SECONDS);
    assertThat(starts).containsExactly(0L, 1000L, 2000L, 3000L, 4000L, 5000L);
  }

  @Test
  void aFixedDelayWaitsAFullPeriodAfterTheSimulatedRunTime() {
    List<Long> starts = new ArrayList<>();
    s.realm()
        .every(
            s.lane(),
            0,
            100,
            TimeUnit.MILLISECONDS,
            cp -> {
              starts.add(nowMs());
              s.clock().advance(30, TimeUnit.MILLISECONDS); // the run takes 30 ms
            });
    s.advance(300, TimeUnit.MILLISECONDS);
    assertThat(starts).containsExactly(0L, 130L, 260L);
  }

  @Test
  void aFixedRateSkipsTheSlotsAMissedRunCovered() {
    List<Long> starts = new ArrayList<>();
    s.realm()
        .atRate(
            s.lane(),
            0,
            100,
            TimeUnit.MILLISECONDS,
            cp -> {
              starts.add(nowMs());
              s.clock().advance(250, TimeUnit.MILLISECONDS); // longer than two periods
            });
    s.advance(700, TimeUnit.MILLISECONDS);
    // slots 100 and 200 were missed and skipped; the next runs are at the slots 300 and 600
    assertThat(starts).containsExactly(0L, 300L, 600L);
  }

  @Test
  void aFixedRateKeepsItsScheduleWhenRunsAreShort() {
    List<Long> starts = new ArrayList<>();
    s.realm().atRate(s.lane(), 50, 100, TimeUnit.MILLISECONDS, cp -> starts.add(nowMs()));
    s.advance(450, TimeUnit.MILLISECONDS);
    assertThat(starts).containsExactly(50L, 150L, 250L, 350L, 450L);
  }

  @Test
  void aJumpLikeADeviceSleepFiresLateOnceAndASkippedSlotIsNotMadeUp() {
    List<Long> starts = new ArrayList<>();
    s.realm().atRate(s.lane(), 0, 100, TimeUnit.MILLISECONDS, cp -> starts.add(nowMs()));
    s.settle();
    s.jump(1050, TimeUnit.MILLISECONDS);
    assertThat(starts).containsExactly(0L, 1050L);
    s.advance(50, TimeUnit.MILLISECONDS);
    assertThat(starts).containsExactly(0L, 1050L, 1100L);
  }

  @Test
  void continueReportsEachFailureOnceAndStopEndsTheJob() {
    AtomicInteger runs = new AtomicInteger();
    Job<Void> surviving =
        s.realm()
            .every(
                s.lane(),
                0,
                10,
                TimeUnit.MILLISECONDS,
                RepeatFailure.CONTINUE,
                cp -> {
                  if (runs.incrementAndGet() <= 3) {
                    throw new IllegalStateException("run " + runs.get());
                  }
                });
    s.advance(100, TimeUnit.MILLISECONDS);
    assertThat(s.handler().errors()).hasSize(3);
    assertThat(surviving.isDone()).isFalse();
    assertThat(runs.get()).isEqualTo(11);
    surviving.cancel();

    IOException boom = new IOException("fatal");
    AtomicInteger stopRuns = new AtomicInteger();
    Job<Void> stopping =
        s.realm()
            .every(
                s.lane(),
                0,
                10,
                TimeUnit.MILLISECONDS,
                cp -> {
                  stopRuns.incrementAndGet();
                  throw boom;
                });
    s.advance(100, TimeUnit.MILLISECONDS);
    assertThat(stopping.outcomeOrNull().failureOrNull()).isSameAs(boom);
    assertThat(stopRuns.get()).isEqualTo(1);
  }

  @Test
  void aLaneThatRefusesIsClassifiedLikeInProduction() {
    AtomicInteger runs = new AtomicInteger();
    s.lane().refuseWith(new java.util.concurrent.RejectedExecutionException("full"));
    Job<Void> surviving =
        s.realm()
            .every(
                s.lane(),
                0,
                10,
                TimeUnit.MILLISECONDS,
                RepeatFailure.CONTINUE,
                cp -> runs.incrementAndGet());
    s.advance(30, TimeUnit.MILLISECONDS);
    assertThat(runs.get()).isEqualTo(0);
    assertThat(surviving.isDone()).isFalse();
    assertThat(s.handler().errors().size() >= 3).isTrue();
    s.lane().refuseWith(null);
    s.advance(10, TimeUnit.MILLISECONDS);
    assertThat(runs.get()).isEqualTo(1);
  }

  @Test
  void cancelAndCloseRemoveTimersAtOnce() {
    Job<Void> job = s.realm().every(s.lane(), 100, 100, TimeUnit.MILLISECONDS, cp -> {});
    Job<String> once = s.realm().after(s.lane(), 1, TimeUnit.HOURS, cp -> "never");
    assertThat(s.pendingTimers()).isEqualTo(2);
    assertThat(job.cancel()).isTrue();
    assertThat(s.pendingTimers()).isEqualTo(1);
    s.realm().closeAsync();
    assertThat(s.pendingTimers()).isEqualTo(0);
    assertThat(once.outcomeOrNull().isCancelled()).isTrue();
  }

  @Test
  void shuttingTheEngineDownDiscardsPendingTimers() {
    Job<String> once = s.realm().after(s.lane(), 1, TimeUnit.HOURS, cp -> "never");
    s.engine().shutdown();
    assertThat(once.outcomeOrNull().failureOrNull()).isInstanceOf(DroppedException.class);
    assertThat(s.pendingTimers()).isEqualTo(0);
  }

  @Test
  void aTickOfTenMillisecondsRoundsDeadlinesUpToTheTick() {
    try (TestScheduler coarse = new TestScheduler(10, TimeUnit.MILLISECONDS)) {
      Job<String> job = coarse.realm().after(coarse.lane(), 25, TimeUnit.MILLISECONDS, cp -> "x");
      coarse.advance(29, TimeUnit.MILLISECONDS);
      assertThat(job.isDone()).isFalse();
      coarse.advance(1, TimeUnit.MILLISECONDS);
      assertThat(job.isDone()).isTrue();
      assertThat(coarse.clock().nanos()).isEqualTo(30 * MS);
    }
  }

  @Test
  void chainsOfJobsSettleWithoutAnyWaiting() {
    Job<Integer> chained =
        s.realm()
            .after(s.lane(), 10, TimeUnit.MILLISECONDS, cp -> 20)
            .then(s.lane(), (v, cp) -> v + 1)
            .then(s.lane(), (v, cp) -> v * 2);
    s.advance(10, TimeUnit.MILLISECONDS);
    assertThat(chained.outcomeOrNull()).isEqualTo(Outcome.success(42));
  }
}
