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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** The wheel with a fake clock and no threads: tick 10, 8 buckets (a revolution is 80). */
class TimerWheelTest {

  private static final class Probe implements Timeout {
    final String name;
    int fired;
    int discarded;
    Throwable reason;

    Probe(String name) {
      this.name = name;
    }

    @Override
    public void fire() {
      fired++;
    }

    @Override
    public void discard(Throwable why) {
      discarded++;
      reason = why;
    }
  }

  private final TimerWheel wheel = new TimerWheel(0, 10, 8);

  private TimerEntry add(long deadline, String name) {
    return wheel.schedule(deadline, new Probe(name)).entry;
  }

  private static List<String> names(List<TimerEntry> entries) {
    List<String> out = new ArrayList<>();
    for (TimerEntry e : entries) {
      out.add(((Probe) e.timeout).name);
    }
    return out;
  }

  @Test
  void aTimerNeverFiresBeforeItsDeadlineAndFiresAtTheFirstTickAfterIt() {
    add(25, "a"); // tick 3 = time 30
    add(30, "b"); // tick 3 = time 30
    add(31, "c"); // tick 4 = time 40
    assertThat(wheel.advanceTo(29)).isEmpty();
    assertThat(names(wheel.advanceTo(30))).containsExactly("a", "b");
    assertThat(wheel.advanceTo(39)).isEmpty();
    assertThat(names(wheel.advanceTo(40))).containsExactly("c");
    assertThat(wheel.size()).isEqualTo(0);
  }

  @Test
  void dueTimersComeInDeadlineOrderThenInsertionOrder() {
    add(50, "late");
    add(20, "first-a");
    add(20, "first-b");
    add(35, "middle");
    assertThat(names(wheel.advanceTo(100))).containsExactly("first-a", "first-b", "middle", "late");
  }

  @Test
  void aTimerMoreThanOneRevolutionAheadIsSkippedUntilItsRound() {
    add(1000, "far"); // tick 100, bucket 100 mod 8 = 4
    for (long now = 10; now < 1000; now += 10) {
      assertThat(wheel.advanceTo(now)).isEmpty();
    }
    assertThat(wheel.size()).isEqualTo(1);
    assertThat(names(wheel.advanceTo(1000))).containsExactly("far");
  }

  @Test
  void aLongJumpFiresEverythingDueInOrderAndLeavesTheFutureAlone() {
    add(15, "a");
    add(900, "b");
    add(333, "c");
    add(100_000_000, "future");
    assertThat(names(wheel.advanceTo(50_000))).containsExactly("a", "c", "b");
    assertThat(wheel.size()).isEqualTo(1);
    assertThat(names(wheel.advanceTo(100_000_000))).containsExactly("future");
  }

  @Test
  void aDeadlineInThePastFiresAtTheNextTick() {
    wheel.advanceTo(500);
    add(100, "past");
    assertThat(wheel.advanceTo(509)).isEmpty();
    assertThat(names(wheel.advanceTo(510))).containsExactly("past");
  }

  @Test
  void cancelRemovesTheEntryAtOnceAndExactlyOnce() {
    TimerEntry a = add(25, "a");
    TimerEntry b = add(26, "b");
    add(500, "c");
    assertThat(wheel.size()).isEqualTo(3);
    int bucketsBefore = wheel.nonEmptyBuckets();
    assertThat(a.cancel()).isTrue();
    assertThat(wheel.size()).isEqualTo(2);
    assertThat(a.cancel()).isFalse();
    assertThat(wheel.state(a)).isEqualTo(TimerEntry.CANCELLED);
    assertThat(b.cancel()).isTrue();
    assertThat(wheel.nonEmptyBuckets() < bucketsBefore).isTrue();
    assertThat(wheel.advanceTo(100)).isEmpty();
    assertThat(((Probe) a.timeout).fired).isEqualTo(0);
  }

  @Test
  void aFiredTimerCannotBeCancelledAndACancelledOneNeverFires() {
    TimerEntry a = add(10, "a");
    List<TimerEntry> due = wheel.advanceTo(10);
    assertThat(due).hasSize(1);
    assertThat(a.cancel()).isFalse();
    assertThat(wheel.state(a)).isEqualTo(TimerEntry.FIRED);
  }

  @Test
  void cancellingEverythingLeavesNoBucketBehind() {
    List<TimerEntry> entries = new ArrayList<>();
    for (int i = 1; i <= 200; i++) {
      entries.add(add(i * 7L, "t" + i));
    }
    for (TimerEntry e : entries) {
      assertThat(e.cancel()).isTrue();
    }
    assertThat(wheel.size()).isEqualTo(0);
    assertThat(wheel.nonEmptyBuckets()).isEqualTo(0);
    assertThat(wheel.prepareSleep()).isEqualTo(TimerWheel.EXIT);
  }

  @Test
  void prepareSleepTellsWhenToWakeAndWhenToExit() {
    assertThat(wheel.prepareSleep()).isEqualTo(TimerWheel.EXIT);
    add(35, "a"); // tick 4
    add(95, "b"); // tick 10
    assertThat(wheel.prepareSleep()).isEqualTo(40);
    wheel.awake();
    assertThat(names(wheel.advanceTo(40))).containsExactly("a");
    assertThat(wheel.prepareSleep()).isEqualTo(100);
    wheel.awake();
    assertThat(names(wheel.advanceTo(100))).containsExactly("b");
    assertThat(wheel.prepareSleep()).isEqualTo(TimerWheel.EXIT);
  }

