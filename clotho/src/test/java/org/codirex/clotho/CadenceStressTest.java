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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.locks.LockSupport;
import org.codirex.clotho.support.RecordingHandler;
import org.junit.jupiter.api.Test;

/**
 * Stress tests of the timers. They show that the invariants held in the interleavings that
 * happened; they are not a proof (jcstress in phase 6 covers the wheel and the job state machine).
 */
class CadenceStressTest extends EngineTestBase {

  private static final long MS = TimeUnit.MILLISECONDS.toNanos(1);

  private final RecordingHandler handler = new RecordingHandler();

  private Engine engine() {
    return track(
        builder().ioMaxThreads(8).computeThreads(4).timerTick(1, TimeUnit.MILLISECONDS).build());
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
  void thousandsOfTimersWithConcurrentCancellationEndExactlyOnce() throws Exception {
    Engine engine = engine();
    Realm realm = Realm.builder("stress").cadence(engine.cadence()).uncaughtHandler(handler).open();
    int n = 6_000;
    AtomicReferenceArray<Job<Long>> jobs = new AtomicReferenceArray<>(n);
    AtomicInteger[] ran = new AtomicInteger[n];
    long[] begin = new long[n];
    long[] delayMs = new long[n];
    for (int i = 0; i < n; i++) {
      ran[i] = new AtomicInteger();
    }
    AtomicBoolean creating = new AtomicBoolean(true);
    List<Thread> cancellers = new ArrayList<>();
    for (int c = 0; c < 4; c++) {
      Thread t =
          new Thread(
              () -> {
                ThreadLocalRandom rnd = ThreadLocalRandom.current();
                while (creating.get()) {
                  Job<Long> job = jobs.get(rnd.nextInt(n));
                  if (job != null && rnd.nextInt(3) == 0) {
                    job.cancel();
                  }
                }
              });
      t.setDaemon(true);
      t.start();
      cancellers.add(t);
    }
    List<Thread> seen = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      int id = i;
      delayMs[i] = ThreadLocalRandom.current().nextInt(41);
      begin[i] = System.nanoTime();
      jobs.set(
          i,
          realm.after(
              engine.io(),
              delayMs[i],
              TimeUnit.MILLISECONDS,
              cp -> {
                ran[id].incrementAndGet();
                return System.nanoTime();
              }));
      if (i % 1000 == 0) {
        seen.addAll(engine.liveThreadSnapshot());
      }
    }
    creating.set(false);
    for (Thread t : cancellers) {
      t.join(20_000);
    }
    assertThat(realm.close(60, TimeUnit.SECONDS)).isTrue();
    int succeeded = 0;
    int cancelled = 0;
    for (int i = 0; i < n; i++) {
      Outcome<Long> o = jobs.get(i).outcomeOrNull();
      assertThat(o).isNotNull();
      if (o.isSuccess()) {
        assertThat(ran[i].get()).isEqualTo(1);
        assertThat(o.getOrThrow() - begin[i] >= delayMs[i] * MS).isTrue();
        succeeded++;
      } else {
        assertThat(o.isCancelled()).isTrue();
        assertThat(ran[i].get() <= 1).isTrue();
        cancelled++;
      }
    }
    assertThat(succeeded + cancelled).isEqualTo(n);
    assertThat(succeeded > 0 && cancelled > 0).isTrue();
    assertThat(engine.cadence().pending()).isEqualTo(0);
    awaitTrue(() -> !hasCadenceThread(engine));
    assertThat(handler.errors()).isEmpty();
    assertThat(engine.close(30, TimeUnit.SECONDS)).isTrue();
    seen.addAll(engine.liveThreadSnapshot());
    for (Thread t : seen) {
      t.join(20_000);
      assertThat(t.isAlive()).isFalse();
    }
  }

