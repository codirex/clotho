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

import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class TestClockTest {

  @Test
  void itOnlyMovesWhenTheTestMovesIt() {
    TestClock clock = new TestClock(5);
    assertThat(clock.nanos()).isEqualTo(5);
    assertThat(clock.getAsLong()).isEqualTo(5);
    clock.advance(2, TimeUnit.MILLISECONDS);
    assertThat(clock.nanos()).isEqualTo(5 + 2_000_000);
    clock.set(10_000_000);
    assertThat(clock.nanos()).isEqualTo(10_000_000);
  }

  @Test
  void itNeverGoesBackwards() {
    TestClock clock = new TestClock(100);
    assertThatThrownBy(() -> clock.advance(-1, TimeUnit.NANOSECONDS))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> clock.set(99)).isInstanceOf(IllegalArgumentException.class);
    clock.set(100);
    assertThat(clock.nanos()).isEqualTo(100);
  }
}
