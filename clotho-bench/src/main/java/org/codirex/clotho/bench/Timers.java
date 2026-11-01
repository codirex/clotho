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

package org.codirex.clotho.bench;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.codirex.clotho.Job;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;

/**
 * Timers that never fire: schedule {@value #COUNT} one-hour timers, then cancel them all. This is
 * the timeout pattern (almost every timeout is cancelled). Clotho uses one hashed timer wheel;
 * {@code ScheduledThreadPoolExecutor} uses a heap, with and without removing cancelled entries.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@OperationsPerInvocation(Timers.COUNT)
@Threads(1)
@Fork(jvmArgsAppend = {"-Xms1g", "-Xmx1g"})
public class Timers {

  static final int COUNT = 10_000;

  /** The two scheduled executors. */
  @State(Scope.Benchmark)
  public static class Schedulers {
    public ScheduledThreadPoolExecutor keepCancelled;
    public ScheduledThreadPoolExecutor removeCancelled;

    @Setup
    public void setUp() {
      keepCancelled = new ScheduledThreadPoolExecutor(1);
      removeCancelled = new ScheduledThreadPoolExecutor(1);
      removeCancelled.setRemoveOnCancelPolicy(true);
    }

    @TearDown(Level.Invocation)
    public void drain() {
      keepCancelled.getQueue().clear();
      removeCancelled.getQueue().clear();
    }

    @TearDown
    public void tearDown() {
      keepCancelled.shutdownNow();
      removeCancelled.shutdownNow();
    }
  }

  @Benchmark
  public void clotho(Pools p) {
    @SuppressWarnings("unchecked")
    Job<Integer>[] jobs = (Job<Integer>[]) new Job<?>[COUNT];
    for (int i = 0; i < COUNT; i++) {
      jobs[i] = p.realm.after(p.lane, 1, TimeUnit.HOURS, cp -> 1);
    }
    for (Job<Integer> job : jobs) {
      job.cancel();
    }
  }

  @Benchmark
  public void scheduledExecutorKeepCancelled(Schedulers s) {
    run(s.keepCancelled);
  }

  @Benchmark
  public void scheduledExecutorRemoveCancelled(Schedulers s) {
    run(s.removeCancelled);
  }

  private static void run(ScheduledThreadPoolExecutor executor) {
    ScheduledFuture<?>[] futures = new ScheduledFuture<?>[COUNT];
    for (int i = 0; i < COUNT; i++) {
      futures[i] = executor.schedule(() -> 1, 1, TimeUnit.HOURS);
    }
    for (ScheduledFuture<?> f : futures) {
      f.cancel(false);
    }
  }
}
