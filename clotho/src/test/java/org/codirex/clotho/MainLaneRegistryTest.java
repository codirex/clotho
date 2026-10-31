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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.codirex.clotho.support.BrokenHost;
import org.codirex.clotho.support.ServiceHostA;
import org.codirex.clotho.support.ServiceHostB;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class MainLaneRegistryTest {

  private final MainThreadHost host =
      new MainThreadHost() {
        @Override
        public boolean isMainThread() {
          return false;
        }

        @Override
        public void post(Runnable task) {
          task.run();
        }
      };

  @AfterEach
  void reset() {
    Clotho.uninstallMainLane();
  }

  /** A class loader that sees a META-INF/services file listing the given providers. */
  private static ClassLoader loaderWith(String... providers) throws Exception {
    Path dir = Files.createTempDirectory("clotho-services");
    Path services = dir.resolve("META-INF").resolve("services");
    Files.createDirectories(services);
    if (providers.length > 0) {
      Files.write(
          services.resolve(MainThreadHost.class.getName()),
          String.join("\n", providers).getBytes(StandardCharsets.UTF_8));
    }
    File file = dir.toFile();
    return new URLClassLoader(
        new URL[] {file.toURI().toURL()}, MainLaneRegistryTest.class.getClassLoader());
  }

  @Test
  void installIsIdempotentForTheSameLaneAndRefusesAnotherOne() {
    MainLane first = MainLane.of("first", host);
    MainLane second = MainLane.of("second", host);
    assertThat(Clotho.installedMainLane()).isNull();
    Clotho.install(first);
    Clotho.install(first);
    assertThat(Clotho.installedMainLane()).isSameAs(first);
    assertThatThrownBy(() -> Clotho.install(second)).isInstanceOf(IllegalStateException.class);
    assertThat(Clotho.installedMainLane()).isSameAs(first);
    assertThat(Lane.main()).isSameAs(first);
  }

  @Test
  void installRejectsNullAndUninstallAllowsAReplacement() {
    assertThatThrownBy(() -> Clotho.install(null)).isInstanceOf(NullPointerException.class);
    MainLane first = MainLane.of("first", host);
    Clotho.install(first);
    Clotho.uninstallMainLane();
    assertThat(Clotho.installedMainLane()).isNull();
    MainLane second = MainLane.of("second", host);
    Clotho.install(second);
    assertThat(Lane.main()).isSameAs(second);
  }

  @Test
  void laneMainExplainsHowToInstallWhenNothingIsAvailable() {
    assertThatThrownBy(Lane::main).isInstanceOf(MainLaneNotInstalledException.class);
    try {
      Lane.main();
    } catch (MainLaneNotInstalledException e) {
      assertThat(e.getMessage().contains("Clotho.install")).isTrue();
      assertThat(e.getMessage().contains("META-INF/services")).isTrue();
    }
    assertThat(Clotho.installedMainLane()).isNull();
  }

  @Test
  void discoverFindsNothingOneOrSeveralProviders() throws Exception {
    assertThat(MainLaneRegistry.discover(loaderWith())).isNull();
    MainThreadHost one = MainLaneRegistry.discover(loaderWith(ServiceHostA.class.getName()));
    assertThat(one).isInstanceOf(ServiceHostA.class);
    assertThatThrownBy(
            () ->
                MainLaneRegistry.discover(
                    loaderWith(ServiceHostA.class.getName(), ServiceHostB.class.getName())))
        .isInstanceOf(IllegalStateException.class);
    try {
      MainLaneRegistry.discover(
          loaderWith(ServiceHostA.class.getName(), ServiceHostB.class.getName()));
    } catch (IllegalStateException e) {
      assertThat(e.getMessage().contains("ServiceHostA")).isTrue();
      assertThat(e.getMessage().contains("ServiceHostB")).isTrue();
    }
  }

  @Test
  void aProviderThatCannotBeLoadedIsReportedWithItsCause() throws Exception {
    ClassLoader loader = loaderWith(BrokenHost.class.getName());
    assertThatThrownBy(() -> MainLaneRegistry.discover(loader))
        .isInstanceOf(IllegalStateException.class)
        .hasCauseInstanceOf(java.util.ServiceConfigurationError.class);
  }

  @Test
  void requireInstallsTheDiscoveredHostOnceAndKeepsIt() throws Exception {
    ClassLoader loader = loaderWith(ServiceHostA.class.getName());
    MainLane lane = MainLaneRegistry.require(loader);
    assertThat(lane).isNotNull();
    assertThat(Clotho.installedMainLane()).isSameAs(lane);
    assertThat(MainLaneRegistry.require(loader)).isSameAs(lane);
  }

  @Test
  void anExplicitInstallWinsOverDiscovery() throws Exception {
    MainLane explicit = MainLane.of("explicit", host);
    Clotho.install(explicit);
    ClassLoader loader = loaderWith(ServiceHostA.class.getName(), ServiceHostB.class.getName());
    assertThat(MainLaneRegistry.require(loader)).isSameAs(explicit);
  }
}
