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

import static org.assertj.core.api.Assertions.assertThat;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import androidx.startup.AppInitializer;
import org.codirex.clotho.Clotho;
import org.codirex.clotho.MainLane;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

@RunWith(RobolectricTestRunner.class)
public class ClothoInitializerTest {

  @After
  public void tearDown() {
    Clotho.uninstallMainLane();
  }

  @Test
  public void createInstallsTheAndroidMainLane() {
    Context app = RuntimeEnvironment.getApplication();
    assertThat(Clotho.installedMainLane()).isNull();
    MainLane created = new ClothoInitializer().create(app);
    assertThat(created).isSameAs(AndroidMainLane.get());
    assertThat(Clotho.installedMainLane()).isSameAs(created);
  }

  @Test
  public void createIsIdempotent() {
    Context app = RuntimeEnvironment.getApplication();
    MainLane first = new ClothoInitializer().create(app);
    MainLane second = new ClothoInitializer().create(app);
    assertThat(second).isSameAs(first);
  }

  @Test
  public void thereAreNoDependenciesOnOtherInitializers() {
    assertThat(new ClothoInitializer().dependencies()).isEmpty();
  }

  @Test
  public void androidxStartupCanRunIt() {
    Context app = RuntimeEnvironment.getApplication();
    // AppInitializer caches results for the process, so only the returned lane is checked here.
    MainLane lane = AppInitializer.getInstance(app).initializeComponent(ClothoInitializer.class);
    assertThat(lane).isSameAs(AndroidMainLane.get());
  }

  @Test
  public void theManifestDeclaresTheInitializerForStartup() throws Exception {
    Context app = RuntimeEnvironment.getApplication();
    ComponentName provider = new ComponentName(app, "androidx.startup.InitializationProvider");
    ProviderInfo info =
        app.getPackageManager().getProviderInfo(provider, PackageManager.GET_META_DATA);
    assertThat(info.metaData).isNotNull();
    assertThat(info.metaData.getString(ClothoInitializer.class.getName()))
        .isEqualTo("androidx.startup");
  }
}
