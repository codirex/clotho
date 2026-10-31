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
package org.codirex.clotho.test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.codirex.clotho.Realm;
import org.codirex.clotho.UncaughtHandler;

/** An {@link UncaughtHandler} that remembers every error, for assertions. */
public final class RecordingUncaughtHandler implements UncaughtHandler {

  private final List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());

  /** Creates a handler with no recorded errors. */
  public RecordingUncaughtHandler() {}

  @Override
  public void uncaught(Realm realm, Throwable error) {
    errors.add(error);
  }

  /**
   * Returns the errors received so far, oldest first.
   *
   * @return a snapshot
   */
  public List<Throwable> errors() {
    synchronized (errors) {
      return new ArrayList<>(errors);
    }
  }

  /** Forgets the errors received so far. */
  public void clear() {
    errors.clear();
  }
}
