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
import org.codirex.clotho.test.DeterministicLane;
import org.codirex.clotho.test.TestClock;
import org.openjdk.jcstress.annotations.Actor;
import org.openjdk.jcstress.annotations.Arbiter;
import org.openjdk.jcstress.annotations.Expect;
import org.openjdk.jcstress.annotations.JCStressTest;
import org.openjdk.jcstress.annotations.Outcome;
import org.openjdk.jcstress.annotations.State;
import org.openjdk.jcstress.infra.results.III_Result;

/**
 * A periodic job cancelled while its tick fires and runs. r1 = runs that started (0 or 1), r2 =
 * timers left in the wheel, r3 = 1 if the job is done. Whatever the order, no timer stays behind
 * and the job ends cancelled.
 */
@JCStressTest
@Outcome(id = "0, 0, 1", expect = Expect.ACCEPTABLE, desc = "cancelled before the run started")
@Outcome(id = "1, 0, 1", expect = Expect.ACCEPTABLE, desc = "the run started; then it ended cancelled")
@Outcome(expect = Expect.FORBIDDEN, desc = "a timer left behind, a second run, or not done")
@State
public class PeriodicCancelVersusTick {

  private final TestClock clock = new TestClock();
  private final Engine engine =
      Engine.builder().manualTimers().timerTick(1, TimeUnit.MILLISECONDS).ticker(clock).build();
  private final Realm realm = Realm.builder("jcstress").cadence(engine.cadence()).open();
  private final DeterministicLane lane = new DeterministicLane();
  private final AtomicInteger runs = new AtomicInteger();
  private final Job<Void> job;

  public PeriodicCancelVersusTick() {
    job = realm.every(lane, 10, 1000, TimeUnit.MILLISECONDS, cp -> runs.incrementAndGet());
    clock.advance(10, TimeUnit.MILLISECONDS); // the first tick is due
  }

  @Actor
  public void tick(III_Result r) {
    engine.cadence().runDue();
    lane.runAll();
  }

  @Actor
  public void cancel(III_Result r) {
    job.cancel();
  }

  @Arbiter
  public void arbiter(III_Result r) {
    r.r1 = runs.get();
    r.r2 = engine.cadence().pending();
    r.r3 = job.isDone() ? 1 : 0;
  }
}
