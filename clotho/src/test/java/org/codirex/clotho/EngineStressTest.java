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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.codirex.clotho.support.RecordingHandler;
import org.junit.jupiter.api.Test;

/**
 * Stress tests for the engine invariants. They show that these invariants held in the interleavings
 * that happened during the runs; they cannot prove that no other interleaving exists (jcstress in
 * phase 6 covers the critical structures).
 */
class EngineStressTest extends EngineTestBase {

  private static final int PRODUCERS = 6;
  private static final int JOBS_PER_PRODUCER = 3000;

  private final RecordingHandler handler = new RecordingHandler();

  private record Submitted(Job<Integer> job, int id) {}

  /**
   * Runs the producers against the engine and checks: a job that succeeded ran exactly once, a job
   * that failed with a rejection never ran, nothing else happened, nothing stays pending.
   */
  private void hammer(Engine engine, Lane[] lanes, boolean cancelSome) throws Exception {
    Realm realm = Realm.builder("stress").uncaughtHandler(handler).open();
    int total = PRODUCERS * JOBS_PER_PRODUCER;
    AtomicInteger[] ran = new AtomicInteger[total];
    for (int i = 0; i < total; i++) {
      ran[i] = new AtomicInteger();
    }
    List<Submitted> all = java.util.Collections.synchronizedList(new ArrayList<>());
    CountDownLatch go = new CountDownLatch(1);
    List<Thread> producers = new ArrayList<>();
    for (int p = 0; p < PRODUCERS; p++) {
      int base = p * JOBS_PER_PRODUCER;
      Thread t =
          new Thread(
              () -> {
                try {
                  go.await();
                } catch (InterruptedException e) {
                  return;
                }
                ThreadLocalRandom rnd = ThreadLocalRandom.current();
                for (int i = 0; i < JOBS_PER_PRODUCER; i++) {
                  int id = base + i;
                  Lane lane = lanes[rnd.nextInt(lanes.length)];
                  Priority priority = Priority.values()[rnd.nextInt(3)];
                  Job<Integer> job = realm.launch(lane, priority, cp -> ran[id].incrementAndGet());
                  if (cancelSome && rnd.nextInt(10) == 0) {
                    job.cancel();
                  }
                  all.add(new Submitted(job, id));
                }
              });
      t.setDaemon(true);
      t.start();
      producers.add(t);
    }
    go.countDown();
    for (Thread t : producers) {
      t.join(60_000);
      assertThat(t.isAlive()).isFalse();
    }
    assertThat(realm.close(60, TimeUnit.SECONDS)).isTrue();
    assertThat(all).hasSize(total);
    int succeeded = 0;
    int rejected = 0;
    int cancelled = 0;
    for (Submitted s : all) {
      Outcome<Integer> o = s.job().outcomeOrNull();
      assertThat(o).isNotNull();
      int count = ran[s.id()].get();
      if (o.isSuccess()) {
        assertThat(count).isEqualTo(1);
        succeeded++;
      } else if (o.isCancelled()) {
        assertThat(count <= 1).isTrue();
        cancelled++;
      } else {
        assertThat(o.failureOrNull()).isInstanceOf(RejectedExecutionException.class);
        assertThat(count).isEqualTo(0);
        rejected++;
      }
    }
    assertThat(succeeded + rejected + cancelled).isEqualTo(total);
    assertThat(realm.outstanding()).isEqualTo(0);
    assertThat(((PoolLane) engine.compute()).queuedCount()).isEqualTo(0);
    assertThat(((PoolLane) engine.io()).queuedCount()).isEqualTo(0);
    assertThat(handler.errors()).isEmpty();
  }

  private Lane[] allLanes(Engine engine) {
    return new Lane[] {engine.compute(), engine.io(), engine.serial("s"), engine.single("one")};
  }

  @Test
  void unboundedQueuesRunEveryJobExactlyOnce() throws Exception {
    Engine engine = track(builder().computeThreads(4).ioMaxThreads(8).build());
    hammer(engine, allLanes(engine), false);
  }

