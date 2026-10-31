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
import java.util.OptionalLong;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.codirex.clotho.support.ManualLane;
import org.junit.jupiter.api.Test;

/** The manual cadence: no thread, timers fire only on runDue(), time is the engine clock. */
class ManualCadenceTest extends EngineTestBase {

  private static final long MS = TimeUnit.MILLISECONDS.toNanos(1);

  private final AtomicLong clock = new AtomicLong();
  private final ManualLane lane = new ManualLane();

  private Engine engine() {
    return track(
        builder().manualTimers().timerTick(1, TimeUnit.MILLISECONDS).ticker(clock::get).build());
  }

  private Realm realm(Engine engine) {
    return Realm.builder("manual").cadence(engine.cadence()).open();
  }

  @Test
  void noThreadIsEverStartedAndNothingFiresUntilRunDueIsCalled() throws Exception {
    Engine engine = engine();
    Realm realm = realm(engine);
    Job<String> job = realm.after(lane, 10, TimeUnit.MILLISECONDS, cp -> "x");
    assertThat(engine.cadence().isManual()).isTrue();
    assertThat(engine.liveThreads()).isEqualTo(0);
    assertThat(engine.cadence().pending()).isEqualTo(1);
    clock.set(50 * MS); // time passed, but nobody called runDue()
    assertThat(lane.size()).isEqualTo(0);
    assertThat(engine.cadence().runDue()).isEqualTo(1);
    assertThat(lane.size()).isEqualTo(1);
    lane.runAll();
    assertThat(job.outcomeOrNull()).isEqualTo(Outcome.success("x"));
    assertThat(engine.liveThreads()).isEqualTo(0);
  }

  @Test
  void aTimerFiresOnTheTickAtOrAfterItsDeadlineAndNotBefore() throws Exception {
    Engine engine = engine();
    Realm realm = realm(engine);
    realm.after(lane, 10, TimeUnit.MILLISECONDS, cp -> 1);
    clock.set(9 * MS);
    assertThat(engine.cadence().runDue()).isEqualTo(0);
    clock.set(10 * MS);
    assertThat(engine.cadence().runDue()).isEqualTo(1);
  }

  @Test
  void nextDueNanosTellsWhenTheEarliestTimerFires() throws Exception {
    Engine engine = engine();
    Realm realm = realm(engine);
    assertThat(engine.cadence().nextDueNanos()).isEqualTo(OptionalLong.empty());
    realm.after(lane, 30, TimeUnit.MILLISECONDS, cp -> 1);
    realm.after(lane, 20, TimeUnit.MILLISECONDS, cp -> 2);
    assertThat(engine.cadence().nextDueNanos().getAsLong()).isEqualTo(20 * MS);
    clock.set(20 * MS);
    engine.cadence().runDue();
    assertThat(engine.cadence().nextDueNanos().getAsLong()).isEqualTo(30 * MS);
  }

  @Test
  void runDueRefusesANonManualCadence() throws Exception {
    Engine real = track(builder().build());
    assertThat(real.cadence().isManual()).isFalse();
    assertThatThrownBy(() -> real.cadence().runDue()).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void periodicJobsFollowTheControlledClock() throws Exception {
    Engine engine = engine();
    Realm realm = realm(engine);
    List<Long> starts = new ArrayList<>();
    Job<Void> job =
        realm.every(lane, 0, 100, TimeUnit.MILLISECONDS, cp -> starts.add(clock.get() / MS));
    lane.runAll(); // the first run is immediate
    for (int i = 1; i <= 3; i++) {
      clock.set(i * 100 * MS);
      assertThat(engine.cadence().runDue()).isEqualTo(1);
      lane.runAll();
    }
    assertThat(starts).containsExactly(0L, 100L, 200L, 300L);
    assertThat(job.cancel()).isTrue();
    assertThat(engine.cadence().pending()).isEqualTo(0);
  }

  @Test
  void shutdownDiscardsPendingManualTimers() throws Exception {
    Engine engine = engine();
    Realm realm = realm(engine);
    Job<String> job = realm.after(lane, 1, TimeUnit.HOURS, cp -> "never");
    engine.shutdown();
    assertThat(job.outcomeOrNull().failureOrNull()).isInstanceOf(DroppedException.class);
    assertThat(engine.cadence().pending()).isEqualTo(0);
  }
}
