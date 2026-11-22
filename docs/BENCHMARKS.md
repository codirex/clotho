# Benchmarks

`clotho-bench` compares Clotho with `java.util.concurrent` using JMH. It is not part of `build`:

```
./gradlew -PwithBench :clotho-bench:bench                        # full run, long
./gradlew -PwithBench :clotho-bench:bench -PbenchQuick           # smoke run, about 2 minutes
./gradlew -PwithBench :clotho-bench:bench -PbenchFilter=Timers   # one class
./gradlew -PwithBench :clotho-bench:bench -PbenchFilter=LaneOnlyThroughput "-PbenchProfile=stack:lines=6;top=15"   # with a profiler
python3 scripts/jmh_to_md.py clotho-bench/build/results/jmh/results.json > report.md
```

On GitHub: Actions, **Benchmarks**, Run workflow. The inputs are `quick` (smoke run), `filter` (class
regex) and `profiler` (an extra JMH profiler, for example `stack:lines=6;top=15`). The report is in
the run summary and in the job log, and the raw JSON is an artifact.

## What is measured

| Class | Question | Compared with |
|---|---|---|
| `RoundTripSingleProducer` | cost of a hand-off and back (mean and p50/p99/p99.9) | `ThreadPoolExecutor`, `ForkJoinPool`, `CompletableFuture` |
| `RoundTripManyProducers` | the same with one caller per core (submit contention) | same |
| `BatchThroughput` | tasks per millisecond, 1000 tasks per batch, with 0 and 1000 CPU tokens per task | same |
| `LaneOnlyThroughput` | the same batch of empty tasks sent straight to the lane, with no `Job` and no `Realm`: how much of the `BatchThroughput` cost is the lane itself | `ThreadPoolExecutor`, `ForkJoinPool` |
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
| BatchThroughput, work=0 | 884.3 ops/ms | ForkJoinPool: 16,936 ops/ms | Large scheduling-only throughput gap; see [Scheduling cost of empty tasks](#scheduling-cost-of-empty-tasks) |
| BatchThroughput, work=1000 | 1,584 ops/ms | ForkJoinPool: 1,632 ops/ms | Clotho is within the reported confidence interval of ForkJoinPool and CompletableFuture |
| Chain | 20.7 us/op | CompletableFuture: 16.1 us/op | Clotho is about 29% slower in this three-stage chain |
| OrderedLane, single | 5,047 ops/ms | single-thread executor: 7,324 ops/ms | ClothoSingle is about 31% lower throughput |
| OrderedLane, serial | 3,816 ops/ms | single-thread executor: 7,324 ops/ms | ClothoSerial is about 48% lower throughput |
| RealmScope | 15.8 us/op | plain pool: 15.4 us/op | Within the reported confidence interval; effectively no measured latency difference |
| RoundTripManyProducers | 7.948 us/op | ForkJoinPool: 5.970 us/op | Clotho is slower than ForkJoinPool, but faster than ThreadPoolExecutor and CompletableFuture |
| RoundTripSingleProducer | 16.1 us/op | CompletableFuture: 15.1 us/op | Close to the alternatives; no claim of a win |
| Timers | 5,962 ops/ms | ScheduledThreadPoolExecutor (keep cancelled): 14,505 ops/ms | Clotho is clearly below keep-cancelled; it overlaps the remove-on-cancel result (6,293 ± 445 ops/ms) |

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

## Scheduling cost of empty tasks

The `BatchThroughput` work=0 gap from the Alpha02 findings was investigated in November 2026 on
GitHub-hosted runners (4 shared vCPUs, Temurin 21). It was not tuned: none of the engine experiments
below improved the result, so the engine is unchanged. This is a record of what was measured and
tried, not a performance claim. Every number comes from one run; compare rows within a run, because
the same benchmark varied by about 2x between runners.

### Where the cost is

`LaneOnlyThroughput` sends the same empty task straight to the lane, with no `Job` and no `Realm`.
In one run ([Run #44654418744](https://github.com/codirex/clotho/actions/runs/44654418744), commit
[`5aa52d0`](https://github.com/codirex/clotho/commit/5aa52d00ed4e1a85227e12c07b37beaafa34d03f)):

| work=0, ops/ms | ForkJoinPool | ThreadPoolExecutor | Clotho |
|---|---:|---:|---:|
| lane only (`LaneOnlyThroughput`) | 20,327 | 15,872 | 1,030 |
| `BatchThroughput` | 19,267 | 8,897 | 645 |

That is about 970 ns per task for the lane alone against about 1,550 ns for the full path, so the
lane is about 63% of Clotho's per-task cost and the `Job` and `Realm` bookkeeping adds about 580 ns.
Allocation is high too: about 217 B/op for the lane alone (the comparators allocate far less), and
429 B/op for `BatchThroughput` against 52 for `ForkJoinPool`
([Run #44520348809](https://github.com/codirex/clotho/actions/runs/44520348809)).

### What was tried

| Experiment | Change | Result |
|---|---|---|
| Spin before parking | an idle worker watches the queued counter up to 1,024 times before it parks | `BatchThroughput` work=0: 727 ± 28 against 699 ± 50 ops/ms on `main` (+4%, inside the error bars). [Run #44520669565](https://github.com/codirex/clotho/actions/runs/44520669565) against Run #44520348809 |
| Keep idle workers off the locks | check `queued == 0` first, a lock-free emptiness check per queue, one shared-queue lock acquisition when nothing is local | no change: `LaneOnlyThroughput` Clotho is 5.8% of `ThreadPoolExecutor` against 6.5% on `main`. [Run #44728787299](https://github.com/codirex/clotho/actions/runs/44728787299), on a runner about twice as slow overall |
| Lock-free queue levels | one `ConcurrentLinkedQueue` per priority level instead of one monitor, with the previous change | slower: `LaneOnlyThroughput` 595 against 1,030 ops/ms and `BatchThroughput` work=0 486 against 645, on runners of the same speed (`ThreadPoolExecutor` lane-only about 15.9k ops/ms in both). [Run #44846323353](https://github.com/codirex/clotho/actions/runs/44846323353) |

The experiments lived on the branches `perf/f1-spin`, `perf/f1-lock-light` and `perf/f1-lock-free`,
which were deleted afterwards; the run IDs identify the results.

### Stack profile

A JMH `stack` profile of `LaneOnlyThroughput.clothoLane` on unchanged `main`
([Run #44737047282](https://github.com/codirex/clotho/actions/runs/44737047282)): the benchmark
thread waited on the latch in only 1.5% of the samples, so the producer is the bottleneck. Samples by
state were RUNNABLE 50.7%, BLOCKED 25.5%, TIMED_WAITING 22.3% and WAITING 1.5%. Of the BLOCKED
samples, about 41% were in `RankedQueue.peekBest` and 37% in `RankedQueue.pollBest` (the workers) and
22% in `RankedQueue.add` (the producer), all on the monitor of the shared queue.

Replacing that monitor with lock-free queues made the benchmark slower, so the lock type is not the
cause. A hypothesis that has not been tested: awake workers chase a nearly empty queue, so each task
crosses between cores on its own, while the `ThreadPoolExecutor` queue builds up and its workers take
tasks in runs.

### What it means

The gap belongs to empty tasks. With `work=1000` Clotho measured 1,327 ops/ms against 1,540 for
`ForkJoinPool` and 1,333 for `ThreadPoolExecutor` (Run #44520348809). A next experiment would change
the park, wake or steal rules, for example taking several tasks per lock acquisition or waking a
worker only when no other is searching. That needs the jcstress scenarios and a design decision, so it
is not part of this record.

## Recorded runs

| Date | Commit | Environment | Workflow |
|---|---|---|---|
| 2026-11-01 | [`8dea60e`](https://github.com/codirex/clotho/commit/8dea60e2c5673f6de08fe04dad394a3d261c2265) | Temurin 21.0.13, JMH 1.37, Ubuntu 26.04, Xeon Platinum 8370C (4 shared vCPUs) | [Run #41264309099](https://github.com/codirex/clotho/actions/runs/41264309099) |
| 2026-11-12 | [`545602a`](https://github.com/codirex/clotho/commit/545602a04155686e4de610a2365675401a4c3b0a) | Temurin 21.0.13, JMH 1.37, Ubuntu 26.04, AMD EPYC 9V45 (4 shared vCPUs) | [Run #43291376455](https://github.com/codirex/clotho/actions/runs/43291376455) |
