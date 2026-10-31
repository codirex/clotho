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

/** Whether blocking calls such as {@link Job#await()} are allowed on a {@link Lane}. */
public enum BlockingPolicy {
  /** Work running on the lane may block. */
  ALLOWED,
  /** Work running on the lane must not block; {@link Job#await()} throws. */
  FORBIDDEN,
  /** The lane runs work in the caller's context and keeps the caller's policy. */
  INHERIT
}
