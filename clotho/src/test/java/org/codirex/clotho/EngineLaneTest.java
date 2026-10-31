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
import static org.codirex.clotho.support.TestKit.awaitTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.codirex.clotho.support.RecordingHandler;
import org.junit.jupiter.api.Test;

class EngineLaneTest extends EngineTestBase {

  private final RecordingHandler handler = new RecordingHandler();
  private final Realm realm = Realm.builder("lanes").uncaughtHandler(handler).open();

  @Test
  void computeForbidsBlockingAndIoAllows() throws Exception {
    Engine engine = track(builder().build());
    assertThat(engine.compute().blockingPolicy()).isEqualTo(BlockingPolicy.FORBIDDEN);
    assertThat(engine.io().blockingPolicy()).isEqualTo(BlockingPolicy.ALLOWED);
    assertThat(engine.serial("s").blockingPolicy()).isEqualTo(BlockingPolicy.ALLOWED);
    assertThat(engine.single("s").blockingPolicy()).isEqualTo(BlockingPolicy.ALLOWED);
  }

  @Test
  void awaitAndRealmCloseAreRefusedOnComputeButWorkOnIo() throws Exception {
    Engine engine = track(builder().build());
    Job<String> done = realm.launch(engine.io(), cp -> "x");
    done.await();
    Job<String> onCompute = realm.launch(engine.compute(), cp -> done.await().getOrThrow());
    Job<String> closeOnCompute =
        realm.launch(
            engine.compute(),
            cp -> {
              Realm other = Realm.open("other");
              other.close();
              return "closed";
            });
    Job<String> onIo = realm.launch(engine.io(), cp -> done.await().getOrThrow());
    assertThat(onCompute.await().failureOrNull()).isInstanceOf(BlockingNotAllowedException.class);
    assertThat(closeOnCompute.await().failureOrNull())
        .isInstanceOf(BlockingNotAllowedException.class);
    assertThat(onIo.await()).isEqualTo(Outcome.success("x"));
  }

  @Test
  void theFrameworkOnlyDetectsItsOwnBlockingCalls() throws Exception {
    // Documented limit: a lock, a sleep or an I/O call on a compute thread cannot be detected.
    Engine engine = track(builder().build());
    Job<String> job =
        realm.launch(
            engine.compute(),
            cp -> {
              new CountDownLatch(0).await();
              Thread.sleep(1);
              return "not detected";
            });
    assertThat(job.await()).isEqualTo(Outcome.success("not detected"));
  }

  @Test
  void computeConcurrencyNeverExceedsTheConfiguredThreads() throws Exception {
    Engine engine = track(builder().computeThreads(3).build());
    AtomicInteger current = new AtomicInteger();
    AtomicInteger max = new AtomicInteger();
    List<Job<Integer>> jobs = new ArrayList<>();
    for (int i = 0; i < 300; i++) {
      jobs.add(
          realm.launch(
              engine.compute(),
              cp -> {
                int c = current.incrementAndGet();
                max.accumulateAndGet(c, Math::max);
                for (int k = 0; k < 20; k++) {
                  Thread.yield();
                }
                current.decrementAndGet();
                return c;
              }));
    }
    realm.close();
    assertThat(max.get() >= 1 && max.get() <= 3).isTrue();
    assertThat(engine.liveThreads() <= 3).isTrue();
    for (Job<Integer> j : jobs) {
      assertThat(j.outcomeOrNull().isSuccess()).isTrue();
    }
  }

  @Test
  void everyTaskRunsExactlyOnceOnEveryLane() throws Exception {
    Engine engine = track(builder().computeThreads(4).ioMaxThreads(8).build());
    int n = 3000;
    AtomicInteger[] ran = new AtomicInteger[n];
    for (int i = 0; i < n; i++) {
      ran[i] = new AtomicInteger();
    }
    Lane[] lanes = {engine.compute(), engine.io(), engine.serial("s"), engine.single("one")};
    for (int i = 0; i < n; i++) {
      int id = i;
      realm.launch(
          lanes[i % lanes.length], Priority.values()[i % 3], cp -> ran[id].incrementAndGet());
    }
    realm.close();
    for (int i = 0; i < n; i++) {
      assertThat(ran[i].get()).isEqualTo(1);
    }
  }