  @Test
  void rejectingQueuesGiveEveryJobEitherOneRunOrARejection() throws Exception {
    Engine engine =
        track(
            builder()
                .computeThreads(4)
                .ioMaxThreads(4)
                .computeQueue(QueueLimit.of(64, RejectionPolicy.REJECT))
                .ioQueue(QueueLimit.of(64, RejectionPolicy.REJECT))
                .build());
    Lane[] lanes = {
      engine.compute(),
      engine.io(),
      engine.serial("s", QueueLimit.of(64, RejectionPolicy.REJECT)),
      engine.single("one", QueueLimit.of(64, RejectionPolicy.REJECT))
    };
    hammer(engine, lanes, false);
  }

  @Test
  void droppingQueuesNeverLoseAJobWithoutAFinalOutcome() throws Exception {
    Engine engine =
        track(
            builder()
                .computeThreads(2)
                .ioMaxThreads(2)
                .computeQueue(QueueLimit.of(16, RejectionPolicy.DROP_OLDEST))
                .ioQueue(QueueLimit.of(16, RejectionPolicy.DROP_OLDEST))
                .build());
    Lane[] lanes = {
      engine.compute(),
      engine.io(),
      engine.serial("s", QueueLimit.of(16, RejectionPolicy.DROP_OLDEST)),
      engine.single("one", QueueLimit.of(16, RejectionPolicy.DROP_OLDEST))
    };
    hammer(engine, lanes, false);
  }

  @Test
  void callerRunsQueuesStayConsistentWhenCallersAreNotTrusted() throws Exception {
    Engine engine =
        track(
            builder()
                .computeThreads(2)
                .computeQueue(QueueLimit.of(8, RejectionPolicy.CALLER_RUNS))
                .ioQueue(QueueLimit.of(8, RejectionPolicy.CALLER_RUNS))
                .build());
    hammer(engine, new Lane[] {engine.compute(), engine.io()}, false);
  }

  @Test
  void cancellationRacingTheSchedulerKeepsTheInvariants() throws Exception {
    Engine engine = track(builder().computeThreads(4).ioMaxThreads(8).build());
    hammer(engine, allLanes(engine), true);
  }

  @Test
  void shutdownNowInTheMiddleOfTheLoadLeavesNoJobWithoutAnOutcomeAndNoThread() throws Exception {
    Engine engine = track(builder().computeThreads(4).ioMaxThreads(8).build());
    Lane[] lanes = allLanes(engine);
    Realm realm = Realm.builder("kill").uncaughtHandler(handler).open();
    int total = PRODUCERS * JOBS_PER_PRODUCER;
    AtomicInteger[] ran = new AtomicInteger[total];
    for (int i = 0; i < total; i++) {
      ran[i] = new AtomicInteger();
    }
    List<Submitted> all = java.util.Collections.synchronizedList(new ArrayList<>());
    AtomicInteger submitted = new AtomicInteger();
    AtomicBoolean stop = new AtomicBoolean();
    List<Thread> producers = new ArrayList<>();
    for (int p = 0; p < PRODUCERS; p++) {
      int base = p * JOBS_PER_PRODUCER;
      Thread t =
          new Thread(
              () -> {
                for (int i = 0; i < JOBS_PER_PRODUCER && !stop.get(); i++) {
                  int id = base + i;
                  Lane lane = lanes[ThreadLocalRandom.current().nextInt(lanes.length)];
                  Job<Integer> job = realm.launch(lane, cp -> ran[id].incrementAndGet());
                  all.add(new Submitted(job, id));
                  submitted.incrementAndGet();
                }
              });
      t.setDaemon(true);
      t.start();
      producers.add(t);
    }
    org.codirex.clotho.support.TestKit.awaitTrue(() -> submitted.get() > 2000);
    List<Thread> threads = engine.liveThreadSnapshot();
    engine.shutdownNow();
    for (Thread t : producers) {
      t.join(60_000);
      assertThat(t.isAlive()).isFalse();
    }
    stop.set(true);
    assertThat(engine.awaitTermination(60, TimeUnit.SECONDS)).isTrue();
    assertThat(realm.close(60, TimeUnit.SECONDS)).isTrue();
    for (Thread t : threads) {
      t.join(20_000);
      assertThat(t.isAlive()).isFalse();
    }
    for (Submitted s : all) {
      Outcome<Integer> o = s.job().outcomeOrNull();
      assertThat(o).isNotNull();
      int count = ran[s.id()].get();
      if (o.isSuccess()) {
        assertThat(count).isEqualTo(1);
      } else {
        assertThat(o.failureOrNull()).isInstanceOf(RejectedExecutionException.class);
        assertThat(count).isEqualTo(0);
      }
    }
    assertThat(realm.outstanding()).isEqualTo(0);
  }

