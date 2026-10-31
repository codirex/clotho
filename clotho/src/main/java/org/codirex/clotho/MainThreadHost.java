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
 * The bridge between Clotho and the main (UI) thread of a platform.
 *
 * <p>A host is the only thing a platform has to provide: Clotho builds the whole {@link MainLane}
 * (ordering, blocking policy, {@code immediate()}, discard handling) on top of it with {@link
 * MainLane#of}. On the JVM a host can be discovered with {@link java.util.ServiceLoader} (service
 * file {@code META-INF/services/org.codirex.clotho.MainThreadHost}); on Android the {@code
 * clotho-android} module installs one through {@code androidx.startup}.
 *
 * <p>Contract for implementations:
 *
 * <ul>
 *   <li>{@link #post} may be called from any thread. Posted tasks run on the main thread, one at a
 *       time, in the order in which {@code post} returned (FIFO).
 *   <li>{@link #post} throws {@link java.util.concurrent.RejectedExecutionException} only when it
 *       can no longer accept tasks (for example the loop is quitting); the lane then reports it as
 *       a {@link LaneClosedException}. A host must queue the tasks of a busy loop, not refuse them.
 *   <li>If the host removes an accepted task without running it, it must call {@link
 *       Lane.Discardable#discarded} on it (when it implements that interface) and must not run it
 *       afterwards. A host whose loop never stops, such as the Android main looper, never needs to.
 *   <li>{@link #isMainThread()} is cheap and callable from any thread.
 * </ul>
 */
public interface MainThreadHost {

  /**
   * Tells whether the calling thread is the main thread.
   *
   * @return {@code true} on the main thread
   */
  boolean isMainThread();

  /**
   * Queues a task to run on the main thread, after the tasks posted before it.
   *
   * @param task the task
   * @throws java.util.concurrent.RejectedExecutionException if the task cannot be accepted
   */
  void post(Runnable task);
}
