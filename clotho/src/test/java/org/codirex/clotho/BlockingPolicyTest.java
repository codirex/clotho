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
import static org.codirex.clotho.support.TestKit.join;
import static org.codirex.clotho.support.TestKit.start;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.codirex.clotho.support.ManualLane;
import org.junit.jupiter.api.Test;

class BlockingPolicyTest {

  private final Realm realm = Realm.open("blocking");
  private final ManualLane allowed = new ManualLane(BlockingPolicy.ALLOWED);
  private final ManualLane forbidden = new ManualLane(BlockingPolicy.FORBIDDEN);

  private Job<String> done() throws Exception {
    Job<String> job = realm.launch(allowed, cp -> "done");
    allowed.runAll();
    return job;
  }

  @Test
  void awaitFromAForbiddenLaneThrows() throws Exception {
    Job<String> other = done();
    Job<String> job = realm.launch(forbidden, cp -> other.await().getOrThrow());
    forbidden.runAll();
    assertThat(job.await().failureOrNull()).isInstanceOf(BlockingNotAllowedException.class);
  }

  @Test
  void timedAwaitFromAForbiddenLaneThrowsToo() throws Exception {
    Job<String> other = done();
    Job<String> job = realm.launch(forbidden, cp -> other.await(1, TimeUnit.SECONDS).getOrThrow());
    forbidden.runAll();
    assertThat(job.await().failureOrNull()).isInstanceOf(BlockingNotAllowedException.class);
  }

  @Test
  void awaitFromAnAllowedLaneWorks() throws Exception {
    Job<String> other = done();
    Job<String> job = realm.launch(allowed, cp -> other.await().getOrThrow() + "!");
    allowed.runAll();
    assertThat(job.await()).isEqualTo(Outcome.success("done!"));
  }

  @Test
  void aDirectLaneInsideAForbiddenLaneKeepsTheForbiddenPolicy() throws Exception {
    Job<String> other = done();
    AtomicReference<Outcome<String>> inner = new AtomicReference<>();
    Job<String> job =
        realm.launch(
            forbidden,
            cp -> {
              Job<String> nested = realm.launch(Lane.direct(), c -> other.await().getOrThrow());
              inner.set(nested.await());
              return "outer";
            });
    forbidden.runAll();
    assertThat(job.await().failureOrNull()).isInstanceOf(BlockingNotAllowedException.class);
  }

  @Test
  void theDecisionNeverDependsOnTheThreadName() throws Exception {
    Job<String> other = done();
    AtomicReference<Outcome<String>> forbiddenResult = new AtomicReference<>();
    AtomicReference<Outcome<String>> allowedResult = new AtomicReference<>();
    Job<String> f = realm.launch(forbidden, cp -> other.await().getOrThrow());
    Job<String> a = realm.launch(allowed, cp -> other.await().getOrThrow());
    Thread tf = start("main", forbidden::runAll);
    join(tf);
    Thread ta = start("main", allowed::runAll);
    join(ta);
    forbiddenResult.set(f.outcomeOrNull());
    allowedResult.set(a.outcomeOrNull());
    assertThat(forbiddenResult.get().failureOrNull())
        .isInstanceOf(BlockingNotAllowedException.class);
    assertThat(allowedResult.get()).isEqualTo(Outcome.success("done"));
  }

  @Test
  void theContextIsRestoredAfterAForbiddenTask() throws Exception {
    Job<String> other = done();
    realm.launch(forbidden, cp -> "just run");
    forbidden.runAll();
    assertThat(other.await()).isEqualTo(Outcome.success("done"));
  }

  @Test
  void callbacksOnAForbiddenLaneCannotBlockEither() throws Exception {
    Job<String> other = done();
    AtomicReference<Throwable> seen = new AtomicReference<>();
    other.onComplete(
        forbidden,
        o -> {
          try {
            other.await();
          } catch (Throwable t) {
            seen.set(t);
          }
        });
    forbidden.runAll();
    assertThat(seen.get()).isInstanceOf(BlockingNotAllowedException.class);
  }
}
