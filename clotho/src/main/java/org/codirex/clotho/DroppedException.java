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

import java.util.concurrent.RejectedExecutionException;

/**
 * The final failure of a job whose task was accepted but removed without running: dropped by {@link
 * RejectionPolicy#DROP_OLDEST} ({@link Reason#QUEUE_OVERFLOW}), or discarded because the engine was
 * shut down ({@link Reason#SHUTDOWN}), including pending timers of its {@link Cadence}.
 */
public final class DroppedException extends RejectedExecutionException {

  private static final long serialVersionUID = 1L;

  /** Why the task was removed. */
  public enum Reason {
    /** A bounded queue was full and this was its oldest task. Transient: later tasks may run. */
    QUEUE_OVERFLOW,
    /** The engine was shut down. Final: nothing will run on that engine again. */
    SHUTDOWN
  }

  /** Why the task was removed. */
  private final Reason reason;

  /**
   * Creates the exception for {@link Reason#QUEUE_OVERFLOW}.
   *
   * @param message the detail message
   */
  public DroppedException(String message) {
    this(message, Reason.QUEUE_OVERFLOW);
  }

  /**
   * Creates the exception.
   *
   * @param message the detail message
   * @param reason why the task was removed
   */
  public DroppedException(String message, Reason reason) {
    super(message);
    this.reason = reason;
  }

  /**
   * Creates the exception with a cause; the reason is {@link Reason#SHUTDOWN} if the cause is a
   * {@link LaneClosedException}, otherwise {@link Reason#QUEUE_OVERFLOW}.
   *
   * @param message the detail message
   * @param cause why the task could not be kept
   */
  public DroppedException(String message, Throwable cause) {
    super(message, cause);
    this.reason = cause instanceof LaneClosedException ? Reason.SHUTDOWN : Reason.QUEUE_OVERFLOW;
  }

  /**
   * Returns why the task was removed.
   *
   * @return the reason
   */
  public Reason reason() {
    return reason;
  }
}
