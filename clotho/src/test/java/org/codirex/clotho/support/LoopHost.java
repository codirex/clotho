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
package org.codirex.clotho.support;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import org.codirex.clotho.Lane;
import org.codirex.clotho.MainThreadHost;

/** A fake main thread: one thread running posted tasks in order, like a UI loop. */
public final class LoopHost implements MainThreadHost {

  private final LinkedBlockingQueue<Runnable> queue = new LinkedBlockingQueue<>();
  private final List<Throwable> uncaught = Collections.synchronizedList(new ArrayList<>());
  private final Thread thread;
  private volatile boolean quit;

  public LoopHost() {
    thread = new Thread(this::loop, "fake-main");
    thread.setDaemon(true);
    thread.start();
  }

  private void loop() {
    while (!quit) {
      Runnable task;
      try {
        task = queue.take();
      } catch (InterruptedException e) {
        return;
      }
      try {
        task.run();
      } catch (Throwable t) {
        // A real UI loop would crash here; the fake records the exception and keeps going.
        uncaught.add(t);
      }
    }
  }

  @Override
  public boolean isMainThread() {
    return Thread.currentThread() == thread;
  }

  @Override
  public void post(Runnable task) {
    if (quit) {
      throw new RejectedExecutionException("fake main loop has quit");
    }
    queue.add(task);
  }

  public Thread thread() {
    return thread;
  }

  public List<Throwable> uncaught() {
    synchronized (uncaught) {
      return new ArrayList<>(uncaught);
    }
  }

  /** Blocks until every task posted before this call has run. */
  public void drain() throws InterruptedException {
    CountDownLatch done = new CountDownLatch(1);
    post(done::countDown);
    if (!done.await(20, TimeUnit.SECONDS)) {
      throw new AssertionError("main loop did not drain");
    }
  }

  /** Runs the task on the loop thread, directly through the host (no lane involved). */
  public void runOnMain(Runnable task) throws InterruptedException {
    CountDownLatch done = new CountDownLatch(1);
    post(
        () -> {
          try {
            task.run();
          } finally {
            done.countDown();
          }
        });
    if (!done.await(20, TimeUnit.SECONDS)) {
      throw new AssertionError("main task did not run");
    }
  }

  /** Stops the loop. Tasks still queued are discarded with the given reason, never run. */
  public void quitDiscarding(Throwable reason) {
    quit = true;
    thread.interrupt();
    Runnable task;
    while ((task = queue.poll()) != null) {
      if (task instanceof Lane.Discardable discardable) {
        discardable.discarded(reason);
      }
    }
  }

  public void quit() {
    quit = true;
    thread.interrupt();
  }
}
