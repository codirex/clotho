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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.codirex.clotho.support.TestKit.awaitTrue;
import static org.codirex.clotho.support.TestKit.join;
import static org.codirex.clotho.support.TestKit.start;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.codirex.clotho.support.ManualLane;
import org.codirex.clotho.support.RecordingHandler;
import org.codirex.clotho.support.ScriptedLane;
import org.codirex.clotho.support.ThreadLane;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Periodic jobs with real threads and the real clock. */
class PeriodicTest extends EngineTestBase {

  private static final long MS = TimeUnit.MILLISECONDS.toNanos(1);

  private final RecordingHandler handler = new RecordingHandler();
  private final List<ThreadLane> threadLanes = new ArrayList<>();

  @AfterEach
  void closeThreadLanes() {
    for (ThreadLane l : threadLanes) {
      l.close();
    }
  }

  private Engine engine() {
    return track(builder().timerTick(1, TimeUnit.MILLISECONDS).build());
  }

  private Realm realm(Engine engine) {
    return Realm.builder("periodic").cadence(engine.cadence()).uncaughtHandler(handler).open();
  }

  private ThreadLane threads() {
    ThreadLane l = new ThreadLane(2);
    threadLanes.add(l);
    return l;
  }

  private static void spinFor(long nanos) {
    long end = System.nanoTime() + nanos;
    while (System.nanoTime() < end) {
      Thread.yield();
    }
  }

  @Test
  void everyWaitsOnePeriodAfterTheEndOfEachRunAndNeverOverlaps() throws Exception {
    Engine engine = engine();
    Realm realm = realm(engine);
    List<long[]> runs = Collections.synchronizedList(new ArrayList<>());
    AtomicReference<Job<Void>> self = new AtomicReference<>();
    CountDownLatch finished = new CountDownLatch(1);
    Job<Void> job =
        realm.every(
            engine.io(),
            0,
            40,
            TimeUnit.MILLISECONDS,
            cp -> {
              long begin = System.nanoTime();
              spinFor(30 * MS);
              runs.add(new long[] {begin, System.nanoTime()});
              if (runs.size() == 4) {
                self.get().cancel();
                finished.countDown();
              }
            });
    self.set(job);
    assertThat(finished.await(20, TimeUnit.SECONDS)).isTrue();
    assertThat(job.await(20, TimeUnit.SECONDS).isCancelled()).isTrue();
    assertThat(runs).hasSize(4);
    for (int i = 0; i + 1 < runs.size(); i++) {
      long gap = runs.get(i + 1)[0] - runs.get(i)[1];
      assertThat(gap >= 40 * MS).isTrue(); // one period after the END of the previous run
    }
    realm.close();
  }

  @Test
  void twoRunsOfTheSameJobNeverOverlapEvenWhenARunIsLongerThanThePeriod() throws Exception {
    Engine engine = engine();
    Realm realm = realm(engine);
    for (boolean fixedRate : new boolean[] {false, true}) {
      AtomicInteger inside = new AtomicInteger();
      AtomicInteger maxInside = new AtomicInteger();
      AtomicInteger runs = new AtomicInteger();
      AtomicReference<Job<Void>> self = new AtomicReference<>();
      CountDownLatch finished = new CountDownLatch(1);
      Action action =
          cp -> {
            maxInside.accumulateAndGet(inside.incrementAndGet(), Math::max);
            spinFor(25 * MS);
            inside.decrementAndGet();
            if (runs.incrementAndGet() == 6) {
              self.get().cancel();
              finished.countDown();
            }
          };
      Job<Void> job =
          fixedRate
              ? realm.atRate(engine.io(), 0, 5, TimeUnit.MILLISECONDS, action)
              : realm.every(engine.io(), 0, 5, TimeUnit.MILLISECONDS, action);
      self.set(job);
      assertThat(finished.await(30, TimeUnit.SECONDS)).isTrue();
      job.await(20, TimeUnit.SECONDS);
      assertThat(maxInside.get()).isEqualTo(1);
    }
    realm.close();
  }

