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

import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * A handle to a unit of work owned by a {@link Realm}. It replaces {@code Future}.
 *
 * <p>The outcome of a job is decided by a single atomic step; once decided it never changes, every
 * waiter and callback sees the same {@link Outcome}, and callbacks run exactly once. See {@code
 * docs/SEMANTICS.md} for the full rules.
 *
 * <p>This interface cannot be implemented outside the library.
 *
 * @param <T> the value type
 */
public sealed interface Job<T> permits JobImpl {

  /**
   * Returns the realm this job belongs to for its whole life.
   *
   * @return the realm
   */
  Realm realm();

  /**
   * Tells whether the job has its final outcome.
   *
   * @return {@code true} once the outcome is decided
   */
  boolean isDone();

  /**
   * Tells whether cancellation was requested or the job already ended cancelled.
   *
   * @return {@code true} if cancelled or asked to cancel
   */
  boolean isCancellationRequested();

  /**
   * Returns the outcome if decided.
   *
   * @return the outcome, or {@code null} while the job is not done
   */
  Outcome<T> outcomeOrNull();

  /**
   * Blocks until the job is done.
   *
   * <p>Throws if called from work running on a lane whose policy is {@link
   * BlockingPolicy#FORBIDDEN}, even if the job is already done, so misuse is found early.
   *
   * @return the final outcome
   * @throws InterruptedException if the calling thread is interrupted while waiting
   * @throws BlockingNotAllowedException if the current lane forbids blocking
   */
  Outcome<T> await() throws InterruptedException;

  /**
   * Blocks until the job is done or the timeout expires.
   *
   * @param timeout the maximum time to wait
   * @param unit the unit of {@code timeout}
   * @return the final outcome
   * @throws InterruptedException if the calling thread is interrupted while waiting
   * @throws TimeoutException if the job is not done in time; the job is not cancelled
   * @throws BlockingNotAllowedException if the current lane forbids blocking
   */
  Outcome<T> await(long timeout, TimeUnit unit) throws InterruptedException, TimeoutException;

  /**
   * Requests cancellation without interrupting the running thread.
   *
   * @return {@code true} if this call made the job cancelled (not started) or cancel-requested
   *     (running); {@code false} if the job is already done or cancellation was already requested
   */
  boolean cancel();

  /**
   * Requests cancellation, optionally interrupting the thread that is running the work.
   *
   * <p>The interrupt is delivered only while the work is running and is cleared when the work
   * returns, so it cannot leak into the next task of a pooled thread. Calling this on a job whose
   * cancellation was already requested still delivers the interrupt, and returns {@code false}.
   *
   * @param interrupt whether to interrupt the running thread
   * @return the same value as {@link #cancel()}
   */
  boolean cancel(boolean interrupt);

  /**
   * Runs {@code step} on {@code lane} with the value of this job once it succeeds.
   *
   * <p>If this job fails or is cancelled, the returned job ends the same way without running the
   * step. The returned job never cancels this job: it does not own it.
   *
   * @param lane where the step runs
   * @param step the transformation
   * @param <R> the result type
   * @return the dependent job, in the same realm
   * @throws RealmClosedException if the realm is closing or closed
   */
  <R> Job<R> then(Lane lane, Step<? super T, ? extends R> step);

  /**
   * Runs {@code step} on {@code lane} with the failure of this job if it fails.
   *
   * <p>A success passes through unchanged and a cancellation stays a cancellation.
   *
   * @param lane where the step runs
   * @param step the recovery; its result becomes the value of the returned job
   * @return the dependent job, in the same realm
   * @throws RealmClosedException if the realm is closing or closed
   */
  Job<T> recover(Lane lane, Step<? super Throwable, ? extends T> step);

  /**
   * Calls {@code completion} on {@code lane} exactly once with the final outcome.
   *
   * <p>The callback counts as outstanding work of the realm: {@link Realm#close()} waits for it.
   *
   * @param lane where the callback runs
   * @param completion the callback
   * @throws RealmClosedException if the realm is closing or closed
   */
  void onComplete(Lane lane, Completion<? super T> completion);
}
