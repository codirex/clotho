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

import java.util.Objects;

/**
 * The final result of a {@link Job}: exactly one of {@link Success}, {@link Failure} or {@link
 * Cancelled}. An outcome never changes once a job has one.
 *
 * @param <T> the value type of the job
 */
public sealed interface Outcome<T> permits Outcome.Success, Outcome.Failure, Outcome.Cancelled {

  /**
   * Tells whether this is a {@link Success}.
   *
   * @return {@code true} for a success
   */
  boolean isSuccess();

  /**
   * Tells whether this is a {@link Failure}.
   *
   * @return {@code true} for a failure
   */
  boolean isFailure();

  /**
   * Tells whether this is {@link Cancelled}.
   *
   * @return {@code true} for a cancellation
   */
  boolean isCancelled();

  /**
   * Returns the value or throws.
   *
   * @return the value of a success
   * @throws JobFailedException for a failure, with the failure as cause
   * @throws CancelledException for a cancellation
   */
  T getOrThrow();

  /**
   * Returns the value of a success.
   *
   * @return the value, or {@code null} if this is not a success (or the value is {@code null})
   */
  T valueOrNull();

  /**
   * Returns the cause of a failure.
   *
   * @return the cause, or {@code null} if this is not a failure
   */
  Throwable failureOrNull();

  /**
   * Creates a success.
   *
   * @param value the value, may be {@code null}
   * @param <T> the value type
   * @return the outcome
   */
  static <T> Outcome<T> success(T value) {
    return new Success<>(value);
  }

  /**
   * Creates a failure.
   *
   * @param cause the cause, not {@code null}
   * @param <T> the value type
   * @return the outcome
   */
  static <T> Outcome<T> failure(Throwable cause) {
    return new Failure<>(Objects.requireNonNull(cause, "cause"));
  }

  /**
   * Returns the cancelled outcome without a late failure.
   *
   * @param <T> the value type
   * @return the outcome
   */
  @SuppressWarnings("unchecked")
  static <T> Outcome<T> cancelled() {
    return (Outcome<T>) Cancelled.PLAIN;
  }

  /**
   * A job that produced a value.
   *
   * @param <T> the value type
   */
  final class Success<T> implements Outcome<T> {
    private final T value;

    Success(T value) {
      this.value = value;
    }

    /**
     * Returns the value.
     *
     * @return the value, may be {@code null}
     */
    public T value() {
      return value;
    }

    @Override
    public boolean isSuccess() {
      return true;
    }

    @Override
    public boolean isFailure() {
      return false;
    }

    @Override
    public boolean isCancelled() {
      return false;
    }

    @Override
    public T getOrThrow() {
      return value;
    }

    @Override
    public T valueOrNull() {
      return value;
    }

    @Override
    public Throwable failureOrNull() {
      return null;
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof Success<?> that && Objects.equals(value, that.value);
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(value);
    }

    @Override
    public String toString() {
      return "Success(" + value + ")";
    }
  }

  /**
   * A job that failed.
   *
   * @param <T> the value type
   */
  final class Failure<T> implements Outcome<T> {
    private final Throwable cause;

    Failure(Throwable cause) {
      this.cause = cause;
    }

    /**
     * Returns the cause.
     *
     * @return the failure cause, never {@code null}
     */
    public Throwable cause() {
      return cause;
    }

    @Override
    public boolean isSuccess() {
      return false;
    }

    @Override
    public boolean isFailure() {
      return true;
    }

    @Override
    public boolean isCancelled() {
      return false;
    }

    @Override
    public T getOrThrow() {
      throw new JobFailedException(cause);
    }

    @Override
    public T valueOrNull() {
      return null;
    }

    @Override
    public Throwable failureOrNull() {
      return cause;
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof Failure<?> that && cause == that.cause;
    }

    @Override
    public int hashCode() {
      return System.identityHashCode(cause);
    }

    @Override
    public String toString() {
      return "Failure(" + cause + ")";
    }
  }

  /**
   * A job that was cancelled. Any value it computed after cancellation was requested is dropped.
   *
   * @param <T> the value type
   */
  final class Cancelled<T> implements Outcome<T> {
    static final Cancelled<?> PLAIN = new Cancelled<>(null);

    private final Throwable lateFailure;

    Cancelled(Throwable lateFailure) {
      this.lateFailure = lateFailure;
    }

    /**
     * Returns the exception the work threw after cancellation was already requested.
     *
     * <p>It is informational only and is not reported to the {@link UncaughtHandler}: such
     * exceptions are usually a normal side effect of cancelling (for example an I/O error after a
     * socket was closed).
     *
     * @return the late failure, or {@code null} if there was none
     */
    public Throwable lateFailure() {
      return lateFailure;
    }

    @Override
    public boolean isSuccess() {
      return false;
    }

    @Override
    public boolean isFailure() {
      return false;
    }

    @Override
    public boolean isCancelled() {
      return true;
    }

    @Override
    public T getOrThrow() {
      throw new CancelledException();
    }

    @Override
    public T valueOrNull() {
      return null;
    }

    @Override
    public Throwable failureOrNull() {
      return null;
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof Cancelled<?> that && lateFailure == that.lateFailure;
    }

    @Override
    public int hashCode() {
      return System.identityHashCode(lateFailure);
    }

    @Override
    public String toString() {
      return lateFailure == null ? "Cancelled" : "Cancelled(late: " + lateFailure + ")";
    }
  }
}
