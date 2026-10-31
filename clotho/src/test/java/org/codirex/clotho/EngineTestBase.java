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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;

/** Tracks engines created by a test and shuts them down afterwards. */
abstract class EngineTestBase {

  private final List<Engine> engines = new ArrayList<>();

  Engine.Builder builder() {
    return Engine.builder().name("t" + engines.size());
  }

  Engine track(Engine engine) {
    engines.add(engine);
    return engine;
  }

  @AfterEach
  void shutDownEngines() throws Exception {
    for (Engine engine : engines) {
      engine.shutdownNow();
      if (!engine.awaitTermination(20, TimeUnit.SECONDS)) {
        throw new AssertionError("engine " + engine + " did not terminate");
      }
    }
  }

  /** A task that signals when it runs and then blocks until released. */
  static final class Gate {
    final CountDownLatch started = new CountDownLatch(1);
    final CountDownLatch release = new CountDownLatch(1);

    Work<String> work() {
      return cp -> {
        started.countDown();
        release.await();
        return "gate";
      };
    }

    /** Like {@link #work()} but keeps waiting when interrupted. */
    Work<String> stubbornWork() {
      return cp -> {
        started.countDown();
        for (; ; ) {
          try {
            release.await();
            return "stubborn";
          } catch (InterruptedException e) {
            // ignore on purpose
          }
        }
      };
    }

    void awaitStarted() throws InterruptedException {
      if (!started.await(20, TimeUnit.SECONDS)) {
        throw new AssertionError("gate task never started");
      }
    }

    void open() {
      release.countDown();
    }
  }
}
