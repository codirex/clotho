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
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.codirex.clotho.support.RecordingHandler;
import org.codirex.clotho.support.ThreadLane;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ConcurrencyStressTest {

  private static final int ROUNDS = 20_000;

  private final RecordingHandler handler = new RecordingHandler();
  private final ThreadLane pool = new ThreadLane(4);

  @AfterEach
  void tearDown() {
    pool.close();
  }

  @Test
  void cancelRacingCompletionAlwaysAgreesWithTheOutcome() throws Exception {
    Realm realm = Realm.builder("stress-cancel").uncaughtHandler(handler).open();
    int cancelled = 0;
    int succeeded = 0;
    List<AtomicInteger> counters = new ArrayList<>();
    for (int i = 0; i < ROUNDS; i++) {
      CyclicBarrier go = new CyclicBarrier(2);
      Job<Integer> job =
          realm.launch(
              pool,
              cp -> {
                go.await(20, TimeUnit.SECONDS);
                return 1;
              });
      AtomicInteger callbacks = new AtomicInteger();
      counters.add(callbacks);
      job.onComplete(Lane.direct(), o -> callbacks.incrementAndGet());
      go.await(20, TimeUnit.SECONDS);
      boolean cancelReturned = job.cancel();
      Outcome<Integer> outcome = job.await(20, TimeUnit.SECONDS);
      assertThat(cancelReturned).isEqualTo(outcome.isCancelled());
      assertThat(job.outcomeOrNull()).isSameAs(outcome);
      assertThat(job.await()).isSameAs(outcome);
      if (outcome.isCancelled()) {
        cancelled++;
      } else {
        succeeded++;
      }
    }
    assertThat(cancelled + succeeded).isEqualTo(ROUNDS);
    // await() may return before callbacks ran; close() waits for them (they are outstanding work).
    assertThat(realm.close(20, TimeUnit.SECONDS)).isTrue();
    for (AtomicInteger c : counters) {
      assertThat(c.get()).isEqualTo(1);
    }
    assertThat(handler.errors()).isEmpty();
  }

  @Test
  void cancelBeforeAndAfterStartRacingStaysConsistent() throws Exception {
    Realm realm = Realm.builder("stress-start").uncaughtHandler(handler).open();
    for (int i = 0; i < ROUNDS; i++) {
      AtomicInteger ran = new AtomicInteger();
      Job<Integer> job =
          realm.launch(
              pool,
              cp -> {
                ran.incrementAndGet();
                return 1;
              });
      boolean cancelReturned = job.cancel();
      Outcome<Integer> outcome = job.await(20, TimeUnit.SECONDS);
      assertThat(cancelReturned).isEqualTo(outcome.isCancelled());
      assertThat(ran.get() <= 1).isTrue();
      if (outcome.isSuccess()) {
        assertThat(ran.get()).isEqualTo(1);
      }
    }
    assertThat(realm.close(20, TimeUnit.SECONDS)).isTrue();
  }

  @Test
  void raceBetweenTwoThreadsDecidesOnceAndCancelsTheLoser() throws Exception {
    Realm realm = Realm.builder("stress-race").uncaughtHandler(handler).open();
    List<AtomicInteger> counters = new ArrayList<>();
    for (int i = 0; i < 5_000; i++) {
      Job<Integer> a = realm.launch(pool, cp -> 1);
      Job<Integer> b = realm.launch(pool, cp -> 2);
      Job<Integer> race = realm.race(List.of(a, b), Ownership.OWNED);
      AtomicInteger callbacks = new AtomicInteger();
      counters.add(callbacks);
      race.onComplete(Lane.direct(), o -> callbacks.incrementAndGet());
      Outcome<Integer> outcome = race.await(20, TimeUnit.SECONDS);
      assertThat(outcome.isSuccess()).isTrue();
      int v = outcome.getOrThrow();
      assertThat(v == 1 || v == 2).isTrue();
      assertThat(race.await()).isSameAs(outcome);
    }
    assertThat(realm.close(20, TimeUnit.SECONDS)).isTrue();
    for (AtomicInteger c : counters) {
      assertThat(c.get()).isEqualTo(1);
    }
    assertThat(handler.errors()).isEmpty();
  }

  @Test
  void launchingWhileClosingEitherRunsToCompletionOrIsRejected() throws Exception {
    for (int round = 0; round < 200; round++) {
      Realm realm = Realm.builder("stress-close").uncaughtHandler(handler).open();
      int launchers = 4;
      CountDownLatch go = new CountDownLatch(1);
      List<Job<Integer>> accepted = java.util.Collections.synchronizedList(new ArrayList<>());
      AtomicInteger rejected = new AtomicInteger();
      List<Thread> threads = new ArrayList<>();
      for (int t = 0; t < launchers; t++) {
        Thread thread =
            new Thread(
                () -> {
                  try {
                    go.await();
                  } catch (InterruptedException e) {
                    return;
                  }
                  for (int k = 0; k < 200; k++) {
                    try {
                      accepted.add(realm.launch(pool, cp -> 7));
                    } catch (RealmClosedException e) {
                      rejected.incrementAndGet();
                    }
                  }
                });
        thread.setDaemon(true);
        thread.start();
        threads.add(thread);
      }
      go.countDown();
      realm.close();
      for (Thread thread : threads) {
        thread.join(20_000);
      }
      assertThat(realm.isClosed()).isTrue();
      assertThat(realm.outstanding()).isEqualTo(0);
      for (Job<Integer> job : accepted) {
        assertThat(job.isDone()).isTrue();
        assertThat(job.outcomeOrNull()).isEqualTo(Outcome.success(7));
      }
      assertThat(accepted.size() + rejected.get()).isEqualTo(launchers * 200);
    }
    assertThat(handler.errors()).isEmpty();
  }

  @Test
  void manyChainsCompleteAndCloseLeavesNothingOutstanding() throws Exception {
    Realm realm = Realm.builder("stress-chains").uncaughtHandler(handler).open();
    List<Job<Integer>> tails = new ArrayList<>();
    for (int i = 0; i < 5_000; i++) {
      tails.add(
          realm
              .launch(pool, cp -> 1)
              .then(pool, (v, cp) -> v + 1)
              .then(pool, (v, cp) -> v + 1)
              .recover(pool, (t, cp) -> -1));
    }
    realm.close();
    assertThat(realm.outstanding()).isEqualTo(0);
    for (Job<Integer> tail : tails) {
      assertThat(tail.outcomeOrNull()).isEqualTo(Outcome.success(3));
    }
    assertThat(handler.errors()).isEmpty();
  }
}
