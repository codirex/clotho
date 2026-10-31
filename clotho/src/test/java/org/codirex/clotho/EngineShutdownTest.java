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
import static org.codirex.clotho.support.TestKit.join;
import static org.codirex.clotho.support.TestKit.start;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.codirex.clotho.support.RecordingHandler;
import org.junit.jupiter.api.Test;

class EngineShutdownTest extends EngineTestBase {

  private final RecordingHandler handler = new RecordingHandler();
  private final Realm realm = Realm.builder("shutdown").uncaughtHandler(handler).open();

  private static void assertAllDead(List<Thread> threads) throws InterruptedException {
    for (Thread t : threads) {
      t.join(20_000);
      assertThat(t.isAlive()).isFalse();
    }
  }

  @Test
  void gracefulShutdownRunsAcceptedTasksRejectsNewOnesAndEndsTheThreads() throws Exception {
    Engine engine = track(builder().computeThreads(1).build());
    Gate gate = new Gate();
    realm.launch(engine.compute(), gate.work());
    gate.awaitStarted();
    AtomicInteger ran = new AtomicInteger();
    List<Job<Integer>> queued = new ArrayList<>();
    for (int i = 0; i < 3; i++) {
      queued.add(realm.launch(engine.compute(), cp -> ran.incrementAndGet()));
    }
    List<Thread> threads = engine.liveThreadSnapshot();
    engine.shutdown();
    assertThat(engine.isShutdown()).isTrue();
    assertThat(engine.isTerminated()).isFalse();
    Job<Integer> late = realm.launch(engine.compute(), cp -> ran.incrementAndGet());
    assertThat(late.outcomeOrNull().failureOrNull()).isInstanceOf(RejectedExecutionException.class);
    gate.open();
    assertThat(engine.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
    assertThat(engine.isTerminated()).isTrue();
    assertThat(engine.liveThreads()).isEqualTo(0);
    assertAllDead(threads);
    for (Job<Integer> j : queued) {
      assertThat(j.outcomeOrNull().isSuccess()).isTrue();
    }
    assertThat(ran.get()).isEqualTo(3);
  }

  @Test
  void everyLaneRejectsAfterShutdownAndNewLanesCannotBeCreated() throws Exception {
    Engine engine = track(builder().build());
    Lane serial = engine.serial("s");
    Lane single = engine.single("one");
    engine.shutdown();
    for (Lane lane : new Lane[] {engine.compute(), engine.io(), serial, single}) {
      assertThatThrownBy(() -> lane.execute(() -> {}))
          .isInstanceOf(RejectedExecutionException.class);
      Job<String> job = realm.launch(lane, cp -> "no");
      assertThat(job.outcomeOrNull().failureOrNull())
          .isInstanceOf(RejectedExecutionException.class);
    }
    assertThatThrownBy(() -> engine.serial("x")).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> engine.single("x")).isInstanceOf(IllegalStateException.class);
    assertThat(engine.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
  }

  @Test
  void tasksSubmittedByRunningTasksDuringGracefulShutdownAreRejected() throws Exception {
    Engine engine = track(builder().computeThreads(1).build());
    Gate gate = new Gate();
    CountDownLatch shutdownCalled = new CountDownLatch(1);
    Job<Outcome<String>> parent =
        realm.launch(
            engine.compute(),
            cp -> {
              gate.started.countDown();
              shutdownCalled.await();
              return realm.launch(engine.compute(), c -> "child").outcomeOrNull();
            });
    gate.awaitStarted();
    engine.shutdown();
    shutdownCalled.countDown();
    Outcome<String> child = parent.await().getOrThrow();
    assertThat(child.failureOrNull()).isInstanceOf(RejectedExecutionException.class);
    assertThat(engine.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
  }

  @Test
  void shutdownNowDiscardsQueuedTasksWithADocumentedOutcomeAndInterruptsRunningOnes()
      throws Exception {
    Engine engine = track(builder().computeThreads(1).build());
    Gate gate = new Gate();
    AtomicBoolean interrupted = new AtomicBoolean();
    Job<String> running =
        realm.launch(
            engine.compute(),
            cp -> {
              gate.started.countDown();
              try {
                gate.release.await();
              } catch (InterruptedException e) {
                interrupted.set(true);
                throw e;
              }
              return "not reached";
            });
    gate.awaitStarted();
    AtomicInteger ran = new AtomicInteger();
    List<Job<Integer>> queued = new ArrayList<>();
    for (int i = 0; i < 4; i++) {
      queued.add(realm.launch(engine.compute(), cp -> ran.incrementAndGet()));
    }
    List<Thread> threads = engine.liveThreadSnapshot();
    assertThat(engine.shutdownNow()).isEqualTo(4);
    for (Job<Integer> j : queued) {
      assertThat(j.isDone()).isTrue();
      assertThat(j.outcomeOrNull().failureOrNull()).isInstanceOf(DroppedException.class);
    }
    assertThat(engine.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
    assertThat(interrupted.get()).isTrue();
    assertThat(running.await().failureOrNull()).isInstanceOf(InterruptedException.class);
    assertThat(ran.get()).isEqualTo(0);
    assertAllDead(threads);
    assertThat(realm.close(20, TimeUnit.SECONDS)).isTrue();
  }

  @Test
  void shutdownNowDiscardsQueuedTasksOfSerialAndSingleLanes() throws Exception {
    Engine engine = track(builder().build());
    Lane serial = engine.serial("s");
    Lane single = engine.single("one");
    Gate g1 = new Gate();
    Gate g2 = new Gate();
    realm.launch(serial, g1.stubbornWork());
    realm.launch(single, g2.stubbornWork());
    g1.awaitStarted();
    g2.awaitStarted();
    Job<String> s = realm.launch(serial, cp -> "s");
    Job<String> o = realm.launch(single, cp -> "o");
    assertThat(engine.shutdownNow()).isEqualTo(2);
    assertThat(s.outcomeOrNull().failureOrNull()).isInstanceOf(DroppedException.class);
    assertThat(o.outcomeOrNull().failureOrNull()).isInstanceOf(DroppedException.class);
    g1.open();
    g2.open();
    assertThat(engine.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
    assertThat(realm.close(20, TimeUnit.SECONDS)).isTrue();
  }

  @Test
  void aTaskThatIgnoresInterruptsKeepsItsThreadAndTerminationIsHonestAboutIt() throws Exception {
    Engine engine = track(builder().computeThreads(1).build());
    Gate gate = new Gate();
    Job<String> stubborn = realm.launch(engine.compute(), gate.stubbornWork());
    gate.awaitStarted();
    List<Thread> threads = engine.liveThreadSnapshot();
    engine.shutdownNow();
    assertThat(engine.awaitTermination(100, TimeUnit.MILLISECONDS)).isFalse();
    assertThat(engine.isTerminated()).isFalse();
    assertThat(engine.liveThreads()).isEqualTo(1);
    gate.open();
    assertThat(engine.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
    assertAllDead(threads);
    assertThat(stubborn.await()).isEqualTo(Outcome.success("stubborn"));
  }

  @Test
  void closeWaitsForRunningTasksWithoutCancellingThem() throws Exception {
    Engine engine = track(builder().build());
    Gate gate = new Gate();
    Job<String> job = realm.launch(engine.io(), gate.work());
    gate.awaitStarted();
    AtomicBoolean returned = new AtomicBoolean();
    Thread closer =
        start(
            "closer",
            () -> {
              engine.close();
              returned.set(true);
            });
    org.codirex.clotho.support.TestKit.awaitTrue(engine::isShutdown);
    assertThat(returned.get()).isFalse();
    assertThat(job.isCancellationRequested()).isFalse();
    gate.open();
    join(closer);
    assertThat(returned.get()).isTrue();
    assertThat(engine.isTerminated()).isTrue();
    assertThat(job.await()).isEqualTo(Outcome.success("gate"));
  }

  @Test
  void closeWithATimeoutEscalatesToShutdownNowAndReportsFalse() throws Exception {
    Engine engine = track(builder().build());
    Gate gate = new Gate();
    AtomicBoolean interrupted = new AtomicBoolean();
    realm.launch(
        engine.io(),
        cp -> {
          gate.started.countDown();
          try {
            gate.release.await();
          } catch (InterruptedException e) {
            interrupted.set(true);
          }
          return "stopped";
        });
    gate.awaitStarted();
    assertThat(engine.close(50, TimeUnit.MILLISECONDS)).isFalse();
    assertThat(engine.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
    assertThat(interrupted.get()).isTrue();
    assertThat(engine.close(1, TimeUnit.SECONDS)).isTrue();
  }

  @Test
  void closeInterruptedEscalatesAndRestoresTheInterruptFlag() throws Exception {
    Engine engine = track(builder().build());
    Gate gate = new Gate();
    realm.launch(
        engine.io(),
        cp -> {
          gate.started.countDown();
          try {
            gate.release.await();
          } catch (InterruptedException e) {
            // stop on interrupt
          }
          return "stopped";
        });
    gate.awaitStarted();
    AtomicBoolean flag = new AtomicBoolean();
    Thread closer =
        start(
            "interrupted-closer",
            () -> {
              Thread.currentThread().interrupt();
              engine.close();
              flag.set(Thread.currentThread().isInterrupted());
            });
    join(closer);
    assertThat(flag.get()).isTrue();
    assertThat(engine.isTerminated()).isTrue();
  }

  @Test
  void waitingForTheEngineFromItsOwnThreadOrFromAForbiddenLaneIsRefused() throws Exception {
    Engine engine = track(builder().build());
    Job<String> own =
        realm.launch(
            engine.io(),
            cp -> {
              engine.close();
              return "closed";
            });
    assertThat(own.await().failureOrNull()).isInstanceOf(IllegalStateException.class);
    Job<Boolean> forbidden =
        realm.launch(engine.compute(), cp -> engine.awaitTermination(1, TimeUnit.MILLISECONDS));
    assertThat(forbidden.await().failureOrNull()).isInstanceOf(BlockingNotAllowedException.class);
    assertThat(engine.isShutdown()).isFalse();
  }

  @Test
  void shutdownIsIdempotentAndAwaitBeforeShutdownTimesOut() throws Exception {
    Engine engine = track(builder().build());
    assertThat(engine.awaitTermination(10, TimeUnit.MILLISECONDS)).isFalse();
    engine.shutdown();
    engine.shutdown();
    assertThat(engine.shutdownNow()).isEqualTo(0);
    assertThat(engine.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
    engine.close();
  }

  @Test
  void theGlobalEngineCannotBeShutDownByAnyone() throws Exception {
    Engine global = Engine.global();
    assertThat(Engine.global()).isSameAs(global);
    assertThat(global.isGlobal()).isTrue();
    assertThatThrownBy(global::shutdown).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(global::shutdownNow).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(global::close).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> global.close(1, TimeUnit.SECONDS))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> global.single("x")).isInstanceOf(IllegalStateException.class);
    assertThat(global.isShutdown()).isFalse();
    assertThat(Lane.compute().blockingPolicy()).isEqualTo(BlockingPolicy.FORBIDDEN);
    assertThat(Lane.io().blockingPolicy()).isEqualTo(BlockingPolicy.ALLOWED);
    assertThat(realm.launch(Lane.io(), cp -> "global io").await())
        .isEqualTo(Outcome.success("global io"));
    assertThat(realm.launch(Lane.compute(), cp -> "global compute").await())
        .isEqualTo(Outcome.success("global compute"));
    assertThat(global.serial("global-serial")).isNotNull();
  }

  @Test
  void aRawTaskDiscardedByShutdownNowIsReportedNotLost() throws Exception {
    List<Throwable> seen = Collections.synchronizedList(new ArrayList<>());
    Engine engine =
        track(builder().computeThreads(1).taskErrorHandler((t, e) -> seen.add(e)).build());
    Gate gate = new Gate();
    realm.launch(engine.compute(), gate.work());
    gate.awaitStarted();
    engine.compute().execute(() -> {});
    engine.shutdownNow();
    assertThat(seen).hasSize(1);
    assertThat(seen.get(0)).isInstanceOf(DroppedException.class);
    assertThat(engine.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
  }

  @Test
  void noOwnedThreadSurvivesTheShutdownOfABusyEngine() throws Exception {
    Engine engine = track(builder().computeThreads(4).ioMaxThreads(8).build());
    Lane[] lanes = {engine.compute(), engine.io(), engine.serial("s"), engine.single("one")};
    List<Thread> seen = new ArrayList<>();
    for (int round = 0; round < 20; round++) {
      for (int i = 0; i < 400; i++) {
        realm.launch(lanes[i % lanes.length], cp -> Thread.currentThread().getName());
      }
      seen.addAll(engine.liveThreadSnapshot());
    }
    assertThat(engine.close(30, TimeUnit.SECONDS)).isTrue();
    assertThat(engine.liveThreads()).isEqualTo(0);
    assertAllDead(seen);
    assertThat(realm.close(20, TimeUnit.SECONDS)).isTrue();
    for (Thread t : Thread.getAllStackTraces().keySet()) {
      if (t.getName().startsWith(engine.name() + "-")) {
        assertThat(t.isAlive() && t.getState() != Thread.State.TERMINATED).isFalse();
      }
    }
  }
}
