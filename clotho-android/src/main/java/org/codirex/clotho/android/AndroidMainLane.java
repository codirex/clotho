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
package org.codirex.clotho.android;

import org.codirex.clotho.Clotho;
import org.codirex.clotho.MainLane;

/**
 * The Clotho main lane for Android, backed by the main {@link android.os.Looper}.
 *
 * <p>It is installed automatically through {@code androidx.startup} before the application starts.
 * Call {@link #install()} yourself only if you removed that initializer from your manifest.
 */
public final class AndroidMainLane {

  private static final Object LOCK = new Object();
  private static MainLane lane;

  private AndroidMainLane() {}

  /**
   * Returns the Android main lane without installing it.
   *
   * @return the lane, always the same instance
   */
  public static MainLane get() {
    synchronized (LOCK) {
      if (lane == null) {
        lane = MainLane.of("android", new AndroidMainThreadHost());
      }
      return lane;
    }
  }

  /**
   * Installs the Android main lane as {@code Lane.main()}. Calling it again does nothing.
   *
   * @return the installed lane
   * @throws IllegalStateException if a different main lane is already installed
   */
  public static MainLane install() {
    MainLane main = get();
    Clotho.install(main);
    return main;
  }
}
