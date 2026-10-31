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

import java.util.List;
import org.codirex.clotho.test.DeterministicLane;
import org.openjdk.jcstress.annotations.Actor;
import org.openjdk.jcstress.annotations.Arbiter;
import org.openjdk.jcstress.annotations.Expect;
import org.openjdk.jcstress.annotations.JCStressTest;
import org.openjdk.jcstress.annotations.Outcome;
import org.openjdk.jcstress.annotations.State;
import org.openjdk.jcstress.infra.results.II_Result;

/**
 * A race whose first input succeeds while the race is cancelled. r1 = what cancel() returned,
 * r2 = the outcome (1 = Success, 2 = Cancelled). One atomic decision, as for a plain job.
 */
@JCStressTest
@Outcome(id = "1, 2", expect = Expect.ACCEPTABLE, desc = "cancel won: Cancelled, value dropped")
@Outcome(id = "0, 1", expect = Expect.ACCEPTABLE, desc = "the success won: cancel() is false")
@Outcome(expect = Expect.FORBIDDEN, desc = "cancel() and the outcome disagree")
@State
public class RaceSuccessVersusCancel {

  private final DeterministicLane lane = new DeterministicLane();
  private final Realm realm = Realm.open("jcstress");
  private final Job<Integer> first = realm.launch(lane, cp -> 1);
  private final Job<Integer> second = realm.launch(lane, cp -> 2);
  private final Job<Integer> race = realm.race(List.of(first, second), Ownership.OWNED);

  @Actor
  public void runner(II_Result r) {
    lane.runAll();
  }

  @Actor
  public void canceller(II_Result r) {
    r.r1 = race.cancel() ? 1 : 0;
  }

  @Arbiter
  public void arbiter(II_Result r) {
    org.codirex.clotho.Outcome<Integer> o = race.outcomeOrNull();
    r.r2 = o == null ? 9 : o.isSuccess() ? 1 : o.isCancelled() ? 2 : 3;
  }
}
