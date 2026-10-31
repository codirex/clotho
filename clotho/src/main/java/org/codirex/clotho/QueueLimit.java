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

/** The capacity of a lane queue and the {@link RejectionPolicy} applied when it is full. */
public final class QueueLimit {

  private static final QueueLimit UNBOUNDED = new QueueLimit(0, RejectionPolicy.REJECT);

  private final int capacity;
  private final RejectionPolicy policy;

  private QueueLimit(int capacity, RejectionPolicy policy) {
    this.capacity = capacity;
    this.policy = policy;
  }

  /**
   * Returns the limit of an unbounded queue.
   *
   * @return the shared unbounded limit
   */
  public static QueueLimit unbounded() {
    return UNBOUNDED;
  }

  /**
   * Creates a bounded limit.
   *
   * @param capacity the maximum number of waiting tasks, at least 1
   * @param policy what to do when the queue is full
   * @return the limit
   * @throws IllegalArgumentException if {@code capacity} is less than 1
   */
  public static QueueLimit of(int capacity, RejectionPolicy policy) {
    if (capacity < 1) {
      throw new IllegalArgumentException("capacity must be at least 1");
    }
    return new QueueLimit(capacity, Objects.requireNonNull(policy, "policy"));
  }

  /**
   * Returns the capacity.
   *
   * @return the maximum number of waiting tasks, or 0 if unbounded
   */
  public int capacity() {
    return capacity;
  }

  /**
   * Returns the rejection policy.
   *
   * @return the policy (meaningless when unbounded)
   */
  public RejectionPolicy policy() {
    return policy;
  }

  @Override
  public String toString() {
    return capacity == 0 ? "unbounded" : capacity + "/" + policy;
  }
}
