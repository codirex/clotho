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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.codirex.clotho.support.LoopHost;
import org.codirex.clotho.support.RecordingHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class MainLaneTest extends EngineTestBase {

  private final LoopHost host = new LoopHost();
  private final MainLane lane = MainLane.of("test", host);
  private final RecordingHandler handler = new RecordingHandler();
  private final Realm realm = Realm.builder("main").uncaughtHandler(handler).open();

  @AfterEach
  void tearDownMain() {
    Clotho.uninstallMainLane();
    host.quit();
  }

  @Test
  void executeAlwaysPostsEvenWhenAlreadyOnTheMainThread() throws Exception {
    List<String> log = Collections.synchronizedList(new ArrayList<>());
    host.runOnMain(
        () -> {
          lane.execute(() -> log.add("posted"));
          log.add("current task continues");
        });
    host.drain();
    assertThat(log).containsExactly("current task continues", "posted");
  }

  @Test
  void postedTasksRunOnTheMainThreadInSubmissionOrderPerProducer() throws Exception {
    int producers = 4;
    int each = 500;
    List<int[]> seen = new ArrayList<>();
    List<Thread> runners = Collections.synchronizedList(new ArrayList<>());
    CountDownLatch go = new CountDownLatch(1);
    List<Thread> threads = new ArrayList<>();
    for (int p = 0; p < producers; p++) {
      int producer = p;
      Thread t =
          new Thread(
              () -> {
                try {
                  go.await();
                } catch (InterruptedException e) {
                  return;
                }
                for (int i = 0; i < each; i++) {
                  int seq = i;
                  lane.execute(
                      () -> {
                        seen.add(new int[] {producer, seq});
                        runners.add(Thread.currentThread());
                      });
                }
              });
      t.setDaemon(true);
      t.start();
      threads.add(t);
    }
    go.countDown();
    for (Thread t : threads) {
      t.join(20_000);
    }
    host.drain();
    assertThat(seen).hasSize(producers * each);
    int[] next = new int[producers];
    for (int[] entry : seen) {
      assertThat(entry[1]).isEqualTo(next[entry[0]]);
      next[entry[0]]++;
    }
    for (Thread t : runners) {
      assertThat(t).isSameAs(host.thread());
    }
  }

  @Test
  void priorityIsIgnoredAndOrderIsFifo() throws Exception {
    CountDownLatch release = new CountDownLatch(1);
    CountDownLatch blocked = new CountDownLatch(1);
    host.post(
        () -> {
          blocked.countDown();
          try {
            release.await();
          } catch (InterruptedException e) {
            // test is over
          }
        });
    assertThat(blocked.await(20, TimeUnit.SECONDS)).isTrue();
    List<String> order = Collections.synchronizedList(new ArrayList<>());
    realm.launch(lane, Priority.LOW, cp -> order.add("low"));
    realm.launch(lane, Priority.HIGH, cp -> order.add("high"));
    realm.launch(lane, Priority.NORMAL, cp -> order.add("normal"));
    release.countDown();
    realm.close();
    assertThat(order).containsExactly("low", "high", "normal");
  }

  @Test
  void immediateRunsInlineOnTheMainThreadAndPostsFromOtherThreads() throws Exception {
    AtomicBoolean ranInline = new AtomicBoolean();
    host.runOnMain(
        () -> {
          AtomicBoolean ran = new AtomicBoolean();
          lane.immediate().execute(() -> ran.set(true));
          ranInline.set(ran.get());
        });
    assertThat(ranInline.get()).isTrue();

    CountDownLatch blocked = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    host.post(
        () -> {
          blocked.countDown();
          try {
            release.await();
          } catch (InterruptedException e) {
            // test is over
          }
        });
    assertThat(blocked.await(20, TimeUnit.SECONDS)).isTrue();
    AtomicReference<Thread> ranOn = new AtomicReference<>();
    lane.immediate().execute(() -> ranOn.set(Thread.currentThread()));
    assertThat(ranOn.get()).isNull();
    release.countDown();
    host.drain();
    assertThat(ranOn.get()).isSameAs(host.thread());
  }

  @Test
  void immediateBypassesTasksThatAreAlreadyQueued() throws Exception {
    List<String> order = Collections.synchronizedList(new ArrayList<>());
    host.runOnMain(
        () -> {
          lane.execute(() -> order.add("queued first"));
          lane.immediate().execute(() -> order.add("immediate"));
        });
    host.drain();
    assertThat(order).containsExactly("immediate", "queued first");
  }

  @Test
  void theMainLaneForbidsBlockingInsideItsTasks() throws Exception {
    assertThat(lane.blockingPolicy()).isEqualTo(BlockingPolicy.FORBIDDEN);
    assertThat(lane.immediate().blockingPolicy()).isEqualTo(BlockingPolicy.FORBIDDEN);
    Job<String> done = realm.launch(Lane.direct(), cp -> "x");
    Job<String> job = realm.launch(lane, cp -> done.await().getOrThrow());
    Job<String> closing =
        realm.launch(
            lane,
            cp -> {
              Realm.open("other").close();
              return "closed";
            });
    assertThat(job.await().failureOrNull()).isInstanceOf(BlockingNotAllowedException.class);
    assertThat(closing.await().failureOrNull()).isInstanceOf(BlockingNotAllowedException.class);
  }

  @Test
  void onceInstalledBlockingIsRefusedOnTheMainThreadEvenOutsideClothoTasks() throws Exception {
    Job<String> done = realm.launch(Lane.direct(), cp -> "x");
    List<Throwable> notInstalled = Collections.synchronizedList(new ArrayList<>());
    host.runOnMain(
        () -> {
          try {
            done.await();
          } catch (Throwable t) {
            notInstalled.add(t);
          }
        });
    assertThat(notInstalled).isEmpty();

    Clotho.install(lane);
    List<Throwable> refused = Collections.synchronizedList(new ArrayList<>());
    host.runOnMain(
        () -> {
          try {
            done.await();
          } catch (Throwable t) {
            refused.add(t);
          }
          try {
            done.await(1, TimeUnit.SECONDS);
          } catch (Throwable t) {
            refused.add(t);
          }
          try {
            Realm.open("tmp").close();
          } catch (Throwable t) {
            refused.add(t);
          }
          try {
            realm.closeAsync().await(1, TimeUnit.MILLISECONDS);
          } catch (Throwable t) {
            refused.add(t);
          }
        });
    assertThat(refused).hasSize(4);
    for (Throwable t : refused) {
      assertThat(t).isInstanceOf(BlockingNotAllowedException.class);
    }
    // other threads are not affected
    assertThat(done.await()).isEqualTo(Outcome.success("x"));
  }

  @Test
  void cancellingAQueuedJobEndsItAtOnceAndItsQueuedTaskDoesNothing() throws Exception {
    CountDownLatch blocked = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    host.post(
        () -> {
          blocked.countDown();
          try {
            release.await();
          } catch (InterruptedException e) {
            // test is over
          }
        });
    assertThat(blocked.await(20, TimeUnit.SECONDS)).isTrue();
    AtomicBoolean ran = new AtomicBoolean();
    Job<String> job =
        realm.launch(
            lane,
            cp -> {
              ran.set(true);
              return "x";
            });
    assertThat(job.cancel()).isTrue();
    assertThat(job.isDone()).isTrue();
    assertThat(job.outcomeOrNull().isCancelled()).isTrue();
    release.countDown();
    host.drain();
    assertThat(ran.get()).isFalse();
  }

  @Test
  void jobsNeverThrowIntoTheLoopButRawExceptionsReachTheHost() throws Exception {
    IllegalStateException rawFailure = new IllegalStateException("raw");
    realm.launch(
        lane,
        cp -> {
          throw new IllegalStateException("inside a job");
        });
    lane.execute(
        () -> {
          throw rawFailure;
        });
    AtomicBoolean laterRan = new AtomicBoolean();
    lane.execute(() -> laterRan.set(true));
    host.drain();
    assertThat(host.uncaught()).hasSize(1);
    assertThat(host.uncaught().get(0)).isSameAs(rawFailure);
    assertThat(laterRan.get()).isTrue();
  }

  @Test
  void whenTheHostStopsAcceptingTheLaneRejectsAndJobsFail() throws Exception {
    host.quit();
    assertThatThrownBy(() -> lane.execute(() -> {})).isInstanceOf(RejectedExecutionException.class);
    Job<String> job = realm.launch(lane, cp -> "never");
    assertThat(job.outcomeOrNull().failureOrNull()).isInstanceOf(RejectedExecutionException.class);
    AtomicBoolean ran = new AtomicBoolean();
    // off the main thread the immediate view posts, so it is rejected like the lane itself
    assertThatThrownBy(() -> lane.immediate().execute(() -> ran.set(true)))
        .isInstanceOf(RejectedExecutionException.class);
    assertThat(ran.get()).isFalse();
  }

  @Test
  void tasksTheHostDiscardsEndTheirJobsWithTheReasonAndReleaseTheRealm() throws Exception {
    CountDownLatch blocked = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    host.post(
        () -> {
          blocked.countDown();
          try {
            release.await();
          } catch (InterruptedException e) {
            // quitting
          }
        });
    assertThat(blocked.await(20, TimeUnit.SECONDS)).isTrue();
    AtomicBoolean ran = new AtomicBoolean();
    Job<String> job =
        realm.launch(
            lane,
            cp -> {
              ran.set(true);
              return "x";
            });
    Job<String> done = realm.launch(Lane.direct(), cp -> "y");
    AtomicInteger callbacks = new AtomicInteger();
    done.onComplete(lane, o -> callbacks.incrementAndGet());
    lane.execute(() -> ran.set(true));
    DroppedException reason = new DroppedException("loop quit");
    host.quitDiscarding(reason);
    assertThat(job.outcomeOrNull().failureOrNull()).isSameAs(reason);
    assertThat(handler.errors()).hasSize(1);
    assertThat(handler.errors().get(0)).isSameAs(reason);
    assertThat(realm.close(20, TimeUnit.SECONDS)).isTrue();
    assertThat(ran.get()).isFalse();
    assertThat(callbacks.get()).isEqualTo(0);
    release.countDown();
  }

  @Test
  void theLaneContextIsRestoredAfterEveryMainTask() throws Exception {
    AtomicReference<Lane> insideLane = new AtomicReference<>();
    lane.execute(() -> insideLane.set(LaneContext.current()));
    host.drain();
    assertThat(insideLane.get()).isSameAs(lane);
    AtomicReference<Lane> after = new AtomicReference<>(lane);
    host.runOnMain(() -> after.set(LaneContext.current()));
    assertThat(after.get()).isNull();
  }

  @Test
  void resultsComeBackToTheMainThreadThroughThen() throws Exception {
    Engine engine = track(builder().build());
    AtomicReference<Thread> producedOn = new AtomicReference<>();
    AtomicReference<Thread> shownOn = new AtomicReference<>();
    Job<Integer> shown =
        realm
            .launch(
                engine.io(),
                cp -> {
                  producedOn.set(Thread.currentThread());
                  return 41;
                })
            .then(
                lane,
                (v, cp) -> {
                  shownOn.set(Thread.currentThread());
                  return v + 1;
                });
    assertThat(shown.await()).isEqualTo(Outcome.success(42));
    assertThat(producedOn.get()).isNotEqualTo(host.thread());
    assertThat(shownOn.get()).isSameAs(host.thread());
  }

  @Test
  void callerRunsNeverRunsPoolWorkInlineOnTheMainThread() throws Exception {
    Engine engine =
        track(
            builder()
                .computeThreads(1)
                .computeQueue(QueueLimit.of(1, RejectionPolicy.CALLER_RUNS))
                .build());
    Clotho.install(lane);
    Gate gate = new Gate();
    realm.launch(engine.compute(), gate.work());
    gate.awaitStarted();
    realm.launch(engine.compute(), cp -> "fills the queue");
    AtomicInteger ran = new AtomicInteger();
    AtomicReference<Job<Integer>> fromMainLane = new AtomicReference<>();
    AtomicReference<Job<Integer>> fromRawMain = new AtomicReference<>();
    Job<String> viaLane =
        realm.launch(
            lane,
            cp -> {
              fromMainLane.set(realm.launch(engine.compute(), c -> ran.incrementAndGet()));
              return "submitted";
            });
    assertThat(viaLane.await().isSuccess()).isTrue();
    host.runOnMain(
        () -> fromRawMain.set(realm.launch(engine.compute(), c -> ran.incrementAndGet())));
    for (Job<Integer> j : List.of(fromMainLane.get(), fromRawMain.get())) {
      assertThat(j.outcomeOrNull().failureOrNull()).isInstanceOf(RejectedExecutionException.class);
    }
    assertThat(ran.get()).isEqualTo(0);
    gate.open();
    realm.close();
  }

  @Test
  void aSecondLaneOverTheSameHostBehavesTheSame() throws Exception {
    MainLane other = MainLane.of("other", host);
    AtomicReference<Thread> ranOn = new AtomicReference<>();
    realm.launch(other, cp -> ranOn.getAndSet(Thread.currentThread())).await();
    assertThat(ranOn.get()).isSameAs(host.thread());
    assertThat(other.isMainThread()).isFalse();
    AtomicBoolean onMain = new AtomicBoolean();
    host.runOnMain(() -> onMain.set(other.isMainThread()));
    assertThat(onMain.get()).isTrue();
  }
}
