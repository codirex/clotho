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

/** Checkpoint that never reports cancellation. Not part of the public API. */
final class NeverCancelled implements Checkpoint {

  static final NeverCancelled INSTANCE = new NeverCancelled();

  private NeverCancelled() {}

  @Override
  public boolean isCancelled() {
    return false;
  }

  @Override
  public void check() {}
}
