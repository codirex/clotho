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
import java.util.concurrent.TimeUnit;
import org.codirex.clotho.Outcome;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Threads;

/**
 * A dependent pipeline of three stages ({@code then} against {@code thenApplyAsync}). Executors
 * have no equivalent without hand-written nesting, so only {@code CompletableFuture} is compared.
 */
@BenchmarkMode({Mode.AverageTime, Mode.SampleTime})
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Threads(1)
@Fork(jvmArgsAppend = {"-Xms1g", "-Xmx1g"})
public class Chain {

  @Benchmark
  public Outcome<Integer> clotho(Pools p) throws InterruptedException {
    return p.realm
        .launch(p.lane, cp -> 1)
        .then(p.lane, (v, cp) -> v + 1)
        .then(p.lane, (v, cp) -> v + 1)
        .await();
  }

  @Benchmark
  public Integer completableFuture(Pools p) throws InterruptedException, ExecutionException {
    return CompletableFuture.supplyAsync(() -> 1, p.threadPool)
        .thenApplyAsync(v -> v + 1, p.threadPool)
        .thenApplyAsync(v -> v + 1, p.threadPool)
        .get();
  }
}
