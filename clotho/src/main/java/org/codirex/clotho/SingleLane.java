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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.RejectedExecutionException;

/**
 * A lane with one dedicated thread owned by the engine: every task runs on that same thread, in
 * submission order. Priority is ignored. Not part of the public API.
 *
 * <p>The thread starts with the first task and lives until the engine shuts down.
 */
final class SingleLane implements Lane, ManagedLane {

  private final Engine engine;
  private final String name;
  private final QueueLimit limit;
  private final Object lock = new Object();
  private final ArrayDeque<Runnable> queue = new ArrayDeque<>();
  private Thread thread;
  private boolean stopNow;

  SingleLane(Engine engine, String name, QueueLimit limit) {
    this.engine = engine;
    this.name = name;
    this.limit = limit;
  }

  @Override
  public BlockingPolicy blockingPolicy() {
    return BlockingPolicy.ALLOWED;
  }

  @Override
  public void execute(Runnable task) {
    Objects.requireNonNull(task, "task");
    Runnable dropped = null;
    synchronized (lock) {
      if (engine.isShutdown() || stopNow) {
        throw new LaneClosedException(this + " is shut down");
      }
      int cap = limit.capacity();
      if (cap > 0 && queue.size() >= cap) {
        if (limit.policy() == RejectionPolicy.REJECT) {
          throw new RejectedExecutionException(this + " queue is full (" + cap + ")");
        }
        dropped = queue.pollFirst();
      }
      queue.addLast(task);
      if (thread == null) {
        Thread t = engine.newThread(this::loop, engine.name() + "-single-" + name);
        engine.threadStarting(t);
        try {
          t.start();
        } catch (Throwable e) {
          engine.threadEnded(t);
          queue.removeLast();
          throw new RejectedExecutionException(this + " cannot start its thread", e);
        }
        thread = t;
      } else {
        lock.notifyAll();
      }
    }
    if (dropped != null) {
      Engine.discard(engine, dropped, new DroppedException(this + " dropped its oldest task"));
    }
  }

  private void loop() {
    try {
      for (; ; ) {
        Runnable task;
        synchronized (lock) {
          for (; ; ) {
            if (stopNow) {
              return;
            }
            task = queue.pollFirst();
            if (task != null) {
              break;
            }
            if (engine.isShutdown()) {
              return;
            }
            try {
              lock.wait();
            } catch (InterruptedException e) {
              // Only shutdownNow interrupts this thread; the loop re-checks the flags.
            }
          }
        }
        Runnable toRun = task;
        LaneContext.runIn(
            this,
            () -> {
              try {
                toRun.run();
              } catch (Throwable t) {
                engine.reportTaskError(t);
              }
            });
        if (!stopNow) {
          Thread.interrupted();
        }
      }
    } finally {
      engine.threadEnded(Thread.currentThread());
    }
  }

  @Override
  public void onShutdown() {
    synchronized (lock) {
      lock.notifyAll();
    }
  }

  @Override
  public int onShutdownNow() {
    List<Runnable> drained;
    synchronized (lock) {
      stopNow = true;
      drained = new ArrayList<>(queue);
      queue.clear();
      if (thread != null) {
        thread.interrupt();
      }
      lock.notifyAll();
    }
    for (Runnable r : drained) {
      Engine.discard(
          engine,
          r,
          new DroppedException(
              this + " discarded a queued task (shutdownNow)", DroppedException.Reason.SHUTDOWN));
    }
    return drained.size();
  }

  @Override
  public String toString() {
    return "Lane(" + engine.name() + "/single:" + name + ")";
  }
}
