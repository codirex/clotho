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

import java.util.ArrayDeque;
import org.codirex.clotho.BlockingPolicy;
import org.codirex.clotho.Lane;

/** A lane whose tasks run only when the test says so, on the calling thread. */
public final class ManualLane implements Lane {

  private final ArrayDeque<Runnable> queue = new ArrayDeque<>();
  private final BlockingPolicy policy;

  public ManualLane() {
    this(BlockingPolicy.ALLOWED);
  }

  public ManualLane(BlockingPolicy policy) {
    this.policy = policy;
  }

  @Override
  public synchronized void execute(Runnable task) {
    queue.add(task);
  }

  @Override
  public BlockingPolicy blockingPolicy() {
    return policy;
  }

  public synchronized int size() {
    return queue.size();
  }

  /** Runs the oldest queued task. Returns false if there was none. */
  public boolean runNext() {
    Runnable task;
    synchronized (this) {
      task = queue.poll();
    }
    if (task == null) {
      return false;
    }
    task.run();
    return true;
  }

  /** Runs tasks until the queue is empty, including tasks queued meanwhile. */
  public int runAll() {
    int count = 0;
    while (runNext()) {
      count++;
    }
    return count;
  }

  @Override
  public String toString() {
    return "ManualLane(" + policy + ")";
  }
}
