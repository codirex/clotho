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
import org.codirex.clotho.BlockingPolicy;
import org.codirex.clotho.Lane;
import org.codirex.clotho.Priority;

/**
 * A lane that runs nothing by itself. Tasks wait in a queue until the test runs them, on the
 * calling thread and in submission order. It lets a test decide exactly when asynchronous code
 * proceeds, without threads, sleeps or races.
 *
 * <p>Priorities are recorded ({@link #priorities()}) but do not change the order. The lane is
 * thread-safe; tasks run outside its lock.
 */
public final class DeterministicLane implements Lane {

  private static final class Queued {
    final Runnable task;
    final Priority priority;

    Queued(Runnable task, Priority priority) {
      this.task = task;
      this.priority = priority;
    }
  }

  private final Object lock = new Object();
  private final ArrayDeque<Queued> queue = new ArrayDeque<>();
  private final BlockingPolicy policy;
  private RejectedExecutionException rejection;

  /** Creates a lane that allows blocking. */
  public DeterministicLane() {
    this(BlockingPolicy.ALLOWED);
  }

  /**
   * Creates a lane with the given blocking policy, for testing code that must not block.
   *
   * @param policy the policy reported by {@link #blockingPolicy()}
   */
  public DeterministicLane(BlockingPolicy policy) {
    this.policy = Objects.requireNonNull(policy, "policy");
  }

  @Override
  public void execute(Runnable task) {
    execute(task, Priority.NORMAL);
  }

  @Override
  public void execute(Runnable task, Priority priority) {
    Objects.requireNonNull(task, "task");
    Objects.requireNonNull(priority, "priority");
    synchronized (lock) {
      if (rejection != null) {
        throw rejection;
      }
      queue.addLast(new Queued(task, priority));
    }
  }

  @Override
  public BlockingPolicy blockingPolicy() {
    return policy;
  }

  /**
   * Returns the number of tasks waiting.
   *
   * @return the queue size
   */
  public int size() {
    synchronized (lock) {
      return queue.size();
    }
  }

  /**
   * Tells whether no task is waiting.
   *
   * @return {@code true} if the queue is empty
   */
  public boolean isEmpty() {
    return size() == 0;
  }

  /**
   * Returns the priorities of the waiting tasks, oldest first.
   *
   * @return a snapshot
   */
  public List<Priority> priorities() {
    synchronized (lock) {
      List<Priority> result = new ArrayList<>(queue.size());
      for (Queued q : queue) {
        result.add(q.priority);
      }
      return result;
    }
  }

  /**
   * Runs the oldest waiting task on the calling thread.
   *
   * @return {@code true} if a task ran, {@code false} if the queue was empty
   */
  public boolean runNext() {
    Queued next;
    synchronized (lock) {
      next = queue.pollFirst();
    }
    if (next == null) {
      return false;
    }
    next.task.run();
    return true;
  }

  /**
   * Runs waiting tasks, including the ones they submit, until the queue is empty.
   *
   * @return the number of tasks that ran
   * @throws AssertionError if more than one million tasks ran (a task that always submits another)
   */
  public int runAll() {
    return runAll(1_000_000);
  }

  /**
   * Runs waiting tasks until the queue is empty or {@code max} tasks ran.
   *
   * @param max the maximum number of tasks to run
   * @return the number of tasks that ran
   * @throws AssertionError if the limit was reached while tasks were still waiting
   */
  public int runAll(int max) {
    int ran = 0;
    while (ran < max && runNext()) {
      ran++;
    }
    if (ran == max && !isEmpty()) {
      throw new AssertionError("more than " + max + " tasks: a task keeps submitting new ones");
    }
    return ran;
  }

  /**
   * Makes {@link #execute} throw, to test how code reacts to a refusing lane. Tasks already queued
   * are not affected.
   *
   * @param refusal the exception to throw, or {@code null} to accept tasks again
   */
  public void refuseWith(RejectedExecutionException refusal) {
    synchronized (lock) {
      this.rejection = refusal;
    }
  }

  /**
   * Removes every waiting task without running it, telling the ones that implement {@link
   * Lane.Discardable} (such as the tasks of jobs) why. This is what a bounded or closing lane does.
   *
   * @param reason why the tasks are discarded
   * @return the number of tasks removed
   */
  public int discardAll(Throwable reason) {
    List<Queued> removed;
    synchronized (lock) {
      removed = new ArrayList<>(queue);
      queue.clear();
    }
    for (Queued q : removed) {
      if (q.task instanceof Lane.Discardable discardable) {
        discardable.discarded(reason);
      }
    }
    return removed.size();
  }

  @Override
  public String toString() {
    return "DeterministicLane(" + size() + " waiting)";
  }
}
