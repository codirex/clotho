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
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** Priorities and aging, made deterministic with one compute thread and a fake clock. */
class EnginePriorityTest extends EngineTestBase {

  private static final long STEP = 10;

  private final AtomicLong clock = new AtomicLong();
  private final Realm realm = Realm.open("priority");
  private final List<String> order = Collections.synchronizedList(new ArrayList<>());

  private Engine engine() {
    return track(
        builder()
            .computeThreads(1)
            .agingStep(STEP, TimeUnit.NANOSECONDS)
            .ticker(clock::get)
            .build());
  }

  private Job<String> task(Engine engine, Priority priority, String label) {
    return realm.launch(
        engine.compute(),
        priority,
        cp -> {
          order.add(label);
          return label;
        });
  }

  /** Occupies the only compute thread; returns after it is running. */
  private Gate block(Engine engine) throws Exception {
    Gate gate = new Gate();
    realm.launch(engine.compute(), gate.work());
    gate.awaitStarted();
    return gate;
  }

  @Test
  void higherPriorityRunsFirstAndSamePriorityKeepsSubmissionOrder() throws Exception {
    Engine engine = engine();
    Gate gate = block(engine);
    task(engine, Priority.LOW, "L1");
    task(engine, Priority.NORMAL, "N1");
    task(engine, Priority.HIGH, "H1");
    task(engine, Priority.NORMAL, "N2");
    task(engine, Priority.HIGH, "H2");
    task(engine, Priority.LOW, "L2");
    gate.open();
    realm.close();
    assertThat(order).containsExactly("H1", "H2", "N1", "N2", "L1", "L2");
  }

  @Test
  void justBeforeTheSecondStepAnOldLowStillWaitsForHigh() throws Exception {
    Engine engine = engine();
    Gate gate = block(engine);
    task(engine, Priority.LOW, "L");
    task(engine, Priority.HIGH, "H1");
    task(engine, Priority.HIGH, "H2");
    clock.set(2 * STEP - 1);
    gate.open();
    realm.close();
    assertThat(order).containsExactly("H1", "H2", "L");
  }

  @Test
  void afterTwoStepsAnOldLowGoesBeforeHighTasksThatWereQueuedAfterIt() throws Exception {
    Engine engine = engine();
    Gate gate = block(engine);
    task(engine, Priority.LOW, "L");
    task(engine, Priority.HIGH, "H1");
    task(engine, Priority.HIGH, "H2");
    clock.set(2 * STEP);
    gate.open();
    realm.close();
    assertThat(order).containsExactly("L", "H1", "H2");
  }

  @Test
  void normalIsPromotedAfterOneStep() throws Exception {
    Engine engine = engine();
    Gate gate = block(engine);
    task(engine, Priority.NORMAL, "N");
    task(engine, Priority.HIGH, "H");
    clock.set(STEP);
    gate.open();
    realm.close();
    assertThat(order).containsExactly("N", "H");
  }

  @Test
  void priorityIsChosenAtSubmissionTimeNotAtRunTime() throws Exception {
    Engine engine = engine();
    Gate gate = block(engine);
    clock.set(0);
    task(engine, Priority.LOW, "old-low");
    clock.set(5 * STEP);
    task(engine, Priority.HIGH, "new-high");
    gate.open();
    realm.close();
    // old-low waited 5 steps (capped at HIGH) and was queued first: it goes first
    assertThat(order).containsExactly("old-low", "new-high");
  }

  @Test
  void continuousHighLoadCannotStarveALowTask() throws Exception {
    Engine engine = engine();
    Gate gate = block(engine);
    CountDownLatch lowRan = new CountDownLatch(1);
    AtomicInteger highRan = new AtomicInteger();
    int budget = 1000;
    realm.launch(
        engine.compute(),
        Priority.LOW,
        cp -> {
          lowRan.countDown();
          return "low";
        });
    // Each HIGH task advances the clock by half a step and queues the next HIGH task, so there is
    // always a HIGH task waiting. Without aging the LOW task would never run.
    Work<String>[] chain = newChain();
    chain[0] =
        cp -> {
          int n = highRan.incrementAndGet();
          clock.addAndGet(STEP / 2);
          if (lowRan.getCount() > 0 && n < budget) {
            realm.launch(engine.compute(), Priority.HIGH, chain[0]);
          }
          return "high";
        };
    realm.launch(engine.compute(), Priority.HIGH, chain[0]);
    gate.open();
    assertThat(lowRan.await(20, TimeUnit.SECONDS)).isTrue();
    realm.close();
    // two steps of aging = four HIGH tasks; allow a small margin
    assertThat(highRan.get() < 10).isTrue();
  }

  @SuppressWarnings("unchecked")
  private static Work<String>[] newChain() {
    return (Work<String>[]) new Work<?>[1];
  }

  @Test
  void withoutAgingALowTaskWaitsForAllHighTasks() throws Exception {
    Engine engine =
        track(
            builder()
                .computeThreads(1)
                .agingStep(0, TimeUnit.NANOSECONDS)
                .ticker(clock::get)
                .build());
    Gate gate = block(engine);
    task(engine, Priority.LOW, "L");
    task(engine, Priority.HIGH, "H1");
    clock.set(1_000_000);
    task(engine, Priority.HIGH, "H2");
    gate.open();
    realm.close();
    assertThat(order).containsExactly("H1", "H2", "L");
  }
}
