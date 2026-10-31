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
 * A transformation applied to the result of a previous job.
 *
 * @param <I> the input type
 * @param <O> the output type
 */
@FunctionalInterface
public interface Step<I, O> {

  /**
   * Applies this step.
   *
   * @param input the value (or failure cause for recovery steps) of the previous job
   * @param checkpoint the cancellation checkpoint of the job running this step
   * @return the result
   * @throws Exception any failure; it becomes the {@link Outcome.Failure} of the resulting job
   */
  O apply(I input, Checkpoint checkpoint) throws Exception;
}
