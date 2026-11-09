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
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import org.codirex.clotho.support.RecordingHandler;
import org.junit.jupiter.api.Test;

/**
 * Shutdown racing with the start of an engine thread.
 *
 * <p>Each test holds one thread of the engine inside its creation (a package-private seam of the
 * engine builder), so the interleaving is chosen by the test with latches instead of being found by
 * chance: the submission that needs the thread is accepted, the thread is not started yet, and
 * {@code shutdown()} runs in that window. Until that thread has started and ended, termination must
 * not be reported, and no thread may start after {@code shutdown()} returned.
 */
class EngineShutdownRaceTest extends EngineTestBase {

  private final RecordingHandler handler = new RecordingHandler();

  @Test
  void terminationWaitsForATimerThreadThatIsAboutToStart() throws Exception {
    ThreadGate gate = new ThreadGate(name -> name.endsWith("-cadence"));
    Engine engine = track(builder().timerTick(1, TimeUnit.MILLISECONDS).threadMaker(gate).build());
    Realm realm = Realm.builder("race").cadence(engine.cadence()).uncaughtHandler(handler).open();
    AtomicReference<Job<String>> timer = new AtomicReference<>();

    assertShutdownWaitsForTheThreadStart(
        engine,
        gate,
        () -> timer.set(realm.after(engine.io(), 1, TimeUnit.HOURS, cp -> "never")),
        () -> true);

    // The timer was scheduled before shutdown began: it is discarded, as it always was.
    Throwable cause = timer.get().outcomeOrNull().failureOrNull();
    assertThat(cause).isInstanceOf(DroppedException.class);
    assertThat(((DroppedException) cause).reason()).isEqualTo(DroppedException.Reason.SHUTDOWN);
    assertThat(realm.close(20, TimeUnit.SECONDS)).isTrue();
    assertThat(handler.errors()).isEmpty();
  }

  @Test
  void terminationWaitsForAPoolWorkerThatIsAboutToStart() throws Exception {
    ThreadGate gate = new ThreadGate(name -> name.contains("-io-"));
    Engine engine = track(builder().threadMaker(gate).build());
    CountDownLatch ran = new CountDownLatch(1);

    assertShutdownWaitsForTheThreadStart(
        engine, gate, () -> engine.io().execute(ran::countDown), () -> ran.getCount() == 0);

    // The accepted task was not lost: it ran before the engine terminated.
    assertThat(ran.getCount()).isZero();
  }

  @Test
  void terminationWaitsForTheThreadOfASingleLane() throws Exception {
    ThreadGate gate = new ThreadGate(name -> name.contains("-single-"));
    Engine engine = track(builder().threadMaker(gate).build());
    Lane single = engine.single("s");
    CountDownLatch ran = new CountDownLatch(1);

    assertShutdownWaitsForTheThreadStart(
        engine, gate, () -> single.execute(ran::countDown), () -> ran.getCount() == 0);

    assertThat(ran.getCount()).isZero();
  }

