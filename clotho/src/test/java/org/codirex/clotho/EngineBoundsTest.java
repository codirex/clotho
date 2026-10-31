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
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.codirex.clotho.support.RecordingHandler;
import org.junit.jupiter.api.Test;

/** Bounded queues: every accepted task ends up run, or with a documented final outcome. */
class EngineBoundsTest extends EngineTestBase {

  private final RecordingHandler handler = new RecordingHandler();
  private final Realm realm = Realm.builder("bounds").uncaughtHandler(handler).open();

  private Engine computeEngine(QueueLimit limit, int threads) {
    return track(builder().computeThreads(threads).computeQueue(limit).build());
  }

  @Test
  void rejectGivesAFailureWithRejectedExecutionException() throws Exception {
    Engine engine = computeEngine(QueueLimit.of(2, RejectionPolicy.REJECT), 1);
    Gate gate = new Gate();
    realm.launch(engine.compute(), gate.work());
    gate.awaitStarted();
    AtomicInteger ran = new AtomicInteger();
    Job<Integer> j1 = realm.launch(engine.compute(), cp -> ran.incrementAndGet());
    Job<Integer> j2 = realm.launch(engine.compute(), cp -> ran.incrementAndGet());
    Job<Integer> j3 = realm.launch(engine.compute(), cp -> ran.incrementAndGet());
    assertThat(j3.isDone()).isTrue();
    Throwable cause = j3.outcomeOrNull().failureOrNull();
    assertThat(cause).isInstanceOf(RejectedExecutionException.class);
    assertThat(cause).isNotInstanceOf(DroppedException.class);
    gate.open();
    realm.close();
    assertThat(j1.outcomeOrNull().isSuccess()).isTrue();
    assertThat(j2.outcomeOrNull().isSuccess()).isTrue();
    assertThat(ran.get()).isEqualTo(2);
  }

  @Test
  void dropOldestRemovesTheLongestWaitingTaskRegardlessOfPriority() throws Exception {
    Engine engine = computeEngine(QueueLimit.of(2, RejectionPolicy.DROP_OLDEST), 1);
    Gate gate = new Gate();
    realm.launch(engine.compute(), gate.work());
    gate.awaitStarted();
    AtomicInteger ran = new AtomicInteger();
    Job<String> oldHigh =
        realm.launch(
            engine.compute(),
            Priority.HIGH,
            cp -> {
              ran.incrementAndGet();
              return "oldHigh";
            });
    Job<String> newLow = realm.launch(engine.compute(), Priority.LOW, cp -> "newLow");
    Job<String> newest = realm.launch(engine.compute(), Priority.NORMAL, cp -> "newest");
    // the dropped job is finished at once, with a documented outcome, and never runs
    assertThat(oldHigh.isDone()).isTrue();
    assertThat(oldHigh.outcomeOrNull().failureOrNull()).isInstanceOf(DroppedException.class);
    gate.open();
    realm.close();
    assertThat(newLow.outcomeOrNull()).isEqualTo(Outcome.success("newLow"));
    assertThat(newest.outcomeOrNull()).isEqualTo(Outcome.success("newest"));
    assertThat(ran.get()).isEqualTo(0);
    assertThat(realm.outstanding()).isEqualTo(0);
  }

  @Test
  void aDroppedTaskOfAFailFastRealmFailsTheRealm() throws Exception {
    Realm failFast =
        Realm.builder("ff").policy(RealmPolicy.FAIL_FAST).uncaughtHandler(handler).open();
    Engine engine = computeEngine(QueueLimit.of(1, RejectionPolicy.DROP_OLDEST), 1);
    Gate gate = new Gate();
    realm.launch(engine.compute(), gate.work());
    gate.awaitStarted();
    Job<String> a = failFast.launch(engine.compute(), cp -> "a");
    Job<String> b = failFast.launch(engine.compute(), cp -> "b");
    assertThat(a.outcomeOrNull().failureOrNull()).isInstanceOf(DroppedException.class);
    assertThat(failFast.failure()).isInstanceOf(DroppedException.class);
    assertThat(b.outcomeOrNull().isCancelled()).isTrue();
    gate.open();
    failFast.close();
  }

