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

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.codirex.clotho.Job;
import org.codirex.clotho.Lane;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;

/**
 * Strictly ordered execution: {@value #BATCH} tasks in submission order, one at a time. Clotho's
 * {@code serial} lane (shares the pool, thread may change) and {@code single} lane (dedicated
 * thread) against {@code Executors.newSingleThreadExecutor()}. Waiting for the last task is
 * enough because order is guaranteed.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@OperationsPerInvocation(OrderedLane.BATCH)
@Threads(1)
@Fork(jvmArgsAppend = {"-Xms1g", "-Xmx1g"})
public class OrderedLane {

  static final int BATCH = 1000;

  /** Extra lanes on top of {@link Pools}. */
  @State(Scope.Benchmark)
  public static class Lanes {
    public Lane serial;
    public Lane single;
    public ExecutorService singleThread;

    @Setup
    public void setUp(Pools p) {
      serial = p.engine.serial("bench-serial");
      single = p.engine.single("bench-single");
      singleThread = Executors.newSingleThreadExecutor();
    }

    @TearDown
    public void tearDown() throws InterruptedException {
      singleThread.shutdown();
      singleThread.awaitTermination(10, TimeUnit.SECONDS);
    }
  }

  @Benchmark
  public Object clothoSerial(Pools p, Lanes l) throws InterruptedException {
    return runOn(p, l.serial);
  }

  @Benchmark
  public Object clothoSingle(Pools p, Lanes l) throws InterruptedException {
    return runOn(p, l.single);
  }

  @Benchmark
  public Object singleThreadExecutor(Lanes l) throws Exception {
    Future<Integer> last = null;
    for (int i = 0; i < BATCH; i++) {
      last = l.singleThread.submit(() -> 1);
    }
    return last.get();
  }

  private static Object runOn(Pools p, Lane lane) throws InterruptedException {
    Job<Integer> last = null;
    for (int i = 0; i < BATCH; i++) {
      last = p.realm.launch(lane, cp -> 1);
    }
    return last.await();
  }
}