  @Test
  void atRateSkipsMissedSlotsInsteadOfCatchingUp() throws Exception {
    Engine engine = engine();
    Realm realm = realm(engine);
    List<Long> starts = Collections.synchronizedList(new ArrayList<>());
    AtomicReference<Job<Void>> self = new AtomicReference<>();
    CountDownLatch finished = new CountDownLatch(1);
    Job<Void> job =
        realm.atRate(
            engine.io(),
            0,
            50,
            TimeUnit.MILLISECONDS,
            cp -> {
              starts.add(System.nanoTime());
              spinFor(120 * MS);
              if (starts.size() == 4) {
                self.get().cancel();
                finished.countDown();
              }
            });
    self.set(job);
    assertThat(finished.await(30, TimeUnit.SECONDS)).isTrue();
    job.await(20, TimeUnit.SECONDS);
    // a run of 120 ms with a 50 ms period misses the slots at +50 and +100: the next run is at
    // the slot +150. Catching up would start it at +120.
    for (int i = 0; i + 1 < starts.size(); i++) {
      long gap = starts.get(i + 1) - starts.get(i);
      assertThat(gap >= 140 * MS).isTrue();
    }
    realm.close();
  }

  @Test
  void everyWithAFixedDelayIncludesTheRunTimeInTheGap() throws Exception {
    Engine engine = engine();
    Realm realm = realm(engine);
    List<Long> starts = Collections.synchronizedList(new ArrayList<>());
    AtomicReference<Job<Void>> self = new AtomicReference<>();
    CountDownLatch finished = new CountDownLatch(1);
    Job<Void> job =
        realm.every(
            engine.io(),
            0,
            50,
            TimeUnit.MILLISECONDS,
            cp -> {
              starts.add(System.nanoTime());
              spinFor(120 * MS);
              if (starts.size() == 3) {
                self.get().cancel();
                finished.countDown();
              }
            });
    self.set(job);
    assertThat(finished.await(30, TimeUnit.SECONDS)).isTrue();
    job.await(20, TimeUnit.SECONDS);
    for (int i = 0; i + 1 < starts.size(); i++) {
      assertThat(starts.get(i + 1) - starts.get(i) >= 170 * MS).isTrue();
    }
    realm.close();
  }

  @Test
  void atRateNeverBurstsAndNeverRunsEarlyWhenRunsAreShort() throws Exception {
    Engine engine = engine();
    Realm realm = realm(engine);
    List<Long> starts = Collections.synchronizedList(new ArrayList<>());
    AtomicReference<Job<Void>> self = new AtomicReference<>();
    CountDownLatch finished = new CountDownLatch(1);
    Job<Void> job =
        realm.atRate(
            engine.io(),
            0,
            20,
            TimeUnit.MILLISECONDS,
            cp -> {
              starts.add(System.nanoTime());
              if (starts.size() == 10) {
                self.get().cancel();
                finished.countDown();
              }
            });
    self.set(job);
    assertThat(finished.await(60, TimeUnit.SECONDS)).isTrue();
    job.await(20, TimeUnit.SECONDS);
    // Lateness is not asserted: on a loaded machine a late tick makes the job skip a slot, which
    // is correct. What must never happen is a run before its slot, or a burst of catch-up runs.
    for (int i = 0; i + 1 < starts.size(); i++) {
      assertThat(starts.get(i + 1) - starts.get(i) >= 15 * MS).isTrue();
    }
    assertThat(starts.get(9) - starts.get(0) >= 9 * 20 * MS - 5 * MS).isTrue();
    realm.close();
  }

  @Test
  void theInitialDelayIsHonouredAndZeroStartsAtOnce() throws Exception {
    Engine engine = engine();
    Realm realm = realm(engine);
    AtomicReference<Job<Void>> delayed = new AtomicReference<>();
    AtomicInteger delayedRuns = new AtomicInteger();
    long begin = System.nanoTime();
    CountDownLatch first = new CountDownLatch(1);
    AtomicReference<Long> firstAt = new AtomicReference<>();
    delayed.set(
        realm.every(
            engine.io(),
            60,
            100_000,
            TimeUnit.MILLISECONDS,
            cp -> {
              delayedRuns.incrementAndGet();
              firstAt.set(System.nanoTime());
              first.countDown();
            }));
    assertThat(first.await(20, TimeUnit.SECONDS)).isTrue();
    assertThat(firstAt.get() - begin >= 60 * MS).isTrue();
    delayed.get().cancel();

    CountDownLatch immediate = new CountDownLatch(1);
    Job<Void> now =
        realm.every(engine.io(), 0, 100_000, TimeUnit.MILLISECONDS, cp -> immediate.countDown());
    assertThat(immediate.await(20, TimeUnit.SECONDS)).isTrue();
    now.cancel();
    realm.close();
  }

