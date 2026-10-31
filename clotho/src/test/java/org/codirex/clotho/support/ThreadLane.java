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

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.codirex.clotho.Lane;

/** A real-thread lane for tests. Threads are daemons; call {@link #close()} when done. */
public final class ThreadLane implements Lane {

  private final ExecutorService pool;

  public ThreadLane(int threads) {
    AtomicInteger counter = new AtomicInteger();
    this.pool =
        Executors.newFixedThreadPool(
            threads,
            runnable -> {
              Thread t = new Thread(runnable, "test-lane-" + counter.incrementAndGet());
              t.setDaemon(true);
              return t;
            });
  }

  @Override
  public void execute(Runnable task) {
    pool.execute(task);
  }

  public void close() {
    pool.shutdownNow();
  }
}
