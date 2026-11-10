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
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.codirex.clotho.support.ManualLane;
import org.junit.jupiter.api.Test;

class LaneContextTest {

  @Test
  void capturedAbsenceTemporarilyClearsAForbiddenContext() {
    Lane forbidden = new ManualLane(BlockingPolicy.FORBIDDEN);
    LaneContext.runIn(
        forbidden,
        () -> {
          LaneContext.runWith(
              null,
              () -> {
                assertThat(LaneContext.current()).isNull();
                assertThatCode(LaneContext::checkMayBlock).doesNotThrowAnyException();
              });
          assertThat(LaneContext.current()).isSameAs(forbidden);
          assertThatThrownBy(LaneContext::checkMayBlock)
              .isInstanceOf(BlockingNotAllowedException.class);
        });
    assertThat(LaneContext.current()).isNull();
  }

  @Test
  void capturedForbiddenContextOverridesAllowedContextAndRestoresItAfterFailure() {
    Lane allowed = new ManualLane(BlockingPolicy.ALLOWED);
    Lane forbidden = new ManualLane(BlockingPolicy.FORBIDDEN);
    RuntimeException failure = new IllegalStateException("callback");
    LaneContext.runIn(
        allowed,
        () -> {
          assertThatThrownBy(
                  () ->
                      LaneContext.runWith(
                          forbidden,
                          () -> {
                            assertThat(LaneContext.current()).isSameAs(forbidden);
                            assertThatThrownBy(LaneContext::checkMayBlock)
                                .isInstanceOf(BlockingNotAllowedException.class);
                            throw failure;
                          }))
              .isSameAs(failure);
          assertThat(LaneContext.current()).isSameAs(allowed);
          assertThatCode(LaneContext::checkMayBlock).doesNotThrowAnyException();
        });
    assertThat(LaneContext.current()).isNull();
  }

  @Test
  void capturedContextIsRemovedAfterFailureWhenThereWasNoPreviousContext() {
    Lane captured = new ManualLane(BlockingPolicy.ALLOWED);
    RuntimeException failure = new IllegalArgumentException("callback");
    assertThatThrownBy(
            () ->
                LaneContext.runWith(
                    captured,
                    () -> {
                      assertThat(LaneContext.current()).isSameAs(captured);
                      throw failure;
                    }))
        .isSameAs(failure);
    assertThat(LaneContext.current()).isNull();
  }

  @Test
  void directLaneInheritsTheExplicitlyRestoredContext() {
    Lane captured = new ManualLane(BlockingPolicy.FORBIDDEN);
    LaneContext.runWith(
        captured,
        () ->
            LaneContext.runIn(
                Lane.direct(), () -> assertThat(LaneContext.current()).isSameAs(captured)));
    LaneContext.runWith(
        null,
        () -> LaneContext.runIn(Lane.direct(), () -> assertThat(LaneContext.current()).isNull()));
    assertThat(LaneContext.current()).isNull();
  }
}
