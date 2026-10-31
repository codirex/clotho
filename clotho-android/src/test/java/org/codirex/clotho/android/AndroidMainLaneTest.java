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
package org.codirex.clotho.android;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.robolectric.Shadows.shadowOf;

import android.os.Looper;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.codirex.clotho.BlockingNotAllowedException;
import org.codirex.clotho.BlockingPolicy;
import org.codirex.clotho.Clotho;
import org.codirex.clotho.Job;
import org.codirex.clotho.Lane;
import org.codirex.clotho.MainLane;
import org.codirex.clotho.Outcome;
import org.codirex.clotho.Realm;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class AndroidMainLaneTest {

  private final MainLane lane = AndroidMainLane.get();

  @After
  public void tearDown() {
    Clotho.uninstallMainLane();
  }

  private static void idleMainLooper() {
    shadowOf(Looper.getMainLooper()).idle();
  }

  @Test
  public void theLaneIsAlwaysTheSameInstanceAndInstallIsIdempotent() {
    assertThat(AndroidMainLane.get()).isSameAs(lane);
    assertThat(AndroidMainLane.install()).isSameAs(lane);
    assertThat(AndroidMainLane.install()).isSameAs(lane);
    assertThat(Lane.main()).isSameAs(lane);
    assertThat(Clotho.installedMainLane()).isSameAs(lane);
  }

  @Test
  public void executePostsEvenWhenAlreadyOnTheMainThread() {
    List<String> log = new ArrayList<>();
    lane.execute(() -> log.add("posted"));
    log.add("after execute");
    assertThat(log).containsExactly("after execute");
    idleMainLooper();
    assertThat(log).containsExactly("after execute", "posted");
  }

  @Test
  public void postedTasksKeepSubmissionOrder() {
    List<Integer> order = new ArrayList<>();
    for (int i = 0; i < 100; i++) {
      int id = i;
      lane.execute(() -> order.add(id));
    }
    idleMainLooper();
    assertThat(order).hasSize(100);
    for (int i = 0; i < 100; i++) {
      assertThat(order.get(i)).isEqualTo(i);
    }
  }

  @Test
  public void isMainThreadIsTrueOnTheMainThreadAndFalseElsewhere() throws Exception {
    assertThat(lane.isMainThread()).isTrue();
    AtomicBoolean other = new AtomicBoolean(true);
    Thread t = new Thread(() -> other.set(lane.isMainThread()));
    t.start();
    t.join(20_000);
    assertThat(other.get()).isFalse();
  }

  @Test
  public void immediateRunsInlineOnMainAndPostsFromOtherThreads() throws Exception {
    AtomicBoolean ran = new AtomicBoolean();
    lane.immediate().execute(() -> ran.set(true));
    assertThat(ran.get()).isTrue();

    AtomicReference<Boolean> onMain = new AtomicReference<>();
    Thread t =
        new Thread(
            () ->
                lane.immediate()
                    .execute(() -> onMain.set(Looper.myLooper() == Looper.getMainLooper())));
    t.start();
    t.join(20_000);
    assertThat(onMain.get()).isNull();
    idleMainLooper();
    assertThat(onMain.get()).isTrue();
  }

  @Test
  public void immediateBypassesTasksAlreadyQueued() {
    List<String> order = Collections.synchronizedList(new ArrayList<>());
    lane.execute(() -> order.add("queued first"));
    lane.immediate().execute(() -> order.add("immediate"));
    idleMainLooper();
    assertThat(order).containsExactly("immediate", "queued first");
  }

  @Test
  public void jobsRunOnTheMainLooperAndBlockingOnTheMainThreadIsRefusedOnceInstalled() {
    AndroidMainLane.install();
    Realm realm = Realm.open("android-main");
    Job<String> job = realm.launch(lane, cp -> "x");
    assertThat(job.isDone()).isFalse();
    idleMainLooper();
    assertThat(job.outcomeOrNull()).isEqualTo(Outcome.success("x"));
    assertThat(lane.blockingPolicy()).isEqualTo(BlockingPolicy.FORBIDDEN);
    assertThatThrownBy(job::await).isInstanceOf(BlockingNotAllowedException.class);
    realm.closeAsync();
  }

  @Test
  public void aCancelledQueuedJobEndsAtOnceAndNeverRuns() {
    Realm realm = Realm.open("cancel");
    AtomicBoolean ran = new AtomicBoolean();
    Job<String> job =
        realm.launch(
            lane,
            cp -> {
              ran.set(true);
              return "x";
            });
    assertThat(job.cancel()).isTrue();
    assertThat(job.isDone()).isTrue();
    idleMainLooper();
    assertThat(ran.get()).isFalse();
    realm.closeAsync();
  }
}
