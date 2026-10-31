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
 * Scheduling priority of a job on the work-stealing lanes ({@link Engine#compute()} and {@link
 * Engine#io()}).
 *
 * <p>Priority has no effect on {@link Engine#serial serial} and {@link Engine#single single} lanes:
 * their order guarantee is stronger. See {@code docs/ENGINE.md}.
 */
public enum Priority {
  /** Runs after {@link #NORMAL} and {@link #HIGH} work, until aging promotes it. */
  LOW(0),
  /** The default. */
  NORMAL(1),
  /** Runs first. */
  HIGH(2);

  private final int level;

  Priority(int level) {
    this.level = level;
  }

  int level() {
    return level;
  }
}
