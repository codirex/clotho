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

import org.openjdk.jcstress.annotations.Actor;
import org.openjdk.jcstress.annotations.Arbiter;
import org.openjdk.jcstress.annotations.Expect;
import org.openjdk.jcstress.annotations.JCStressTest;
import org.openjdk.jcstress.annotations.Outcome;
import org.openjdk.jcstress.annotations.State;
import org.openjdk.jcstress.infra.results.III_Result;

/**
 * A timer added while time advances. r1 = fired by the racing advance, r2 = fired by the later
 * advance (arbiter), r3 = timers left. It fires exactly once, at the latest by the later advance.
 */
@JCStressTest
@Outcome(id = "1, 0, 0", expect = Expect.ACCEPTABLE, desc = "added before the advance")
@Outcome(id = "0, 1, 0", expect = Expect.ACCEPTABLE, desc = "added after the advance: next tick")
@Outcome(expect = Expect.FORBIDDEN, desc = "lost, fired twice or left behind")
@State
public class TimerWheelScheduleVersusAdvance {

  private final TimerWheel wheel = new TimerWheel(0, 10, 8);

  @Actor
  public void schedule(III_Result r) {
    wheel.schedule(
        10,
        new Timeout() {
          @Override
          public void fire() {}

          @Override
          public void discard(Throwable reason) {}
        });
  }

  @Actor
  public void advance(III_Result r) {
    r.r1 = wheel.advanceTo(10).size();
  }

  @Arbiter
  public void arbiter(III_Result r) {
    r.r2 = wheel.advanceTo(20).size();
    r.r3 = wheel.size();
  }
}