  @Test
  void aDroppedRawTaskIsReportedToTheEngineErrorHandler() throws Exception {
    List<Throwable> seen = Collections.synchronizedList(new ArrayList<>());
    Engine engine =
        track(
            builder()
                .computeThreads(1)
                .computeQueue(QueueLimit.of(1, RejectionPolicy.DROP_OLDEST))
                .taskErrorHandler((t, e) -> seen.add(e))
                .build());
    Gate gate = new Gate();
    realm.launch(engine.compute(), gate.work());
    gate.awaitStarted();
    AtomicInteger ran = new AtomicInteger();
    engine.compute().execute(ran::incrementAndGet);
    engine.compute().execute(ran::incrementAndGet);
    assertThat(seen).hasSize(1);
    assertThat(seen.get(0)).isInstanceOf(DroppedException.class);
    gate.open();
    realm.close();
    awaitRan(ran, 1);
  }

  private static void awaitRan(AtomicInteger ran, int expected) {
    org.codirex.clotho.support.TestKit.awaitTrue(() -> ran.get() >= expected);
    assertThat(ran.get()).isEqualTo(expected);
  }

  @Test
  void aDroppedCompletionCallbackIsReportedAndDoesNotBlockTheRealmClose() throws Exception {
    List<Throwable> engineErrors = Collections.synchronizedList(new ArrayList<>());
    Engine engine =
        track(
            builder()
                .computeThreads(1)
                .computeQueue(QueueLimit.of(1, RejectionPolicy.DROP_OLDEST))
                .taskErrorHandler((t, e) -> engineErrors.add(e))
                .build());
    Gate gate = new Gate();
    realm.launch(engine.compute(), gate.work());
    gate.awaitStarted();
    Job<String> done = realm.launch(Lane.direct(), cp -> "v");
    AtomicInteger callbackRan = new AtomicInteger();
    done.onComplete(engine.compute(), o -> callbackRan.incrementAndGet());
    // the callback is queued; the next raw task drops it
    engine.compute().execute(() -> {});
    assertThat(handler.errors()).hasSize(1);
    assertThat(handler.errors().get(0)).isInstanceOf(DroppedException.class);
    gate.open();
    assertThat(realm.close(20, TimeUnit.SECONDS)).isTrue();
    assertThat(callbackRan.get()).isEqualTo(0);
  }

  @Test
  void callerRunsRefusesCallersThatAreNotKnownToBeOnALane() throws Exception {
    Engine engine = computeEngine(QueueLimit.of(1, RejectionPolicy.CALLER_RUNS), 1);
    Gate gate = new Gate();
    realm.launch(engine.compute(), gate.work());
    gate.awaitStarted();
    Job<String> queued = realm.launch(engine.compute(), cp -> "queued");
    AtomicInteger ran = new AtomicInteger();
    Job<String> refused =
        realm.launch(
            engine.compute(),
            cp -> {
              ran.incrementAndGet();
              return "no";
            });
    assertThat(refused.outcomeOrNull().failureOrNull())
        .isInstanceOf(RejectedExecutionException.class);
    assertThat(ran.get()).isEqualTo(0);
    gate.open();
    assertThat(queued.await()).isEqualTo(Outcome.success("queued"));
  }

  @Test
  void callerRunsFromAnAllowedLaneRunsInlineUnderTheTargetsBlockingPolicy() throws Exception {
    Engine engine =
        track(
            builder()
                .computeThreads(1)
                .computeQueue(QueueLimit.of(1, RejectionPolicy.CALLER_RUNS))
                .build());
    Gate gate = new Gate();
    realm.launch(engine.compute(), gate.work());
    gate.awaitStarted();
    realm.launch(engine.compute(), cp -> "fills the queue");
    Job<String> done = realm.launch(engine.io(), cp -> "x");
    done.await();
    AtomicReference<Thread> submitter = new AtomicReference<>();
    AtomicReference<Thread> inline = new AtomicReference<>();
    AtomicReference<Throwable> blockingAttempt = new AtomicReference<>();
    Job<String> outer =
        realm.launch(
            engine.io(),
            cp -> {
              submitter.set(Thread.currentThread());
              Job<String> inner =
                  realm.launch(
                      engine.compute(),
                      c -> {
                        inline.set(Thread.currentThread());
                        try {
                          done.await();
                        } catch (Throwable t) {
                          blockingAttempt.set(t);
                        }
                        return "ran inline";
                      });
              assertThat(inner.isDone()).isTrue();
              return inner.outcomeOrNull().getOrThrow();
            });
    assertThat(outer.await()).isEqualTo(Outcome.success("ran inline"));
    assertThat(inline.get()).isSameAs(submitter.get());
    // inline work runs with the policy of the lane it was meant for: compute forbids blocking
    assertThat(blockingAttempt.get()).isInstanceOf(BlockingNotAllowedException.class);
    gate.open();
    realm.close();
  }

