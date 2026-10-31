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
 * A unit of work that produces a value.
 *
 * <p>Unlike {@link Runnable} and {@link java.util.concurrent.Callable}, a {@code Work} receives a
 * {@link Checkpoint} through which it observes cancellation cooperatively.
 *
 * @param <T> the type of the produced value
 */
@FunctionalInterface
public interface Work<T> {

  /**
   * Runs the work.
   *
   * @param checkpoint the cancellation checkpoint of the job running this work
   * @return the produced value
   * @throws Exception any failure; it becomes the {@link Outcome.Failure} of the job
   */
  T run(Checkpoint checkpoint) throws Exception;
}
