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
 * A lane refused a task because it is closed for good: its engine was shut down or its host stopped
 * accepting tasks.
 *
 * <p>It is a {@link RejectedExecutionException}, but unlike a refusal caused by a full queue it
 * will never succeed later. Periodic jobs rely on this difference: with {@link
 * RepeatFailure#CONTINUE} they skip a tick refused because of a full queue, and end with a failure
 * when the lane is closed.
 */
public final class LaneClosedException extends RejectedExecutionException {

  private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param message the detail message
   */
  public LaneClosedException(String message) {
    super(message);
  }

  /**
   * Creates the exception with a cause.
   *
   * @param message the detail message
   * @param cause why the lane is closed
   */
  public LaneClosedException(String message, Throwable cause) {
    super(message, cause);
  }
}