  @Test
  void callerRunsNeverPutsAllowedWorkOnAThreadThatForbidsBlocking() throws Exception {
    Engine engine =
        track(
            builder()
                .computeThreads(2)
                .ioMaxThreads(1)
                .ioQueue(QueueLimit.of(1, RejectionPolicy.CALLER_RUNS))
                .build());
    Gate ioGate = new Gate();
    realm.launch(engine.io(), ioGate.work());
    ioGate.awaitStarted();
    realm.launch(engine.io(), cp -> "fills the io queue");
    AtomicInteger ran = new AtomicInteger();
    Job<Outcome<String>> fromCompute =
        realm.launch(
            engine.compute(),
            cp -> {
              Job<String> inner =
                  realm.launch(
                      engine.io(),
                      c -> {
                        ran.incrementAndGet();
                        return "blocking work";
                      });
              return inner.outcomeOrNull();
            });
    Outcome<String> inner = fromCompute.await().getOrThrow();
    assertThat(inner.failureOrNull()).isInstanceOf(RejectedExecutionException.class);
    assertThat(ran.get()).isEqualTo(0);
    ioGate.open();
    realm.close();
  }

  @Test
  void callerRunsFromAForbiddenLaneIsFineForAForbiddenTarget() throws Exception {
    Engine engine =
        track(
            builder()
                .computeThreads(2)
                .computeQueue(QueueLimit.of(1, RejectionPolicy.CALLER_RUNS))
                .build());
    CountDownLatch submitterRunning = new CountDownLatch(1);
    CountDownLatch go = new CountDownLatch(1);
    AtomicReference<Thread> submitter = new AtomicReference<>();
    AtomicReference<Thread> inline = new AtomicReference<>();
    Job<String> submitting =
        realm.launch(
            engine.compute(),
            cp -> {
              submitter.set(Thread.currentThread());
              submitterRunning.countDown();
              go.await();
              Job<String> inner =
                  realm.launch(
                      engine.compute(),
                      c -> {
                        inline.set(Thread.currentThread());
                        return "inline";
                      });
              return inner.outcomeOrNull().getOrThrow();
            });
    // wait for each worker before the next submission, so the bounded queue never looks full
    assertThat(submitterRunning.await(20, TimeUnit.SECONDS)).isTrue();
    Gate other = new Gate();
    realm.launch(engine.compute(), other.work());
    other.awaitStarted();
    realm.launch(engine.compute(), cp -> "fills the queue");
    go.countDown();
    assertThat(submitting.await()).isEqualTo(Outcome.success("inline"));
    assertThat(inline.get()).isSameAs(submitter.get());
    other.open();
    realm.close();
  }

  @Test
  void boundedQueueNeverHoldsMoreThanItsCapacity() throws Exception {
    int capacity = 5;
    Engine engine = computeEngine(QueueLimit.of(capacity, RejectionPolicy.REJECT), 1);
    Gate gate = new Gate();
    realm.launch(engine.compute(), gate.work());
    gate.awaitStarted();
    int accepted = 0;
    for (int i = 0; i < 50; i++) {
      Job<Integer> j = realm.launch(engine.compute(), cp -> 1);
      if (!j.isDone()) {
        accepted++;
      }
    }
    assertThat(accepted).isEqualTo(capacity);
    assertThat(((PoolLane) engine.compute()).queuedCount()).isEqualTo(capacity);
    gate.open();
    realm.close();
    assertThat(((PoolLane) engine.compute()).queuedCount()).isEqualTo(0);
  }
}