  @Test
  void aWorkerThatSubmitsManyLocalTasksGetsThemRunExactlyOnceAcrossThreads() throws Exception {
    Engine engine = track(builder().computeThreads(4).build());
    Lane compute = engine.compute();
    int n = 20_000;
    AtomicInteger[] ran = new AtomicInteger[n];
    for (int i = 0; i < n; i++) {
      ran[i] = new AtomicInteger();
    }
    java.util.Set<Thread> runners = java.util.concurrent.ConcurrentHashMap.newKeySet();
    CountDownLatch done = new CountDownLatch(n);
    Realm realm = Realm.builder("local").uncaughtHandler(handler).open();
    Job<String> root =
        realm.launch(
            compute,
            cp -> {
              for (int i = 0; i < n; i++) {
                int id = i;
                compute.execute(
                    () -> {
                      ran[id].incrementAndGet();
                      runners.add(Thread.currentThread());
                      done.countDown();
                    });
              }
              return "submitted";
            });
    assertThat(root.await().isSuccess()).isTrue();
    assertThat(done.await(60, TimeUnit.SECONDS)).isTrue();
    for (int i = 0; i < n; i++) {
      assertThat(ran[i].get()).isEqualTo(1);
    }
    assertThat(runners.size() >= 1 && runners.size() <= 4).isTrue();
    realm.close();
  }

  @Test
  void aLowTaskStillRunsUnderContinuousHighLoadWithTheRealClock() throws Exception {
    Engine engine = track(builder().computeThreads(2).agingStep(5, TimeUnit.MILLISECONDS).build());
    Realm realm = Realm.builder("aging").uncaughtHandler(handler).open();
    AtomicBoolean flood = new AtomicBoolean(true);
    AtomicInteger highRan = new AtomicInteger();
    List<Thread> floods = new ArrayList<>();
    for (int f = 0; f < 2; f++) {
      Thread t =
          new Thread(
              () -> {
                // closed loop: at most 8 HIGH jobs in flight per producer, so the queue stays short
                // but is never empty while the flood runs
                AtomicInteger inFlight = new AtomicInteger();
                while (flood.get()) {
                  if (inFlight.get() >= 8) {
                    Thread.yield();
                    continue;
                  }
                  inFlight.incrementAndGet();
                  realm.launch(
                      engine.compute(),
                      Priority.HIGH,
                      cp -> {
                        highRan.incrementAndGet();
                        inFlight.decrementAndGet();
                        return 1;
                      });
                }
              });
      t.setDaemon(true);
      t.start();
      floods.add(t);
    }
    org.codirex.clotho.support.TestKit.awaitTrue(() -> highRan.get() > 100);
    Job<String> low = realm.launch(engine.compute(), Priority.LOW, cp -> "low");
    Outcome<String> outcome = low.await(30, TimeUnit.SECONDS);
    boolean floodWasStillRunning = flood.get();
    flood.set(false);
    for (Thread t : floods) {
      t.join(20_000);
    }
    assertThat(outcome).isEqualTo(Outcome.success("low"));
    assertThat(floodWasStillRunning).isTrue();
    realm.close();
  }
}
