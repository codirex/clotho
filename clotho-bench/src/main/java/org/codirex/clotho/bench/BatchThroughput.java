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

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.codirex.clotho.Job;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Throughput: one caller submits a batch of {@value #BATCH} tasks and waits for all of them. The
 * cost per task is reported. {@code work} is the CPU burnt inside each task ({@code
 * Blackhole.consumeCPU} tokens): 0 measures pure scheduling overhead, larger values approach a
 * real workload where the scheduler matters less.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@OperationsPerInvocation(BatchThroughput.BATCH)
@Threads(1)
@Fork(jvmArgsAppend = {"-Xms2g", "-Xmx2g"})
public class BatchThroughput {

  static final int BATCH = 1000;

  @Param({"0", "1000"})
  public int work;

  @Benchmark
  public void clotho(Pools p, Blackhole bh) throws InterruptedException {
    @SuppressWarnings("unchecked")
    Job<Integer>[] jobs = (Job<Integer>[]) new Job<?>[BATCH];
    int w = work;
    for (int i = 0; i < BATCH; i++) {
      jobs[i] =
          p.realm.launch(
              p.lane,
              cp -> {
                Blackhole.consumeCPU(w);
                return 1;
              });
    }
    for (Job<Integer> job : jobs) {
      bh.consume(job.await());
    }
  }

  @Benchmark
  public void threadPoolExecutor(Pools p, Blackhole bh) throws Exception {
    @SuppressWarnings("unchecked")
    Future<Integer>[] futures = (Future<Integer>[]) new Future<?>[BATCH];
    int w = work;
    for (int i = 0; i < BATCH; i++) {
      futures[i] =
          p.threadPool.submit(
              () -> {
                Blackhole.consumeCPU(w);
                return 1;
              });
    }
    for (Future<Integer> f : futures) {
      bh.consume(f.get());
    }
  }

  @Benchmark
  public void forkJoinPool(Pools p, Blackhole bh) throws Exception {
    @SuppressWarnings("unchecked")
    Future<Integer>[] futures = (Future<Integer>[]) new Future<?>[BATCH];
    int w = work;
    for (int i = 0; i < BATCH; i++) {
      futures[i] =
          p.forkJoin.submit(
              () -> {
                Blackhole.consumeCPU(w);
                return 1;
              });
    }
    for (Future<Integer> f : futures) {
      bh.consume(f.get());
    }
  }

  @Benchmark
  public void completableFuture(Pools p, Blackhole bh) throws InterruptedException, ExecutionException {
    @SuppressWarnings("unchecked")
    CompletableFuture<Integer>[] futures = (CompletableFuture<Integer>[]) new CompletableFuture<?>[BATCH];
    int w = work;
    for (int i = 0; i < BATCH; i++) {
      futures[i] =
          CompletableFuture.supplyAsync(
              () -> {
                Blackhole.consumeCPU(w);
                return 1;
              },
              p.threadPool);
    }
    for (CompletableFuture<Integer> f : futures) {
      bh.consume(f.get());
    }
  }
}
