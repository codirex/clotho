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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

/** The handoff of a pool worker that ends while a task accepted before shutdown is queued. */
class EnginePoolHandoffTest extends EngineTestBase {

  // This test covers the handoff in the finally block of PoolLane.Worker.run(), reached by a worker
  // that ENDS BECAUSE ITS LOOP FAILS (an engine clock that throws once). It does not reproduce the
  // narrow race between a retiring worker and a submission: no seam reaches that interleaving.
  //
  // Contract (docs/ENGINE.md, section 2): after shutdown() returns no submission starts a thread,
  // but a worker that is ending may start one replacement to run a task accepted before the
  // shutdown. The replacement is counted before that worker ends, so the engine is not terminated
  // until the accepted task has run.
  @Test
  void aFailedWorkerHandsAcceptedTaskToAReplacementAfterShutdown() throws Exception {
    FailingTicker ticker = new FailingTicker();
    List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());
    ThreadGate gate = new ThreadGate(name -> name.endsWith("-io-2"));
    Engine.Builder config = builder().ioMaxThreads(1).ticker(ticker).threadMaker(gate);
    config.taskErrorHandler((thread, error) -> errors.add(error));
    Engine engine = track(config.build());
    Blocker first = new Blocker();
    CountDownLatch secondRan = new CountDownLatch(1);

    try {
      engine.io().execute(first);
      assertThat(first.started.await(20, TimeUnit.SECONDS)).isTrue();
      // The only worker is busy and the pool is at its maximum: the accepted task waits queued.
      engine.io().execute(secondRan::countDown);
      engine.shutdown();
      // shutdown() returned: the first worker is the only thread that was ever created.
      assertThat(gate.createdCount()).isEqualTo(1);

      ticker.armed.set(true);
      first.release.countDown();
      // The first worker ends with the task still queued and is creating its replacement. It is
      // still counted, so the engine is not terminated and the task has not run.
      gate.awaitArrived();
      assertThat(engine.isTerminated()).isFalse();
      assertThat(engine.liveThreads()).isEqualTo(1);
      assertThat(secondRan.getCount()).isEqualTo(1);
    } finally {
      first.release.countDown();
      gate.open();
    }

    assertThat(engine.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
    // The accepted task ran before termination was reported, on the replacement worker, which
    // started after shutdown() had returned.
    assertThat(secondRan.getCount()).isZero();
    assertThat(gate.createdCount()).isEqualTo(2);
    assertThat(engine.liveThreads()).isZero();
    assertThat(errors).hasSize(1);
    assertThat(errors.get(0)).isInstanceOf(IllegalStateException.class);
  }

  /** A task that signals when it runs and then waits until it is released. */
  private static final class Blocker implements Runnable {

    final CountDownLatch started = new CountDownLatch(1);
    final CountDownLatch release = new CountDownLatch(1);

    @Override
    public void run() {
      started.countDown();
      try {
        release.await();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }

  /** The engine clock: after it is armed, it throws once, on an I/O worker. */
  private static final class FailingTicker implements LongSupplier {

    final AtomicBoolean armed = new AtomicBoolean();

    @Override
    public long getAsLong() {
      boolean onAnIoWorker = Thread.currentThread().getName().contains("-io-");
      if (onAnIoWorker && armed.compareAndSet(true, false)) {
        throw new IllegalStateException("simulated failure of the worker loop");
      }
      return System.nanoTime();
    }
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
