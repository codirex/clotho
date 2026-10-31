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

/** Work that produces no value, run again and again by a periodic job. */
@FunctionalInterface
public interface Action {

  /**
   * Runs once.
   *
   * @param checkpoint the cancellation checkpoint of the periodic job
   * @throws Exception any failure; see {@link RepeatFailure}
   */
  void run(Checkpoint checkpoint) throws Exception;
}
