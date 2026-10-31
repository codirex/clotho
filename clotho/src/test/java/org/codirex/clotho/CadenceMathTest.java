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

import org.junit.jupiter.api.Test;

class CadenceMathTest {

  @Test
  void fixedRateKeepsTheOriginalScheduleAndSkipsMissedSlots() {
    long first = 1_000;
    long period = 100;
    // after slot 0 was served:
    assertThat(CadenceMath.nextFixedRateIndex(first, period, 0, 500)).isEqualTo(1);
    assertThat(CadenceMath.nextFixedRateIndex(first, period, 0, 1_000)).isEqualTo(1);
    assertThat(CadenceMath.nextFixedRateIndex(first, period, 0, 1_001)).isEqualTo(1);
    assertThat(CadenceMath.nextFixedRateIndex(first, period, 0, 1_100)).isEqualTo(1);
    assertThat(CadenceMath.nextFixedRateIndex(first, period, 0, 1_130)).isEqualTo(2);
    // a run that took 2.5 periods: slots 1 and 2 are skipped, there is no burst
    assertThat(CadenceMath.nextFixedRateIndex(first, period, 0, 1_250)).isEqualTo(3);
    assertThat(CadenceMath.nextFixedRateIndex(first, period, 0, 1_000 + 100 * 5_000 + 1))
        .isEqualTo(5_001);
    // a slot is never served twice, whatever the clock says
    assertThat(CadenceMath.nextFixedRateIndex(first, period, 7, 1_000)).isEqualTo(8);
    assertThat(CadenceMath.nextFixedRateIndex(first, period, 7, 1_750)).isEqualTo(8);
    assertThat(CadenceMath.slotTime(first, period, 3)).isEqualTo(1_300);
  }

  @Test
  void fixedDelayIsOnePeriodAfterTheEnd() {
    assertThat(CadenceMath.nextFixedDelay(777, 50)).isEqualTo(827);
  }

  @Test
  void arithmeticSaturatesInsteadOfOverflowing() {
    assertThat(CadenceMath.saturatedAdd(Long.MAX_VALUE, 1)).isEqualTo(Long.MAX_VALUE);
    assertThat(CadenceMath.saturatedAdd(Long.MIN_VALUE, -1)).isEqualTo(Long.MIN_VALUE);
    assertThat(CadenceMath.saturatedAdd(5, 6)).isEqualTo(11);
    assertThat(CadenceMath.saturatedMultiply(Long.MAX_VALUE, 2)).isEqualTo(Long.MAX_VALUE);
    assertThat(CadenceMath.saturatedMultiply(Long.MAX_VALUE, -2)).isEqualTo(Long.MIN_VALUE);
    assertThat(CadenceMath.saturatedMultiply(6, 7)).isEqualTo(42);
    assertThat(CadenceMath.deadline(10, Long.MAX_VALUE))
        .isEqualTo(10 + CadenceMath.MAX_DELAY_NANOS);
    assertThat(CadenceMath.deadline(10, -5)).isEqualTo(10);
    assertThat(CadenceMath.deadline(Long.MAX_VALUE - 1, 100) > 0).isTrue();
  }
}
