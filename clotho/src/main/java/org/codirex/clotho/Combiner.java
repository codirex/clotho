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
 * Combines the results of two jobs.
 *
 * @param <A> the first input type
 * @param <B> the second input type
 * @param <R> the result type
 */
@FunctionalInterface
public interface Combiner<A, B, R> {

  /**
   * Combines two values.
   *
   * @param first the result of the first job
   * @param second the result of the second job
   * @param checkpoint the cancellation checkpoint of the combining job
   * @return the combined value
   * @throws Exception any failure; it becomes the {@link Outcome.Failure} of the combining job
   */
  R combine(A first, B second, Checkpoint checkpoint) throws Exception;
}
