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

import org.codirex.clotho.test.DeterministicLane;
import org.openjdk.jcstress.annotations.Actor;
import org.openjdk.jcstress.annotations.Arbiter;
import org.openjdk.jcstress.annotations.Expect;
import org.openjdk.jcstress.annotations.JCStressTest;
import org.openjdk.jcstress.annotations.Outcome;
import org.openjdk.jcstress.annotations.State;
import org.openjdk.jcstress.infra.results.II_Result;

/**
 * cancel() racing the completion of the job. r1 = what cancel() returned (1 = true), r2 = the final
 * outcome (1 = Success, 2 = Cancelled). The two must agree: the single atomic decision.
 */
@JCStressTest
@Outcome(id = "1, 2", expect = Expect.ACCEPTABLE, desc = "cancel won: the job is Cancelled")
@Outcome(id = "0, 1", expect = Expect.ACCEPTABLE, desc = "completion won: Success, cancel() false")
@Outcome(expect = Expect.FORBIDDEN, desc = "cancel() and the outcome disagree, or no outcome")
@State
public class JobCancelVersusCompletion {

  private final DeterministicLane lane = new DeterministicLane();
  private final Job<Integer> job = Realm.open("jcstress").launch(lane, cp -> 7);

  @Actor
  public void runner(II_Result r) {
    lane.runNext();
  }

  @Actor
  public void canceller(II_Result r) {
    r.r1 = job.cancel() ? 1 : 0;
  }

  @Arbiter
  public void arbiter(II_Result r) {
    org.codirex.clotho.Outcome<Integer> o = job.outcomeOrNull();
    r.r2 = o == null ? 9 : o.isSuccess() ? 1 : o.isCancelled() ? 2 : 3;
  }
}
