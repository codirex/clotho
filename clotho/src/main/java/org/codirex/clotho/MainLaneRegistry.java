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

import java.util.ArrayList;
import java.util.List;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.concurrent.atomic.AtomicReference;

/** Holds the installed main lane. Not part of the public API. */
final class MainLaneRegistry {

  private static final AtomicReference<MainLane> INSTALLED = new AtomicReference<>();

  private MainLaneRegistry() {}

  /** Returns the installed lane, or null. Never triggers discovery. */
  static MainLane peek() {
    return INSTALLED.get();
  }

  static void install(MainLane lane) {
    if (lane == null) {
      throw new NullPointerException("lane");
    }
    if (INSTALLED.get() == lane) {
      return;
    }
    if (!INSTALLED.compareAndSet(null, lane)) {
      throw new IllegalStateException("a main lane is already installed: " + INSTALLED.get());
    }
  }

  static void uninstall() {
    INSTALLED.set(null);
  }

  /** Returns the installed lane, discovering a {@link MainThreadHost} provider if none is. */
  static MainLane require(ClassLoader loader) {
    MainLane installed = INSTALLED.get();
    if (installed != null) {
      return installed;
    }
    MainThreadHost host = discover(loader);
    if (host == null) {
      throw new MainLaneNotInstalledException(
          "No main lane is installed. Call Clotho.install(MainLane.of(name, host)), or provide a "
              + "MainThreadHost in META-INF/services/org.codirex.clotho.MainThreadHost. On "
              + "Android the clotho-android module installs one through androidx.startup; if you "
              + "disabled that initializer, call AndroidMainLane.install().");
    }
    INSTALLED.compareAndSet(null, MainLane.of("discovered", host));
    return INSTALLED.get();
  }

  /**
   * Looks for a {@link MainThreadHost} service. Returns null if there is none; throws if there are
   * several or a provider cannot be loaded.
   */
  static MainThreadHost discover(ClassLoader loader) {
    List<MainThreadHost> found = new ArrayList<>();
    try {
      for (MainThreadHost host : ServiceLoader.load(MainThreadHost.class, loader)) {
        found.add(host);
      }
    } catch (ServiceConfigurationError e) {
      throw new IllegalStateException(
          "cannot load a MainThreadHost provider: " + e.getMessage(), e);
    }
    if (found.isEmpty()) {
      return null;
    }
    if (found.size() > 1) {
      StringBuilder names = new StringBuilder();
      for (MainThreadHost host : found) {
        if (names.length() > 0) {
          names.append(", ");
        }
        names.append(host.getClass().getName());
      }
      throw new IllegalStateException(
          "several MainThreadHost providers found, install one explicitly: " + names);
    }
    return found.get(0);
  }
}
