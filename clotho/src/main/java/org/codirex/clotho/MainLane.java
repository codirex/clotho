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

import java.util.Objects;

/**
 * A {@link Lane} that runs tasks on the main (UI) thread.
 *
 * <p>Behaviour (full details in {@code docs/ANDROID.md}):
 *
 * <ul>
 *   <li><b>Always posts.</b> {@link #execute} queues the task even when the caller is already on
 *       the main thread, so a task never runs inside another one and the order is the order of
 *       submission (FIFO, priority is ignored).
 *   <li><b>Blocking is forbidden.</b> The policy is {@link BlockingPolicy#FORBIDDEN}. In addition,
 *       once a main lane is installed, {@link Job#await()}, {@link Realm#close()} and the other
 *       waiting calls throw {@link BlockingNotAllowedException} on <em>any</em> thread for which
 *       {@link #isMainThread()} is true, even outside Clotho tasks.
 *   <li><b>Exceptions.</b> A job never throws into the loop: its failure becomes its outcome. A raw
 *       {@link Runnable} that throws is not caught; the exception reaches the host loop exactly as
 *       with {@code Handler.post}, so the platform's uncaught-exception handling applies.
 *   <li><b>Cancellation.</b> Cancelling a job that is still queued ends it at once; its queued task
 *       stays in the host queue and does nothing when its turn comes.
 *   <li><b>After the host stops accepting</b> (loop quit), {@link #execute} throws {@link
 *       java.util.concurrent.RejectedExecutionException}, so a job launched afterwards ends as
 *       {@link Outcome.Failure}. Tasks the host discards end their job as {@link Outcome.Failure}
 *       with the reason the host gave.
 * </ul>
 */
public interface MainLane extends Lane {

  /**
   * Tells whether the calling thread is the main thread.
   *
   * @return {@code true} on the main thread
   */
  boolean isMainThread();

  /**
   * Returns a view of this lane that runs the task immediately when the caller is already on the
   * main thread, and posts it otherwise.
   *
   * <p>Immediate execution bypasses the queue: such a task can run before tasks that were posted
   * earlier and are still waiting, and it runs inside the task that submitted it. Use it only when
   * that is acceptable (for example to apply a result without an extra frame of delay). Its
   * blocking policy and exception behaviour are those of the main lane, except that an exception
   * thrown by a raw {@link Runnable} run inline propagates to the caller.
   *
   * @return the immediate view, never {@code null}
   */
  Lane immediate();

  /**
   * Always {@link BlockingPolicy#FORBIDDEN}.
   *
   * @return {@link BlockingPolicy#FORBIDDEN}
   */
  @Override
  default BlockingPolicy blockingPolicy() {
    return BlockingPolicy.FORBIDDEN;
  }

  /**
   * Builds a main lane on top of a platform host.
   *
   * @param name a name for diagnostics
   * @param host the platform bridge
   * @return the lane
   */
  static MainLane of(String name, MainThreadHost host) {
    return new HostMainLane(
        Objects.requireNonNull(name, "name"), Objects.requireNonNull(host, "host"));
  }
}
