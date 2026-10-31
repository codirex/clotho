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
import org.junit.jupiter.api.Test;

class OutcomeTest {

  @Test
  void successCarriesValue() {
    Outcome<String> o = Outcome.success("v");
    assertThat(o.isSuccess()).isTrue();
    assertThat(o.isFailure()).isFalse();
    assertThat(o.isCancelled()).isFalse();
    assertThat(o.getOrThrow()).isEqualTo("v");
    assertThat(o.valueOrNull()).isEqualTo("v");
    assertThat(o.failureOrNull()).isNull();
  }

  @Test
  void successMayCarryNull() {
    Outcome<String> o = Outcome.success(null);
    assertThat(o.isSuccess()).isTrue();
    assertThat(o.getOrThrow()).isNull();
  }

  @Test
  void failureCarriesCauseAndThrowsWrapped() {
    IOException cause = new IOException("boom");
    Outcome<String> o = Outcome.failure(cause);
    assertThat(o.isFailure()).isTrue();
    assertThat(o.failureOrNull()).isSameAs(cause);
    assertThat(o.valueOrNull()).isNull();
    assertThatThrownBy(o::getOrThrow)
        .isInstanceOf(JobFailedException.class)
        .hasCauseInstanceOf(IOException.class);
  }

  @Test
  void failureRejectsNullCause() {
    assertThatThrownBy(() -> Outcome.failure(null)).isInstanceOf(NullPointerException.class);
  }

  @Test
  void cancelledThrowsCancelledException() {
    Outcome<String> o = Outcome.cancelled();
    assertThat(o.isCancelled()).isTrue();
    assertThat(o.valueOrNull()).isNull();
    assertThat(o.failureOrNull()).isNull();
    assertThatThrownBy(o::getOrThrow).isInstanceOf(CancelledException.class);
    assertThat(((Outcome.Cancelled<String>) o).lateFailure()).isNull();
  }

  @Test
  void equalityFollowsContent() {
    assertThat(Outcome.success("a")).isEqualTo(Outcome.success("a"));
    assertThat(Outcome.success("a")).isNotEqualTo(Outcome.success("b"));
    IOException cause = new IOException();
    assertThat(Outcome.failure(cause)).isEqualTo(Outcome.failure(cause));
    assertThat(Outcome.failure(cause)).isNotEqualTo(Outcome.failure(new IOException()));
    assertThat(Outcome.cancelled()).isEqualTo(Outcome.cancelled());
    assertThat(Outcome.success("a")).isNotEqualTo(Outcome.cancelled());
  }
}
