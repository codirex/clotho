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
package org.codirex.clotho.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import org.codirex.clotho.BlockingNotAllowedException;
import org.codirex.clotho.BlockingPolicy;
import org.codirex.clotho.DroppedException;
import org.codirex.clotho.Job;
import org.codirex.clotho.Outcome;
import org.codirex.clotho.Priority;
import org.codirex.clotho.Realm;
import org.junit.jupiter.api.Test;

class DeterministicLaneTest {

  private final DeterministicLane lane = new DeterministicLane();
  private final Realm realm = Realm.open("deterministic");

  @Test
  void nothingRunsUntilTheTestRunsIt() {
    AtomicInteger ran = new AtomicInteger();
    Job<Integer> job = realm.launch(lane, cp -> ran.incrementAndGet());
    assertThat(lane.size()).isEqualTo(1);
    assertThat(ran.get()).isEqualTo(0);
    assertThat(job.isDone()).isFalse();
    assertThat(lane.runNext()).isTrue();
    assertThat(lane.runNext()).isFalse();
    assertThat(job.outcomeOrNull()).isEqualTo(Outcome.success(1));
  }

  @Test
  void tasksRunInSubmissionOrderAndRunAllIncludesTasksTheySubmit() {
    List<String> order = new ArrayList<>();
    realm.launch(
        lane,
        cp -> {
          order.add("a");
          realm.launch(lane, c -> order.add("c"));
          return 1;
        });
    realm.launch(lane, cp -> order.add("b"));
    assertThat(lane.runAll()).isEqualTo(3);
    assertThat(order).containsExactly("a", "b", "c");
  }

  @Test
  void prioritiesAreRecordedButDoNotReorder() {
    realm.launch(lane, Priority.LOW, cp -> 1);
    realm.launch(lane, Priority.HIGH, cp -> 2);
    assertThat(lane.priorities()).containsExactly(Priority.LOW, Priority.HIGH);
  }

  @Test
  void aRunawayTaskIsReportedInsteadOfLoopingForever() {
    Runnable[] self = new Runnable[1];
    self[0] = () -> lane.execute(self[0]);
    lane.execute(self[0]);
    assertThatThrownBy(() -> lane.runAll(50)).isInstanceOf(AssertionError.class);
  }

  @Test
  void aForbiddenLaneMakesBlockingCallsFail() throws Exception {
    DeterministicLane forbidden = new DeterministicLane(BlockingPolicy.FORBIDDEN);
    Job<String> done = realm.launch(lane, cp -> "x");
    lane.runAll();
    Job<String> job = realm.launch(forbidden, cp -> done.await().getOrThrow());
    forbidden.runAll();
    assertThat(job.outcomeOrNull().failureOrNull()).isInstanceOf(BlockingNotAllowedException.class);
  }

  @Test
  void aRefusingLaneMakesJobsFailAndCanAcceptAgain() {
    RejectedExecutionException refusal = new RejectedExecutionException("full");
    lane.refuseWith(refusal);
    Job<String> refused = realm.launch(lane, cp -> "no");
    assertThat(refused.outcomeOrNull().failureOrNull()).isSameAs(refusal);
    lane.refuseWith(null);
    Job<String> accepted = realm.launch(lane, cp -> "yes");
    lane.runAll();
    assertThat(accepted.outcomeOrNull()).isEqualTo(Outcome.success("yes"));
  }

  @Test
  void discardAllEndsJobsWithTheReasonAndRunsNothing() {
    AtomicInteger ran = new AtomicInteger();
    Job<Integer> a = realm.launch(lane, cp -> ran.incrementAndGet());
    Job<Integer> b = realm.launch(lane, cp -> ran.incrementAndGet());
    DroppedException reason = new DroppedException("dropped by the test");
    assertThat(lane.discardAll(reason)).isEqualTo(2);
    assertThat(a.outcomeOrNull().failureOrNull()).isSameAs(reason);
    assertThat(b.outcomeOrNull().failureOrNull()).isSameAs(reason);
    assertThat(lane.runAll()).isEqualTo(0);
    assertThat(ran.get()).isEqualTo(0);
  }
}
