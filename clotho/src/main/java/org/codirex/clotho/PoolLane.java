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

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/**
 * A work-stealing pool: the implementation of the compute and I/O lanes. Not part of the public
 * API.
 *
 * <p>Tasks submitted from outside go to a shared queue; tasks submitted by a worker of this lane go
 * to that worker's local queue. A worker takes the best task (see {@link RankedQueue}) among its
 * local and the shared queue, and steals the best task of another worker when both are empty.
 * Queues are lock-protected, not lock-free; correctness is the goal of this phase.
 *
 * <p>Idle workers park on a lock-free stack. A submitter claims one idle worker (removing it from
 * the stack) and unparks it, or starts a new worker if the pool is below its maximum, so a claimed
 * wake-up can never be lost. Workers retire after the keep-alive time.
 */
final class PoolLane implements Lane, ManagedLane {

  private static final ThreadLocal<Worker> CURRENT = new ThreadLocal<>();

  private final Engine engine;
  private final String name;
  private final BlockingPolicy policy;
  private final int maxThreads;
  private final long keepAliveNanos;
  private final QueueLimit limit;

  private final RankedQueue shared = new RankedQueue();
  private final CopyOnWriteArrayList<Worker> workers = new CopyOnWriteArrayList<>();
  private final ConcurrentLinkedDeque<Worker> idle = new ConcurrentLinkedDeque<>();
  private final AtomicInteger queued = new AtomicInteger();
  private final AtomicInteger threads = new AtomicInteger();
  private final AtomicInteger workerIds = new AtomicInteger();
  private final AtomicLong seq = new AtomicLong();
  private volatile boolean stopNow;

  PoolLane(
      Engine engine,
      String name,
      BlockingPolicy policy,
      int maxThreads,
      long keepAliveNanos,
      QueueLimit limit) {
    this.engine = engine;
    this.name = name;
    this.policy = policy;
    this.maxThreads = maxThreads;
    this.keepAliveNanos = keepAliveNanos;
    this.limit = limit;
  }

  @Override
  public BlockingPolicy blockingPolicy() {
    return policy;
  }

  @Override
  public void execute(Runnable task) {
    execute(task, Priority.NORMAL);
  }

  @Override
  public void execute(Runnable runnable, Priority priority) {
    Objects.requireNonNull(runnable, "task");
    Objects.requireNonNull(priority, "priority");
    EngineTask task = new EngineTask(runnable, priority, seq.incrementAndGet());
    List<EngineTask> dropped = null;
    boolean runInline = false;
    engine.enterSubmit();
    try {
      int cap = limit.capacity();
      for (; ; ) {
        int q = queued.get();
        if (cap == 0 || q < cap) {
          if (queued.compareAndSet(q, q + 1)) {
            break;
          }
          continue;
        }
        RejectionPolicy p = limit.policy();
        if (p == RejectionPolicy.REJECT) {
          throw new RejectedExecutionException(this + " queue is full (" + cap + ")");
        }
        if (p == RejectionPolicy.DROP_OLDEST) {
          EngineTask victim = dropOldest();
          if (victim != null) {
            if (dropped == null) {
              dropped = new ArrayList<>(2);
            }
            dropped.add(victim);
          } else {
            Thread.yield();
          }
          continue;
        }
        if (!callerMayRunInline()) {
          throw new RejectedExecutionException(
              this + " queue is full and the caller may not run this work inline");
        }
        runInline = true;
        break;
      }
      if (!runInline) {
        Worker current = CURRENT.get();
        RankedQueue target = current != null && current.lane() == this ? current.local : shared;
        task.enqueueNanos = engine.nanoTime();
        target.add(task);
        wakeOrSpawn(task, target);
      }
    } finally {
      engine.exitSubmit();
      if (dropped != null) {
        for (EngineTask victim : dropped) {
          discard(victim, new DroppedException(this + " dropped its oldest task (queue full)"));
        }
      }
    }
    if (runInline) {
      try {
        runnable.run();
      } catch (Throwable t) {
        engine.reportTaskError(t);
      }
    }
  }

  /** The caller may run work inline only if that cannot put blocking work on a no-block path. */
  private boolean callerMayRunInline() {
    MainLane main = MainLaneRegistry.peek();
    if (main != null && main.isMainThread()) {
      return false;
    }
    BlockingPolicy caller = callerPolicy();
    if (caller == null) {
      return false;
    }
    return !(caller == BlockingPolicy.FORBIDDEN && policy == BlockingPolicy.ALLOWED);
  }

  private static BlockingPolicy callerPolicy() {
    Lane lane = LaneContext.current();
    if (lane != null) {
      return lane.blockingPolicy();
    }
    Worker w = CURRENT.get();
    return w != null ? w.lane().policy : null;
  }

  private void wakeOrSpawn(EngineTask task, RankedQueue target) {
    Worker w = idle.poll();
    if (w != null) {
      LockSupport.unpark(w.thread);
      return;
    }
    spawnIfRoom(task, target);
  }

  private void spawnIfRoom(EngineTask task, RankedQueue target) {
    for (; ; ) {
      int n = threads.get();
      if (n >= maxThreads) {
        return;
      }
      if (threads.compareAndSet(n, n + 1)) {
        break;
      }
    }
    try {
      startWorker();
    } catch (Throwable e) {
      threads.decrementAndGet();
      if (task != null && threads.get() == 0 && target.remove(task)) {
        queued.decrementAndGet();
        throw new RejectedExecutionException(this + " cannot start a worker thread", e);
      }
      engine.reportTaskError(e);
    }
  }

