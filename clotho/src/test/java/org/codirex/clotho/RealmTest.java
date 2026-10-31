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
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.codirex.clotho.support.ManualLane;
import org.codirex.clotho.support.RecordingHandler;
import org.codirex.clotho.support.ThreadLane;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class RealmTest {

  private final RecordingHandler handler = new RecordingHandler();
  private final ManualLane lane = new ManualLane();
  private final List<ThreadLane> threadLanes = new ArrayList<>();

  @AfterEach
  void tearDown() {
    for (ThreadLane l : threadLanes) {
      l.close();
    }
  }

  private Realm realm(RealmPolicy policy) {
    return Realm.builder("test").policy(policy).uncaughtHandler(handler).open();
  }

  private ThreadLane threads(int n) {
    ThreadLane l = new ThreadLane(n);
    threadLanes.add(l);
    return l;
  }

  @Test
  void emptyRealmClosesImmediately() throws Exception {
    Realm realm = realm(RealmPolicy.SUPERVISED);
    assertThat(realm.isClosing()).isFalse();
    realm.close();
    assertThat(realm.isClosing()).isTrue();
    assertThat(realm.isClosed()).isTrue();
    realm.close();
    assertThat(realm.close(1, TimeUnit.MILLISECONDS)).isTrue();
  }

  @Test
  void closeWaitsForChildrenWithoutCancellingThem() throws Exception {
    Realm realm = realm(RealmPolicy.SUPERVISED);
    ThreadLane pool = threads(2);
    CountDownLatch release = new CountDownLatch(1);
    CountDownLatch started = new CountDownLatch(1);
    Job<String> job =
        realm.launch(
            pool,
            cp -> {
              started.countDown();
              release.await();
              return "finished";
            });
    assertThat(started.await(20, TimeUnit.SECONDS)).isTrue();
    AtomicBoolean closeReturned = new AtomicBoolean();
    Thread closer =
        start(
            "closer",
            () -> {
              realm.close();
              closeReturned.set(true);
            });
    awaitTrue(realm::isClosing);
    assertThat(realm.isClosed()).isFalse();
    assertThat(closeReturned.get()).isFalse();
    assertThat(job.isCancellationRequested()).isFalse();
    release.countDown();
    join(closer);
    assertThat(closeReturned.get()).isTrue();
    assertThat(realm.isClosed()).isTrue();
    assertThat(job.await()).isEqualTo(Outcome.success("finished"));
  }

  @Test
  void newWorkIsRejectedOnceClosingStarted() throws Exception {
    Realm realm = realm(RealmPolicy.SUPERVISED);
    ThreadLane pool = threads(2);
    CountDownLatch release = new CountDownLatch(1);
    Job<String> job =
        realm.launch(
            pool,
            cp -> {
              release.await();
              return "x";
            });
    Thread closer = start("closer", realm::close);
    awaitTrue(realm::isClosing);
    assertThatThrownBy(() -> realm.launch(lane, cp -> "late"))
        .isInstanceOf(RealmClosedException.class);
    assertThatThrownBy(() -> job.then(lane, (v, cp) -> v)).isInstanceOf(RealmClosedException.class);
    release.countDown();
    join(closer);
    assertThat(realm.isClosed()).isTrue();
  }

  @Test
  void derivedJobsCreatedBeforeCloseStillCompleteAndCloseWaitsForThem() throws Exception {
    Realm realm = realm(RealmPolicy.SUPERVISED);
    Job<Integer> a = realm.launch(lane, cp -> 1);
    Job<Integer> b = a.then(lane, (v, cp) -> v + 1);
    Thread closer = start("closer", realm::close);
    awaitTrue(realm::isClosing);
    assertThat(realm.isClosed()).isFalse();
    lane.runAll();
    join(closer);
    assertThat(b.await()).isEqualTo(Outcome.success(2));
    assertThat(realm.isClosed()).isTrue();
  }

  @Test
  void completionCallbacksAreOutstandingWork() throws Exception {
    Realm realm = realm(RealmPolicy.SUPERVISED);
    ManualLane callbackLane = new ManualLane();
    Job<Integer> a = realm.launch(lane, cp -> 1);
    AtomicInteger calls = new AtomicInteger();
    a.onComplete(callbackLane, o -> calls.incrementAndGet());
    lane.runAll();
    assertThat(realm.outstanding()).isEqualTo(1);
    Thread closer = start("closer", realm::close);
    awaitTrue(realm::isClosing);
    assertThat(realm.isClosed()).isFalse();
    callbackLane.runAll();
    join(closer);
    assertThat(calls.get()).isEqualTo(1);
    assertThat(realm.isClosed()).isTrue();
  }

  @Test
  void closeTimeoutCancelsInterruptsReportsOnceAndNeverClaimsTheyStopped() throws Exception {
    Realm realm = realm(RealmPolicy.SUPERVISED);
    ThreadLane pool = threads(2);
    CountDownLatch release = new CountDownLatch(1);
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch interruptSeen = new CountDownLatch(1);
    Job<String> stubborn =
        realm.launch(
            pool,
            cp -> {
              started.countDown();
              boolean seen = false;
              for (; ; ) {
                try {
                  release.await();
                  break;
                } catch (InterruptedException e) {
                  if (!seen) {
                    seen = true;
                    interruptSeen.countDown();
                  }
                }
              }
              return "ignored everything";
            });
    assertThat(started.await(20, TimeUnit.SECONDS)).isTrue();

    assertThat(realm.close(50, TimeUnit.MILLISECONDS)).isFalse();
    assertThat(interruptSeen.await(20, TimeUnit.SECONDS)).isTrue();
    assertThat(realm.isClosing()).isTrue();
    assertThat(realm.isClosed()).isFalse();
    assertThat(stubborn.isCancellationRequested()).isTrue();
    assertThat(stubborn.isDone()).isFalse();
    assertThat(handler.errors()).hasSize(1);
    assertThat(handler.errors().get(0)).isInstanceOf(LeakedJobException.class);

    assertThat(realm.close(10, TimeUnit.MILLISECONDS)).isFalse();
    assertThat(handler.errors()).hasSize(1);

    release.countDown();
    assertThat(realm.close(20, TimeUnit.SECONDS)).isTrue();
    assertThat(realm.isClosed()).isTrue();
    assertThat(stubborn.await().isCancelled()).isTrue();
    assertThat(handler.errors()).hasSize(1);
  }

  @Test
  void jobsLaunchedBeforeClosingButScheduledLaterAreCancelledByTimeout() throws Exception {
    Realm realm = realm(RealmPolicy.SUPERVISED);
    AtomicBoolean ran = new AtomicBoolean();
    Job<String> queued =
        realm.launch(
            lane,
            cp -> {
              ran.set(true);
              return "x";
            });
    assertThat(realm.close(10, TimeUnit.MILLISECONDS)).isTrue();
    assertThat(queued.await().isCancelled()).isTrue();
    lane.runAll();
    assertThat(ran.get()).isFalse();
    assertThat(handler.errors()).isEmpty();
  }

  @Test
  void closeKeepsTheInterruptFlagAndCancelsWhenInterrupted() throws Exception {
    Realm realm = realm(RealmPolicy.SUPERVISED);
    Job<String> pending = realm.launch(lane, cp -> "never");
    AtomicBoolean flagAfterClose = new AtomicBoolean();
    Thread t =
        start(
            "interrupted-closer",
            () -> {
              Thread.currentThread().interrupt();
              realm.close();
              flagAfterClose.set(Thread.currentThread().isInterrupted());
            });
    join(t);
    assertThat(flagAfterClose.get()).isTrue();
    assertThat(pending.await().isCancelled()).isTrue();
    assertThat(realm.isClosed()).isTrue();
  }

  @Test
  void timedCloseThrowsWhenInterruptedButKeepsClosing() throws Exception {
    Realm realm = realm(RealmPolicy.SUPERVISED);
    Job<String> pending = realm.launch(lane, cp -> "never");
    AtomicBoolean threw = new AtomicBoolean();
    Thread t =
        start(
            "interrupted-closer",
            () -> {
              Thread.currentThread().interrupt();
              try {
                realm.close(20, TimeUnit.SECONDS);
              } catch (InterruptedException e) {
                threw.set(true);
              }
            });
    join(t);
    assertThat(threw.get()).isTrue();
    assertThat(realm.isClosing()).isTrue();
    assertThat(pending.await().isCancelled()).isTrue();
  }

  @Test
  void cancelCancelsCurrentJobsOnlyAndLaterJobsRunNormally() throws Exception {
    Realm realm = realm(RealmPolicy.SUPERVISED);
    Job<String> a = realm.launch(lane, cp -> "a");
    Job<String> b = realm.launch(lane, cp -> "b");
    realm.cancel();
    Job<String> c = realm.launch(lane, cp -> "c");
    lane.runAll();
    assertThat(a.await().isCancelled()).isTrue();
    assertThat(b.await().isCancelled()).isTrue();
    assertThat(c.await()).isEqualTo(Outcome.success("c"));
  }

  @Test
  void failFastCancelsSiblingsRecordsAndReportsTheFailureOnce() throws Exception {
    Realm realm = realm(RealmPolicy.FAIL_FAST);
    IOException boom = new IOException("boom");
    Job<String> bad =
        realm.launch(
            lane,
            cp -> {
              throw boom;
            });
    AtomicBoolean ran = new AtomicBoolean();
    Job<String> s1 =
        realm.launch(
            lane,
            cp -> {
              ran.set(true);
              return "s1";
            });
    Job<String> s2 = realm.launch(lane, cp -> "s2");
    lane.runAll();
    assertThat(bad.await().failureOrNull()).isSameAs(boom);
    assertThat(s1.await().isCancelled()).isTrue();
    assertThat(s2.await().isCancelled()).isTrue();
    assertThat(ran.get()).isFalse();
    assertThat(realm.failure()).isSameAs(boom);
    assertThat(handler.errors()).hasSize(1);
    assertThat(handler.errors().get(0)).isSameAs(boom);
  }

  @Test
  void failFastLaterLaunchesAreBornCancelled() throws Exception {
    Realm realm = realm(RealmPolicy.FAIL_FAST);
    realm.launch(
        lane,
        cp -> {
          throw new IllegalStateException("first");
        });
    lane.runAll();
    AtomicBoolean ran = new AtomicBoolean();
    Job<String> late =
        realm.launch(
            lane,
            cp -> {
              ran.set(true);
              return "late";
            });
    assertThat(late.isDone()).isTrue();
    lane.runAll();
    assertThat(late.await().isCancelled()).isTrue();
    assertThat(ran.get()).isFalse();
  }

  @Test
  void failFastKeepsTheFirstFailureOnly() throws Exception {
    Realm realm = realm(RealmPolicy.FAIL_FAST);
    IOException first = new IOException("first");
    realm.launch(
        lane,
        cp -> {
          throw first;
        });
    realm.launch(
        lane,
        cp -> {
          throw new IOException("second");
        });
    lane.runAll();
    assertThat(realm.failure()).isSameAs(first);
    assertThat(handler.errors()).hasSize(1);
  }

  @Test
  void failFastIgnoresFailuresOfDerivedJobs() throws Exception {
    Realm realm = realm(RealmPolicy.FAIL_FAST);
    Job<Integer> root = realm.launch(lane, cp -> 1);
    Job<Integer> derived =
        root.then(
            lane,
            (v, cp) -> {
              throw new IllegalStateException("derived");
            });
    Job<String> sibling = realm.launch(lane, cp -> "sibling");
    lane.runAll();
    assertThat(derived.await().isFailure()).isTrue();
    assertThat(sibling.await()).isEqualTo(Outcome.success("sibling"));
    assertThat(realm.failure()).isNull();
    assertThat(handler.errors()).isEmpty();
  }

  @Test
  void supervisedFailureDoesNotAffectSiblings() throws Exception {
    Realm realm = realm(RealmPolicy.SUPERVISED);
    Job<String> bad =
        realm.launch(
            lane,
            cp -> {
              throw new IOException("boom");
            });
    Job<String> good = realm.launch(lane, cp -> "good");
    lane.runAll();
    assertThat(bad.await().isFailure()).isTrue();
    assertThat(good.await()).isEqualTo(Outcome.success("good"));
    assertThat(realm.failure()).isNull();
    assertThat(handler.errors()).isEmpty();
  }

  @Test
  void defaultHandlerIsUsedWhenTheRealmHasNone() throws Exception {
    RecordingHandler global = new RecordingHandler();
    UncaughtHandler previous = Clotho.defaultUncaughtHandler();
    Clotho.setDefaultUncaughtHandler(global);
    try {
      Realm realm = Realm.open("default-handler");
      Job<Integer> job = realm.launch(lane, cp -> 1);
      IllegalStateException bad = new IllegalStateException("callback");
      job.onComplete(
          Lane.direct(),
          o -> {
            throw bad;
          });
      lane.runAll();
      assertThat(global.errors()).hasSize(1);
      assertThat(global.errors().get(0)).isSameAs(bad);
    } finally {
      Clotho.setDefaultUncaughtHandler(previous);
    }
  }

  @Test
  void aThrowingHandlerDoesNotBreakTheRealm() throws Exception {
    Realm realm =
        Realm.builder("bad-handler")
            .uncaughtHandler(
                (r, e) -> {
                  throw new IllegalStateException("handler broke");
                })
            .open();
    Job<Integer> job = realm.launch(lane, cp -> 1);
    AtomicInteger calls = new AtomicInteger();
    job.onComplete(
        Lane.direct(),
        o -> {
          throw new IllegalStateException("callback");
        });
    job.onComplete(Lane.direct(), o -> calls.incrementAndGet());
    lane.runAll();
    assertThat(calls.get()).isEqualTo(1);
    assertThat(job.await().isSuccess()).isTrue();
    realm.close();
    assertThat(realm.isClosed()).isTrue();
  }
}
