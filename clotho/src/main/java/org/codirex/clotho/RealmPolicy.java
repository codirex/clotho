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

/** How a {@link Realm} reacts to the failure of one of its jobs. */
public enum RealmPolicy {
  /**
   * The failure of a job started with {@link Realm#launch launch} cancels every job of the realm,
   * records the failure ({@link Realm#failure()}) and reports it once to the {@link
   * UncaughtHandler}. Later launches return jobs that are already cancelled.
   */
  FAIL_FAST,
  /** Jobs are independent; a failure only affects the job itself and its dependents. */
  SUPERVISED
}
