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

import java.util.concurrent.TimeUnit;
import org.codirex.clotho.Outcome;
import org.codirex.clotho.Realm;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Threads;

/**
 * The price of structured concurrency: open a realm, run one task, close the realm (which waits
 * for everything it owns), against the same task on a plain pool with no scope at all.
 */
@BenchmarkMode({Mode.AverageTime, Mode.SampleTime})
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Threads(1)
@Fork(jvmArgsAppend = {"-Xms1g", "-Xmx1g"})
public class RealmScope {

  @Benchmark
  public Outcome<Integer> clothoOpenRunClose(Pools p) throws InterruptedException {
    try (Realm realm = Realm.open("scope")) {
      return realm.launch(p.lane, cp -> 1).await();
    }
  }

  @Benchmark
  public Integer threadPoolNoScope(Pools p) throws Exception {
    return p.threadPool.submit(() -> 1).get();
  }
}