  @Test
  void engineShutdownRacingTimerCreationLeavesNoJobPendingAndNoThread() throws Exception {
    Engine engine = engine();
    Realm realm =
        Realm.builder("shutdown-race").cadence(engine.cadence()).uncaughtHandler(handler).open();
    int producers = 4;
    int each = 1_500;
    int total = producers * each;
    AtomicInteger[] ran = new AtomicInteger[total];
    for (int i = 0; i < total; i++) {
      ran[i] = new AtomicInteger();
    }
    List<Job<Integer>> all = Collections.synchronizedList(new ArrayList<>());
    AtomicInteger submitted = new AtomicInteger();
    Lane[] lanes = {engine.compute(), engine.io(), engine.serial("s")};
    List<Thread> threads = new ArrayList<>();
    for (int p = 0; p < producers; p++) {
      int base = p * each;
      Thread t =
          new Thread(
              () -> {
                ThreadLocalRandom rnd = ThreadLocalRandom.current();
                for (int i = 0; i < each; i++) {
                  int id = base + i;
                  all.add(
                      realm.after(
                          lanes[rnd.nextInt(lanes.length)],
                          rnd.nextInt(30),
                          TimeUnit.MILLISECONDS,
                          cp -> ran[id].incrementAndGet() * 0 + id));
                  submitted.incrementAndGet();
                }
              });
      t.setDaemon(true);
      t.start();
      threads.add(t);
    }
    awaitTrue(() -> submitted.get() > 1_500);
    List<Thread> owned = engine.liveThreadSnapshot();
    engine.shutdownNow();
    for (Thread t : threads) {
      t.join(60_000);
      assertThat(t.isAlive()).isFalse();
    }
    assertThat(engine.awaitTermination(60, TimeUnit.SECONDS)).isTrue();
    assertThat(realm.close(60, TimeUnit.SECONDS)).isTrue();
    assertThat(all).hasSize(total);
    for (Job<Integer> job : all) {
      Outcome<Integer> o = job.outcomeOrNull();
      assertThat(o).isNotNull();
      if (o.isSuccess()) {
        assertThat(ran[o.getOrThrow()].get()).isEqualTo(1);
      } else {
        assertThat(o.failureOrNull()).isInstanceOf(RejectedExecutionException.class);
      }
    }
    assertThat(engine.cadence().pending()).isEqualTo(0);
    assertThat(realm.outstanding()).isEqualTo(0);
    for (Thread t : owned) {
      t.join(20_000);
      assertThat(t.isAlive()).isFalse();
    }
    assertThat(engine.liveThreads()).isEqualTo(0);
  }

  @Test
  void aRunNeverStartsAfterCancelReturnedExceptTheOneAlreadyInFlight() throws Exception {
    Engine engine = engine();
    Realm realm =
        Realm.builder("cancel-race").cadence(engine.cadence()).uncaughtHandler(handler).open();
    for (int round = 0; round < 300; round++) {
      AtomicInteger started = new AtomicInteger();
      Job<Void> job =
          realm.every(engine.io(), 0, 1, TimeUnit.MILLISECONDS, cp -> started.incrementAndGet());
      LockSupport.parkNanos(ThreadLocalRandom.current().nextInt(3_000_000));
      boolean cancelled = job.cancel();
      int atCancel = started.get();
      Outcome<Void> outcome = job.await(20, TimeUnit.SECONDS);
      if (cancelled) {
        assertThat(outcome.isCancelled()).isTrue();
        assertThat(started.get() <= atCancel + 1).isTrue();
      }
      assertThat(started.get() <= atCancel + 1).isTrue();
    }
    assertThat(realm.close(30, TimeUnit.SECONDS)).isTrue();
    assertThat(engine.cadence().pending()).isEqualTo(0);
    assertThat(handler.errors()).isEmpty();
  }

