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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.codirex.clotho.support.LoopHost;
import org.codirex.clotho.support.ManualLane;
import org.codirex.clotho.support.RecordingHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class RealmCloseAsyncTest extends EngineTestBase {

  private final RecordingHandler handler = new RecordingHandler();
  private final Realm realm = Realm.builder("async").uncaughtHandler(handler).open();
  private final ManualLane lane = new ManualLane();

  @AfterEach
  void reset() {
    Clotho.uninstallMainLane();
  }

  @Test
  void returnsAtOnceWhileAJobIgnoresCancellationAndTheRealmStaysClosing() throws Exception {
    Engine engine = track(builder().build());
    Gate gate = new Gate();
    Job<String> stubborn = realm.launch(engine.io(), gate.stubbornWork());
    gate.awaitStarted();
    Realm.Closing closing = realm.closeAsync();
    assertThat(closing.realm()).isSameAs(realm);
    assertThat(closing.isClosed()).isFalse();
    assertThat(realm.isClosing()).isTrue();
    assertThat(realm.isClosed()).isFalse();
    assertThat(stubborn.isCancellationRequested()).isTrue();
    assertThat(stubborn.isDone()).isFalse();
    assertThatThrownBy(() -> realm.launch(lane, cp -> "late"))
        .isInstanceOf(RealmClosedException.class);
    assertThat(handler.errors()).isEmpty();
    gate.open();
    awaitTrue(closing::isClosed);
    assertThat(realm.isClosed()).isTrue();
    assertThat(stubborn.await().isCancelled()).isTrue();
  }

  @Test
  void cancelsPendingJobsAndIsClosedAtOnceWhenNothingIsRunning() throws Exception {
    AtomicBoolean ran = new AtomicBoolean();
    Job<String> a =
        realm.launch(
            lane,
            cp -> {
              ran.set(true);
              return "a";
            });
    Job<String> b = a.then(lane, (v, cp) -> v);
    Realm.Closing closing = realm.closeAsync();
    assertThat(closing.isClosed()).isTrue();
    assertThat(a.outcomeOrNull().isCancelled()).isTrue();
    assertThat(b.outcomeOrNull().isCancelled()).isTrue();
    lane.runAll();
    assertThat(ran.get()).isFalse();
  }

  @Test
  void callingItAgainReturnsTheSameHandle() throws Exception {
    Realm.Closing first = realm.closeAsync();
    assertThat(realm.closeAsync()).isSameAs(first);
    realm.close();
    assertThat(realm.closeAsync()).isSameAs(first);
  }

  @Test
  void doesNotInterruptTheThreadsRunningJobs() throws Exception {
    Engine engine = track(builder().build());
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch cancelSeen = new CountDownLatch(1);
    AtomicBoolean interrupted = new AtomicBoolean(true);
    CountDownLatch releaseJob = new CountDownLatch(1);
    realm.launch(
        engine.io(),
        cp -> {
          started.countDown();
          releaseJob.await();
          interrupted.set(Thread.currentThread().isInterrupted());
          cancelSeen.countDown();
          return "x";
        });
    assertThat(started.await(20, TimeUnit.SECONDS)).isTrue();
    realm.closeAsync();
    releaseJob.countDown();
    assertThat(cancelSeen.await(20, TimeUnit.SECONDS)).isTrue();
    assertThat(interrupted.get()).isFalse();
  }

  @Test
  void onClosedRunsExactlyOnceAfterTheRealmIsClosedOnTheGivenLaneInOrder() throws Exception {
    Engine engine = track(builder().build());
    Gate gate = new Gate();
    realm.launch(engine.io(), gate.work());
    gate.awaitStarted();
    ManualLane callbackLane = new ManualLane();
    List<Integer> order = new ArrayList<>();
    Realm.Closing closing = realm.closeAsync();
    closing.onClosed(callbackLane, () -> order.add(1));
    closing.onClosed(callbackLane, () -> order.add(2));
    closing.onClosed(callbackLane, () -> order.add(3));
    assertThat(callbackLane.size()).isEqualTo(0);
    gate.open();
    awaitTrue(closing::isClosed);
    // the callbacks are handed to their lane right after the realm became closed, not atomically
    // with isClosed()
    awaitTrue(() -> callbackLane.size() == 3);
    assertThat(order).isEmpty();
    callbackLane.runAll();
    assertThat(order).containsExactly(1, 2, 3);
    closing.onClosed(callbackLane, () -> order.add(4));
    assertThat(callbackLane.size()).isEqualTo(1);
    callbackLane.runAll();
    assertThat(order).containsExactly(1, 2, 3, 4);
  }

  @Test
  void closeCallbacksAreNotOutstandingWorkOfTheRealm() throws Exception {
    ManualLane callbackLane = new ManualLane();
    Realm.Closing closing = realm.closeAsync();
    closing.onClosed(callbackLane, () -> {});
    assertThat(realm.outstanding()).isEqualTo(0);
    assertThat(realm.isClosed()).isTrue();
    callbackLane.runAll();
  }

  @Test
  void aThrowingOrRejectedCloseCallbackIsReportedAndOthersStillRun() throws Exception {
    Realm.Closing closing = realm.closeAsync();
    IllegalStateException bad = new IllegalStateException("callback");
    AtomicInteger ran = new AtomicInteger();
    closing.onClosed(
        Lane.direct(),
        () -> {
          throw bad;
        });
    RejectedExecutionException refusal = new RejectedExecutionException("no");
    closing.onClosed(
        task -> {
          throw refusal;
        },
        () -> ran.incrementAndGet());
    closing.onClosed(Lane.direct(), ran::incrementAndGet);
    assertThat(ran.get()).isEqualTo(1);
    assertThat(handler.errors()).hasSize(2);
    assertThat(handler.errors().get(0)).isSameAs(bad);
    assertThat(handler.errors().get(1)).isSameAs(refusal);
  }

  @Test
  void awaitReportsTimeoutAndCompletion() throws Exception {
    Engine engine = track(builder().build());
    Gate gate = new Gate();
    realm.launch(engine.io(), gate.stubbornWork());
    gate.awaitStarted();
    Realm.Closing closing = realm.closeAsync();
    assertThat(closing.await(20, TimeUnit.MILLISECONDS)).isFalse();
    gate.open();
    assertThat(closing.await(20, TimeUnit.SECONDS)).isTrue();
  }

  @Test
  void closeWithATimeoutKeepsItsMeaningAfterCloseAsync() throws Exception {
    Engine engine = track(builder().build());
    Gate gate = new Gate();
    CountDownLatch interruptSeen = new CountDownLatch(1);
    CountDownLatch finish = new CountDownLatch(1);
    Job<String> stubborn =
        realm.launch(
            engine.io(),
            cp -> {
              gate.started.countDown();
              boolean seen = false;
              for (; ; ) {
                try {
                  finish.await();
                  break;
                } catch (InterruptedException e) {
                  if (!seen) {
                    seen = true;
                    interruptSeen.countDown();
                  }
                }
              }
              return "ignored";
            });
    gate.awaitStarted();
    Realm.Closing closing = realm.closeAsync();
    assertThat(realm.close(50, TimeUnit.MILLISECONDS)).isFalse();
    assertThat(interruptSeen.await(20, TimeUnit.SECONDS)).isTrue();
    assertThat(handler.errors()).hasSize(1);
    assertThat(handler.errors().get(0)).isInstanceOf(LeakedJobException.class);
    assertThat(closing.isClosed()).isFalse();
    finish.countDown();
    assertThat(realm.close(20, TimeUnit.SECONDS)).isTrue();
    assertThat(closing.isClosed()).isTrue();
    assertThat(stubborn.await().isCancelled()).isTrue();
    assertThat(handler.errors()).hasSize(1);
  }

  @Test
  void closeAfterCloseAsyncWaitsForEverything() throws Exception {
    Engine engine = track(builder().build());
    Gate gate = new Gate();
    realm.launch(engine.io(), gate.stubbornWork());
    gate.awaitStarted();
    realm.closeAsync();
    AtomicBoolean returned = new AtomicBoolean();
    Thread closer =
        start(
            "closer",
            () -> {
              realm.close();
              returned.set(true);
            });
    awaitTrue(realm::isClosing);
    assertThat(returned.get()).isFalse();
    gate.open();
    join(closer);
    assertThat(returned.get()).isTrue();
    assertThat(realm.isClosed()).isTrue();
  }

  @Test
  void itMayBeCalledWhereBlockingIsForbidden() throws Exception {
    Engine engine = track(builder().build());
    Realm other = Realm.open("other");
    Job<Realm.Closing> fromCompute = realm.launch(engine.compute(), cp -> other.closeAsync());
    assertThat(fromCompute.await().getOrThrow().isClosed()).isTrue();

    LoopHost host = new LoopHost();
    try {
      MainLane main = MainLane.of("main", host);
      Clotho.install(main);
      Realm third = Realm.open("third");
      List<Realm.Closing> result = new ArrayList<>();
      host.runOnMain(() -> result.add(third.closeAsync()));
      assertThat(result).hasSize(1);
      assertThat(result.get(0).isClosed()).isTrue();
    } finally {
      host.quit();
    }
  }

  @Test
  void aFailFastRealmClosesAsyncToo() throws Exception {
    Realm failFast =
        Realm.builder("ff").policy(RealmPolicy.FAIL_FAST).uncaughtHandler(handler).open();
    Job<String> a = failFast.launch(lane, cp -> "a");
    Realm.Closing closing = failFast.closeAsync();
    assertThat(closing.isClosed()).isTrue();
    assertThat(a.outcomeOrNull().isCancelled()).isTrue();
    assertThat(failFast.failure()).isNull();
  }
}
