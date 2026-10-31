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

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.codirex.clotho.support.ManualLane;
import org.codirex.clotho.support.RecordingHandler;
import org.codirex.clotho.support.ThreadLane;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class JobStateTest {

  private final RecordingHandler handler = new RecordingHandler();
  private final Realm realm = Realm.builder("state").uncaughtHandler(handler).open();
  private final ManualLane lane = new ManualLane();
  private final List<ThreadLane> threadLanes = new ArrayList<>();

  @AfterEach
  void tearDown() {
    for (ThreadLane l : threadLanes) {
      l.close();
    }
  }

  private ThreadLane threads(int n) {
    ThreadLane l = new ThreadLane(n);
    threadLanes.add(l);
    return l;
  }

  @Test
  void workDoesNotRunInlineAndSucceeds() throws Exception {
    Job<String> job = realm.launch(lane, cp -> "ok");
    assertThat(job.isDone()).isFalse();
    assertThat(job.outcomeOrNull()).isNull();
    lane.runAll();
    assertThat(job.isDone()).isTrue();
    assertThat(job.await()).isEqualTo(Outcome.success("ok"));
  }

  @Test
  void nullResultIsSuccess() throws Exception {
    Job<String> job = realm.launch(lane, cp -> null);
    lane.runAll();
    assertThat(job.await().isSuccess()).isTrue();
    assertThat(job.await().valueOrNull()).isNull();
  }

  @Test
  void cancelBeforeStartNeverRunsTheWork() throws Exception {
    AtomicBoolean ran = new AtomicBoolean();
    Job<String> job =
        realm.launch(
            lane,
            cp -> {
              ran.set(true);
              return "x";
            });
    assertThat(job.cancel()).isTrue();
    assertThat(job.isDone()).isTrue();
    assertThat(job.isCancellationRequested()).isTrue();
    lane.runAll();
    assertThat(ran.get()).isFalse();
    assertThat(job.await().isCancelled()).isTrue();
  }

  @Test
  void cooperativeCancelWhileRunning() throws Exception {
    AtomicReference<Job<String>> self = new AtomicReference<>();
    Job<String> job =
        realm.launch(
            lane,
            cp -> {
              self.get().cancel();
              cp.check();
              return "unreachable";
            });
    self.set(job);
    lane.runAll();
    Outcome<String> o = job.await();
    assertThat(o.isCancelled()).isTrue();
    assertThat(((Outcome.Cancelled<String>) o).lateFailure()).isNull();
    assertThat(handler.errors()).isEmpty();
  }

  @Test
  void cancelRequestedBeforeResultDropsTheValue() throws Exception {
    AtomicReference<Job<String>> self = new AtomicReference<>();
    Job<String> job =
        realm.launch(
            lane,
            cp -> {
              self.get().cancel();
              return "value";
            });
    self.set(job);
    lane.runAll();
    Outcome<String> o = job.await();
    assertThat(o.isCancelled()).isTrue();
    assertThat(o.valueOrNull()).isNull();
  }

  @Test
  void completionBeforeCancelKeepsSuccess() throws Exception {
    Job<String> job = realm.launch(lane, cp -> "done");
    lane.runAll();
    Outcome<String> before = job.await();
    assertThat(job.cancel()).isFalse();
    assertThat(job.await()).isSameAs(before);
    assertThat(before.isSuccess()).isTrue();
    assertThat(job.isCancellationRequested()).isFalse();
  }

  @Test
  void exceptionAfterCancelRequestIsALateFailureAndNotReported() throws Exception {
    AtomicReference<Job<String>> self = new AtomicReference<>();
    IOException late = new IOException("socket closed");
    Job<String> job =
        realm.launch(
            lane,
            cp -> {
              self.get().cancel();
              throw late;
            });
    self.set(job);
    lane.runAll();
    Outcome<String> o = job.await();
    assertThat(o.isCancelled()).isTrue();
    assertThat(((Outcome.Cancelled<String>) o).lateFailure()).isSameAs(late);
    assertThat(handler.errors()).isEmpty();
  }

  @Test
  void failureIsDeliveredWithTheSameException() throws Exception {
    IllegalStateException boom = new IllegalStateException("boom");
    Job<String> job =
        realm.launch(
            lane,
            cp -> {
              throw boom;
            });
    lane.runAll();
    assertThat(job.await().failureOrNull()).isSameAs(boom);
  }

  @Test
  void errorsAreDeliveredAsFailureToo() throws Exception {
    AssertionError error = new AssertionError("err");
    Job<String> job =
        realm.launch(
            lane,
            cp -> {
              throw error;
            });
    lane.runAll();
    assertThat(job.await().failureOrNull()).isSameAs(error);
  }

  @Test
  void cancelledExceptionWithoutRequestIsAFailure() throws Exception {
    Job<String> job =
        realm.launch(
            lane,
            cp -> {
              throw new CancelledException();
            });
    lane.runAll();
    Outcome<String> o = job.await();
    assertThat(o.isFailure()).isTrue();
    assertThat(o.failureOrNull()).isInstanceOf(CancelledException.class);
  }

  @Test
  void unresponsiveWorkStaysNotDoneUntilItReturns() throws Exception {
    AtomicReference<Job<String>> self = new AtomicReference<>();
    AtomicBoolean doneInside = new AtomicBoolean(true);
    AtomicBoolean requestedInside = new AtomicBoolean();
    AtomicBoolean checkpointSawIt = new AtomicBoolean();
    Job<String> job =
        realm.launch(
            lane,
            cp -> {
              self.get().cancel();
              doneInside.set(self.get().isDone());
              requestedInside.set(self.get().isCancellationRequested());
              checkpointSawIt.set(cp.isCancelled());
              return "ignored the request";
            });
    self.set(job);
    lane.runAll();
    assertThat(doneInside.get()).isFalse();
    assertThat(requestedInside.get()).isTrue();
    assertThat(checkpointSawIt.get()).isTrue();
    assertThat(job.await().isCancelled()).isTrue();
  }

  @Test
  void secondCancelReturnsFalse() throws Exception {
    AtomicReference<Job<String>> self = new AtomicReference<>();
    AtomicBoolean first = new AtomicBoolean();
    AtomicBoolean second = new AtomicBoolean(true);
    Job<String> job =
        realm.launch(
            lane,
            cp -> {
              first.set(self.get().cancel());
              second.set(self.get().cancel());
              return "x";
            });
    self.set(job);
    lane.runAll();
    assertThat(first.get()).isTrue();
    assertThat(second.get()).isFalse();
  }

  @Test
  void outcomeIsTheSameInstanceForEveryObserver() throws Exception {
    Job<String> job = realm.launch(lane, cp -> "v");
    List<Outcome<?>> seen = Collections.synchronizedList(new ArrayList<>());
    job.onComplete(Lane.direct(), seen::add);
    lane.runAll();
    job.onComplete(Lane.direct(), seen::add);
    Outcome<String> awaited = job.await();
    assertThat(seen).hasSize(2);
    assertThat(seen.get(0)).isSameAs(awaited);
    assertThat(seen.get(1)).isSameAs(awaited);
    assertThat(job.outcomeOrNull()).isSameAs(awaited);
  }

  @Test
  void callbacksRunExactlyOnceInRegistrationOrder() throws Exception {
    Job<String> job = realm.launch(lane, cp -> "v");
    List<Integer> order = Collections.synchronizedList(new ArrayList<>());
    AtomicInteger late = new AtomicInteger();
    job.onComplete(Lane.direct(), o -> order.add(1));
    job.onComplete(Lane.direct(), o -> order.add(2));
    job.onComplete(Lane.direct(), o -> order.add(3));
    lane.runAll();
    job.onComplete(Lane.direct(), o -> late.incrementAndGet());
    assertThat(order).containsExactly(1, 2, 3);
    assertThat(late.get()).isEqualTo(1);
  }

  @Test
  void callbackRunsOnItsOwnLane() throws Exception {
    ManualLane callbackLane = new ManualLane();
    Job<String> job = realm.launch(lane, cp -> "v");
    AtomicInteger calls = new AtomicInteger();
    job.onComplete(callbackLane, o -> calls.incrementAndGet());
    lane.runAll();
    assertThat(calls.get()).isEqualTo(0);
    callbackLane.runAll();
    assertThat(calls.get()).isEqualTo(1);
  }

  @Test
  void throwingCallbackIsReportedAndOthersStillRun() throws Exception {
    Job<String> job = realm.launch(lane, cp -> "v");
    IllegalStateException bad = new IllegalStateException("bad callback");
    AtomicInteger calls = new AtomicInteger();
    job.onComplete(
        Lane.direct(),
        o -> {
          throw bad;
        });
    job.onComplete(Lane.direct(), o -> calls.incrementAndGet());
    lane.runAll();
    assertThat(calls.get()).isEqualTo(1);
    assertThat(handler.errors()).hasSize(1);
    assertThat(handler.errors().get(0)).isSameAs(bad);
    assertThat(job.await().isSuccess()).isTrue();
  }

  @Test
  void laneRejectionBecomesFailure() throws Exception {
    RejectedExecutionException rejection = new RejectedExecutionException("full");
    Lane rejecting =
        task -> {
          throw rejection;
        };
    Job<String> job = realm.launch(rejecting, cp -> "never");
    assertThat(job.isDone()).isTrue();
    assertThat(job.await().failureOrNull()).isSameAs(rejection);
  }

  @Test
  void timedAwaitTimesOutWithoutCancelling() throws Exception {
    Job<String> job = realm.launch(lane, cp -> "later");
    assertThatThrownBy(() -> job.await(5, TimeUnit.MILLISECONDS))
        .isInstanceOf(TimeoutException.class);
    assertThat(job.isCancellationRequested()).isFalse();
    lane.runAll();
    assertThat(job.await(5, TimeUnit.SECONDS).isSuccess()).isTrue();
  }

  @Test
  void interruptReachesTheRunningWorkAndDoesNotLeakToTheNextTask() throws Exception {
    ThreadLane single = threads(1);
    CountDownLatch started = new CountDownLatch(1);
    AtomicBoolean interrupted = new AtomicBoolean();
    Job<String> blocked =
        realm.launch(
            single,
            cp -> {
              started.countDown();
              try {
                new CountDownLatch(1).await();
              } catch (InterruptedException e) {
                interrupted.set(true);
                throw e;
              }
              return "never";
            });
    assertThat(started.await(20, TimeUnit.SECONDS)).isTrue();
    assertThat(blocked.cancel(true)).isTrue();
    Outcome<String> o = blocked.await(20, TimeUnit.SECONDS);
    assertThat(o.isCancelled()).isTrue();
    assertThat(((Outcome.Cancelled<String>) o).lateFailure())
        .isInstanceOf(InterruptedException.class);
    assertThat(interrupted.get()).isTrue();

    AtomicBoolean nextSawInterrupt = new AtomicBoolean(true);
    Job<String> next =
        realm.launch(
            single,
            cp -> {
              nextSawInterrupt.set(Thread.currentThread().isInterrupted());
              return "clean";
            });
    assertThat(next.await(20, TimeUnit.SECONDS).isSuccess()).isTrue();
    assertThat(nextSawInterrupt.get()).isFalse();
  }

  @Test
  void interruptOnAnAlreadyRequestedCancelStillInterrupts() throws Exception {
    ThreadLane single = threads(1);
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch interruptSeen = new CountDownLatch(1);
    Job<String> job =
        realm.launch(
            single,
            cp -> {
              started.countDown();
              for (; ; ) {
                try {
                  new CountDownLatch(1).await();
                } catch (InterruptedException e) {
                  interruptSeen.countDown();
                  return "stopped";
                }
              }
            });
    assertThat(started.await(20, TimeUnit.SECONDS)).isTrue();
    assertThat(job.cancel()).isTrue();
    assertThat(job.cancel(true)).isFalse();
    assertThat(interruptSeen.await(20, TimeUnit.SECONDS)).isTrue();
    assertThat(job.await(20, TimeUnit.SECONDS).isCancelled()).isTrue();
    awaitTrue(job::isDone);
  }
}
