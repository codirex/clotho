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

/** A lane that an {@link Engine} shuts down. Not part of the public API. */
interface ManagedLane {

  /** Graceful shutdown started: wake threads so they can drain and exit. */
  void onShutdown();

  /**
   * Immediate shutdown: discard queued tasks and interrupt threads. Returns the discarded count.
   */
  int onShutdownNow();
}
