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

import android.content.Context;
import androidx.startup.Initializer;
import java.util.Collections;
import java.util.List;
import org.codirex.clotho.MainLane;

/** Installs the Android main lane at application start, through {@code androidx.startup}. */
public final class ClothoInitializer implements Initializer<MainLane> {

  @Override
  public MainLane create(Context context) {
    return AndroidMainLane.install();
  }

  @Override
  public List<Class<? extends Initializer<?>>> dependencies() {
    return Collections.emptyList();
  }
}
