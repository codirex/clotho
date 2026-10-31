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

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

/** Small helpers shared by the tests. */
public final class TestKit {

  private TestKit() {}

  /** Spins (no sleeping) until the condition holds; fails after 20 seconds. */
  public static void awaitTrue(BooleanSupplier condition) {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() > deadline) {
        throw new AssertionError("condition not reached in time");
      }
      LockSupport.parkNanos(50_000);
    }
  }

  /** Starts a thread running the task. */
  public static Thread start(String name, Runnable task) {
    Thread thread = new Thread(task, name);
    thread.setDaemon(true);
    thread.start();
    return thread;
  }

  /** Joins the thread, failing if it does not end in 20 seconds. */
  public static void join(Thread thread) throws InterruptedException {
    thread.join(TimeUnit.SECONDS.toMillis(20));
    if (thread.isAlive()) {
      throw new AssertionError("thread " + thread.getName() + " did not finish");
    }
  }
}
