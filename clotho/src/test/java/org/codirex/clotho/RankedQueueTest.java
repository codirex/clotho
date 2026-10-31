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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class RankedQueueTest {

  private long seq;

  private EngineTask task(Priority priority, long enqueueNanos) {
    EngineTask t = new EngineTask(() -> {}, priority, ++seq);
    t.enqueueNanos = enqueueNanos;
    return t;
  }

  @Test
  void higherPriorityFirstWithoutAging() {
    RankedQueue q = new RankedQueue();
    EngineTask low = task(Priority.LOW, 0);
    EngineTask normal = task(Priority.NORMAL, 0);
    EngineTask high = task(Priority.HIGH, 0);
    q.add(low);
    q.add(normal);
    q.add(high);
    assertThat(q.pollBest(0, 0)).isSameAs(high);
    assertThat(q.pollBest(0, 0)).isSameAs(normal);
    assertThat(q.pollBest(0, 0)).isSameAs(low);
    assertThat(q.pollBest(0, 0)).isNull();
  }

  @Test
  void fifoWithinTheSamePriority() {
    RankedQueue q = new RankedQueue();
    EngineTask a = task(Priority.NORMAL, 0);
    EngineTask b = task(Priority.NORMAL, 0);
    EngineTask c = task(Priority.NORMAL, 0);
    q.add(a);
    q.add(b);
    q.add(c);
    assertThat(q.pollBest(0, 0)).isSameAs(a);
    assertThat(q.pollBest(0, 0)).isSameAs(b);
    assertThat(q.pollBest(0, 0)).isSameAs(c);
  }

  @Test
  void effectivePriorityRisesOneLevelPerStepAndCapsAtHigh() {
    EngineTask low = task(Priority.LOW, 0);
    assertThat(RankedQueue.effective(low, 9, 10)).isEqualTo(0);
    assertThat(RankedQueue.effective(low, 10, 10)).isEqualTo(1);
    assertThat(RankedQueue.effective(low, 19, 10)).isEqualTo(1);
    assertThat(RankedQueue.effective(low, 20, 10)).isEqualTo(2);
    assertThat(RankedQueue.effective(low, 1_000_000, 10)).isEqualTo(2);
    EngineTask normal = task(Priority.NORMAL, 0);
    assertThat(RankedQueue.effective(normal, 9, 10)).isEqualTo(1);
    assertThat(RankedQueue.effective(normal, 10, 10)).isEqualTo(2);
    EngineTask high = task(Priority.HIGH, 0);
    assertThat(RankedQueue.effective(high, 1_000_000, 10)).isEqualTo(2);
  }

  @Test
  void agingDisabledOrClockGoingBackwardsKeepsTheBasePriority() {
    EngineTask low = task(Priority.LOW, 100);
    assertThat(RankedQueue.effective(low, 1_000_000, 0)).isEqualTo(0);
    assertThat(RankedQueue.effective(low, 50, 10)).isEqualTo(0);
  }

  @Test
  void agedLowGoesBeforeANewerHighButNotBeforeAnOlderHigh() {
    RankedQueue q = new RankedQueue();
    EngineTask olderHigh = task(Priority.HIGH, 0);
    EngineTask low = task(Priority.LOW, 0);
    EngineTask newerHigh = task(Priority.HIGH, 0);
    q.add(low);
    q.add(olderHigh);
    q.add(newerHigh);
    // low was created before newerHigh and after olderHigh (sequence numbers break the tie)
    assertThat(q.pollBest(20, 10)).isSameAs(olderHigh);
    assertThat(q.pollBest(20, 10)).isSameAs(low);
    assertThat(q.pollBest(20, 10)).isSameAs(newerHigh);
  }

  @Test
  void justBeforeTheSecondStepLowStillLosesToHigh() {
    RankedQueue q = new RankedQueue();
    EngineTask low = task(Priority.LOW, 0);
    EngineTask high = task(Priority.HIGH, 0);
    q.add(low);
    q.add(high);
    assertThat(q.pollBest(19, 10)).isSameAs(high);
    q.add(high);
    assertThat(q.pollBest(20, 10)).isSameAs(low);
  }

  @Test
  void oldestIgnoresPriority() {
    RankedQueue q = new RankedQueue();
    EngineTask high = task(Priority.HIGH, 5);
    EngineTask low = task(Priority.LOW, 3);
    q.add(high);
    q.add(low);
    assertThat(q.peekOldest()).isSameAs(low);
    assertThat(q.pollOldest()).isSameAs(low);
    assertThat(q.pollOldest()).isSameAs(high);
    assertThat(q.pollOldest()).isNull();
  }

  @Test
  void removeUsesIdentityAndDrainEmptiesTheQueue() {
    RankedQueue q = new RankedQueue();
    EngineTask a = task(Priority.NORMAL, 0);
    EngineTask b = task(Priority.LOW, 0);
    q.add(a);
    q.add(b);
    assertThat(q.remove(a)).isTrue();
    assertThat(q.remove(a)).isFalse();
    assertThat(q.size()).isEqualTo(1);
    List<EngineTask> sink = new ArrayList<>();
    assertThat(q.drainTo(sink)).isEqualTo(1);
    assertThat(sink.get(0)).isSameAs(b);
    assertThat(q.size()).isEqualTo(0);
  }
}
