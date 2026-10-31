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

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;

/**
 * A value that many threads can read and change atomically, without locks.
 *
 * <p>Contract:
 *
 * <ul>
 *   <li><b>Visibility.</b> {@link #get()} is a volatile read and {@link #set} a volatile write: a
 *       thread that reads a value sees everything the writer did before writing it. A cell makes
 *       the <em>replacement</em> of its value atomic; it does not make a mutable value thread-safe.
 *   <li><b>{@code null}</b> is a legal value ({@code Cell.of(null)}, {@code set(null)}, a function
 *       that returns {@code null}). The functions and {@code compareAndSet} handle it. Functions
 *       themselves must not be {@code null}.
 *   <li><b>Update functions</b> ({@link #update}, {@link #updateAndGet}, {@link #getAndUpdate}) run
 *       in a retry loop: if another thread changes the cell while a function runs, the result is
 *       thrown away and the function is called again with the newer value. So a function can be
 *       called <em>several times</em> for one update, on the calling thread, with different
 *       arguments. It must be fast, free of side effects and must not depend on anything but its
 *       argument. No lock is held while it runs. If it throws, the exception reaches the caller and
 *       the cell is unchanged.
 *   <li><b>No lost updates.</b> Every update that returns is applied exactly once, on the value
 *       that the cell held at that moment. The loop is lock-free (some thread always makes
 *       progress) but not wait-free: under heavy contention one thread can retry many times.
 *   <li>{@link #compareAndSet} compares with {@link Objects#equals(Object, Object)}, so {@code
 *       cell.compareAndSet(1000, 1001)} works for boxed numbers. {@link #compareAndSetSame}
 *       compares by reference like {@code AtomicReference}.
 * </ul>
 *
 * @param <T> the type of the value
 */
public final class Cell<T> {

  private final AtomicReference<T> ref;

  private Cell(T initial) {
    this.ref = new AtomicReference<>(initial);
  }

  /**
   * Creates a cell.
   *
   * @param initial the first value, may be {@code null}
   * @param <T> the value type
   * @return the cell
   */
  public static <T> Cell<T> of(T initial) {
    return new Cell<>(initial);
  }

  /**
   * Returns the current value.
   *
   * @return the value, may be {@code null}
   */
  public T get() {
    return ref.get();
  }

  /**
   * Replaces the value.
   *
   * @param value the new value, may be {@code null}
   */
  public void set(T value) {
    ref.set(value);
  }

  /**
   * Replaces the value and returns the previous one.
   *
   * @param value the new value, may be {@code null}
   * @return the value that was replaced
   */
  public T getAndSet(T value) {
    return ref.getAndSet(value);
  }

  /**
   * Sets {@code newValue} if the current value equals {@code expected} ({@link Objects#equals}).
   *
   * @param expected the value to compare with, may be {@code null}
   * @param newValue the new value, may be {@code null}
   * @return {@code true} if the value was replaced; {@code false} if the current value was not
   *     equal to {@code expected}
   */
  public boolean compareAndSet(T expected, T newValue) {
    for (; ; ) {
      T current = ref.get();
      if (!Objects.equals(current, expected)) {
        return false;
      }
      if (ref.compareAndSet(current, newValue)) {
        return true;
      }
      // The cell changed between the read and the write: look again.
    }
  }

  /**
   * Sets {@code newValue} if the current value is the very same object as {@code expected} ({@code
   * ==}), like {@link AtomicReference#compareAndSet}.
   *
   * @param expected the object to compare with by reference
   * @param newValue the new value
   * @return {@code true} if the value was replaced
   */
  public boolean compareAndSetSame(T expected, T newValue) {
    return ref.compareAndSet(expected, newValue);
  }

  /**
   * Replaces the value with {@code function(value)}, atomically. See the class contract: the
   * function can be called more than once.
   *
   * @param function a fast, side-effect free function; may return {@code null}
   */
  public void update(UnaryOperator<T> function) {
    updateAndGet(function);
  }

  /**
   * Replaces the value with {@code function(value)}, atomically, and returns the new value.
   *
   * @param function a fast, side-effect free function; may return {@code null}
   * @return the value that this call stored
   */
  public T updateAndGet(UnaryOperator<T> function) {
    Objects.requireNonNull(function, "function");
    for (; ; ) {
      T previous = ref.get();
      T next = function.apply(previous);
      if (ref.compareAndSet(previous, next)) {
        return next;
      }
    }
  }

  /**
   * Replaces the value with {@code function(value)}, atomically, and returns the value it replaced.
   *
   * @param function a fast, side-effect free function; may return {@code null}
   * @return the value that this call replaced
   */
  public T getAndUpdate(UnaryOperator<T> function) {
    Objects.requireNonNull(function, "function");
    for (; ; ) {
      T previous = ref.get();
      T next = function.apply(previous);
      if (ref.compareAndSet(previous, next)) {
        return previous;
      }
    }
  }

  @Override
  public String toString() {
    return "Cell(" + ref.get() + ")";
  }
}