  @Test
  void rejectedSubmissionsNeverMakeATerminatedEngineUnterminated() throws Exception {
    Engine engine = track(builder().timerTick(1, TimeUnit.MILLISECONDS).build());
    Lane single = engine.single("s");
    Realm realm = Realm.builder("race").cadence(engine.cadence()).uncaughtHandler(handler).open();
    CountDownLatch ran = new CountDownLatch(3);
    engine.io().execute(ran::countDown);
    engine.compute().execute(ran::countDown);
    single.execute(ran::countDown);
    Job<String> pending = realm.after(engine.io(), 1, TimeUnit.HOURS, cp -> "never");
    engine.shutdown();
    assertThat(engine.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
    assertThat(ran.getCount()).isZero();
    assertThat(pending.outcomeOrNull().failureOrNull()).isInstanceOf(DroppedException.class);

    // Every submission from now on is rejected. Rejecting one briefly raises the count of the
    // submissions in flight; that must never make the engine unterminated again.
    int hammerers = 4;
    int attempts = 5_000;
    AtomicInteger rejected = new AtomicInteger();
    AtomicInteger finished = new AtomicInteger();
    List<Thread> hammers = new ArrayList<>();
    for (int i = 0; i < hammerers; i++) {
      Runnable task = () -> hammer(engine, single, realm, attempts, rejected, finished);
      hammers.add(start("hammer-" + i, task));
    }
    boolean everUnterminated = false;
    while (finished.get() < hammerers) {
      everUnterminated |= !engine.isTerminated();
    }
    for (Thread hammer : hammers) {
      join(hammer);
    }

    assertThat(everUnterminated).isFalse();
    assertThat(engine.isTerminated()).isTrue();
    assertThat(rejected.get()).isEqualTo(hammerers * attempts * 3);
    assertThat(realm.close(20, TimeUnit.SECONDS)).isTrue();
  }

  /**
   * Runs {@code submit} on its own thread, which blocks at the gate inside the creation of an engine
   * thread, then shuts the engine down and checks what can be observed while that thread is held
   * back and after it is released.
   */
  private void assertShutdownWaitsForTheThreadStart(
      Engine engine, ThreadGate gate, Runnable submit, BooleanSupplier submissionHandled)
      throws Exception {
    AtomicInteger createdWhenShutdownReturned = new AtomicInteger(-1);
    AtomicInteger createdWhenTerminationReported = new AtomicInteger(-1);
    AtomicBoolean handledWhenTerminationReported = new AtomicBoolean();
    AtomicBoolean terminated = new AtomicBoolean();
    Thread submitter = thread("submitter", submit);
    Thread shutter =
        thread(
            "shutter",
            () -> {
              engine.shutdown();
              createdWhenShutdownReturned.set(gate.createdCount());
            });
    Thread waiter =
        thread(
            "waiter",
            () -> {
              try {
                terminated.set(engine.awaitTermination(20, TimeUnit.SECONDS));
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
              createdWhenTerminationReported.set(gate.createdCount());
              handledWhenTerminationReported.set(submissionHandled.getAsBoolean());
            });
    submitter.start();
    try {
      gate.awaitArrived();
      shutter.start();
      awaitTrue(engine::isShutdown);
      // The submission is accepted and its thread is not started yet: not terminated.
      assertThat(engine.isTerminated()).isFalse();
      waiter.start();
    } finally {
      gate.open();
    }
    join(submitter);
    join(shutter);
    join(waiter);

    assertThat(terminated.get()).isTrue();
    // shutdown() returned only after the thread was created: no thread starts after it returns.
    assertThat(createdWhenShutdownReturned.get()).isEqualTo(1);
    // Termination was reported only after that thread had started, done its work and ended.
    assertThat(createdWhenTerminationReported.get()).isEqualTo(1);
    assertThat(handledWhenTerminationReported.get()).isTrue();
    assertThat(gate.createdCount()).isEqualTo(1);
    assertThat(engine.liveThreads()).isZero();
  }

  private static void hammer(
      Engine engine,
      Lane single,
      Realm realm,
      int attempts,
      AtomicInteger rejected,
      AtomicInteger finished) {
    try {
      for (int i = 0; i < attempts; i++) {
        for (Lane lane : List.of(engine.io(), engine.compute(), single)) {
          try {
            lane.execute(() -> {});
          } catch (RejectedExecutionException expected) {
            rejected.incrementAndGet();
          }
        }
        // A timer on a terminated engine is refused too: the job fails, nothing is scheduled.
        realm.after(engine.io(), 1, TimeUnit.HOURS, cp -> "late");
      }
    } finally {
      finished.incrementAndGet();
    }
  }

  private static Thread thread(String name, Runnable task) {
    Thread thread = new Thread(task, name);
    thread.setDaemon(true);
    return thread;
  }

  /** Holds back the first engine thread whose name matches while it is being created. */
  private static final class ThreadGate implements Engine.ThreadMaker {

    private final Predicate<String> matches;
    private final AtomicBoolean taken = new AtomicBoolean();
    private final CountDownLatch arrived = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);
    private final List<Thread> created = Collections.synchronizedList(new ArrayList<>());

    ThreadGate(Predicate<String> matches) {
      this.matches = matches;
    }

    @Override
    public Thread make(Runnable body, String name) {
      if (matches.test(name) && taken.compareAndSet(false, true)) {
        arrived.countDown();
        try {
          if (!release.await(30, TimeUnit.SECONDS)) {
            throw new IllegalStateException("the thread gate was never opened");
          }
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new IllegalStateException("interrupted at the thread gate", e);
        }
      }
      Thread thread = new Thread(body, name);
      created.add(thread);
      return thread;
    }

    void awaitArrived() throws InterruptedException {
      assertThat(arrived.await(20, TimeUnit.SECONDS)).isTrue();
    }

    void open() {
      release.countDown();
    }

    int createdCount() {
      return created.size();
    }
  }
}
