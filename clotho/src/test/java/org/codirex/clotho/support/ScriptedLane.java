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
package org.codirex.clotho.support;

import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import org.codirex.clotho.DroppedException;
import org.codirex.clotho.Lane;
import org.codirex.clotho.LaneClosedException;

/** A lane whose reaction to a task is chosen by the test. */
public final class ScriptedLane implements Lane {

  /** What the lane does with the next task. */
  public enum Mode {
    ACCEPT,
    FULL,
    CLOSED,
    DISCARD_OVERFLOW,
    DISCARD_SHUTDOWN
  }

  private final Lane delegate;
  private volatile Mode mode = Mode.ACCEPT;
  private final AtomicInteger calls = new AtomicInteger();

  public ScriptedLane(Lane delegate) {
    this.delegate = delegate;
  }

  public void mode(Mode mode) {
    this.mode = mode;
  }

  public int calls() {
    return calls.get();
  }

  @Override
  public void execute(Runnable task) {
    calls.incrementAndGet();
    switch (mode) {
      case ACCEPT:
        delegate.execute(task);
        return;
      case FULL:
        throw new RejectedExecutionException("full");
      case CLOSED:
        throw new LaneClosedException("closed");
      case DISCARD_OVERFLOW:
        ((Lane.Discardable) task).discarded(new DroppedException("overflow"));
        return;
      case DISCARD_SHUTDOWN:
        ((Lane.Discardable) task)
            .discarded(new DroppedException("shutdown", DroppedException.Reason.SHUTDOWN));
        return;
      default:
        throw new AssertionError(mode);
    }
  }
}
