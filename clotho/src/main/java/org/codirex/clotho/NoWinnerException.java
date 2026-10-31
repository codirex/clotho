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
 * The failure of a race in which no participant succeeded and at least one failed.
 *
 * <p>The cause is the failure of the first failed participant (in list order); the failures of the
 * remaining failed participants are attached as suppressed exceptions, also in list order.
 */
public final class NoWinnerException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param message the detail message
   * @param cause the failure of the first failed participant
   */
  public NoWinnerException(String message, Throwable cause) {
    super(message, cause);
  }
}
