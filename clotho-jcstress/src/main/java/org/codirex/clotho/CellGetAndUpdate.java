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
 * Two getAndUpdate(+1) on one cell. r1, r2 = the values each call replaced, r3 = the final value.
 * No update is lost and the two calls replace different values.
 */
@JCStressTest
@Outcome(id = "0, 1, 2", expect = Expect.ACCEPTABLE, desc = "first actor first")
@Outcome(id = "1, 0, 2", expect = Expect.ACCEPTABLE, desc = "second actor first")
@Outcome(expect = Expect.FORBIDDEN, desc = "a lost update or a repeated value")
@State
public class CellGetAndUpdate {

  private final Cell<Integer> cell = Cell.of(0);

  @Actor
  public void actor1(III_Result r) {
    r.r1 = cell.getAndUpdate(v -> v + 1);
  }

  @Actor
  public void actor2(III_Result r) {
    r.r2 = cell.getAndUpdate(v -> v + 1);
  }

  @Arbiter
  public void arbiter(III_Result r) {
    r.r3 = cell.get();
  }
}
