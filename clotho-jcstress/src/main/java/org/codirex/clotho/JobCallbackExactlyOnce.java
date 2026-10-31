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

import java.util.concurrent.atomic.AtomicInteger;
import org.codirex.clotho.test.DeterministicLane;
import org.openjdk.jcstress.annotations.Actor;
import org.openjdk.jcstress.annotations.Arbiter;
import org.openjdk.jcstress.annotations.Expect;
import org.openjdk.jcstress.annotations.JCStressTest;
import org.openjdk.jcstress.annotations.Outcome;
import org.openjdk.jcstress.annotations.State;
import org.openjdk.jcstress.infra.results.II_Result;

/**
 * onComplete() registered while the job completes. r1 = how many times the callback ran, r2 = the
 * outstanding work left in the realm. Exactly once, and nothing left over.
 */
@JCStressTest
@Outcome(id = "1, 0", expect = Expect.ACCEPTABLE, desc = "the callback ran exactly once")
@Outcome(expect = Expect.FORBIDDEN, desc = "lost or repeated callback, or work left in the realm")
@State
public class JobCallbackExactlyOnce {

  private final DeterministicLane lane = new DeterministicLane();
  private final Realm realm = Realm.open("jcstress");
  private final Job<Integer> job = realm.launch(lane, cp -> 1);
  private final AtomicInteger calls = new AtomicInteger();

  @Actor
  public void register(II_Result r) {
    job.onComplete(Lane.direct(), o -> calls.incrementAndGet());
  }

  @Actor
  public void complete(II_Result r) {
    lane.runNext();
  }

  @Arbiter
  public void arbiter(II_Result r) {
    r.r1 = calls.get();
    r.r2 = realm.outstanding();
  }
}
