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
package org.codirex.clotho.test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.RejectedExecutionException;
import org.codirex.clotho.DroppedException;
import org.codirex.clotho.Lane;
import org.codirex.clotho.MainThreadHost;

/**
 * A main thread for tests: posted tasks wait until the test calls {@link #idle()} on the thread
 * that acts as the main thread (by default the thread that created this object). Use it with {@code
 * MainLane.of("test", new ManualMainThread())}.
 */
public final class ManualMainThread implements MainThreadHost {

  private final Thread mainThread;
  private final Object lock = new Object();
  private final ArrayDeque<Runnable> queue = new ArrayDeque<>();
  private boolean quit;

  /** Creates a host whose main thread is the calling thread. */
  public ManualMainThread() {
    this(Thread.currentThread());
  }

  /**
   * Creates a host with the given main thread.
   *
   * @param mainThread the thread that {@link #isMainThread()} recognises and that must call {@link
   *     #idle()}
   */
  public ManualMainThread(Thread mainThread) {
    this.mainThread = Objects.requireNonNull(mainThread, "mainThread");
  }

  @Override
  public boolean isMainThread() {
    return Thread.currentThread() == mainThread;
  }

  @Override
  public void post(Runnable task) {
    Objects.requireNonNull(task, "task");
    synchronized (lock) {
      if (quit) {
        throw new RejectedExecutionException("the manual main thread has quit");
      }
      queue.addLast(task);
    }
  }

  /**
   * Returns the number of posted tasks that have not run.
   *
   * @return the number of waiting tasks
   */
  public int pending() {
    synchronized (lock) {
      return queue.size();
    }
  }

  /**
   * Runs the posted tasks, including the ones posted meanwhile, until none is left. An exception
   * thrown by a raw task propagates, as it would crash a real loop; the tasks after it stay queued.
   *
   * @return the number of tasks that ran
   * @throws IllegalStateException if not called on the main thread
   */
  public int idle() {
    if (!isMainThread()) {
      throw new IllegalStateException("idle() must be called on the main thread");
    }
    int ran = 0;
    for (; ; ) {
      Runnable task;
      synchronized (lock) {
        task = queue.pollFirst();
      }
      if (task == null) {
        return ran;
      }
      task.run();
      ran++;
    }
  }

  /**
   * Stops accepting tasks and discards the waiting ones with a shutdown {@link DroppedException}.
   */
  public void quit() {
    quit(new DroppedException("the manual main thread quit", DroppedException.Reason.SHUTDOWN));
  }

  /**
   * Stops accepting tasks and discards the waiting ones, telling those that implement {@link
   * Lane.Discardable} the given reason.
   *
   * @param reason why the tasks are discarded
   */
  public void quit(Throwable reason) {
    List<Runnable> removed;
    synchronized (lock) {
      quit = true;
      removed = new ArrayList<>(queue);
      queue.clear();
    }
    for (Runnable task : removed) {
      if (task instanceof Lane.Discardable discardable) {
        discardable.discarded(reason);
      }
    }
  }
}
