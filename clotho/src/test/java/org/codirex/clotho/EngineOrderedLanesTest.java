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
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class EngineOrderedLanesTest extends EngineTestBase {

  private final Realm realm = Realm.open("ordered");

  @Test
  void serialRunsInSubmissionOrderOneAtATimeWithoutSynchronisationInTheTasks() throws Exception {
    Engine engine = track(builder().ioMaxThreads(4).build());
    Lane serial = engine.serial("s");
    int n = 5000;
    // Plain, unsynchronised state: safe only if the lane gives a happens-before edge.
    List<Integer> seen = new ArrayList<>();
    int[] counter = new int[1];
    AtomicInteger inside = new AtomicInteger();
    AtomicInteger maxInside = new AtomicInteger();
    for (int i = 0; i < n; i++) {
      int id = i;
      realm.launch(
          serial,
          cp -> {
            maxInside.accumulateAndGet(inside.incrementAndGet(), Math::max);
            seen.add(id);
            counter[0]++;
            inside.decrementAndGet();
            return id;
          });
    }
    realm.close();
    assertThat(maxInside.get()).isEqualTo(1);
    assertThat(counter[0]).isEqualTo(n);
    for (int i = 0; i < n; i++) {
      assertThat(seen.get(i)).isEqualTo(i);
    }
  }

  @Test
  void serialKeepsEachProducersOrderWithConcurrentProducers() throws Exception {
    Engine engine = track(builder().ioMaxThreads(4).build());
    Lane serial = engine.serial("s");
    int producers = 4;
    int each = 1500;
    List<int[]> seen = new ArrayList<>();
    AtomicInteger inside = new AtomicInteger();
    AtomicInteger maxInside = new AtomicInteger();
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
                  realm.launch(
                      serial,
                      cp -> {
                        maxInside.accumulateAndGet(inside.incrementAndGet(), Math::max);
                        seen.add(new int[] {producer, seq});
                        inside.decrementAndGet();
                        return seq;
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
    realm.close();
    assertThat(maxInside.get()).isEqualTo(1);
    assertThat(seen).hasSize(producers * each);
    int[] next = new int[producers];
    for (int[] entry : seen) {
      assertThat(entry[1]).isEqualTo(next[entry[0]]);
      next[entry[0]]++;
    }
  }

  @Test
  void serialAndSingleIgnorePriority() throws Exception {
    Engine engine = track(builder().build());
    for (Lane lane : new Lane[] {engine.serial("s"), engine.single("one")}) {
      Gate gate = new Gate();
      List<String> order = new ArrayList<>();
      Realm r = Realm.open("prio");
      r.launch(lane, gate.work());
      gate.awaitStarted();
      r.launch(lane, Priority.LOW, cp -> order.add("low"));
      r.launch(lane, Priority.HIGH, cp -> order.add("high"));
      r.launch(lane, Priority.NORMAL, cp -> order.add("normal"));
      gate.open();
      r.close();
      assertThat(order).containsExactly("low", "high", "normal");
    }
  }

  @Test
  void aTaskSubmittedByATaskGoesAfterTheTasksAlreadyQueued() throws Exception {
    Engine engine = track(builder().build());
    Lane serial = engine.serial("s");
    List<String> order = new ArrayList<>();
    CountDownLatch allDone = new CountDownLatch(3);
    Gate gate = new Gate();
    realm.launch(serial, gate.work());
    gate.awaitStarted();
    realm.launch(
        serial,
        cp -> {
          order.add("A");
          realm.launch(
              serial,
              c -> {
                order.add("B");
                allDone.countDown();
                return "B";
              });
          allDone.countDown();
          return "A";
        });
    realm.launch(
        serial,
        cp -> {
          order.add("C");
          allDone.countDown();
          return "C";
        });
    gate.open();
    assertThat(allDone.await(20, TimeUnit.SECONDS)).isTrue();
    realm.close();
    assertThat(order).containsExactly("A", "C", "B");
  }

  @Test
  void blockingIsAllowedOnSerialAndSingle() throws Exception {
    Engine engine = track(builder().build());
    Job<String> done = realm.launch(engine.io(), cp -> "v");
    done.await();
    Job<String> a = realm.launch(engine.serial("s"), cp -> done.await().getOrThrow());
    Job<String> b = realm.launch(engine.single("one"), cp -> done.await().getOrThrow());
    assertThat(a.await()).isEqualTo(Outcome.success("v"));
    assertThat(b.await()).isEqualTo(Outcome.success("v"));
  }

  @Test
  void singleRunsEverythingOnTheSameOwnedThreadInOrder() throws Exception {
    Engine engine = track(builder().build());
    Lane single = engine.single("worker");
    assertThat(engine.liveThreads()).isEqualTo(0);
    int n = 2000;
    List<Thread> threads = new ArrayList<>();
    List<Integer> order = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      int id = i;
      realm.launch(
          single,
          cp -> {
            threads.add(Thread.currentThread());
            order.add(id);
            return id;
          });
    }
    realm.close();
    assertThat(engine.liveThreads()).isEqualTo(1);
    Thread owner = threads.get(0);
    assertThat(owner.getName().contains("single-worker")).isTrue();
    for (int i = 0; i < n; i++) {
      assertThat(threads.get(i)).isSameAs(owner);
      assertThat(order.get(i)).isEqualTo(i);
    }
    engine.shutdown();
    assertThat(engine.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
    owner.join(20_000);
    assertThat(owner.isAlive()).isFalse();
  }

  @Test
  void orderedLanesRefuseCallerRuns() throws Exception {
    Engine engine = track(builder().build());
    QueueLimit callerRuns = QueueLimit.of(4, RejectionPolicy.CALLER_RUNS);
    assertThatThrownBy(() -> engine.serial("s", callerRuns))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> engine.single("s", callerRuns))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void boundedSerialRejectsOrDropsTheOldestWaitingTask() throws Exception {
    Engine engine = track(builder().build());
    Lane rejecting = engine.serial("r", QueueLimit.of(1, RejectionPolicy.REJECT));
    Gate g1 = new Gate();
    realm.launch(rejecting, g1.work());
    g1.awaitStarted();
    Job<String> queued = realm.launch(rejecting, cp -> "queued");
    Job<String> rejected = realm.launch(rejecting, cp -> "rejected");
    assertThat(rejected.await().failureOrNull())
        .isInstanceOf(java.util.concurrent.RejectedExecutionException.class);
    g1.open();
    assertThat(queued.await()).isEqualTo(Outcome.success("queued"));

    Lane dropping = engine.single("d", QueueLimit.of(1, RejectionPolicy.DROP_OLDEST));
    Gate g2 = new Gate();
    AtomicReference<String> ranOld = new AtomicReference<>();
    realm.launch(dropping, g2.work());
    g2.awaitStarted();
    Job<String> old =
        realm.launch(
            dropping,
            cp -> {
              ranOld.set("ran");
              return "old";
            });
    Job<String> fresh = realm.launch(dropping, cp -> "fresh");
    assertThat(old.await().failureOrNull()).isInstanceOf(DroppedException.class);
    g2.open();
    assertThat(fresh.await()).isEqualTo(Outcome.success("fresh"));
    assertThat(ranOld.get()).isNull();
  }
}