  @Test
  void manyPeriodicJobsNeverOverlapThemselvesAndCloseCancelsThemAll() throws Exception {
    Engine engine = engine();
    Realm realm = Realm.builder("many").cadence(engine.cadence()).uncaughtHandler(handler).open();
    int n = 300;
    AtomicBoolean overlap = new AtomicBoolean();
    AtomicLong totalRuns = new AtomicLong();
    List<Job<Void>> jobs = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      AtomicInteger inside = new AtomicInteger();
      long period = 1 + ThreadLocalRandom.current().nextInt(3);
      Action action =
          cp -> {
            if (inside.incrementAndGet() != 1) {
              overlap.set(true);
            }
            totalRuns.incrementAndGet();
            Thread.yield();
            inside.decrementAndGet();
          };
      jobs.add(
          i % 2 == 0
              ? realm.every(engine.io(), 0, period, TimeUnit.MILLISECONDS, action)
              : realm.atRate(engine.io(), 0, period, TimeUnit.MILLISECONDS, action));
    }
    awaitTrue(() -> totalRuns.get() > 2_000);
    assertThat(realm.close(60, TimeUnit.SECONDS)).isTrue();
    assertThat(overlap.get()).isFalse();
    for (Job<Void> job : jobs) {
      assertThat(job.outcomeOrNull().isCancelled()).isTrue();
    }
    assertThat(engine.cadence().pending()).isEqualTo(0);
    assertThat(handler.errors()).isEmpty();
    awaitTrue(() -> !hasCadenceThread(engine));
  }

  @Test
  void theWheelFiresOrCancelsEveryEntryExactlyOnceUnderConcurrency() throws Exception {
    int n = 20_000;
    TimerWheel wheel = new TimerWheel(0, 10, 64);
    List<TimerEntry> entries = new ArrayList<>(n);
    ThreadLocalRandom rnd = ThreadLocalRandom.current();
    for (int i = 0; i < n; i++) {
      long deadline = rnd.nextInt(200_000);
      entries.add(
          wheel.schedule(
                  deadline,
                  new Timeout() {
                    @Override
                    public void fire() {}

                    @Override
                    public void discard(Throwable reason) {}
                  })
              .entry);
    }
    List<TimerEntry> fired = Collections.synchronizedList(new ArrayList<>());
    AtomicBoolean advancing = new AtomicBoolean(true);
    CountDownLatch go = new CountDownLatch(1);
    Thread advancer =
        new Thread(
            () -> {
              try {
                go.await();
              } catch (InterruptedException e) {
                return;
              }
              long now = 0;
              while (advancing.get()) {
                now += 997;
                fired.addAll(wheel.advanceTo(now));
              }
              fired.addAll(wheel.advanceTo(Long.MAX_VALUE / 4));
            });
    advancer.setDaemon(true);
    advancer.start();
    AtomicInteger cancelled = new AtomicInteger();
    List<Thread> cancellers = new ArrayList<>();
    for (int c = 0; c < 3; c++) {
      int offset = c;
      Thread t =
          new Thread(
              () -> {
                try {
                  go.await();
                } catch (InterruptedException e) {
                  return;
                }
                for (int i = offset; i < n; i += 3) {
                  if (entries.get(i).cancel()) {
                    cancelled.incrementAndGet();
                  }
                }
              });
      t.setDaemon(true);
      t.start();
      cancellers.add(t);
    }
    go.countDown();
    for (Thread t : cancellers) {
      t.join(60_000);
    }
    advancing.set(false);
    advancer.join(60_000);
    assertThat(fired.size() + cancelled.get()).isEqualTo(n);
    java.util.Set<TimerEntry> distinct = new java.util.HashSet<>(fired);
    assertThat(distinct).hasSize(fired.size());
    for (TimerEntry e : entries) {
      boolean wasFired = distinct.contains(e);
      assertThat(wheel.state(e)).isEqualTo(wasFired ? TimerEntry.FIRED : TimerEntry.CANCELLED);
    }
    assertThat(wheel.size()).isEqualTo(0);
    assertThat(wheel.nonEmptyBuckets()).isEqualTo(0);
  }
}
