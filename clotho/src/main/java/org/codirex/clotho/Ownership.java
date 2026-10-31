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

/**
 * Who decides the cancellation of the inputs of a composite job such as {@link Realm#zip zip} or
 * {@link Realm#race race}.
 *
 * <p>Ownership is separate from realm membership. Every job belongs to exactly one realm for its
 * whole life, and the realm waits for it and cancels it when told to. Ownership only answers: when
 * a composite finishes early or is cancelled, may it cancel its inputs?
 */
public enum Ownership {
  /**
   * The composite owns its inputs. When it finishes early (a failure in zip, a winner in race) or
   * is cancelled, it cancels the inputs that are still running. Do not pass jobs that other code
   * depends on.
   */
  OWNED,
  /**
   * The composite only observes its inputs. It never cancels them; they keep running under their
   * own realm and their results are simply ignored once the composite has finished.
   */
  BORROWED
}
