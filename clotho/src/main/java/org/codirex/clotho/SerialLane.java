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
 * A lane that runs tasks one at a time in submission order, on threads of the engine's I/O pool.
 * Not part of the public API.
 *
 * <p>Priority is ignored. The tasks run in a "drain" job on the backing lane: while a drain runs,
 * no other drain of this lane exists ({@code active} flag), which gives mutual exclusion and a
 * happens-before edge between consecutive tasks, even if they run on different threads.
 */
final class SerialLane implements Lane, ManagedLane {

  private static final int BATCH = 32;

  private final Engine engine;
  private final String name;
  private final Lane backing;
  private final QueueLimit limit;
  private final Object lock = new Object();
  private final ArrayDeque<Runnable> queue = new ArrayDeque<>();
  private boolean active;
  private boolean stopped;

  SerialLane(Engine engine, String name, Lane backing, QueueLimit limit) {
    this.engine = engine;
    this.name = name;
    this.backing = backing;
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
    boolean schedule = false;
    synchronized (lock) {
      if (engine.isShutdown() || stopped) {
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
      if (!active) {
        active = true;
        schedule = true;
      }
    }
    if (dropped != null) {
      Engine.discard(engine, dropped, new DroppedException(this + " dropped its oldest task"));
    }
    if (schedule) {
      try {
        backing.execute(new Drain());
      } catch (RejectedExecutionException e) {
        List<Runnable> others;
        synchronized (lock) {
          active = false;
          others = new ArrayList<>(queue);
          queue.clear();
        }
        boolean removed = false;
        for (Runnable r : others) {
          if (r == task && !removed) {
            removed = true;
          } else {
            Engine.discard(engine, r, new DroppedException(this + " could not be scheduled", e));
          }
        }
        throw e;
      }
    }
  }

  @Override
  public void onShutdown() {
    // Queued tasks still run: the drain keeps going on the I/O pool, which drains gracefully.
  }

  @Override
  public int onShutdownNow() {
    List<Runnable> drained;
    synchronized (lock) {
      stopped = true;
      drained = new ArrayList<>(queue);
      queue.clear();
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
    return "Lane(" + engine.name() + "/serial:" + name + ")";
  }

  private final class Drain implements Runnable, Lane.Discardable {

    @Override
    public void run() {
      int ran = 0;
      for (; ; ) {
        Runnable task;
        synchronized (lock) {
          task = queue.pollFirst();
          if (task == null) {
            active = false;
            return;
          }
        }
        LaneContext.runIn(
            SerialLane.this,
            () -> {
              try {
                task.run();
              } catch (Throwable t) {
                engine.reportTaskError(t);
              }
            });
        if (++ran >= BATCH) {
          ran = 0;
          synchronized (lock) {
            if (queue.isEmpty()) {
              active = false;
              return;
            }
          }
          try {
            backing.execute(new Drain());
            return;
          } catch (RejectedExecutionException e) {
            // The backing lane is full or shut down: keep draining on this thread.
          }
        }
      }
    }

    @Override
    public void discarded(Throwable reason) {
      List<Runnable> all;
      synchronized (lock) {
        active = false;
        all = new ArrayList<>(queue);
        queue.clear();
      }
      for (Runnable r : all) {
        Engine.discard(engine, r, reason);
      }
    }
  }
}