  @Test
  void theThreadSleepsUntilTheEarliestRealDeadlineNotUntilANonEmptyBucket() {
    add(1000, "far"); // tick 100, bucket 4
    add(95, "near"); // tick 10, bucket 2
    assertThat(wheel.prepareSleep()).isEqualTo(100);
    wheel.awake();
    assertThat(names(wheel.advanceTo(100))).containsExactly("near");
    // only a timer a whole revolution ahead is left: one wake-up, at its own tick
    assertThat(wheel.prepareSleep()).isEqualTo(1000);
  }

  @Test
  void cancellingTheEarliestTimerMovesTheWakeUpToTheNextOne() {
    TimerEntry earliest = add(35, "a"); // tick 4, bucket 4
    add(115, "b"); // tick 12, bucket 4 (same bucket)
    add(60, "c"); // tick 6, bucket 6
    assertThat(wheel.prepareSleep()).isEqualTo(40);
    assertThat(earliest.cancel()).isTrue();
    assertThat(wheel.prepareSleep()).isEqualTo(60);
    wheel.awake();
    assertThat(names(wheel.advanceTo(60))).containsExactly("c");
    assertThat(wheel.prepareSleep()).isEqualTo(120);
  }

  @Test
  void scheduleAsksForAThreadOnlyWhenNoneRunsAndForAWakeUpOnlyWhenTheNewTimerIsEarlier() {
    TimerWheel.Scheduled first = wheel.schedule(95, new Probe("a")); // tick 10
    assertThat(first.startThread).isTrue();
    TimerWheel.Scheduled second = wheel.schedule(55, new Probe("b"));
    assertThat(second.startThread).isFalse();
    assertThat(second.wakeThread).isFalse();
    assertThat(wheel.prepareSleep()).isEqualTo(60); // sleeping until tick 6
    TimerWheel.Scheduled earlier = wheel.schedule(25, new Probe("c")); // tick 3
    assertThat(earlier.wakeThread).isTrue();
    TimerWheel.Scheduled later = wheel.schedule(85, new Probe("d")); // tick 9
    assertThat(later.wakeThread).isFalse();
    wheel.awake();
    assertThat(wheel.schedule(15, new Probe("e")).wakeThread).isFalse();
  }

  @Test
  void cancellingTheLastTimerWakesASleepingThreadSoItCanEnd() {
    AtomicInteger wakes = new AtomicInteger();
    wheel.setWaker(wakes::incrementAndGet);
    TimerEntry a = add(500, "a");
    TimerEntry b = add(900, "b");
    // the thread is not sleeping yet: nothing to wake
    assertThat(a.cancel()).isTrue();
    assertThat(wakes.get()).isEqualTo(0);
    wheel.prepareSleep();
    // one timer is left: cancelling it empties the wheel while the thread sleeps
    assertThat(b.cancel()).isTrue();
    assertThat(wakes.get()).isEqualTo(1);
    assertThat(b.cancel()).isFalse();
    assertThat(wakes.get()).isEqualTo(1);
    wheel.awake();
    assertThat(wheel.prepareSleep()).isEqualTo(TimerWheel.EXIT);
  }

  @Test
  void aThreadThatFailedToStartLetsTheNextScheduleAskAgain() {
    TimerWheel.Scheduled s = wheel.schedule(50, new Probe("a"));
    assertThat(s.startThread).isTrue();
    s.entry.cancel();
    wheel.threadFailedToStart();
    assertThat(wheel.schedule(60, new Probe("b")).startThread).isTrue();
  }

  @Test
  void closeAndDrainRemovesEverythingInOrderAndRefusesNewTimers() {
    TimerEntry late = add(80, "late");
    TimerEntry early = add(20, "early");
    List<TimerEntry> drained = wheel.closeAndDrain();
    assertThat(names(drained)).containsExactly("early", "late");
    assertThat(wheel.size()).isEqualTo(0);
    assertThat(wheel.isClosed()).isTrue();
    assertThat(wheel.state(early)).isEqualTo(TimerEntry.DISCARDED);
    assertThat(late.cancel()).isFalse();
    assertThat(wheel.schedule(10, new Probe("x"))).isNull();
    assertThat(wheel.advanceTo(1000)).isEmpty();
  }

  @Test
  void aWheelNeedsAPositiveTickAndAPowerOfTwoSize() {
    assertThatThrownBy(() -> new TimerWheel(0, 0, 8)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new TimerWheel(0, 10, 6)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new TimerWheel(0, 10, 1)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void theStartTimeIsTheOriginOfTheTicks() {
    TimerWheel shifted = new TimerWheel(1_000, 10, 8);
    shifted.schedule(1_025, new Probe("a"));
    assertThat(shifted.advanceTo(1_029)).isEmpty();
    assertThat(shifted.advanceTo(1_030)).hasSize(1);
    assertThat(shifted.advanceTo(500)).isEmpty();
  }
}
