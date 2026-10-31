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

import android.os.Handler;
import android.os.Looper;
import java.util.concurrent.RejectedExecutionException;
import org.codirex.clotho.MainThreadHost;

/** The Android main looper as a {@link MainThreadHost}. Not part of the public API. */
final class AndroidMainThreadHost implements MainThreadHost {

  private volatile Handler handler;

  @Override
  public boolean isMainThread() {
    return Looper.getMainLooper().isCurrentThread();
  }

  @Override
  public void post(Runnable task) {
    if (!handler().post(task)) {
      throw new RejectedExecutionException("the main looper is exiting");
    }
  }

  /** One handler for the main looper; recreated only if the main looper itself was replaced. */
  private Handler handler() {
    Looper main = Looper.getMainLooper();
    Handler current = handler;
    if (current == null || current.getLooper() != main) {
      current = new Handler(main);
      handler = current;
    }
    return current;
  }
}
