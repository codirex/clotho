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
 * A timer that comes due while it is cancelled. r1 = timers fired, r2 = 1 if cancel() succeeded,
 * r3 = timers left in the wheel. Exactly one of the two wins and nothing stays behind.
 */
@JCStressTest
@Outcome(id = "1, 0, 0", expect = Expect.ACCEPTABLE, desc = "it fired; cancel() was too late")
@Outcome(id = "0, 1, 0", expect = Expect.ACCEPTABLE, desc = "it was cancelled before firing")
@Outcome(expect = Expect.FORBIDDEN, desc = "fired and cancelled, neither, or left behind")
@State
public class TimerWheelFireVersusCancel {

  private final TimerWheel wheel = new TimerWheel(0, 10, 8);
  private final TimerEntry entry =
      wheel
          .schedule(
              10,
              new Timeout() {
                @Override
                public void fire() {}

                @Override
                public void discard(Throwable reason) {}
              })
          .entry;

  @Actor
  public void advance(III_Result r) {
    r.r1 = wheel.advanceTo(10).size();
  }

  @Actor
  public void cancel(III_Result r) {
    r.r2 = entry.cancel() ? 1 : 0;
  }

  @Arbiter
  public void arbiter(III_Result r) {
    r.r3 = wheel.size();
  }
}