  private void startWorker() {
    Worker w = new Worker();
    Thread t = engine.newThread(w, engine.name() + "-" + name + "-" + workerIds.incrementAndGet());
    w.thread = t;
    workers.add(w);
    engine.threadStarting(t);
    try {
      t.start();
    } catch (Throwable e) {
      workers.remove(w);
      engine.threadEnded(t);
      throw e;
    }
  }

  /** Removes the task that waited longest in any queue. Returns it (not yet discarded). */
  private EngineTask dropOldest() {
    RankedQueue bestQueue = null;
    EngineTask best = null;
    EngineTask s = shared.peekOldest();
    if (s != null) {
      best = s;
      bestQueue = shared;
    }
    for (Worker w : workers) {
      EngineTask o = w.local.peekOldest();
      if (o != null && (best == null || RankedQueue.older(o, best))) {
        best = o;
        bestQueue = w.local;
      }
    }
    if (bestQueue == null) {
      return null;
    }
    EngineTask victim = bestQueue.pollOldest();
    if (victim != null) {
      queued.decrementAndGet();
    }
    return victim;
  }

  private void discard(EngineTask task, Throwable reason) {
    Engine.discard(engine, task.runnable, reason);
  }

  // ---- ManagedLane ----

  @Override
  public void onShutdown() {
    for (Worker w : workers) {
      LockSupport.unpark(w.thread);
    }
  }

  @Override
  public int onShutdownNow() {
    stopNow = true;
    List<EngineTask> drained = new ArrayList<>();
    shared.drainTo(drained);
    for (Worker w : workers) {
      w.local.drainTo(drained);
    }
    queued.addAndGet(-drained.size());
    for (EngineTask t : drained) {
      discard(
          t,
          new DroppedException(
              this + " discarded a queued task (shutdownNow)", DroppedException.Reason.SHUTDOWN));
    }
    for (Worker w : workers) {
      w.thread.interrupt();
      LockSupport.unpark(w.thread);
    }
    return drained.size();
  }

  @Override
  public String toString() {
    return "Lane(" + engine.name() + "/" + name + ")";
  }

  int queuedCount() {
    return queued.get();
  }

  // ---- workers ----

  private final class Worker implements Runnable {
    final RankedQueue local = new RankedQueue();
    Thread thread;

    PoolLane lane() {
      return PoolLane.this;
    }

    @Override
    public void run() {
      CURRENT.set(this);
      try {
        loop();
      } catch (Throwable t) {
        engine.reportTaskError(t);
      } finally {
        CURRENT.remove();
        workers.remove(this);
        idle.remove(this);
        EngineTask leftover;
        while ((leftover = local.pollOldest()) != null) {
          shared.add(leftover);
        }
        threads.decrementAndGet();
        if (!stopNow && queued.get() > 0) {
          Worker other = idle.poll();
          if (other != null) {
            LockSupport.unpark(other.thread);
          } else {
            spawnIfRoom(null, null);
          }
        }
        engine.threadEnded(Thread.currentThread());
      }
    }

    private void loop() {
      boolean idleTiming = false;
      long idleStart = 0;
      for (; ; ) {
        EngineTask task = findTask();
        if (task != null) {
          idleTiming = false;
          runTask(task);
          continue;
        }
        if (stopNow) {
          return;
        }
        if (engine.isShutdown() && queued.get() == 0) {
          return;
        }
        if (!idleTiming) {
          idleStart = System.nanoTime();
          idleTiming = true;
        }
        idle.push(this);
        boolean rescan = queued.get() > 0 || engine.isShutdown() || stopNow;
        if (!rescan) {
          long remaining = keepAliveNanos - (System.nanoTime() - idleStart);
          if (remaining > 0) {
            LockSupport.parkNanos(PoolLane.this, remaining);
            Thread.interrupted();
          }
        }
        if (idle.remove(this)) {
          // Nobody claimed this worker. Retire only after the keep-alive time, with nothing queued.
          if (!rescan
              && keepAliveNanos - (System.nanoTime() - idleStart) <= 0
              && queued.get() == 0
              && !stopNow) {
            return;
          }
        }
      }
    }

    private void runTask(EngineTask task) {
      try {
        task.runnable.run();
      } catch (Throwable t) {
        engine.reportTaskError(t);
      } finally {
        if (!stopNow) {
          Thread.interrupted();
        }
      }
    }

    private EngineTask findTask() {
      long now = engine.nanoTime();
      long step = engine.agingStepNanos();
      EngineTask a = local.peekBest(now, step);
      EngineTask b = shared.peekBest(now, step);
      RankedQueue source = null;
      if (a != null && (b == null || RankedQueue.better(a, b, now, step))) {
        source = local;
      } else if (b != null) {
        source = shared;
      }
      if (source != null) {
        EngineTask t = source.pollBest(now, step);
        if (t != null) {
          queued.decrementAndGet();
          return t;
        }
      }
      Object[] snapshot = workers.toArray();
      int n = snapshot.length;
      if (n > 1) {
        int start = ThreadLocalRandom.current().nextInt(n);
        for (int i = 0; i < n; i++) {
          Worker victim = (Worker) snapshot[(start + i) % n];
          if (victim == this) {
            continue;
          }
          EngineTask stolen = victim.local.pollBest(now, step);
          if (stolen != null) {
            queued.decrementAndGet();
            return stolen;
          }
        }
      }
      EngineTask t = shared.pollBest(now, step);
      if (t != null) {
        queued.decrementAndGet();
      }
      return t;
    }
  }
}
