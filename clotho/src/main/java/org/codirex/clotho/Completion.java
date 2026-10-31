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
 * A callback notified exactly once with the final {@link Outcome} of a job.
 *
 * @param <T> the value type of the job
 */
@FunctionalInterface
public interface Completion<T> {

  /**
   * Called when the job reached its final outcome.
   *
   * <p>Anything thrown here is reported to the {@link UncaughtHandler} of the realm.
   *
   * @param outcome the final outcome, never {@code null}
   */
  void accept(Outcome<? extends T> outcome);
}
