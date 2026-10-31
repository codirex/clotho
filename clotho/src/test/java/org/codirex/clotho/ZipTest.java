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
import org.junit.jupiter.api.Test;

class ZipTest {

  private final Realm realm = Realm.open("zip");
  private final ManualLane inputs = new ManualLane();
  private final ManualLane combine = new ManualLane();

  private Job<String> input(String value) {
    return realm.launch(inputs, cp -> value);
  }

  private Job<String> failing(Exception error) {
    return realm.launch(
        inputs,
        cp -> {
          throw error;
        });
  }

  private Job<String> zip(Job<String> a, Job<String> b, Ownership ownership) {
    return realm.zip(a, b, ownership, combine, (x, y, cp) -> x + y);
  }

  @Test
  void combinesBothValuesOnTheGivenLane() throws Exception {
    Job<String> z = zip(input("a"), input("b"), Ownership.OWNED);
    inputs.runAll();
    assertThat(z.isDone()).isFalse();
    combine.runAll();
    assertThat(z.await()).isEqualTo(Outcome.success("ab"));
  }

  @Test
  void failureOfAnInputFailsTheResultAndOwnedCancelsTheOther() throws Exception {
    IOException boom = new IOException("boom");
    Job<String> a = failing(boom);
    Job<String> b = input("b");
    Job<String> z = zip(a, b, Ownership.OWNED);
    inputs.runNext();
    assertThat(z.await().failureOrNull()).isSameAs(boom);
    assertThat(b.isCancellationRequested()).isTrue();
    inputs.runAll();
    assertThat(b.await().isCancelled()).isTrue();
    assertThat(combine.runAll()).isEqualTo(0);
  }

  @Test
  void failureWithBorrowedLeavesTheOtherInputRunning() throws Exception {
    IOException boom = new IOException("boom");
    Job<String> a = failing(boom);
    Job<String> b = input("b");
    Job<String> z = zip(a, b, Ownership.BORROWED);
    inputs.runAll();
    assertThat(z.await().failureOrNull()).isSameAs(boom);
    assertThat(b.await()).isEqualTo(Outcome.success("b"));
    assertThat(combine.runAll()).isEqualTo(0);
  }

  @Test
  void cancelledInputCancelsTheResult() throws Exception {
    Job<String> a = input("a");
    Job<String> b = input("b");
    Job<String> z = zip(a, b, Ownership.OWNED);
    a.cancel();
    assertThat(z.await().isCancelled()).isTrue();
    assertThat(b.isCancellationRequested()).isTrue();
  }

  @Test
  void cancelledInputWithBorrowedDoesNotTouchTheOther() throws Exception {
    Job<String> a = input("a");
    Job<String> b = input("b");
    Job<String> z = zip(a, b, Ownership.BORROWED);
    a.cancel();
    assertThat(z.await().isCancelled()).isTrue();
    assertThat(b.isCancellationRequested()).isFalse();
    inputs.runAll();
    assertThat(b.await()).isEqualTo(Outcome.success("b"));
  }

  @Test
  void cancellingTheResultCancelsOwnedInputs() throws Exception {
    Job<String> a = input("a");
    Job<String> b = input("b");
    Job<String> z = zip(a, b, Ownership.OWNED);
    assertThat(z.cancel()).isTrue();
    assertThat(z.await().isCancelled()).isTrue();
    assertThat(a.await().isCancelled()).isTrue();
    assertThat(b.await().isCancelled()).isTrue();
  }

  @Test
  void cancellingTheResultLeavesBorrowedInputsAlone() throws Exception {
    Job<String> a = input("a");
    Job<String> b = input("b");
    Job<String> z = zip(a, b, Ownership.BORROWED);
    assertThat(z.cancel()).isTrue();
    inputs.runAll();
    assertThat(a.await()).isEqualTo(Outcome.success("a"));
    assertThat(b.await()).isEqualTo(Outcome.success("b"));
    assertThat(z.await().isCancelled()).isTrue();
    assertThat(combine.runAll()).isEqualTo(0);
  }

  @Test
  void sharedInputSurvivesWhenBorrowed() throws Exception {
    Job<String> shared = input("s");
    Job<String> other1 = input("1");
    Job<String> other2 = input("2");
    Job<String> z1 = zip(shared, other1, Ownership.BORROWED);
    Job<String> z2 = zip(shared, other2, Ownership.BORROWED);
    assertThat(z1.cancel()).isTrue();
    assertThat(shared.isCancellationRequested()).isFalse();
    inputs.runAll();
    combine.runAll();
    assertThat(z2.await()).isEqualTo(Outcome.success("s2"));
  }

  @Test
  void sharedInputIsCancelledWhenTheCompositeOwnsIt() throws Exception {
    Job<String> shared = input("s");
    Job<String> other1 = input("1");
    Job<String> other2 = input("2");
    Job<String> z1 = zip(shared, other1, Ownership.OWNED);
    Job<String> z2 = zip(shared, other2, Ownership.BORROWED);
    assertThat(z1.cancel()).isTrue();
    assertThat(shared.isCancellationRequested()).isTrue();
    assertThat(z2.await().isCancelled()).isTrue();
  }

  @Test
  void combinerFailureIsTheResultFailure() throws Exception {
    IllegalStateException boom = new IllegalStateException("combine");
    Job<String> z =
        realm.zip(
            input("a"),
            input("b"),
            Ownership.OWNED,
            combine,
            (x, y, cp) -> {
              throw boom;
            });
    inputs.runAll();
    combine.runAll();
    assertThat(z.await().failureOrNull()).isSameAs(boom);
  }

  @Test
  void cancellingWhileTheCombinerRunsCancelsOnlyTheResult() throws Exception {
    Job<String> a = input("a");
    Job<String> b = input("b");
    AtomicReference<Job<String>> self = new AtomicReference<>();
    AtomicBoolean ranToEnd = new AtomicBoolean();
    Job<String> z =
        realm.zip(
            a,
            b,
            Ownership.OWNED,
            combine,
            (x, y, cp) -> {
              self.get().cancel();
              cp.check();
              ranToEnd.set(true);
              return x + y;
            });
    self.set(z);
    inputs.runAll();
    combine.runAll();
    assertThat(z.await().isCancelled()).isTrue();
    assertThat(ranToEnd.get()).isFalse();
    assertThat(a.await()).isEqualTo(Outcome.success("a"));
    assertThat(b.await()).isEqualTo(Outcome.success("b"));
  }

  @Test
  void whenBothFailTheFailureThatArrivesFirstWins() throws Exception {
    IOException first = new IOException("first");
    IOException second = new IOException("second");
    Job<String> z = zip(failing(first), failing(second), Ownership.BORROWED);
    inputs.runAll();
    assertThat(z.await().failureOrNull()).isSameAs(first);
  }

  @Test
  void inputsFromAnotherRealmAreRejected() throws Exception {
    Realm other = Realm.open("other");
    Job<String> foreign = other.launch(inputs, cp -> "x");
    Job<String> mine = input("m");
    assertThatThrownBy(() -> zip(foreign, mine, Ownership.OWNED))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> zip(mine, foreign, Ownership.OWNED))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void zipOnAClosedRealmIsRejected() throws Exception {
    Job<String> a = input("a");
    Job<String> b = input("b");
    inputs.runAll();
    realm.close();
    assertThatThrownBy(() -> zip(a, b, Ownership.OWNED)).isInstanceOf(RealmClosedException.class);
  }
}
