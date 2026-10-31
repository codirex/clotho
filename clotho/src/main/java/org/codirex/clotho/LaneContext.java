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
 * Tracks which lane the current thread is running Clotho work for. The check never looks at thread
 * names. Not part of the public API.
 */
final class LaneContext {

  private static final ThreadLocal<Lane> CURRENT = new ThreadLocal<>();

  private LaneContext() {}

  /** Runs the task with {@code lane} as the current lane (unless the lane inherits). */
  static void runIn(Lane lane, Runnable task) {
    if (lane.blockingPolicy() == BlockingPolicy.INHERIT) {
      task.run();
      return;
    }
    Lane previous = CURRENT.get();
    CURRENT.set(lane);
    try {
      task.run();
    } finally {
      if (previous == null) {
        CURRENT.remove();
      } else {
        CURRENT.set(previous);
      }
    }
  }

  /** Returns the lane whose task the current thread runs, or null if none is known. */
  static Lane current() {
    return CURRENT.get();
  }

  /** Throws if the current lane forbids blocking. */
  static void checkMayBlock() {
    Lane current = CURRENT.get();
    if (current != null && current.blockingPolicy() == BlockingPolicy.FORBIDDEN) {
      throw new BlockingNotAllowedException("Blocking is forbidden on lane " + current);
    }
    MainLane main = MainLaneRegistry.peek();
    if (main != null && main.isMainThread()) {
      throw new BlockingNotAllowedException("Blocking is forbidden on the main thread");
    }
  }
}
