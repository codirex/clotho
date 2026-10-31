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
import org.openjdk.jcstress.infra.results.II_Result;

/** Two threads take from a queue that holds one task: exactly one gets it. */
@JCStressTest
@Outcome(id = "1, 0", expect = Expect.ACCEPTABLE, desc = "the first actor got the task")
@Outcome(id = "0, 1", expect = Expect.ACCEPTABLE, desc = "the second actor got the task")
@Outcome(expect = Expect.FORBIDDEN, desc = "the task was taken twice or lost")
@State
public class RankedQueueTakeOnce {

  private final RankedQueue queue = new RankedQueue();

  public RankedQueueTakeOnce() {
    EngineTask task = new EngineTask(() -> {}, Priority.NORMAL, 1);
    queue.add(task);
  }

  @Actor
  public void actor1(II_Result r) {
    r.r1 = queue.pollBest(0, 0) != null ? 1 : 0;
  }

  @Actor
  public void actor2(II_Result r) {
    r.r2 = queue.pollBest(0, 0) != null ? 1 : 0;
  }
}
