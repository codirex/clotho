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

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;

/**
 * Splits the scheduling cost of {@link BatchThroughput} into its layers. One caller submits a
 * batch of {@value #BATCH} empty tasks straight to a lane or pool and waits on one latch. There is
 * no {@code Job}, no {@code Realm} and no per-task allocation by the caller (the same {@code
 * Runnable} is submitted every time), so the difference to {@code BatchThroughput} with {@code
 * work=0} is the cost of the job and realm bookkeeping, and the difference between the rows here is
 * the cost of the lane itself.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@OperationsPerInvocation(LaneOnlyThroughput.BATCH)
@Threads(1)
@State(Scope.Thread)
@Fork(jvmArgsAppend = {"-Xms2g", "-Xmx2g"})
public class LaneOnlyThroughput {

  static final int BATCH = 1000;

  @Benchmark
  public void clothoLane(Pools p) throws InterruptedException {
    CountDownLatch done = new CountDownLatch(BATCH);
    Runnable task = done::countDown;
    for (int i = 0; i < BATCH; i++) {
      p.lane.execute(task);
    }
    done.await();
  }

  @Benchmark
  public void threadPoolExecutor(Pools p) throws InterruptedException {
    CountDownLatch done = new CountDownLatch(BATCH);
    Runnable task = done::countDown;
    for (int i = 0; i < BATCH; i++) {
      p.threadPool.execute(task);
    }
    done.await();
  }

  @Benchmark
  public void forkJoinPool(Pools p) throws InterruptedException {
    CountDownLatch done = new CountDownLatch(BATCH);
    Runnable task = done::countDown;
    for (int i = 0; i < BATCH; i++) {
      p.forkJoin.execute(task);
    }
    done.await();
  }
}