  @Test
  void stopEndsTheJobAtTheFirstFailureAndNoRunFollows() throws Exception {
    Engine engine = engine();
    Realm realm = realm(engine);
    IOException boom = new IOException("third run");
    AtomicInteger runs = new AtomicInteger();
    Job<Void> job =
        realm.every(
            engine.io(),
            0,
            3,
            TimeUnit.MILLISECONDS,
            cp -> {
              if (runs.incrementAndGet() == 3) {
                throw boom;
              }
            });
    Outcome<Void> outcome = job.await(20, TimeUnit.SECONDS);
    assertThat(outcome.failureOrNull()).isSameAs(boom);
    assertThat(new CountDownLatch(1).await(60, TimeUnit.MILLISECONDS)).isFalse();
    assertThat(runs.get()).isEqualTo(3);
    assertThat(engine.cadence().pending()).isEqualTo(0);
    assertThat(handler.errors()).isEmpty();
    realm.close();
  }

  @Test
  void continueReportsEachFailureExactlyOnceAndKeepsGoing() throws Exception {
    Engine engine = engine();
    Realm realm = realm(engine);
    AtomicInteger runs = new AtomicInteger();
    List<Throwable> thrown = Collections.synchronizedList(new ArrayList<>());
    AtomicReference<Job<Void>> self = new AtomicReference<>();
    Job<Void> job =
        realm.every(
            engine.io(),
            0,
            3,
            TimeUnit.MILLISECONDS,
            RepeatFailure.CONTINUE,
            cp -> {
              int n = runs.incrementAndGet();
              if (n <= 5) {
                IllegalStateException e = new IllegalStateException("run " + n);
                thrown.add(e);
                throw e;
              }
              self.get().cancel();
            });
    self.set(job);
    assertThat(job.await(20, TimeUnit.SECONDS).isCancelled()).isTrue();
    assertThat(runs.get()).isEqualTo(6);
    assertThat(handler.errors()).hasSize(5);
    for (int i = 0; i < 5; i++) {
      assertThat(handler.errors().get(i)).isSameAs(thrown.get(i));
    }
    Set<Throwable> distinct = new HashSet<>(handler.errors());
    assertThat(distinct).hasSize(5);
    realm.close();
  }

  @Test
  void aStopFailureOfAFailFastRealmFailsTheRealmButAReportedOneDoesNot() throws Exception {
    Engine engine = engine();
    Realm failFast =
        Realm.builder("ff")
            .policy(RealmPolicy.FAIL_FAST)
            .cadence(engine.cadence())
            .uncaughtHandler(handler)
            .open();
    AtomicInteger runs = new AtomicInteger();
    AtomicReference<Job<Void>> self = new AtomicReference<>();
    Job<Void> continuing =
        failFast.every(
            engine.io(),
            0,
            3,
            TimeUnit.MILLISECONDS,
            RepeatFailure.CONTINUE,
            cp -> {
              if (runs.incrementAndGet() < 3) {
                throw new IllegalStateException("survived");
              }
              self.get().cancel();
            });
    self.set(continuing);
    continuing.await(20, TimeUnit.SECONDS);
    assertThat(failFast.failure()).isNull();
    IOException fatal = new IOException("fatal");
    Job<Void> stopping =
        failFast.every(
            engine.io(),
            0,
            3,
            TimeUnit.MILLISECONDS,
            cp -> {
              throw fatal;
            });
    assertThat(stopping.await(20, TimeUnit.SECONDS).failureOrNull()).isSameAs(fatal);
    assertThat(failFast.failure()).isSameAs(fatal);
    failFast.close();
  }

