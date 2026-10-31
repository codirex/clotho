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

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.codirex.clotho.support.ManualLane;
import org.codirex.clotho.support.RecordingHandler;
import org.junit.jupiter.api.Test;

class JobChainTest {

  private final RecordingHandler handler = new RecordingHandler();
  private final Realm realm = Realm.builder("chain").uncaughtHandler(handler).open();
  private final ManualLane lane = new ManualLane();

  @Test
  void thenTransformsTheValueOnItsOwnLane() throws Exception {
    ManualLane second = new ManualLane();
    Job<Integer> a = realm.launch(lane, cp -> 20);
    Job<Integer> b = a.then(second, (v, cp) -> v + 1);
    lane.runAll();
    assertThat(b.isDone()).isFalse();
    second.runAll();
    assertThat(b.await()).isEqualTo(Outcome.success(21));
    assertThat(b.realm()).isSameAs(realm);
  }

  @Test
  void thenIsSkippedWhenTheParentFails() throws Exception {
    IOException boom = new IOException("boom");
    AtomicBoolean ran = new AtomicBoolean();
    Job<Integer> a =
        realm.launch(
            lane,
            cp -> {
              throw boom;
            });
    Job<Integer> b =
        a.then(
            lane,
            (v, cp) -> {
              ran.set(true);
              return v;
            });
    lane.runAll();
    assertThat(ran.get()).isFalse();
    assertThat(b.await().failureOrNull()).isSameAs(boom);
  }

  @Test
  void parentCancellationCancelsAllDependents() throws Exception {
    AtomicBoolean ran = new AtomicBoolean();
    Job<Integer> a = realm.launch(lane, cp -> 1);
    Job<Integer> b =
        a.then(
            lane,
            (v, cp) -> {
              ran.set(true);
              return v;
            });
    Job<Integer> c = b.recover(lane, (t, cp) -> 0);
    assertThat(a.cancel()).isTrue();
    lane.runAll();
    assertThat(a.await().isCancelled()).isTrue();
    assertThat(b.await().isCancelled()).isTrue();
    assertThat(c.await().isCancelled()).isTrue();
    assertThat(ran.get()).isFalse();
  }

  @Test
  void cancellingADependentDoesNotCancelItsParent() throws Exception {
    AtomicBoolean ran = new AtomicBoolean();
    Job<Integer> a = realm.launch(lane, cp -> 7);
    Job<Integer> b =
        a.then(
            lane,
            (v, cp) -> {
              ran.set(true);
              return v;
            });
    assertThat(b.cancel()).isTrue();
    lane.runAll();
    assertThat(a.await()).isEqualTo(Outcome.success(7));
    assertThat(b.await().isCancelled()).isTrue();
    assertThat(ran.get()).isFalse();
  }

  @Test
  void recoverReplacesAFailureWithAValue() throws Exception {
    IOException boom = new IOException("boom");
    AtomicReference<Throwable> seen = new AtomicReference<>();
    Job<Integer> a =
        realm.launch(
            lane,
            cp -> {
              throw boom;
            });
    Job<Integer> b =
        a.recover(
            lane,
            (t, cp) -> {
              seen.set(t);
              return -1;
            });
    lane.runAll();
    assertThat(seen.get()).isSameAs(boom);
    assertThat(b.await()).isEqualTo(Outcome.success(-1));
  }

  @Test
  void recoverIsSkippedOnSuccessAndKeepsCancellation() throws Exception {
    AtomicBoolean ran = new AtomicBoolean();
    Job<Integer> ok = realm.launch(lane, cp -> 5);
    Job<Integer> passthrough =
        ok.recover(
            lane,
            (t, cp) -> {
              ran.set(true);
              return 0;
            });
    Job<Integer> cancelled = realm.launch(lane, cp -> 5);
    Job<Integer> stillCancelled =
        cancelled.recover(
            lane,
            (t, cp) -> {
              ran.set(true);
              return 0;
            });
    cancelled.cancel();
    lane.runAll();
    assertThat(passthrough.await()).isEqualTo(Outcome.success(5));
    assertThat(stillCancelled.await().isCancelled()).isTrue();
    assertThat(ran.get()).isFalse();
  }

  @Test
  void nestedExceptionsKeepTheirCauseChainAndIdentity() throws Exception {
    IOException inner = new IOException("inner");
    RuntimeException outer = new RuntimeException("outer", inner);
    Job<Integer> a =
        realm.launch(
            lane,
            cp -> {
              throw outer;
            });
    lane.runAll();
    Throwable cause = a.await().failureOrNull();
    assertThat(cause).isSameAs(outer);
    assertThat(cause.getCause()).isSameAs(inner);
  }

  @Test
  void failureInThenStepIsTheFailureOfTheDependent() throws Exception {
    IllegalStateException boom = new IllegalStateException("step");
    Job<Integer> a = realm.launch(lane, cp -> 1);
    Job<Integer> b =
        a.then(
            lane,
            (v, cp) -> {
              throw boom;
            });
    Job<Integer> c = b.then(lane, (v, cp) -> v);
    lane.runAll();
    assertThat(a.await().isSuccess()).isTrue();
    assertThat(b.await().failureOrNull()).isSameAs(boom);
    assertThat(c.await().failureOrNull()).isSameAs(boom);
  }

  @Test
  void failureInRecoverReplacesTheOriginalFailure() throws Exception {
    IOException original = new IOException("original");
    IllegalStateException second = new IllegalStateException("recover failed");
    Job<Integer> a =
        realm.launch(
            lane,
            cp -> {
              throw original;
            });
    Job<Integer> b =
        a.recover(
            lane,
            (t, cp) -> {
              throw second;
            });
    lane.runAll();
    assertThat(b.await().failureOrNull()).isSameAs(second);
    assertThat(a.await().failureOrNull()).isSameAs(original);
  }

  @Test
  void thenOnADoneJobStillRunsOnItsLane() throws Exception {
    Job<Integer> a = realm.launch(lane, cp -> 1);
    lane.runAll();
    Job<Integer> b = a.then(lane, (v, cp) -> v + 1);
    assertThat(b.isDone()).isFalse();
    lane.runAll();
    assertThat(b.await()).isEqualTo(Outcome.success(2));
  }

  @Test
  void dependentsRegisterInTheRealmSoCloseWaitsForThem() throws Exception {
    Job<Integer> a = realm.launch(lane, cp -> 1);
    Job<Integer> b = a.then(lane, (v, cp) -> v);
    assertThat(realm.outstanding()).isEqualTo(2);
    lane.runAll();
    assertThat(b.isDone()).isTrue();
    assertThat(realm.outstanding()).isEqualTo(0);
  }

  @Test
  void deriveOnClosedRealmIsRejected() throws Exception {
    Job<Integer> a = realm.launch(lane, cp -> 1);
    lane.runAll();
    realm.close();
    assertThatThrownBy(() -> a.then(lane, (v, cp) -> v)).isInstanceOf(RealmClosedException.class);
    assertThatThrownBy(() -> a.recover(lane, (t, cp) -> 0))
        .isInstanceOf(RealmClosedException.class);
    assertThatThrownBy(() -> a.onComplete(Lane.direct(), o -> {}))
        .isInstanceOf(RealmClosedException.class);
  }

  @Test
  void directLaneRunsInlineDuringLaunch() throws Exception {
    Job<Integer> a = realm.launch(Lane.direct(), cp -> 3);
    assertThat(a.isDone()).isTrue();
    assertThat(a.await()).isEqualTo(Outcome.success(3));
    assertThat(Lane.direct()).isSameAs(Lane.direct());
  }
}
