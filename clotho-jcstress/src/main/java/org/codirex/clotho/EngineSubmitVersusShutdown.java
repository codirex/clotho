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
package org.codirex.clotho;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.openjdk.jcstress.annotations.Actor;
import org.openjdk.jcstress.annotations.Arbiter;
import org.openjdk.jcstress.annotations.Expect;
import org.openjdk.jcstress.annotations.JCStressTest;
import org.openjdk.jcstress.annotations.Outcome;
import org.openjdk.jcstress.annotations.State;
import org.openjdk.jcstress.infra.results.III_Result;

/**
 * A pool submission racing graceful engine shutdown. r1 = whether submission was accepted, r2 =
 * how many times the task ran, r3 = whether the engine terminated. An accepted task must run once;
 * a rejected task must never run.
 */
@JCStressTest
@Outcome(
    id = "0, 0, 1",
    expect = Expect.ACCEPTABLE,
    desc = "shutdown won: submission was rejected")
@Outcome(
    id = "1, 1, 1",
    expect = Expect.ACCEPTABLE,
    desc = "submission won: accepted work drained")
@Outcome(
    expect = Expect.FORBIDDEN,
    desc = "accepted work was lost or repeated, or engine did not terminate")
@State
public class EngineSubmitVersusShutdown {

  private final Engine engine =
      Engine.builder().computeThreads(1).manualTimers().build();
  private final AtomicInteger runs = new AtomicInteger();

  @Actor
  public void submit(III_Result r) {
    try {
      engine.compute().execute(runs::incrementAndGet);
      r.r1 = 1;
    } catch (LaneClosedException e) {
      r.r1 = 0;
    }
  }

  @Actor
  public void shutdown(III_Result r) {
    engine.shutdown();
  }

  @Arbiter
  public void arbiter(III_Result r) {
    try {
      r.r3 = engine.awaitTermination(5, TimeUnit.SECONDS) ? 1 : 0;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      r.r3 = 0;
    }
    r.r2 = runs.get();
  }
}
