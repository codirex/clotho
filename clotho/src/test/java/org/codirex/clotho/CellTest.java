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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class CellTest {

  @Test
  void getSetAndGetAndSet() {
    Cell<String> cell = Cell.of("a");
    assertThat(cell.get()).isEqualTo("a");
    cell.set("b");
    assertThat(cell.get()).isEqualTo("b");
    assertThat(cell.getAndSet("c")).isEqualTo("b");
    assertThat(cell.get()).isEqualTo("c");
    assertThat(cell.toString()).isEqualTo("Cell(c)");
  }

  @Test
  void nullIsALegalValueEverywhere() {
    Cell<String> cell = Cell.of(null);
    assertThat(cell.get()).isNull();
    assertThat(cell.compareAndSet(null, "x")).isTrue();
    assertThat(cell.compareAndSet(null, "y")).isFalse();
    assertThat(cell.updateAndGet(v -> null)).isNull();
    assertThat(cell.get()).isNull();
    assertThat(cell.getAndUpdate(v -> v == null ? "from null" : "from value")).isNull();
    assertThat(cell.get()).isEqualTo("from null");
    cell.set(null);
    assertThat(cell.getAndSet("z")).isNull();
  }

  @Test
  void compareAndSetComparesByEqualsAndTheSameVariantByReference() {
    Cell<Integer> numbers = Cell.of(1000);
    assertThat(numbers.compareAndSet(1000, 1001)).isTrue();
    assertThat(numbers.get()).isEqualTo(1001);
    assertThat(numbers.compareAndSet(5, 6)).isFalse();
    assertThat(numbers.get()).isEqualTo(1001);

    String original = new String("same text");
    String equalCopy = new String("same text");
    Cell<String> cell = Cell.of(original);
    assertThat(cell.compareAndSetSame(equalCopy, "x")).isFalse();
    assertThat(cell.get()).isSameAs(original);
    assertThat(cell.compareAndSetSame(original, "x")).isTrue();
    assertThat(cell.get()).isEqualTo("x");
    Cell<String> byEquals = Cell.of(original);
    assertThat(byEquals.compareAndSet(equalCopy, "y")).isTrue();
  }

  @Test
  void updateVariantsReturnTheNewOrThePreviousValue() {
    Cell<Integer> cell = Cell.of(10);
    assertThat(cell.updateAndGet(v -> v + 1)).isEqualTo(11);
    assertThat(cell.getAndUpdate(v -> v * 2)).isEqualTo(11);
    assertThat(cell.get()).isEqualTo(22);
    cell.update(v -> v - 2);
    assertThat(cell.get()).isEqualTo(20);
  }

  @Test
  void aFunctionThatThrowsLeavesTheCellUnchanged() {
    Cell<Integer> cell = Cell.of(7);
    IllegalStateException boom = new IllegalStateException("boom");
    assertThatThrownBy(
            () ->
                cell.update(
                    v -> {
                      throw boom;
                    }))
        .isSameAs(boom);
    assertThat(cell.get()).isEqualTo(7);
    assertThatThrownBy(() -> cell.update(null)).isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> cell.updateAndGet(null)).isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> cell.getAndUpdate(null)).isInstanceOf(NullPointerException.class);
  }

  @Test
  void aContendedUpdateCallsTheFunctionAgainWithTheNewerValue() throws Exception {
    Cell<Integer> cell = Cell.of(0);
    List<Integer> seen = Collections.synchronizedList(new ArrayList<>());
    CountDownLatch inFunction = new CountDownLatch(1);
    CountDownLatch proceed = new CountDownLatch(1);
    AtomicInteger calls = new AtomicInteger();
    Thread updater =
        new Thread(
            () ->
                cell.updateAndGet(
                    v -> {
                      seen.add(v);
                      if (calls.incrementAndGet() == 1) {
                        inFunction.countDown();
                        try {
                          proceed.await(20, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                          throw new AssertionError(e);
                        }
                      }
                      return v + 10;
                    }));
    updater.start();
    assertThat(inFunction.await(20, TimeUnit.SECONDS)).isTrue();
    cell.set(5); // another thread changes the cell while the function runs
    proceed.countDown();
    updater.join(20_000);
    assertThat(seen).containsExactly(0, 5);
    assertThat(cell.get()).isEqualTo(15);
  }

  @Test
  void concurrentIncrementsLoseNothingAndEveryResultIsDistinct() throws Exception {
    int threads = 8;
    int each = 25_000;
    Cell<Integer> cell = Cell.of(0);
    List<List<Integer>> previous = new ArrayList<>();
    List<List<Integer>> next = new ArrayList<>();
    CountDownLatch go = new CountDownLatch(1);
    List<Thread> workers = new ArrayList<>();
    for (int t = 0; t < threads; t++) {
      List<Integer> prev = new ArrayList<>();
      List<Integer> nxt = new ArrayList<>();
      previous.add(prev);
      next.add(nxt);
      Thread worker =
          new Thread(
              () -> {
                try {
                  go.await();
                } catch (InterruptedException e) {
                  return;
                }
                for (int i = 0; i < each; i++) {
                  if (i % 2 == 0) {
                    prev.add(cell.getAndUpdate(v -> v + 1));
                  } else {
                    nxt.add(cell.updateAndGet(v -> v + 1));
                  }
                }
              });
      worker.setDaemon(true);
      worker.start();
      workers.add(worker);
    }
    go.countDown();
    for (Thread w : workers) {
      w.join(60_000);
    }
    int total = threads * each;
    assertThat(cell.get()).isEqualTo(total);
    // every update replaced a different value: the previous values and the new values, taken
    // together, are 0..total-1 and 1..total each exactly once
    boolean[] seenPrevious = new boolean[total + 1];
    boolean[] seenNext = new boolean[total + 1];
    for (List<Integer> list : previous) {
      for (int v : list) {
        assertThat(seenPrevious[v]).isFalse();
        seenPrevious[v] = true;
      }
    }
    for (List<Integer> list : next) {
      for (int v : list) {
        assertThat(seenNext[v]).isFalse();
        seenNext[v] = true;
      }
    }
    int updates = 0;
    for (int v = 0; v <= total; v++) {
      boolean asPrevious = seenPrevious[v];
      boolean asNext = seenNext[v + 1 > total ? total : v + 1];
      if (v < total) {
        // value v was replaced by exactly one update: seen as a previous value or its successor
        // was returned as a new value
        assertThat(asPrevious || seenNext[v + 1]).isTrue();
        assertThat(asPrevious && seenNext[v + 1]).isFalse();
        updates++;
      } else {
        assertThat(asNext || true).isTrue();
      }
    }
    assertThat(updates).isEqualTo(total);
  }

  @Test
  void aReplacementOfImmutableValuesKeepsEveryAppendedItem() throws Exception {
    int threads = 6;
    int each = 2_000;
    Cell<List<Integer>> cell = Cell.of(Collections.emptyList());
    CountDownLatch go = new CountDownLatch(1);
    List<Thread> workers = new ArrayList<>();
    for (int t = 0; t < threads; t++) {
      int base = t * each;
      Thread worker =
          new Thread(
              () -> {
                try {
                  go.await();
                } catch (InterruptedException e) {
                  return;
                }
                for (int i = 0; i < each; i++) {
                  int item = base + i;
                  cell.update(
                      list -> {
                        List<Integer> copy = new ArrayList<>(list.size() + 1);
                        copy.addAll(list);
                        copy.add(item);
                        return Collections.unmodifiableList(copy);
                      });
                }
              });
      worker.setDaemon(true);
      worker.start();
      workers.add(worker);
    }
    go.countDown();
    for (Thread w : workers) {
      w.join(60_000);
    }
    List<Integer> result = cell.get();
    assertThat(result).hasSize(threads * each);
    boolean[] present = new boolean[threads * each];
    for (int item : result) {
      assertThat(present[item]).isFalse();
      present[item] = true;
    }
  }

  @Test
  void compareAndSetLoopsCountExactly() throws Exception {
    int threads = 4;
    int each = 20_000;
    Cell<Long> cell = Cell.of(0L);
    List<Thread> workers = new ArrayList<>();
    for (int t = 0; t < threads; t++) {
      Thread worker =
          new Thread(
              () -> {
                for (int i = 0; i < each; i++) {
                  for (; ; ) {
                    long current = cell.get();
                    if (cell.compareAndSet(current, current + 1)) {
                      break;
                    }
                  }
                }
              });
      worker.setDaemon(true);
      worker.start();
      workers.add(worker);
    }
    for (Thread w : workers) {
      w.join(60_000);
    }
    assertThat(cell.get()).isEqualTo((long) threads * each);
  }
}
