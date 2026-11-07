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
 * A queued task that no longer needs to run, such as the task of a job that was cancelled before it
 * started. A bounded lane removes such tasks when its queue is full instead of rejecting live work
 * on their account. Not part of the public API.
 */
interface Skippable {

  /** Returns true if running this task would do nothing. */
  boolean isSkippable();

  static boolean test(Runnable task) {
    return task instanceof Skippable s && s.isSkippable();
  }
}