  // ---- how a refused tick is classified ----

  private static Action counting(AtomicInteger runs) {
    return cp -> runs.incrementAndGet();
  }

  @Test
  void stopEndsTheJobWhenALaneRefusesATick() throws Exception {
    Engine engine = engine();
    Realm realm = realm(engine);
    ScriptedLane lane = new ScriptedLane(threads());
    lane.mode(ScriptedLane.Mode.FULL);
    AtomicInteger runs = new AtomicInteger();
    Job<Void> job = realm.every(lane, 0, 3, TimeUnit.MILLISECONDS, counting(runs));
    Throwable cause = job.await(20, TimeUnit.SECONDS).failureOrNull();
    assertThat(cause).isInstanceOf(RejectedExecutionException.class);
    assertThat(cause).isNotInstanceOf(LaneClosedException.class);
    assertThat(cause).isNotInstanceOf(DroppedException.class);
    assertThat(lane.calls()).isEqualTo(1);
    assertThat(runs.get()).isEqualTo(0);
    realm.close();
  }

  @Test
  void continueSkipsTicksRefusedBecauseOfAFullQueueAndRunsAgainWhenRoomReturns() throws Exception {
    Engine engine = engine();
    Realm realm = realm(engine);
    ScriptedLane lane = new ScriptedLane(threads());
    lane.mode(ScriptedLane.Mode.FULL);
    AtomicInteger runs = new AtomicInteger();
    Job<Void> job =
        realm.every(lane, 0, 3, TimeUnit.MILLISECONDS, RepeatFailure.CONTINUE, counting(runs));
    awaitTrue(() -> handler.errors().size() >= 3);
    assertThat(job.isDone()).isFalse();
    assertThat(runs.get()).isEqualTo(0);
    lane.mode(ScriptedLane.Mode.ACCEPT);
    awaitTrue(() -> runs.get() >= 2);
    assertThat(job.cancel() || job.isDone()).isTrue();
    job.await(20, TimeUnit.SECONDS);
    for (Throwable t : handler.errors()) {
      assertThat(t).isInstanceOf(RejectedExecutionException.class);
      assertThat(t).isNotInstanceOf(LaneClosedException.class);
    }
    realm.close();
  }

  @Test
  void aClosedLaneEndsTheJobEvenWithContinueAndIsNotReportedAsASurvivedFailure() throws Exception {
    Engine engine = engine();
    Realm realm = realm(engine);
    ScriptedLane lane = new ScriptedLane(threads());
    lane.mode(ScriptedLane.Mode.CLOSED);
    Job<Void> job =
        realm.every(
            lane,
            0,
            3,
            TimeUnit.MILLISECONDS,
            RepeatFailure.CONTINUE,
            counting(new AtomicInteger()));
    assertThat(job.await(20, TimeUnit.SECONDS).failureOrNull())
        .isInstanceOf(LaneClosedException.class);
    assertThat(handler.errors()).isEmpty();
    realm.close();
  }

