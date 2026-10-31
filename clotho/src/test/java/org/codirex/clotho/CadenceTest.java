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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.codirex.clotho.support.RecordingHandler;
import org.junit.jupiter.api.Test;

/**
 * One-shot timers with real threads and the real clock. Tolerant: lateness is never asserted tight.
 */
class CadenceTest extends EngineTestBase {

  private static final long MS = TimeUnit.MILLISECONDS.toNanos(1);

  private final RecordingHandler handler = new RecordingHandler();

  private Engine engine() {
    return track(builder().timerTick(1, TimeUnit.MILLISECONDS).build());
  }

  private Realm realm(Engine engine) {
    return Realm.builder("cadence").cadence(engine.cadence()).uncaughtHandler(handler).open();
  }

  private static boolean hasCadenceThread(Engine engine) {
    for (Thread t : engine.liveThreadSnapshot()) {
      if (t.getName().endsWith("-cadence")) {
        return true;
      }
    }
    return false;
  }

  @Test
  void afterRunsOnceNeverBeforeItsDelayAndOnTheGivenLane() throws Exception {
    Engine engine = engine();
    Realm realm = realm(engine);
    AtomicLong ranAt = new AtomicLong();
    AtomicReference<String> thread = new AtomicReference<>();
    AtomicInteger count = new AtomicInteger();
    long begin = System.nanoTime();
    Job<String> job =
        realm.after(
            engine.io(),
            30,
            TimeUnit.MILLISECONDS,
            cp -> {
              ranAt.set(System.nanoTime());
              thread.set(Thread.currentThread().getName());
              count.incrementAndGet();
              return "v";
            });
    assertThat(job.isDone()).isFalse();
    assertThat(engine.cadence().pending()).isEqualTo(1);
    assertThat(job.await(20, TimeUnit.SECONDS)).isEqualTo(Outcome.success("v"));
    assertThat(ranAt.get() - begin >= 30 * MS).isTrue();
    assertThat(thread.get().contains("-io-")).isTrue();
    assertThat(thread.get().endsWith("-cadence")).isFalse();
    realm.close();
    assertThat(count.get()).isEqualTo(1);
    assertThat(engine.cadence().pending()).isEqualTo(0);
  }

  @Test
  void aZeroDelayIsALaunchAndNeedsNoTimerThread() throws Exception {
    Engine engine = engine();
    Realm realm = realm(engine);
    Job<String> job = realm.after(engine.io(), 0, TimeUnit.MILLISECONDS, cp -> "now");
    assertThat(job.await(20, TimeUnit.SECONDS)).isEqualTo(Outcome.success("now"));
    assertThat(hasCadenceThread(engine)).isFalse();
    assertThat(engine.cadence().pending()).isEqualTo(0);
    realm.close();
  }

  @Test
  void cancelBeforeItFiresRemovesTheTimerAtOnceAndTheThreadEnds() throws Exception {
    Engine engine = engine();
    Realm realm = realm(engine);
    AtomicInteger ran = new AtomicInteger();
    Job<String> job =
        realm.after(
            engine.io(),
            1,
            TimeUnit.HOURS,
            cp -> {
              ran.incrementAndGet();
              return "never";
            });
    assertThat(engine.cadence().pending()).isEqualTo(1);
    assertThat(job.cancel()).isTrue();
    assertThat(engine.cadence().pending()).isEqualTo(0);
    assertThat(job.isDone()).isTrue();
    assertThat(job.outcomeOrNull().isCancelled()).isTrue();
    awaitTrue(() -> !hasCadenceThread(engine));
    assertThat(ran.get()).isEqualTo(0);
    realm.close();
  }

  @Test
  void theTimerThreadStartsOnDemandAndEndsWhenNothingIsPending() throws Exception {
    Engine engine = engine();
    Realm realm = realm(engine);
    assertThat(hasCadenceThread(engine)).isFalse();
    realm.after(engine.io(), 5, TimeUnit.MILLISECONDS, cp -> 1).await(20, TimeUnit.SECONDS);
    awaitTrue(() -> !hasCadenceThread(engine));
    // and it starts again for the next timer
    assertThat(
            realm.after(engine.io(), 5, TimeUnit.MILLISECONDS, cp -> 2).await(20, TimeUnit.SECONDS))
        .isEqualTo(Outcome.success(2));
    awaitTrue(() -> !hasCadenceThread(engine));
    realm.close();
  }

