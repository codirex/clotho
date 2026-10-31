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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LifecycleRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.codirex.clotho.Job;
import org.codirex.clotho.Lane;
import org.codirex.clotho.Outcome;
import org.codirex.clotho.Realm;
import org.codirex.clotho.RealmClosedException;
import org.codirex.clotho.RealmPolicy;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class AndroidRealmsTest {

  /** A lifecycle owner backed by the real LifecycleRegistry. */
  private static final class Owner implements LifecycleOwner {
    final LifecycleRegistry registry = new LifecycleRegistry(this);

    @Override
    public Lifecycle getLifecycle() {
      return registry;
    }

    void moveTo(Lifecycle.Event... events) {
      for (Lifecycle.Event e : events) {
        registry.handleLifecycleEvent(e);
      }
    }
  }

  /** A lane that keeps tasks until the test runs them. */
  private static final class QueueLane implements Lane {
    final List<Runnable> tasks = new ArrayList<>();

    @Override
    public void execute(Runnable task) {
      tasks.add(task);
    }

    void runAll() {
      List<Runnable> copy = new ArrayList<>(tasks);
      tasks.clear();
      for (Runnable r : copy) {
        r.run();
      }
    }
  }

  /** A lane that runs each task on its own thread. */
  private static final Lane THREAD_PER_TASK =
      task -> {
        Thread t = new Thread(task, "realm-test");
        t.setDaemon(true);
        t.start();
      };

  private static void spinUntil(java.util.function.BooleanSupplier condition) {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() > deadline) {
        throw new AssertionError("condition not reached");
      }
      Thread.yield();
    }
  }

  @Test
  public void theSameLifecycleGetsTheSameRealmAndOneObserver() {
    Owner owner = new Owner();
    Realm first = AndroidRealms.forLifecycle(owner);
    Realm second = AndroidRealms.forLifecycle(owner);
    assertThat(second).isSameAs(first);
    assertThat(owner.registry.getObserverCount()).isEqualTo(1);
    assertThat(first.name()).isEqualTo("Owner");
    owner.moveTo(Lifecycle.Event.ON_CREATE, Lifecycle.Event.ON_DESTROY);
  }

  @Test
  public void theRealmSurvivesEveryStateBeforeDestroy() {
    Owner owner = new Owner();
    Realm realm = AndroidRealms.forLifecycle(owner);
    owner.moveTo(
        Lifecycle.Event.ON_CREATE,
        Lifecycle.Event.ON_START,
        Lifecycle.Event.ON_RESUME,
        Lifecycle.Event.ON_PAUSE,
        Lifecycle.Event.ON_STOP);
    assertThat(realm.isClosing()).isFalse();
    owner.moveTo(Lifecycle.Event.ON_START, Lifecycle.Event.ON_RESUME);
    assertThat(realm.isClosing()).isFalse();
    owner.moveTo(Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP, Lifecycle.Event.ON_DESTROY);
    assertThat(realm.isClosing()).isTrue();
  }

  @Test
  public void destroyCancelsPendingJobsClosesTheRealmAndReleasesEverything() {
    Owner owner = new Owner();
    Realm realm = AndroidRealms.forLifecycle(owner);
    QueueLane lane = new QueueLane();
    int before = AndroidRealms.trackedCount();
    Job<String> job = realm.launch(lane, cp -> "never");
    owner.moveTo(Lifecycle.Event.ON_CREATE, Lifecycle.Event.ON_DESTROY);
    assertThat(job.outcomeOrNull().isCancelled()).isTrue();
    assertThat(realm.isClosing()).isTrue();
    assertThat(realm.closeAsync().isClosed()).isTrue();
    assertThat(owner.registry.getObserverCount()).isEqualTo(0);
    assertThat(AndroidRealms.trackedCount()).isEqualTo(before - 1);
    assertThatThrownBy(() -> realm.launch(lane, cp -> "late"))
        .isInstanceOf(RealmClosedException.class);
    lane.runAll();
  }

  @Test
  public void aRunningJobThatIgnoresCancellationKeepsTheRealmClosingUntilItEnds() {
    Owner owner = new Owner();
    Realm realm = AndroidRealms.forLifecycle(owner);
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch finish = new CountDownLatch(1);
    realm.launch(
        THREAD_PER_TASK,
        cp -> {
          started.countDown();
          for (; ; ) {
            try {
              finish.await();
              return "done";
            } catch (InterruptedException e) {
              // ignore: this job does not cooperate
            }
          }
        });
    spinUntil(() -> started.getCount() == 0);
    owner.moveTo(Lifecycle.Event.ON_CREATE, Lifecycle.Event.ON_DESTROY);
    Realm.Closing closing = realm.closeAsync();
    assertThat(realm.isClosing()).isTrue();
    assertThat(closing.isClosed()).isFalse();
    finish.countDown();
    spinUntil(closing::isClosed);
  }

  @Test
  public void interruptOnDestroyInterruptsTheThreadsOfRunningJobs() {
    Owner owner = new Owner();
    Realm realm = AndroidRealms.forLifecycle(owner, Realm.builder("interrupting"), true);
    CountDownLatch started = new CountDownLatch(1);
    AtomicReference<Boolean> interrupted = new AtomicReference<>(false);
    realm.launch(
        THREAD_PER_TASK,
        cp -> {
          started.countDown();
          try {
            new CountDownLatch(1).await();
          } catch (InterruptedException e) {
            interrupted.set(true);
          }
          return "stopped";
        });
    spinUntil(() -> started.getCount() == 0);
    owner.moveTo(Lifecycle.Event.ON_CREATE, Lifecycle.Event.ON_DESTROY);
    spinUntil(() -> realm.closeAsync().isClosed());
    assertThat(interrupted.get()).isTrue();
  }

  @Test
  public void aBuilderConfiguresTheRealmAndASecondOpenRealmIsRefused() {
    Owner owner = new Owner();
    Realm realm =
        AndroidRealms.forLifecycle(owner, Realm.builder("custom").policy(RealmPolicy.FAIL_FAST));
    assertThat(realm.name()).isEqualTo("custom");
    assertThat(realm.policy()).isEqualTo(RealmPolicy.FAIL_FAST);
    assertThatThrownBy(() -> AndroidRealms.forLifecycle(owner, Realm.builder("again")))
        .isInstanceOf(IllegalStateException.class);
    assertThat(AndroidRealms.forLifecycle(owner)).isSameAs(realm);
    assertThat(owner.registry.getObserverCount()).isEqualTo(1);
    owner.moveTo(Lifecycle.Event.ON_CREATE, Lifecycle.Event.ON_DESTROY);
  }

  @Test
  public void aRealmClosedByTheCallerIsReplacedWithoutLeavingAnObserverBehind() {
    Owner owner = new Owner();
    Realm first = AndroidRealms.forLifecycle(owner);
    first.close();
    Realm second = AndroidRealms.forLifecycle(owner);
    assertThat(second).isNotSameAs(first);
    assertThat(second.isClosing()).isFalse();
    assertThat(owner.registry.getObserverCount()).isEqualTo(1);
    owner.moveTo(Lifecycle.Event.ON_CREATE, Lifecycle.Event.ON_DESTROY);
    assertThat(second.isClosing()).isTrue();
    assertThat(owner.registry.getObserverCount()).isEqualTo(0);
  }

  @Test
  public void aDestroyedOwnerIsRefusedAndTwoOwnersAreIndependent() {
    Owner destroyed = new Owner();
    destroyed.moveTo(Lifecycle.Event.ON_CREATE, Lifecycle.Event.ON_DESTROY);
    assertThatThrownBy(() -> AndroidRealms.forLifecycle(destroyed))
        .isInstanceOf(IllegalStateException.class);

    Owner a = new Owner();
    Owner b = new Owner();
    Realm ra = AndroidRealms.forLifecycle(a);
    Realm rb = AndroidRealms.forLifecycle(b);
    assertThat(ra).isNotSameAs(rb);
    a.moveTo(Lifecycle.Event.ON_CREATE, Lifecycle.Event.ON_DESTROY);
    assertThat(ra.isClosing()).isTrue();
    assertThat(rb.isClosing()).isFalse();
    b.moveTo(Lifecycle.Event.ON_CREATE, Lifecycle.Event.ON_DESTROY);
  }

  @Test
  public void itMustBeCalledOnTheMainThread() throws Exception {
    Owner owner = new Owner();
    AtomicReference<Throwable> thrown = new AtomicReference<>();
    Thread t =
        new Thread(
            () -> {
              try {
                AndroidRealms.forLifecycle(owner);
              } catch (Throwable e) {
                thrown.set(e);
              }
            });
    t.start();
    t.join(20_000);
    assertThat(thrown.get()).isInstanceOf(IllegalStateException.class);
    assertThat(owner.registry.getObserverCount()).isEqualTo(0);
  }

  @Test
  public void completionCallbacksRegisteredBeforeDestroyStillRunOnceWithTheCancelledOutcome() {
    Owner owner = new Owner();
    Realm realm = AndroidRealms.forLifecycle(owner);
    QueueLane lane = new QueueLane();
    Job<String> job = realm.launch(lane, cp -> "never");
    List<Outcome<?>> seen = new ArrayList<>();
    job.onComplete(Lane.direct(), seen::add);
    owner.moveTo(Lifecycle.Event.ON_CREATE, Lifecycle.Event.ON_DESTROY);
    assertThat(seen).hasSize(1);
    assertThat(seen.get(0).isCancelled()).isTrue();
  }
}
