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

import android.os.Looper;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;
import org.codirex.clotho.Realm;

/**
 * Realms tied to an Android lifecycle.
 *
 * <p>This class needs {@code androidx.lifecycle}, which every {@code ComponentActivity} and {@code
 * Fragment} already brings; the library does not force it on apps that never use this class.
 *
 * <p>Rules (details in {@code docs/ANDROID.md}):
 *
 * <ul>
 *   <li>Call on the main thread. At most one realm is bound to a {@link Lifecycle} at a time.
 *   <li>The realm is closed when the lifecycle reaches {@code ON_DESTROY}, and only then: it
 *       survives stop, pause and any state before destroy. The close is {@link Realm#closeAsync()}:
 *       new work is rejected, current jobs are asked to cancel, nothing blocks, no thread is
 *       interrupted unless you asked for it.
 *   <li>The observer removes itself and the binding at destroy, so neither the lifecycle nor this
 *       class keeps a reference to the realm or the owner afterwards. The realm never references
 *       the owner. Jobs you write may capture the owner; those that ignore cancellation keep it
 *       until they end, which no library can prevent.
 * </ul>
 */
public final class AndroidRealms {

  private static final Map<Lifecycle, Binding> BINDINGS = new WeakHashMap<>();

  private AndroidRealms() {}

  /**
   * Returns the realm bound to the lifecycle of {@code owner}, creating it on first use.
   *
   * <p>Calling it again for the same lifecycle returns the same realm while it is open. If the
   * realm was already closed by the caller, a new one is created and bound.
   *
   * @param owner an activity, a fragment (or its view lifecycle owner), or any other owner
   * @return the realm, named after the owner class
   * @throws IllegalStateException if not called on the main thread or the lifecycle is destroyed
   */
  public static Realm forLifecycle(LifecycleOwner owner) {
    return bind(owner, null, false, true);
  }

  /**
   * Creates a realm from {@code builder} and binds it to the lifecycle of {@code owner}.
   *
   * @param owner the lifecycle owner
   * @param builder the preconfigured realm (name, policy, handler)
   * @return the new realm
   * @throws IllegalStateException if not called on the main thread, the lifecycle is destroyed, or
   *     it already has an open realm
   */
  public static Realm forLifecycle(LifecycleOwner owner, Realm.Builder builder) {
    return bind(owner, Objects.requireNonNull(builder, "builder"), false, false);
  }

  /**
   * Like {@link #forLifecycle(LifecycleOwner, Realm.Builder)}, choosing whether destroying the
   * owner also interrupts the threads that run jobs (see {@link Realm#cancel(boolean)}).
   *
   * @param owner the lifecycle owner
   * @param builder the preconfigured realm
   * @param interruptOnDestroy whether to interrupt running jobs at destroy
   * @return the new realm
   * @throws IllegalStateException under the same conditions as {@link #forLifecycle(LifecycleOwner,
   *     Realm.Builder)}
   */
  public static Realm forLifecycle(
      LifecycleOwner owner, Realm.Builder builder, boolean interruptOnDestroy) {
    return bind(owner, Objects.requireNonNull(builder, "builder"), interruptOnDestroy, false);
  }

  private static Realm bind(
      LifecycleOwner owner, Realm.Builder builder, boolean interrupt, boolean reuse) {
    Objects.requireNonNull(owner, "owner");
    if (!Looper.getMainLooper().isCurrentThread()) {
      throw new IllegalStateException(
          "AndroidRealms.forLifecycle must be called on the main thread");
    }
    Lifecycle lifecycle = owner.getLifecycle();
    if (lifecycle.getCurrentState() == Lifecycle.State.DESTROYED) {
      throw new IllegalStateException("the lifecycle is already destroyed: " + owner);
    }
    synchronized (BINDINGS) {
      Binding existing = BINDINGS.get(lifecycle);
      if (existing != null) {
        if (!existing.realm.isClosing()) {
          if (reuse) {
            return existing.realm;
          }
          throw new IllegalStateException("this lifecycle already has an open realm");
        }
        // The caller closed it themselves: drop the stale binding so it cannot pile up.
        lifecycle.removeObserver(existing.closer);
        BINDINGS.remove(lifecycle);
      }
      Realm realm = (builder != null ? builder : Realm.builder(nameOf(owner))).open();
      Closer closer = new Closer(lifecycle, realm, interrupt);
      BINDINGS.put(lifecycle, new Binding(realm, closer));
      lifecycle.addObserver(closer);
      return realm;
    }
  }

  private static String nameOf(LifecycleOwner owner) {
    String simple = owner.getClass().getSimpleName();
    return simple.isEmpty() ? "lifecycle" : simple;
  }

  /** Number of lifecycles that currently have a binding. For tests. */
  static int trackedCount() {
    synchronized (BINDINGS) {
      return BINDINGS.size();
    }
  }

  private static final class Binding {
    final Realm realm;
    final Closer closer;

    Binding(Realm realm, Closer closer) {
      this.realm = realm;
      this.closer = closer;
    }
  }

  private static final class Closer implements DefaultLifecycleObserver {
    private final Lifecycle lifecycle;
    private final Realm realm;
    private final boolean interrupt;

    Closer(Lifecycle lifecycle, Realm realm, boolean interrupt) {
      this.lifecycle = lifecycle;
      this.realm = realm;
      this.interrupt = interrupt;
    }

    @Override
    public void onDestroy(LifecycleOwner owner) {
      lifecycle.removeObserver(this);
      synchronized (BINDINGS) {
        Binding binding = BINDINGS.get(lifecycle);
        if (binding != null && binding.closer == this) {
          BINDINGS.remove(lifecycle);
        }
      }
      if (interrupt) {
        realm.cancel(true);
      }
      realm.closeAsync();
    }
  }
}
