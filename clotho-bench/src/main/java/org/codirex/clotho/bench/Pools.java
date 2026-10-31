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
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import org.codirex.clotho.Engine;
import org.codirex.clotho.Lane;
import org.codirex.clotho.Realm;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

/**
 * One private Clotho engine and the {@code java.util.concurrent} pools it is compared with, all
 * sized the same ({@code poolThreads}, default: available processors).
 *
 * <p>The pools stay idle while another one is measured (parked threads cost nothing), so each
 * benchmark method measures only its own implementation.
 */
@State(Scope.Benchmark)
public class Pools {

  /** Threads of every pool. 0 means the number of available processors. */
  @Param("0")
  public int poolThreads;

  public Engine engine;
  public Realm realm;
  public Lane lane;
  public ExecutorService threadPool;
  public ForkJoinPool forkJoin;

  @Setup(Level.Trial)
  public void setUp() {
    int n = poolThreads > 0 ? poolThreads : Runtime.getRuntime().availableProcessors();
    engine = Engine.builder().name("bench").computeThreads(n).build();
    realm = Realm.open("bench");
    lane = engine.compute();
    threadPool = Executors.newFixedThreadPool(n);
    forkJoin = new ForkJoinPool(n);
  }

  @TearDown(Level.Trial)
  public void tearDown() throws InterruptedException {
    realm.close();
    engine.close();
    threadPool.shutdown();
    forkJoin.shutdown();
    threadPool.awaitTermination(10, TimeUnit.SECONDS);
    forkJoin.awaitTermination(10, TimeUnit.SECONDS);
  }
}
