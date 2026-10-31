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
 * What a timer does when its deadline comes, or when it is discarded. Not part of the public API.
 */
interface Timeout {

  /**
   * Called by the wheel while it still holds its lock, right after the entry was created and before
   * anybody can see it in {@code pending()} or cancel it. Implementations only store the entry.
   */
  default void scheduled(TimerEntry entry) {}

  /**
   * The deadline came. Runs on the cadence thread, outside every lock, so it must only hand work
   * over to a lane and never run user code itself.
   */
  void fire();

  /** The timer was removed without firing because its cadence was shut down. */
  void discard(Throwable reason);
}
