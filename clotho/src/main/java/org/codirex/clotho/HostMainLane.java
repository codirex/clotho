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

import java.util.Objects;
import java.util.concurrent.RejectedExecutionException;

/** The {@link MainLane} built on a {@link MainThreadHost}. Not part of the public API. */
final class HostMainLane implements MainLane {

  private final String name;
  private final MainThreadHost host;
  private final Lane immediate = new Immediate();

  HostMainLane(String name, MainThreadHost host) {
    this.name = name;
    this.host = host;
  }

  @Override
  public boolean isMainThread() {
    return host.isMainThread();
  }

  @Override
  public void execute(Runnable task) {
    Objects.requireNonNull(task, "task");
    try {
      host.post(new Posted(task));
    } catch (LaneClosedException e) {
      throw e;
    } catch (RejectedExecutionException e) {
      throw new LaneClosedException(this + ": the host stopped accepting tasks", e);
    }
  }

  @Override
  public Lane immediate() {
    return immediate;
  }

  @Override
  public String toString() {
    return "MainLane(" + name + ")";
  }

  /** A task on its way to the main thread. */
  private final class Posted implements Runnable, Lane.Discardable {
    private final Runnable delegate;

    Posted(Runnable delegate) {
      this.delegate = delegate;
    }

    @Override
    public void run() {
      // An exception of a raw task propagates to the host loop; the context is always restored.
      LaneContext.runIn(HostMainLane.this, delegate);
    }

    @Override
    public void discarded(Throwable reason) {
      if (delegate instanceof Lane.Discardable discardable) {
        discardable.discarded(reason);
      } else {
        // A raw task cannot be told: make the loss visible instead of dropping it silently.
        reason.printStackTrace();
      }
    }
  }

  private final class Immediate implements Lane {
    @Override
    public void execute(Runnable task) {
      Objects.requireNonNull(task, "task");
      if (host.isMainThread()) {
        new Posted(task).run();
      } else {
        HostMainLane.this.execute(task);
      }
    }

    @Override
    public BlockingPolicy blockingPolicy() {
      return BlockingPolicy.FORBIDDEN;
    }

    @Override
    public String toString() {
      return HostMainLane.this + ".immediate()";
    }
  }
}
