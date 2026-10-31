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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.os.Looper;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.codirex.clotho.BlockingNotAllowedException;
import org.codirex.clotho.Clotho;
import org.codirex.clotho.Job;
import org.codirex.clotho.Lane;
import org.codirex.clotho.MainLane;
import org.codirex.clotho.Outcome;
import org.codirex.clotho.Realm;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Runs on a device or emulator, with the real main looper and the real manifest merge. The test
 * thread of the instrumentation is not the main thread.
 */
@RunWith(AndroidJUnit4.class)
public class AndroidMainLaneInstrumentedTest {

  /** Runs the task on the main thread and rethrows what it threw. */
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
  public void androidxStartupInstalledTheMainLaneBeforeTheTestsRan() {
    // Nobody called install() here: the manifest entry of the library did it at process start.
    assertNotNull(Clotho.installedMainLane());
    assertSame(AndroidMainLane.get(), Clotho.installedMainLane());
    assertSame(AndroidMainLane.get(), Lane.main());
  }

  @Test
  public void tasksRunOnTheRealMainThreadInSubmissionOrder() throws Exception {
    MainLane lane = Lane.main();
    List<Integer> order = Collections.synchronizedList(new ArrayList<>());
    AtomicReference<Thread> ranOn = new AtomicReference<>();
    CountDownLatch done = new CountDownLatch(100);
    for (int i = 0; i < 100; i++) {
      int id = i;
      lane.execute(
          () -> {
            order.add(id);
            ranOn.set(Thread.currentThread());
            done.countDown();
          });
    }
    assertTrue(done.await(20, TimeUnit.SECONDS));
    assertSame(Looper.getMainLooper().getThread(), ranOn.get());
    for (int i = 0; i < 100; i++) {
      assertEquals(Integer.valueOf(i), order.get(i));
    }
    assertFalse(lane.isMainThread());
  }

  @Test
  public void executePostsEvenFromTheMainThreadAndImmediateRunsInline() throws Throwable {
    MainLane lane = Lane.main();
    onMain(
        () -> {
          assertTrue(lane.isMainThread());
          List<String> order = new ArrayList<>();
          lane.execute(() -> order.add("posted"));
          lane.immediate().execute(() -> order.add("immediate"));
          order.add("after");
          // the posted task has not run yet: it waits for the loop
          assertEquals(2, order.size());
          assertEquals("immediate", order.get(0));
          assertEquals("after", order.get(1));
        });
  }

  @Test
  public void aJobGoesFromTheIoLaneBackToTheMainThread() throws Exception {
    Realm realm = Realm.open("instrumented");
    AtomicReference<Thread> producedOn = new AtomicReference<>();
    AtomicReference<Thread> shownOn = new AtomicReference<>();
    Job<Integer> job =
        realm
            .launch(
                Lane.io(),
                cp -> {
                  producedOn.set(Thread.currentThread());
                  return 41;
                })
            .then(
                Lane.main(),
                (value, cp) -> {
                  shownOn.set(Thread.currentThread());
                  return value + 1;
                });
    // the instrumentation thread is not the main thread, so it may wait
    assertEquals(Outcome.success(42), job.await(20, TimeUnit.SECONDS));
    assertSame(Looper.getMainLooper().getThread(), shownOn.get());
    assertFalse(Looper.getMainLooper().getThread() == producedOn.get());
    realm.close();
  }

  @Test
  public void waitingOnTheMainThreadIsRefusedEvenOutsideClothoTasks() throws Throwable {
    Realm realm = Realm.open("instrumented-block");
    Job<String> done = realm.launch(Lane.io(), cp -> "x");
    done.await(20, TimeUnit.SECONDS);
    onMain(
        () -> {
          try {
            done.await();
            fail("await() must be refused on the main thread");
          } catch (BlockingNotAllowedException expected) {
            // refused, as promised
          } catch (InterruptedException e) {
            throw new AssertionError(e);
          }
        });
    realm.close();
  }
}
