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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LifecycleRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.codirex.clotho.Job;
import org.codirex.clotho.Lane;
import org.codirex.clotho.Realm;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * AndroidRealms with the real androidx lifecycle on the real main looper. The registry enforces the
 * main thread itself, so these tests also prove that the calls are made from the right thread.
 */
@RunWith(AndroidJUnit4.class)
public class AndroidRealmsInstrumentedTest {

  private static final class Owner implements LifecycleOwner {
    final LifecycleRegistry registry = new LifecycleRegistry(this);

    @Override
    public Lifecycle getLifecycle() {
      return registry;
    }
  }

  private static void onMain(Runnable task) throws Throwable {
    AtomicReference<Throwable> thrown = new AtomicReference<>();
    InstrumentationRegistry.getInstrumentation()
        .runOnMainSync(
            () -> {
              try {
                task.run();
              } catch (Throwable t) {
                thrown.set(t);
              }
            });
    if (thrown.get() != null) {
      throw thrown.get();
    }
  }

  @Test
  public void theRealmIsBoundOnceAndClosedOnlyAtDestroy() throws Throwable {
    onMain(
        () -> {
          Owner owner = new Owner();
          Realm realm = AndroidRealms.forLifecycle(owner);
          assertSame(realm, AndroidRealms.forLifecycle(owner));
          assertEquals(1, owner.registry.getObserverCount());
          owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE);
          owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_START);
          owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME);
          owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE);
          owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP);
          assertFalse(realm.isClosing());
          owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY);
          assertTrue(realm.isClosing());
          assertEquals(0, owner.registry.getObserverCount());
        });
  }

  @Test
  public void destroyCancelsRunningJobsWithoutBlockingTheMainThread() throws Throwable {
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch finish = new CountDownLatch(1);
    AtomicReference<Owner> ownerRef = new AtomicReference<>();
    AtomicReference<Realm> realmRef = new AtomicReference<>();
    AtomicReference<Job<String>> jobRef = new AtomicReference<>();
    onMain(
        () -> {
          Owner owner = new Owner();
          ownerRef.set(owner);
          Realm realm = AndroidRealms.forLifecycle(owner);
          realmRef.set(realm);
          owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE);
          jobRef.set(
              realm.launch(
                  Lane.io(),
                  cp -> {
                    started.countDown();
                    // ignores cancellation on purpose, like code that cannot stop at once
                    while (!finish.await(50, TimeUnit.MILLISECONDS)) {
                      // keep working
                    }
                    return "late";
                  }));
        });
    assertTrue(started.await(20, TimeUnit.SECONDS));
    onMain(
        () -> {
          // destroying returns at once although the job is still running
          ownerRef.get().registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY);
          Realm.Closing closing = realmRef.get().closeAsync();
          assertTrue(jobRef.get().isCancellationRequested());
          assertFalse(jobRef.get().isDone());
          assertFalse(closing.isClosed());
        });
    finish.countDown();
    assertTrue(realmRef.get().closeAsync().await(20, TimeUnit.SECONDS));
    assertTrue(jobRef.get().outcomeOrNull().isCancelled());
  }
}
