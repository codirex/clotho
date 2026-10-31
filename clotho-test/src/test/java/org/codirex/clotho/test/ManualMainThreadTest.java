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
import java.util.concurrent.atomic.AtomicReference;
import org.codirex.clotho.BlockingNotAllowedException;
import org.codirex.clotho.Clotho;
import org.codirex.clotho.Job;
import org.codirex.clotho.Lane;
import org.codirex.clotho.MainLane;
import org.codirex.clotho.Outcome;
import org.codirex.clotho.Realm;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ManualMainThreadTest {

  // Created inside each test: the main thread of the host is the thread that creates it.
  private ManualMainThread main;
  private MainLane lane;
  private Realm realm;

  private void init() {
    main = new ManualMainThread();
    lane = MainLane.of("manual", main);
    realm = Realm.open("main");
  }

  @AfterEach
  void reset() {
    Clotho.uninstallMainLane();
  }

  @Test
  void postedTasksWaitForIdleAndRunInOrderOnTheMainThread() {
    init();
    List<String> order = new ArrayList<>();
    AtomicReference<Thread> ranOn = new AtomicReference<>();
    lane.execute(() -> order.add("first"));
    lane.execute(
        () -> {
          order.add("second");
          ranOn.set(Thread.currentThread());
        });
    assertThat(main.pending()).isEqualTo(2);
    assertThat(order).isEmpty();
    assertThat(main.idle()).isEqualTo(2);
    assertThat(order).containsExactly("first", "second");
    assertThat(ranOn.get()).isSameAs(Thread.currentThread());
  }

  @Test
  void idleRunsTasksPostedWhileItRunsAndRefusesOtherThreads() throws Exception {
    init();
    List<String> order = new ArrayList<>();
    lane.execute(
        () -> {
          order.add("a");
          lane.execute(() -> order.add("b"));
        });
    assertThat(main.idle()).isEqualTo(2);
    assertThat(order).containsExactly("a", "b");
    AtomicReference<Throwable> thrown = new AtomicReference<>();
    Thread other =
        new Thread(
            () -> {
              try {
                main.idle();
              } catch (Throwable t) {
                thrown.set(t);
              }
            });
    other.start();
    other.join(20_000);
    assertThat(thrown.get()).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void jobsOnTheMainLaneRunWhenTheTestIdlesTheMainThread() {
    init();
    Job<String> job = realm.launch(lane, cp -> "ui");
    assertThat(job.isDone()).isFalse();
    main.idle();
    assertThat(job.outcomeOrNull()).isEqualTo(Outcome.success("ui"));
  }

  @Test
  void onceInstalledBlockingOnTheMainThreadIsRefused() throws Exception {
    init();
    Job<String> done = realm.launch(Lane.direct(), cp -> "x");
    Clotho.install(lane);
    assertThat(Lane.main()).isSameAs(lane);
    assertThatThrownBy(done::await).isInstanceOf(BlockingNotAllowedException.class);
  }

  @Test
  void quitRejectsNewTasksAndDiscardsWaitingOnes() {
    init();
    Job<String> waiting = realm.launch(lane, cp -> "never");
    main.quit();
    assertThat(waiting.isDone()).isTrue();
    assertThat(waiting.outcomeOrNull().isFailure()).isTrue();
    assertThatThrownBy(() -> lane.execute(() -> {})).isInstanceOf(RejectedExecutionException.class);
    assertThat(main.idle()).isEqualTo(0);
  }
}
