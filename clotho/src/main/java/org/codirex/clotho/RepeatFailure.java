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

/**
 * What a periodic job ({@link Realm#every every}, {@link Realm#atRate atRate}) does when a run
 * fails.
 */
public enum RepeatFailure {
  /**
   * The job ends as {@link Outcome.Failure} with the cause. This is the default.
   *
   * <p>This applies to an exception thrown by the user's code and to a tick that its lane refused,
   * for any reason.
   */
  STOP,
  /**
   * The failure of a run is reported once to the {@link UncaughtHandler} of the realm and the job
   * continues with its next scheduled run.
   *
   * <p>Only user exceptions and ticks refused because of a full queue ({@link
   * java.util.concurrent.RejectedExecutionException} that is not a {@link LaneClosedException} and
   * not a shutdown {@link DroppedException}) are survivable: the refused tick is skipped. A lane
   * that is closed, or a cadence that was shut down, ends the job as {@link Outcome.Failure}
   * whatever the policy. Cancellation always ends the job as {@link Outcome.Cancelled}.
   */
  CONTINUE
}
