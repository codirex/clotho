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

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Covers a submission racing with the last worker's keep-alive retirement. */
class EngineWorkerRetirementRaceTest extends EngineTestBase {

  @Test
  void submissionAcceptedAfterRetirementDecisionIsHandedToAReplacement() throws Exception {
    CountDownLatch retiring = new CountDownLatch(1);
    CountDownLatch releaseRetirement = new CountDownLatch(1);
    CountDownLatch submittedTaskRan = new CountDownLatch(1);
    AtomicInteger runs = new AtomicInteger();

    Engine engine =
        track(
            builder()
                .ioMaxThreads(1)
                .ioKeepAlive(1, TimeUnit.MILLISECONDS)
                .workerRetirementHook(
                    () -> {
                      retiring.countDown();
                      try {
                        if (!releaseRetirement.await(20, TimeUnit.SECONDS)) {
                          throw new AssertionError("retirement gate was never opened");
                        }
                      } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError("interrupted at retirement gate", e);
                      }
                    })
                .build());

    try {
      // Start the only worker. It becomes idle and commits to retirement after keep-alive.
      engine.io().execute(() -> {});
      assertThat(retiring.await(20, TimeUnit.SECONDS)).isTrue();
      assertThat(engine.liveThreads()).isEqualTo(1);

      // The retiring worker has already left the idle stack but is still counted. The submission
      // is accepted, but cannot start a second worker while the configured maximum is one.
      engine.io().execute(
          () -> {
            runs.incrementAndGet();
            submittedTaskRan.countDown();
          });
      assertThat(engine.liveThreads()).isEqualTo(1);
      assertThat(submittedTaskRan.getCount()).isEqualTo(1);
    } finally {
      releaseRetirement.countDown();
    }

    assertThat(submittedTaskRan.await(20, TimeUnit.SECONDS)).isTrue();
    engine.shutdown();
    assertThat(engine.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
    assertThat(runs.get()).isEqualTo(1);
    assertThat(engine.liveThreads()).isZero();
  }
}
