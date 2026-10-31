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
 * Decides where work runs.
 *
 * <p>The thread-backed lanes come from an {@link Engine}; {@link #compute()} and {@link #io()} use
 * the global engine. Any implementation must, for every task it accepts, either run it exactly once
 * or discard it exactly once (see {@link Discardable}); and throw from {@link #execute} if it
 * refuses the task.
 */
public interface Lane {

  /**
   * Runs the task, now or later, on this lane.
   *
   * @param task the task to run
   * @throws java.util.concurrent.RejectedExecutionException if the lane refuses the task
   */
  void execute(Runnable task);

  /**
   * Runs the task with a scheduling priority. Lanes that do not support priorities ignore it.
   *
   * @param task the task to run
   * @param priority the scheduling priority
   * @throws java.util.concurrent.RejectedExecutionException if the lane refuses the task
   */
  default void execute(Runnable task, Priority priority) {
    execute(task);
  }

  /**
   * A task that wants to be told when a lane discards it instead of running it.
   *
   * <p>A lane that removes an accepted task from its queue without running it (queue overflow,
   * shutdown) must call {@link #discarded} exactly once on tasks that implement this interface.
   * Tasks created by jobs implement it, which is how a job always reaches a final outcome.
   */
  interface Discardable {
    /**
     * Called instead of running the task.
     *
     * @param reason why the task was discarded, usually a {@link DroppedException}
     */
    void discarded(Throwable reason);
  }

  /**
   * Tells whether blocking calls are allowed in tasks of this lane.
   *
   * @return the blocking policy; {@link BlockingPolicy#ALLOWED} by default
   */
  default BlockingPolicy blockingPolicy() {
    return BlockingPolicy.ALLOWED;
  }

  /**
   * Returns the lane that runs tasks immediately on the calling thread.
   *
   * <p>It keeps the blocking policy of the caller ({@link BlockingPolicy#INHERIT}).
   *
   * @return the shared direct lane
   */
  static Lane direct() {
    return DirectLane.INSTANCE;
  }

  /**
   * Returns the compute lane of the global engine: CPU-bound work, one thread per core at most,
   * blocking forbidden.
   *
   * @return the global compute lane
   */
  static Lane compute() {
    return Engine.global().compute();
  }

  /**
   * Returns the main lane: the lane that runs on the main (UI) thread.
   *
   * <p>It is the lane installed with {@link Clotho#install(MainLane)}; if none was installed, a
   * single {@link MainThreadHost} service is discovered with {@link java.util.ServiceLoader} and
   * installed. On Android the {@code clotho-android} module installs it before the application
   * starts.
   *
   * @return the main lane
   * @throws MainLaneNotInstalledException if none is installed and none can be discovered
   * @throws IllegalStateException if several providers are found
   */
  static MainLane main() {
    return MainLaneRegistry.require(Clotho.class.getClassLoader());
  }

  /**
   * Returns the I/O lane of the global engine: elastic number of threads, blocking allowed.
   *
   * @return the global I/O lane
   */
  static Lane io() {
    return Engine.global().io();
  }
}
