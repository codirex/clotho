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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.codirex.clotho.support.ManualLane;
import org.codirex.clotho.support.RecordingHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Timeout(5)
class JobInlineDispatchTest {

  private final List<Throwable> expectedErrors = new ArrayList<>();
  private final RecordingHandler handler = new RecordingHandler();
  private final Realm realm = Realm.builder("inline-dispatch").uncaughtHandler(handler).open();

  private JobImpl<Integer> pending() {
    return JobImpl.create(realm, true, Priority.NORMAL);
  }

  @AfterEach
  void noLeakedContextOrUnreportedCallbackAssertions() {
    assertThat(LaneContext.current()).isNull();
    assertThat(handler.errors()).containsExactlyElementsOf(expectedErrors);
    assertThat(realm.outstanding()).isZero();
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void longUnsuccessfulChainsPropagateWithoutRunningSuccessSteps(boolean cancel) {
    JobImpl<Integer> parent = pending();
    Job<Integer> tail = parent;
    AtomicBoolean stepRan = new AtomicBoolean();
    for (int i = 0; i < 10_000; i++) {
      tail =
          tail.then(
              Lane.direct(),
              (value, cp) -> {
                stepRan.set(true);
                return value;
              });
    }
    RuntimeException failure = new IllegalStateException("original");
    Outcome<Integer> outcome = cancel ? Outcome.cancelled() : Outcome.failure(failure);

    parent.completeIfPending(outcome);

    assertThat(tail.outcomeOrNull()).isNotNull();
    assertThat(tail.outcomeOrNull().isCancelled()).isEqualTo(cancel);
    if (!cancel) {
      assertThat(tail.outcomeOrNull().failureOrNull()).isSameAs(failure);
    }
    assertThat(stepRan).isFalse();
  }

  @Test
  void queuedListenerFailureIsReportedOnceAndDoesNotStopRemainingListeners() {
    JobImpl<Integer> trigger = pending();
    JobImpl<Integer> nested = pending();
    RuntimeException failure = new IllegalStateException("listener");
    List<Integer> calls = new ArrayList<>();
    nested.addInline(
        outcome -> {
          calls.add(1);
          throw failure;
        });
    nested.addInline(outcome -> calls.add(2));
    trigger.addInline(outcome -> nested.completeIfPending(Outcome.success(7)));

    trigger.completeIfPending(Outcome.success(1));

    assertThat(calls).containsExactly(1, 2);
    assertThat(handler.errors()).containsExactly(failure);
    expectedErrors.add(failure);
  }

  @Test
  void completedJobListenerRunsImmediatelyDuringDispatchAndReportsItsFailure() {
    JobImpl<Integer> completed = pending();
    completed.completeIfPending(Outcome.success(7));
    JobImpl<Integer> trigger = pending();
    RuntimeException failure = new IllegalStateException("late listener");
    List<String> calls = new ArrayList<>();
    trigger.addInline(
        outcome -> {
          completed.addInline(
              result -> {
                calls.add("listener");
                assertThat(result).isSameAs(completed.outcomeOrNull());
                throw failure;
              });
          calls.add("returned");
        });

    trigger.completeIfPending(Outcome.success(1));

    expectedErrors.add(failure);
    assertThat(calls).containsExactly("listener", "returned");
    assertThat(handler.errors()).containsExactly(failure);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void cancellationPropagationSettlesMultipleLevelsBeforeInlineAwait(boolean timed) {
    JobImpl<Integer> trigger = pending();
    JobImpl<Integer> parent = pending();
    Job<Integer> tail = parent;
    for (int i = 0; i < 100; i++) {
      tail = tail.then(Lane.direct(), (value, cp) -> value + 1);
    }
    Job<Integer> awaited = tail;
    AtomicReference<Outcome<Integer>> result = new AtomicReference<>();
    trigger.addInline(
        outcome -> {
          parent.cancel();
          try {
            result.set(timed ? awaited.await(1, TimeUnit.SECONDS) : awaited.await());
          } catch (InterruptedException | TimeoutException e) {
            throw new AssertionError("cancellation propagation should settle before await", e);
          }
        });

    trigger.completeIfPending(Outcome.success(1));

    assertThat(result.get()).isSameAs(awaited.outcomeOrNull());
    assertThat(result.get().isCancelled()).isTrue();
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void awaitObservesInternalFailurePropagationButDoesNotRunSuccessSteps(boolean fail) {
    JobImpl<Integer> trigger = pending();
    JobImpl<Integer> parent = pending();
    Job<Integer> dependent = parent.then(Lane.direct(), (value, cp) -> value + 1);
    RuntimeException failure = new IllegalArgumentException("original");
    AtomicReference<Outcome<Integer>> result = new AtomicReference<>();
    AtomicBoolean successStepWasDeferred = new AtomicBoolean();
    trigger.addInline(
        outcome -> {
          parent.completeIfPending(fail ? Outcome.failure(failure) : Outcome.success(7));
          try {
            if (fail) {
              result.set(dependent.await(1, TimeUnit.SECONDS));
            } else {
              assertThatThrownBy(() -> dependent.await(1, TimeUnit.MILLISECONDS))
                  .isInstanceOf(TimeoutException.class);
              successStepWasDeferred.set(!dependent.isDone());
            }
          } catch (InterruptedException | TimeoutException e) {
            throw new AssertionError("internal failure propagation should be available", e);
          }
        });

    trigger.completeIfPending(Outcome.success(1));

    if (fail) {
      assertThat(result.get()).isSameAs(dependent.outcomeOrNull());
      assertThat(result.get().failureOrNull()).isSameAs(failure);
    } else {
      assertThat(successStepWasDeferred).isTrue();
      assertThat(dependent.outcomeOrNull()).isEqualTo(Outcome.success(8));
    }
  }

  @ParameterizedTest
  @ValueSource(longs = {0, -1})
  void nonPositiveTimeoutObservesAlreadyPropagatedCancellation(long timeout) throws Exception {
    JobImpl<Integer> trigger = pending();
    JobImpl<Integer> parent = pending();
    Job<Integer> dependent = parent.then(Lane.direct(), (value, cp) -> value);
    AtomicReference<Outcome<Integer>> result = new AtomicReference<>();
    trigger.addInline(
        outcome -> {
          parent.cancel();
          try {
            result.set(dependent.await(timeout, TimeUnit.NANOSECONDS));
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
          } catch (TimeoutException timeoutFailure) {
            throw new AssertionError(timeoutFailure);
          }
          assertThat(dependent.isDone()).isTrue();
        });

    trigger.completeIfPending(Outcome.success(1));

    assertThat(result.get()).isSameAs(dependent.outcomeOrNull());
    assertThat(result.get().isCancelled()).isTrue();
  }

  @ParameterizedTest
  @ValueSource(longs = {0, -1})
  void completedJobCanBeAwaitedWithNonPositiveTimeoutDuringDispatch(long timeout) {
    JobImpl<Integer> completed = pending();
    completed.completeIfPending(Outcome.success(7));
    JobImpl<Integer> trigger = pending();
    AtomicReference<Outcome<Integer>> result = new AtomicReference<>();
    trigger.addInline(
        outcome -> {
          try {
            result.set(completed.await(timeout, TimeUnit.NANOSECONDS));
          } catch (InterruptedException | TimeoutException e) {
            throw new AssertionError("completed job should be immediately available", e);
          }
        });

    trigger.completeIfPending(Outcome.success(1));

    assertThat(result.get()).isSameAs(completed.outcomeOrNull());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void interruptedAwaitKeepsInterruptSemanticsAfterEagerPropagation(boolean timed) {
    JobImpl<Integer> trigger = pending();
    JobImpl<Integer> parent = pending();
    Job<Integer> dependent = parent.then(Lane.direct(), (value, cp) -> value);
    trigger.addInline(
        outcome -> {
          parent.cancel();
          Thread.currentThread().interrupt();
          try {
            assertThatThrownBy(
                    () -> {
                      if (timed) {
                        dependent.await(1, TimeUnit.SECONDS);
                      } else {
                        dependent.await();
                      }
                    })
                .isInstanceOf(InterruptedException.class);
            assertThat(Thread.currentThread().isInterrupted()).isFalse();
            assertThat(dependent.isDone()).isTrue();
          } finally {
            Thread.interrupted();
          }
        });

    trigger.completeIfPending(Outcome.success(1));

    assertThat(dependent.outcomeOrNull().isCancelled()).isTrue();
  }

  @Test
  void timedAwaitWithAnEmptyDispatchQueueTimesOutAndDispatchCanContinue() {
    JobImpl<Integer> trigger = pending();
    JobImpl<Integer> awaited = pending();
    AtomicBoolean continued = new AtomicBoolean();
    trigger.addInline(
        outcome -> {
          assertThatThrownBy(() -> awaited.await(1, TimeUnit.MILLISECONDS))
              .isInstanceOf(TimeoutException.class);
          continued.set(true);
        });

    trigger.completeIfPending(Outcome.success(1));

    assertThat(continued).isTrue();
    assertThat(awaited.isDone()).isFalse();
    awaited.cancel();
  }

  @Test
  void queuedListenersKeepDistinctCapturedContextsAndRestoreTheCaller() {
    JobImpl<Integer> trigger = pending();
    JobImpl<Integer> forbiddenJob = pending();
    JobImpl<Integer> noContextJob = pending();
    Lane forbidden = new ManualLane(BlockingPolicy.FORBIDDEN);
    Lane allowed = new ManualLane(BlockingPolicy.ALLOWED);
    List<Lane> contexts = new ArrayList<>();
    forbiddenJob.addInline(outcome -> contexts.add(LaneContext.current()));
    noContextJob.addInline(outcome -> contexts.add(LaneContext.current()));
    trigger.addInline(
        outcome -> {
          LaneContext.runWith(forbidden, () -> forbiddenJob.completeIfPending(Outcome.success(2)));
          LaneContext.runWith(null, () -> noContextJob.completeIfPending(Outcome.success(3)));
          assertThat(LaneContext.current()).isSameAs(allowed);
        });

    LaneContext.runIn(allowed, () -> trigger.completeIfPending(Outcome.success(1)));

    assertThat(contexts).containsExactly(forbidden, null);
  }
}