  @Test
  void aTickDroppedByAFullQueueIsSurvivedButOneDiscardedByShutdownIsFinal() throws Exception {
    Engine engine = engine();
    Realm realm = realm(engine);
    ScriptedLane overflow = new ScriptedLane(threads());
    overflow.mode(ScriptedLane.Mode.DISCARD_OVERFLOW);
    AtomicInteger runs = new AtomicInteger();
    Job<Void> surviving =
        realm.every(overflow, 0, 3, TimeUnit.MILLISECONDS, RepeatFailure.CONTINUE, counting(runs));
    awaitTrue(() -> handler.errors().size() >= 2);
    overflow.mode(ScriptedLane.Mode.ACCEPT);
    awaitTrue(() -> runs.get() >= 1);
    surviving.cancel();
    surviving.await(20, TimeUnit.SECONDS);
    for (Throwable t : handler.errors()) {
      assertThat(t).isInstanceOf(DroppedException.class);
      assertThat(((DroppedException) t).reason()).isEqualTo(DroppedException.Reason.QUEUE_OVERFLOW);
    }
    int reported = handler.errors().size();

    ScriptedLane shutdown = new ScriptedLane(threads());
    shutdown.mode(ScriptedLane.Mode.DISCARD_SHUTDOWN);
    Job<Void> fatal =
        realm.every(
            shutdown,
            0,
            3,
            TimeUnit.MILLISECONDS,
            RepeatFailure.CONTINUE,
            counting(new AtomicInteger()));
    Throwable cause = fatal.await(20, TimeUnit.SECONDS).failureOrNull();
    assertThat(cause).isInstanceOf(DroppedException.class);
    assertThat(((DroppedException) cause).reason()).isEqualTo(DroppedException.Reason.SHUTDOWN);
    assertThat(handler.errors()).hasSize(reported);

    ScriptedLane stopping = new ScriptedLane(threads());
    stopping.mode(ScriptedLane.Mode.DISCARD_OVERFLOW);
    Job<Void> stopped =
        realm.every(stopping, 0, 3, TimeUnit.MILLISECONDS, counting(new AtomicInteger()));
    assertThat(stopped.await(20, TimeUnit.SECONDS).failureOrNull())
        .isInstanceOf(DroppedException.class);
    realm.close();
  }

  // ---- cancellation ----

  @Test
  void cancelPreventsATickThatIsAlreadyQueuedOnTheLaneAndNoTimerIsPendingMeanwhile()
      throws Exception {
    Engine engine = engine();
    Realm realm = realm(engine);
    ManualLane manual = new ManualLane();
    AtomicInteger runs = new AtomicInteger();
    Job<Void> job = realm.every(manual, 1, 3_600_000, TimeUnit.MILLISECONDS, counting(runs));
    // the first tick comes after 1 ms; the period is an hour
    awaitTrue(() -> manual.size() == 1);
    assertThat(engine.cadence().pending()).isEqualTo(0); // one outstanding tick at most
    assertThat(job.cancel()).isTrue();
    manual.runAll();
    assertThat(runs.get()).isEqualTo(0);
    assertThat(job.outcomeOrNull().isCancelled()).isTrue();
    realm.close();
  }

  @Test
  void cancelBetweenTwoRunsRemovesThePendingTimerAtOnce() throws Exception {
    Engine engine = engine();
    Realm realm = realm(engine);
    AtomicInteger runs = new AtomicInteger();
    Job<Void> job = realm.every(engine.io(), 0, 1, TimeUnit.HOURS, counting(runs));
    awaitTrue(() -> runs.get() == 1 && engine.cadence().pending() == 1);
    assertThat(job.cancel()).isTrue();
    assertThat(engine.cadence().pending()).isEqualTo(0);
    assertThat(job.isDone()).isTrue();
    assertThat(job.outcomeOrNull().isCancelled()).isTrue();
    realm.close();
  }

  @Test
  void cancelDuringARunLetsItFinishAndStopsTheSchedule() throws Exception {
    Engine engine = engine();
    Realm realm = realm(engine);
    Gate gate = new Gate();
    AtomicInteger runs = new AtomicInteger();
    AtomicBoolean seenCancel = new AtomicBoolean();
    Job<Void> job =
        realm.every(
            engine.io(),
            0,
            3,
            TimeUnit.MILLISECONDS,
            cp -> {
              runs.incrementAndGet();
              gate.started.countDown();
              for (; ; ) {
                try {
                  gate.release.await();
                  break;
                } catch (InterruptedException e) {
                  // keeps running: this run does not cooperate with interrupts
                }
              }
              seenCancel.set(cp.isCancelled());
            });
    gate.awaitStarted();
    assertThat(job.cancel()).isTrue();
    assertThat(job.cancel()).isFalse();
    assertThat(job.isDone()).isFalse();
    assertThat(job.isCancellationRequested()).isTrue();
    gate.open();
    assertThat(job.await(20, TimeUnit.SECONDS).isCancelled()).isTrue();
    assertThat(seenCancel.get()).isTrue();
    assertThat(new CountDownLatch(1).await(40, TimeUnit.MILLISECONDS)).isFalse();
    assertThat(runs.get()).isEqualTo(1);
    assertThat(engine.cadence().pending()).isEqualTo(0);
    realm.close();
  }

