# Benchmarks

`clotho-bench` compares Clotho with `java.util.concurrent` using JMH. It is not part of `build`:

```
./gradlew -PwithBench :clotho-bench:bench                        # full run, long
./gradlew -PwithBench :clotho-bench:bench -PbenchQuick           # smoke run, about 2 minutes
./gradlew -PwithBench :clotho-bench:bench -PbenchFilter=Timers   # one class
python3 scripts/jmh_to_md.py clotho-bench/build/results/jmh/results.json > report.md
```

On GitHub: Actions, **Benchmarks**, Run workflow. The report is in the run summary and the raw JSON
is an artifact.

## What is measured

| Class | Question | Compared with |
|---|---|---|
| `RoundTripSingleProducer` | cost of a hand-off and back (mean and p50/p99/p99.9) | `ThreadPoolExecutor`, `ForkJoinPool`, `CompletableFuture` |
| `RoundTripManyProducers` | the same with one caller per core (submit contention) | same |
| `BatchThroughput` | tasks per millisecond, 1000 tasks per batch, with 0 and 1000 CPU tokens per task | same |
| `Chain` | three dependent stages | `CompletableFuture` |
| `OrderedLane` | 1000 tasks in strict order | `newSingleThreadExecutor` |
| `Timers` | schedule and cancel 10,000 timers that never fire | `ScheduledThreadPoolExecutor` (with and without remove-on-cancel) |
| `RealmScope` | cost of open realm, run, close | the same task on a plain pool |

Every implementation is sized the same (`poolThreads`, default: available processors) and runs in
the same JVM fork. `-prof gc` adds bytes allocated per operation.

## Reading the results

- Compare rows of the same table only. A row marked `≈` is within the error of the best row: that
  is no measured difference, not a tie to be advertised.
- Shared CI runners are noisy and have few virtual cores. Use them for relative comparison and for
  regressions; record absolute numbers from a quiet machine with the hardware stated.
- `BatchThroughput` with `work=0` measures pure scheduling overhead; with `work=1000` the task
  dominates and the differences should shrink. If they do not, that is worth investigating.
- A benchmark that Clotho loses is a result. Do not tune the benchmark to change it; change the
  engine only when the data convicts a structure (see ENGINE.md section 6 on lock-free deques).

## Recorded runs

None yet. Paste the output of `scripts/jmh_to_md.py` here together with its environment block.
