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
package org.codirex.clotho.sample;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;
import org.codirex.clotho.Clotho;
import org.codirex.clotho.Lane;
import org.codirex.clotho.Realm;

/**
 * Shows the main lane: work runs on the I/O lane and the result comes back to the main thread. The
 * realm is closed in onDestroy with closeAsync(), which never blocks the main thread.
 */
public class MainActivity extends Activity {

  private final Realm realm = Realm.open("main-activity");

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    TextView view = new TextView(this);
    view.setPadding(48, 96, 48, 48);
    view.setText("Clotho " + Clotho.version() + ": working...");
    setContentView(view);

    realm
        .launch(Lane.io(), cp -> "computed on " + Thread.currentThread().getName())
        .then(
            Lane.main(),
            (text, cp) -> {
              view.setText(
                  "Clotho " + Clotho.version() + "\n" + text + "\nshown on the main thread");
              return text;
            });
  }

  @Override
  protected void onDestroy() {
    realm.closeAsync();
    super.onDestroy();
  }
}
