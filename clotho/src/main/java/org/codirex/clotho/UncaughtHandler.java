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
 * Receives errors that cannot be delivered through a job: exceptions thrown by completion
 * callbacks, jobs that were still running after a close timeout, and the failure that triggered a
 * {@link RealmPolicy#FAIL_FAST} realm.
 *
 * <p>If the handler itself throws, the error is printed to the standard error stream. That is the
 * only place where Clotho prints, to avoid error loops.
 */
@FunctionalInterface
public interface UncaughtHandler {

  /**
   * Handles an error.
   *
   * @param realm the realm in which the error happened
   * @param error the error
   */
  void uncaught(Realm realm, Throwable error);

  /**
   * Returns a handler that prints the realm name and the stack trace to the standard error stream.
   *
   * @return the printing handler
   */
  static UncaughtHandler stderr() {
    return (realm, error) -> {
      System.err.println("Clotho: uncaught error in realm '" + realm.name() + "'");
      error.printStackTrace();
    };
  }
}
