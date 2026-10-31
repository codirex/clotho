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

/** What a bounded lane does with a task that arrives while its queue is full. */
public enum RejectionPolicy {
  /**
   * The submission fails with {@link java.util.concurrent.RejectedExecutionException}; the job ends
   * as {@link Outcome.Failure} with that exception.
   */
  REJECT,
  /**
   * The task that has waited longest (regardless of priority) is removed and the new task is
   * queued. The removed task never runs; if it belongs to a job, the job ends as {@link
   * Outcome.Failure} with a {@link DroppedException}. On ordered lanes the oldest waiting task is
   * the head of the queue.
   */
  DROP_OLDEST,
  /**
   * The submitting thread runs the task itself, but only if that is safe with respect to blocking:
   * the caller must be known to run on a lane, and a caller on a lane that forbids blocking is
   * never used to run work of a lane that allows blocking. Otherwise the submission is rejected
   * like {@link #REJECT}. Not allowed on {@link Engine#serial serial} and {@link Engine#single
   * single} lanes because it would break their ordering.
   */
  CALLER_RUNS
}