  @Test
  void ioRunsBlockedTasksConcurrentlyUpToItsMaximum() throws Exception {
    Engine engine = track(builder().ioMaxThreads(8).build());
    CountDownLatch allRunning = new CountDownLatch(8);
    CountDownLatch release = new CountDownLatch(1);
    List<Job<String>> jobs = new ArrayList<>();
    for (int i = 0; i < 8; i++) {
      jobs.add(
          realm.launch(
              engine.io(),
              cp -> {
                allRunning.countDown();
                release.await();
                return "ok";
              }));
    }
    assertThat(allRunning.await(20, TimeUnit.SECONDS)).isTrue();
    assertThat(engine.liveThreads()).isEqualTo(8);
    AtomicInteger ninth = new AtomicInteger();
    Job<String> extra =
        realm.launch(
            engine.io(),
            cp -> {
              ninth.incrementAndGet();
              return "ninth";
            });
    assertThat(extra.isDone()).isFalse();
    assertThat(engine.liveThreads()).isEqualTo(8);
    release.countDown();
    realm.close();
    assertThat(extra.outcomeOrNull()).isEqualTo(Outcome.success("ninth"));
    assertThat(ninth.get()).isEqualTo(1);
  }

  @Test
  void idleThreadsRetireAfterTheKeepAliveTime() throws Exception {
    Engine engine = track(builder().ioMaxThreads(4).ioKeepAlive(30, TimeUnit.MILLISECONDS).build());
    CountDownLatch running = new CountDownLatch(4);
    CountDownLatch release = new CountDownLatch(1);
    for (int i = 0; i < 4; i++) {
      realm.launch(
          engine.io(),
          cp -> {
            running.countDown();
            release.await();
            return 1;
          });
    }
    assertThat(running.await(20, TimeUnit.SECONDS)).isTrue();
    assertThat(engine.liveThreads()).isEqualTo(4);
    release.countDown();
    realm.close();
    awaitTrue(() -> engine.liveThreads() == 0);
    // and the pool starts threads again when work arrives
    Realm second = Realm.open("second");
    assertThat(second.launch(engine.io(), cp -> 5).await()).isEqualTo(Outcome.success(5));
    second.close();
  }

  @Test
  void idleWorkersStealFromTheWorkerThatSubmittedTheTasks() throws Exception {
    Engine engine = track(builder().computeThreads(4).build());
    Lane compute = engine.compute();
    CyclicBarrier barrier = new CyclicBarrier(2);
    AtomicReference<Thread> first = new AtomicReference<>();
    AtomicReference<Thread> second = new AtomicReference<>();
    CountDownLatch bothDone = new CountDownLatch(2);
    Job<String> parent =
        realm.launch(
            compute,
            cp -> {
              // Raw tasks submitted from a worker go to that worker's local queue.
              compute.execute(
                  () -> {
                    first.set(Thread.currentThread());
                    await(barrier);
                    bothDone.countDown();
                  });
              compute.execute(
                  () -> {
                    second.set(Thread.currentThread());
                    await(barrier);
                    bothDone.countDown();
                  });
              return "submitted";
            });
    assertThat(parent.await().isSuccess()).isTrue();
    // The barrier only opens if two different threads run the two tasks at the same time.
    assertThat(bothDone.await(20, TimeUnit.SECONDS)).isTrue();
    assertThat(first.get()).isNotEqualTo(second.get());
  }

  private static void await(CyclicBarrier barrier) {
    try {
      barrier.await(20, TimeUnit.SECONDS);
    } catch (InterruptedException
        | BrokenBarrierException
        | java.util.concurrent.TimeoutException e) {
      throw new AssertionError(e);
    }
  }

  @Test
  void aRawTaskThatThrowsIsReportedAndTheWorkerSurvives() throws Exception {
    List<Throwable> seen = Collections.synchronizedList(new ArrayList<>());
    Engine engine =
        track(builder().computeThreads(1).taskErrorHandler((t, e) -> seen.add(e)).build());
    IllegalStateException boom = new IllegalStateException("raw");
    CountDownLatch first = new CountDownLatch(1);
    engine
        .compute()
        .execute(
            () -> {
              first.countDown();
              throw boom;
            });
    assertThat(first.await(20, TimeUnit.SECONDS)).isTrue();
    Job<String> next = realm.launch(engine.compute(), cp -> "still alive");
    assertThat(next.await()).isEqualTo(Outcome.success("still alive"));
    assertThat(seen).hasSize(1);
    assertThat(seen.get(0)).isSameAs(boom);
    assertThat(handler.errors()).isEmpty();
  }

  @Test
  void derivedJobsRunOnTheirOwnLaneAndKeepThePriorityOfTheirParent() throws Exception {
    Engine engine = track(builder().computeThreads(2).build());
    Job<Integer> a = realm.launch(engine.compute(), Priority.HIGH, cp -> 1);
    Job<Integer> b = a.then(engine.io(), (v, cp) -> v + 1);
    assertThat(b.await()).isEqualTo(Outcome.success(2));
    Job<Integer> z =
        realm.zip(
            realm.launch(engine.io(), Priority.LOW, cp -> 1),
            realm.launch(engine.io(), Priority.HIGH, cp -> 2),
            Ownership.BORROWED,
            engine.compute(),
            (x, y, cp) -> x + y);
    assertThat(z.await()).isEqualTo(Outcome.success(3));
  }
}
