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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.codirex.clotho.support.ManualLane;
import org.junit.jupiter.api.Test;

class RaceTest {

  private final Realm realm = Realm.open("race");

  private Job<String> value(ManualLane lane, String v) {
    return realm.launch(lane, cp -> v);
  }

  private Job<String> failure(ManualLane lane, Exception error) {
    return realm.launch(
        lane,
        cp -> {
          throw error;
        });
  }

  @Test
  void firstSuccessWinsAndOwnedLosersAreCancelled() throws Exception {
    ManualLane l = new ManualLane();
    Job<String> a = failure(l, new IOException("a"));
    Job<String> b = value(l, "b");
    Job<String> c = value(l, "c");
    Job<String> race = realm.race(List.of(a, b, c), Ownership.OWNED);
    l.runNext();
    assertThat(race.isDone()).isFalse();
    l.runNext();
    assertThat(race.await()).isEqualTo(Outcome.success("b"));
    assertThat(c.isCancellationRequested()).isTrue();
    l.runAll();
    assertThat(c.await().isCancelled()).isTrue();
  }

  @Test
  void borrowedLosersKeepRunning() throws Exception {
    ManualLane l = new ManualLane();
    Job<String> b = value(l, "b");
    Job<String> c = value(l, "c");
    Job<String> race = realm.race(List.of(b, c), Ownership.BORROWED);
    l.runAll();
    assertThat(race.await()).isEqualTo(Outcome.success("b"));
    assertThat(c.await()).isEqualTo(Outcome.success("c"));
  }

  @Test
  void aFailureDoesNotEndTheRace() throws Exception {
    ManualLane l1 = new ManualLane();
    ManualLane l2 = new ManualLane();
    Job<String> a = failure(l1, new IOException("a"));
    Job<String> b = value(l2, "b");
    Job<String> race = realm.race(List.of(a, b), Ownership.OWNED);
    l1.runAll();
    assertThat(race.isDone()).isFalse();
    l2.runAll();
    assertThat(race.await()).isEqualTo(Outcome.success("b"));
  }

  @Test
  void allFailedGivesNoWinnerWithCauseAndSuppressedInListOrder() throws Exception {
    ManualLane l0 = new ManualLane();
    ManualLane l1 = new ManualLane();
    ManualLane l2 = new ManualLane();
    IOException f0 = new IOException("f0");
    IOException f1 = new IOException("f1");
    IOException f2 = new IOException("f2");
    Job<String> race =
        realm.race(List.of(failure(l0, f0), failure(l1, f1), failure(l2, f2)), Ownership.OWNED);
    l2.runAll();
    l0.runAll();
    assertThat(race.isDone()).isFalse();
    l1.runAll();
    Throwable e = race.await().failureOrNull();
    assertThat(e).isInstanceOf(NoWinnerException.class);
    assertThat(e.getCause()).isSameAs(f0);
    assertThat(e.getSuppressed()).hasSize(2);
    assertThat(e.getSuppressed()[0]).isSameAs(f1);
    assertThat(e.getSuppressed()[1]).isSameAs(f2);
  }

  @Test
  void failureAndCancellationTogetherGiveFailure() throws Exception {
    ManualLane l = new ManualLane();
    IOException f = new IOException("f");
    Job<String> a = failure(l, f);
    Job<String> b = value(l, "b");
    Job<String> race = realm.race(List.of(a, b), Ownership.BORROWED);
    b.cancel();
    l.runAll();
    Throwable e = race.await().failureOrNull();
    assertThat(e).isInstanceOf(NoWinnerException.class);
    assertThat(e.getCause()).isSameAs(f);
    assertThat(e.getSuppressed()).isEmpty();
  }

  @Test
  void allCancelledGivesCancelled() throws Exception {
    ManualLane l = new ManualLane();
    Job<String> a = value(l, "a");
    Job<String> b = value(l, "b");
    Job<String> race = realm.race(List.of(a, b), Ownership.BORROWED);
    a.cancel();
    b.cancel();
    assertThat(race.await().isCancelled()).isTrue();
  }

  @Test
  void successDecidedBeforeCancelKeepsTheSuccess() throws Exception {
    ManualLane l = new ManualLane();
    Job<String> a = value(l, "a");
    Job<String> race = realm.race(List.of(a), Ownership.OWNED);
    l.runAll();
    assertThat(race.cancel()).isFalse();
    assertThat(race.await()).isEqualTo(Outcome.success("a"));
  }

  @Test
  void cancelDecidedBeforeSuccessDropsTheValue() throws Exception {
    ManualLane l = new ManualLane();
    Job<String> a = value(l, "a");
    Job<String> b = value(l, "b");
    Job<String> race = realm.race(List.of(a, b), Ownership.BORROWED);
    assertThat(race.cancel()).isTrue();
    l.runAll();
    assertThat(race.await().isCancelled()).isTrue();
    assertThat(a.await()).isEqualTo(Outcome.success("a"));
    assertThat(b.await()).isEqualTo(Outcome.success("b"));
  }

  @Test
  void cancellingAnOwnedRaceCancelsEveryInput() throws Exception {
    ManualLane l = new ManualLane();
    Job<String> a = value(l, "a");
    Job<String> b = value(l, "b");
    Job<String> race = realm.race(List.of(a, b), Ownership.OWNED);
    assertThat(race.cancel()).isTrue();
    assertThat(a.await().isCancelled()).isTrue();
    assertThat(b.await().isCancelled()).isTrue();
  }

  @Test
  void singleInputRaceBehavesLikeTheInput() throws Exception {
    ManualLane l = new ManualLane();
    Job<String> race = realm.race(List.of(value(l, "only")), Ownership.OWNED);
    l.runAll();
    assertThat(race.await()).isEqualTo(Outcome.success("only"));
  }

  @Test
  void emptyAndForeignInputsAreRejected() throws Exception {
    List<Job<String>> none = new ArrayList<>();
    assertThatThrownBy(() -> realm.race(none, Ownership.OWNED))
        .isInstanceOf(IllegalArgumentException.class);
    Realm other = Realm.open("other");
    Job<String> foreign = other.launch(new ManualLane(), cp -> "x");
    assertThatThrownBy(() -> realm.race(Collections.singletonList(foreign), Ownership.OWNED))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