  @Test
  void userCodeNeverRunsOnTheCadenceThread() throws Exception {
    Engine engine = engine();
    Realm realm = realm(engine);
    List<String> names = java.util.Collections.synchronizedList(new ArrayList<>());
    Lane[] lanes = {engine.compute(), engine.io(), engine.serial("s"), engine.single("one")};
    List<Job<String>> jobs = new ArrayList<>();
    for (Lane lane : lanes) {
      jobs.add(
          realm.after(
              lane,
              5,
              TimeUnit.MILLISECONDS,
              cp -> {
                names.add(Thread.currentThread().getName());
                return "x";
              }));
    }
    for (Job<String> j : jobs) {
      assertThat(j.await(20, TimeUnit.SECONDS).isSuccess()).isTrue();
    }
    assertThat(names).hasSize(4);
    for (String n : names) {
      assertThat(n.endsWith("-cadence")).isFalse();
    }
    realm.close();
  }

  @Test
  void lanesThatRunTasksInlineAreRefused() throws Exception {
    Engine engine = engine();
    Realm realm = realm(engine);
    Lane inline =
        new Lane() {
          @Override
          public void execute(Runnable task) {
            task.run();
          }

          @Override
          public BlockingPolicy blockingPolicy() {
            return BlockingPolicy.INHERIT;
          }
        };
    for (Lane lane : new Lane[] {Lane.direct(), inline}) {
      assertThatThrownBy(() -> realm.after(lane, 1, TimeUnit.MILLISECONDS, cp -> 1))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> realm.every(lane, 0, 1, TimeUnit.MILLISECONDS, cp -> {}))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> realm.atRate(lane, 0, 1, TimeUnit.MILLISECONDS, cp -> {}))
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThat(realm.outstanding()).isEqualTo(0);
    realm.close();
  }

  @Test
  void aTimerJobComposesLikeAnyJob() throws Exception {
    Engine engine = engine();
    Realm realm = realm(engine);
    Job<Integer> chained =
        realm
            .after(engine.io(), 5, TimeUnit.MILLISECONDS, cp -> 20)
            .then(engine.compute(), (v, cp) -> v + 1);
    assertThat(chained.await(20, TimeUnit.SECONDS)).isEqualTo(Outcome.success(21));
    Job<Integer> failing =
        realm.after(
            engine.io(),
            5,
            TimeUnit.MILLISECONDS,
            cp -> {
              throw new IllegalStateException("boom");
            });
    assertThat(failing.recover(engine.io(), (t, cp) -> -1).await(20, TimeUnit.SECONDS))
        .isEqualTo(Outcome.success(-1));
    realm.close();
  }

  @Test
  void closeWaitsForAPendingAfterButCloseAsyncCancelsIt() throws Exception {
    Engine engine = engine();
    Realm waiting = realm(engine);
    Job<String> job = waiting.after(engine.io(), 60, TimeUnit.MILLISECONDS, cp -> "ran");
    Thread closer = start("closer", waiting::close);
    join(closer);
    assertThat(job.outcomeOrNull()).isEqualTo(Outcome.success("ran"));

    Realm cancelling = realm(engine);
    Job<String> pending = cancelling.after(engine.io(), 1, TimeUnit.HOURS, cp -> "never");
    Realm.Closing closing = cancelling.closeAsync();
    assertThat(closing.isClosed()).isTrue();
    assertThat(pending.outcomeOrNull().isCancelled()).isTrue();
    assertThat(engine.cadence().pending()).isEqualTo(0);
  }

  @Test
  void theTimeoutOfCloseCancelsAPendingAfterWithoutReportingALeak() throws Exception {
    Engine engine = engine();
    Realm realm = realm(engine);
    Job<String> pending = realm.after(engine.io(), 1, TimeUnit.HOURS, cp -> "never");
    assertThat(realm.close(30, TimeUnit.MILLISECONDS)).isTrue();
    assertThat(pending.outcomeOrNull().isCancelled()).isTrue();
    assertThat(handler.errors()).isEmpty();
    assertThat(engine.cadence().pending()).isEqualTo(0);
  }

  @Test
  void engineShutdownDiscardsPendingTimersEndsTheThreadAndRefusesNewOnes() throws Exception {
    Engine engine = engine();
    Realm realm = realm(engine);
    Job<String> after = realm.after(engine.io(), 1, TimeUnit.HOURS, cp -> "never");
    Job<Void> every = realm.every(engine.io(), 1, 1, TimeUnit.HOURS, cp -> {});
    assertThat(engine.cadence().pending()).isEqualTo(2);
    List<Thread> threads = engine.liveThreadSnapshot();
    engine.shutdown();
    for (Job<?> job : List.of(after, every)) {
      Throwable cause = job.outcomeOrNull().failureOrNull();
      assertThat(cause).isInstanceOf(DroppedException.class);
      assertThat(((DroppedException) cause).reason()).isEqualTo(DroppedException.Reason.SHUTDOWN);
    }
    assertThat(engine.cadence().pending()).isEqualTo(0);
    assertThat(engine.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
    for (Thread t : threads) {
      t.join(20_000);
      assertThat(t.isAlive()).isFalse();
    }
    // the realm is still open; the cadence is not
    Job<String> late = realm.after(engine.io(), 1, TimeUnit.HOURS, cp -> "never");
    assertThat(late.outcomeOrNull().failureOrNull()).isInstanceOf(LaneClosedException.class);
    Job<Void> latePeriodic = realm.every(engine.io(), 1, 1, TimeUnit.HOURS, cp -> {});
    assertThat(latePeriodic.outcomeOrNull().failureOrNull())
        .isInstanceOf(LaneClosedException.class);
    realm.close();
  }

  @Test
  void shutdownNowDiscardsTimersToo() throws Exception {
    Engine engine = engine();
    Realm realm = realm(engine);
    Job<String> after = realm.after(engine.io(), 1, TimeUnit.HOURS, cp -> "never");
    engine.shutdownNow();
    assertThat(after.outcomeOrNull().failureOrNull()).isInstanceOf(DroppedException.class);
    assertThat(engine.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
    assertThat(realm.close(20, TimeUnit.SECONDS)).isTrue();
  }

  @Test
  void aRealmWithoutACadenceUsesTheGlobalOne() throws Exception {
    Realm realm = Realm.open("global-cadence");
    assertThat(realm.cadence()).isSameAs(Engine.global().cadence());
    Job<String> job = realm.after(Lane.io(), 5, TimeUnit.MILLISECONDS, cp -> "global");
    assertThat(job.await(20, TimeUnit.SECONDS)).isEqualTo(Outcome.success("global"));
    realm.close();
  }

  @Test
  void manyDifferentDelaysAreNeverEarly() throws Exception {
    Engine engine = engine();
    Realm realm = realm(engine);
    List<Job<Long>> jobs = new ArrayList<>();
    List<long[]> expected = new ArrayList<>();
    for (int i = 0; i < 40; i++) {
      long delayMs = 1 + ThreadLocalRandom.current().nextInt(25);
      long begin = System.nanoTime();
      Job<Long> job =
          realm.after(engine.io(), delayMs, TimeUnit.MILLISECONDS, cp -> System.nanoTime());
      jobs.add(job);
      expected.add(new long[] {begin, delayMs});
    }
    for (int i = 0; i < jobs.size(); i++) {
      long ranAt = jobs.get(i).await(20, TimeUnit.SECONDS).getOrThrow();
      assertThat(ranAt - expected.get(i)[0] >= expected.get(i)[1] * MS).isTrue();
    }
    realm.close();
  }

  @Test
  void aTimerMoreThanOneRevolutionAheadStillFiresOnTimeAndNotEarly() throws Exception {
    Engine engine = track(builder().timerTick(1, TimeUnit.MILLISECONDS).timerWheelSize(2).build());
    Realm realm = realm(engine);
    long begin = System.nanoTime();
    long ranAt =
        realm
            .after(engine.io(), 40, TimeUnit.MILLISECONDS, cp -> System.nanoTime())
            .await(20, TimeUnit.SECONDS)
            .getOrThrow();
    assertThat(ranAt - begin >= 40 * MS).isTrue();
    realm.close();
  }

  @Test
  void theEngineWiresTheWheelSettingsAndValidatesThem() {
    assertThatThrownBy(() -> builder().timerTick(0, TimeUnit.MILLISECONDS))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> builder().timerWheelSize(3))
        .isInstanceOf(IllegalArgumentException.class);
    Engine engine = engine();
    assertThat(engine.cadence().engine()).isSameAs(engine);
    assertThat(engine.cadence().toString().contains("pending=0")).isTrue();
  }
}
