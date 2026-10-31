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

import java.util.concurrent.atomic.AtomicReference;
import org.codirex.clotho.test.DeterministicLane;
import org.openjdk.jcstress.annotations.Actor;
import org.openjdk.jcstress.annotations.Arbiter;
import org.openjdk.jcstress.annotations.Expect;
import org.openjdk.jcstress.annotations.JCStressTest;
import org.openjdk.jcstress.annotations.Outcome;
import org.openjdk.jcstress.annotations.State;
import org.openjdk.jcstress.infra.results.III_Result;

/**
 * launch() racing closeAsync(). r1 = 1 if the launch was accepted, 0 if rejected; r2 = 1 if the job
 * (when accepted) reached a final outcome; r3 = 1 if the realm is closed. Accepted work is never
 * left pending and the realm always ends closed.
 */
@JCStressTest
@Outcome(id = "0, 1, 1", expect = Expect.ACCEPTABLE, desc = "rejected: nothing to wait for")
@Outcome(id = "1, 1, 1", expect = Expect.ACCEPTABLE, desc = "accepted and then cancelled")
@Outcome(expect = Expect.FORBIDDEN, desc = "a job left pending, or the realm not closed")
@State
public class RealmLaunchVersusClose {

  private final Realm realm = Realm.open("jcstress");
  private final DeterministicLane lane = new DeterministicLane();
  private final AtomicReference<Job<Integer>> job = new AtomicReference<>();

  @Actor
  public void launcher(III_Result r) {
    try {
      job.set(realm.launch(lane, cp -> 1));
      r.r1 = 1;
    } catch (RealmClosedException e) {
      r.r1 = 0;
    }
  }

  @Actor
  public void closer(III_Result r) {
    realm.closeAsync();
  }

  @Arbiter
  public void arbiter(III_Result r) {
    Job<Integer> j = job.get();
    r.r2 = j == null || j.isDone() ? 1 : 0;
    r.r3 = realm.isClosed() ? 1 : 0;
  }
}