  // ---- the realm ----

  @Test
  void closeCancelsPeriodicJobsWithoutWaitingForeverAndWithoutInterruptingARun() throws Exception {
    Engine engine = engine();
    Realm realm = realm(engine);
    AtomicInteger runs = new AtomicInteger();
    Job<Void> quick = realm.every(engine.io(), 0, 2, TimeUnit.MILLISECONDS, counting(runs));
    awaitTrue(() -> runs.get() >= 2);
    Thread closer = start("closer", realm::close);
    join(closer);
    assertThat(quick.outcomeOrNull().isCancelled()).isTrue();
    assertThat(realm.isClosed()).isTrue();

    Realm second = realm(engine);
    Gate gate = new Gate();
    AtomicBoolean interrupted = new AtomicBoolean(true);
    Job<Void> running =
        second.every(
            engine.io(),
            0,
            2,
            TimeUnit.MILLISECONDS,
            cp -> {
              gate.started.countDown();
              try {
                gate.release.await();
              } catch (InterruptedException e) {
                // fall through with the flag cleared by the exception
              }
              interrupted.set(Thread.currentThread().isInterrupted());
            });
    gate.awaitStarted();
    Realm.Closing closing = second.closeAsync();
    assertThat(running.isCancellationRequested()).isTrue();
    assertThat(running.isDone()).isFalse();
    assertThat(closing.isClosed()).isFalse();
    gate.open();
    assertThat(closing.await(20, TimeUnit.SECONDS)).isTrue();
    assertThat(interrupted.get()).isFalse();
    assertThat(running.outcomeOrNull().isCancelled()).isTrue();
  }

  @Test
  void theTimeoutOfCloseAlsoCancelsPeriodicJobs() throws Exception {
    Engine engine = engine();
    Realm realm = realm(engine);
    AtomicInteger runs = new AtomicInteger();
    Job<Void> job = realm.every(engine.io(), 0, 2, TimeUnit.MILLISECONDS, counting(runs));
    awaitTrue(() -> runs.get() >= 1);
    assertThat(realm.close(20, TimeUnit.SECONDS)).isTrue();
    assertThat(job.outcomeOrNull().isCancelled()).isTrue();
  }

  @Test
  void timersCannotBeStartedOnAClosingRealmAndArgumentsAreChecked() throws Exception {
    Engine engine = engine();
    Realm realm = realm(engine);
    assertThatThrownBy(() -> realm.every(engine.io(), 0, 0, TimeUnit.MILLISECONDS, cp -> {}))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> realm.atRate(engine.io(), 0, -5, TimeUnit.MILLISECONDS, cp -> {}))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> realm.every(engine.io(), 0, 1, null, cp -> {}))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> realm.every(null, 0, 1, TimeUnit.MILLISECONDS, cp -> {}))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> realm.after(engine.io(), 1, TimeUnit.MILLISECONDS, null))
        .isInstanceOf(NullPointerException.class);
    realm.closeAsync();
    assertThatThrownBy(() -> realm.every(engine.io(), 0, 1, TimeUnit.MILLISECONDS, cp -> {}))
        .isInstanceOf(RealmClosedException.class);
    assertThatThrownBy(() -> realm.after(engine.io(), 1, TimeUnit.MILLISECONDS, cp -> 1))
        .isInstanceOf(RealmClosedException.class);
  }

  @Test
  void aPeriodicJobNeverSucceedsAndCanBeRecovered() throws Exception {
    Engine engine = engine();
    Realm realm = realm(engine);
    Job<Void> failing =
        realm.every(
            engine.io(),
            0,
            2,
            TimeUnit.MILLISECONDS,
            cp -> {
              throw new IllegalStateException("fails at once");
            });
    Outcome<Void> recovered =
        failing.recover(engine.io(), (t, cp) -> null).await(20, TimeUnit.SECONDS);
    assertThat(recovered.isSuccess()).isTrue();
    assertThat(failing.outcomeOrNull().isFailure()).isTrue();
    realm.close();
  }
}
