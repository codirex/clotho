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

import java.util.ArrayDeque;

/**
 * Lane that runs tasks inline. Reentrant submissions are drained iteratively on the same thread,
 * before the outermost execution returns. Not part of the public API.
 */
final class DirectLane implements Lane {

  static final DirectLane INSTANCE = new DirectLane();

  private static final ThreadLocal<ArrayDeque<Runnable>> QUEUE = new ThreadLocal<>();

  private DirectLane() {}

  @Override
  public void execute(Runnable task) {
    ArrayDeque<Runnable> queue = QUEUE.get();
    if (queue != null) {
      queue.addLast(task);
      return;
    }

    queue = new ArrayDeque<>();
    QUEUE.set(queue);
    queue.addLast(task);
    Throwable failure = null;
    try {
      Runnable next;
      while ((next = queue.pollFirst()) != null) {
        try {
          next.run();
        } catch (Throwable t) {
          if (failure == null) {
            failure = t;
          } else if (failure != t) {
            failure.addSuppressed(t);
          }
        }
      }
    } finally {
      QUEUE.remove();
    }

    if (failure instanceof RuntimeException runtime) {
      throw runtime;
    }
    if (failure instanceof Error error) {
      throw error;
    }
    if (failure != null) {
      throw new AssertionError(failure);
    }
  }

  @Override
  public BlockingPolicy blockingPolicy() {
    return BlockingPolicy.INHERIT;
  }

  @Override
  public String toString() {
    return "Lane.direct()";
  }
}
