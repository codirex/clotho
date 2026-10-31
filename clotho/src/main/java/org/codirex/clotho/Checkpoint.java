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

/** The cooperative cancellation point handed to running {@link Work}. */
public interface Checkpoint {

  /**
   * Tells whether cancellation of the owning job was requested.
   *
   * @return {@code true} once cancellation was requested
   */
  boolean isCancelled();

  /**
   * Throws if cancellation was requested.
   *
   * @throws CancelledException if the owning job was asked to cancel
   */
  void check();

  /**
   * Returns a checkpoint that is never cancelled, for running {@link Work} outside a job.
   *
   * @return a shared never-cancelled checkpoint
   */
  static Checkpoint none() {
    return NeverCancelled.INSTANCE;
  }
}
