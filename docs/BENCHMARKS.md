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

## Alpha02 release measurement

Full JMH run on the exact `0.1.0-alpha02` release commit:

- Workflow: [Run #43291376455](https://github.com/codirex/clotho/actions/runs/43291376455)
- Commit: [`545602a`](https://github.com/codirex/clotho/commit/545602a04155686e4de610a2365675401a4c3b0a)
- Date: 2026-11-12
- Environment: Ubuntu 26.04, AMD EPYC 9V45, 4 shared vCPUs, 15 GiB, Temurin 21.0.13, JMH 1.37
- Result: all benchmark, report-generation, and artifact-upload steps succeeded.
- Artifact: `jmh-results` (raw JMH JSON and generated Markdown report).

### Findings

Scores below are from the Alpha02 run only. Throughput is in ops/ms (higher is better);
latency is in us/op (lower is better). Allocation is normalized bytes per operation.

| Benchmark | Clotho | Best comparator | Interpretation |
|---|---:|---:|---|
| BatchThroughput, work=0 | 884.3 ops/ms | ForkJoinPool: 16,936 ops/ms | Large scheduling-only throughput gap; investigate dispatch/queue overhead |
| BatchThroughput, work=1000 | 1,584 ops/ms | ForkJoinPool: 1,632 ops/ms | Clotho is within the reported confidence interval of ForkJoinPool and CompletableFuture |
| Chain | 20.7 us/op | CompletableFuture: 16.1 us/op | Clotho is about 29% slower in this three-stage chain |
| OrderedLane, single | 5,047 ops/ms | single-thread executor: 7,324 ops/ms | ClothoSingle is about 31% lower throughput |
| OrderedLane, serial | 3,816 ops/ms | single-thread executor: 7,324 ops/ms | ClothoSerial is about 48% lower throughput |
| RealmScope | 15.8 us/op | plain pool: 15.4 us/op | Within the reported confidence interval; effectively no measured latency difference |
| RoundTripManyProducers | 7.948 us/op | ForkJoinPool: 5.970 us/op | Clotho is slower than ForkJoinPool, but faster than ThreadPoolExecutor and CompletableFuture |
| RoundTripSingleProducer | 16.1 us/op | CompletableFuture: 15.1 us/op | Close to the alternatives; no claim of a win |
| Timers | 5,962 ops/ms | ScheduledThreadPoolExecutor (keep cancelled): 14,505 ops/ms | Clotho trails both scheduled-executor configurations in this workload |

Allocation is a visible cost in this run: Clotho ranges from about 320 B/op for
`OrderedLane` to 2,032 B/op for sampled `Chain`. The round-trip measurements are about
456 B/op (single producer) and 446 B/op (many producers); `RealmScope` is about 909 B/op.
These figures should be treated as optimization signals, not as proof of a leak.

### Comparison with the historical run

The historical run below used an Intel Xeon Platinum 8370C, while the Alpha02 run used an AMD EPYC
9V45. Both used four shared vCPUs, Temurin 21.0.13 and JMH 1.37, but the CPU and runner image differ.
The historical artifact also reports allocation as unavailable, whereas the Alpha02 run records
normalized allocation. Therefore, do not interpret raw score differences between these two runs as
a measured regression or improvement. Use each run's within-table comparisons; establish a trend
with repeated runs on a controlled, matching host.

The Alpha02 run completes the release-commit measurement requirement. It does not justify a blanket
performance claim: the results are mixed, with strong relative performance in the many-producer
round trip against two comparators and in work-heavy batches, alongside clear scheduling-only,
chain, ordered-lane, timer, and allocation costs. Any optimization should target a measured workload
and be followed by a repeat benchmark.

## Recorded runs

| Date | Commit | Environment | Workflow |
|---|---|---|---|
| 2026-11-01 | [`8dea60e`](https://github.com/codirex/clotho/commit/8dea60e2c5673f6de08fe04dad394a3d261c2265) | Temurin 21.0.13, JMH 1.37, Ubuntu 26.04, Xeon Platinum 8370C (4 shared vCPUs) | [Run #41264309099](https://github.com/codirex/clotho/actions/runs/41264309099) |
| 2026-11-12 | [`545602a`](https://github.com/codirex/clotho/commit/545602a04155686e4de610a2365675401a4c3b0a) | Temurin 21.0.13, JMH 1.37, Ubuntu 26.04, AMD EPYC 9V45 (4 shared vCPUs) | [Run #43291376455](https://github.com/codirex/clotho/actions/runs/43291376455) |
