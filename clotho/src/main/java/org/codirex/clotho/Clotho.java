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
 * Entry point of the Clotho framework.
 *
 * <p>This class is intentionally platform neutral. Platform support, such as the Android main
 * thread, is attached through service providers or explicit installation and never changes this
 * class.
 */
public final class Clotho {

  private static volatile UncaughtHandler defaultHandler = UncaughtHandler.stderr();

  private Clotho() {}

  /**
   * Returns the handler used by realms that were not given their own.
   *
   * @return the default handler, never {@code null}
   */
  public static UncaughtHandler defaultUncaughtHandler() {
    return defaultHandler;
  }

  /**
   * Replaces the handler used by realms that were not given their own.
   *
   * @param handler the new default handler, not {@code null}
   */
  public static void setDefaultUncaughtHandler(UncaughtHandler handler) {
    defaultHandler = java.util.Objects.requireNonNull(handler, "handler");
  }

  /**
   * Installs the main lane used by {@link Lane#main()}.
   *
   * <p>Installing the same instance again does nothing. Installing a different one while another is
   * installed fails, so two libraries cannot silently fight over the main thread.
   *
   * @param lane the main lane, not {@code null}
   * @throws IllegalStateException if a different main lane is already installed
   */
  public static void install(MainLane lane) {
    MainLaneRegistry.install(lane);
  }

  /**
   * Returns the installed main lane without searching for providers.
   *
   * @return the installed lane, or {@code null}
   */
  public static MainLane installedMainLane() {
    return MainLaneRegistry.peek();
  }

  /**
   * Removes the installed main lane. Meant for tests; jobs already tied to the old lane keep
   * working with it.
   */
  public static void uninstallMainLane() {
    MainLaneRegistry.uninstall();
  }
}
