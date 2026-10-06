# JVM benchmarks

Interpret results using the project's [implementation priorities](../README.md#implementation-priorities): execution
time, allocation, and predictability matter together. Give particular attention to large structures with zero or few
matches, checking whether allocated bytes grow with input size despite a fixed match count. A small retained result
does not establish low temporary allocation, and timing alone does not capture all downstream GC effects.

Run from the repository root:

```sh
bb bench
```

This compiles Java and runs the library and benchmark correctness tests **once**,
then measures **each case in three fresh JVMs** by default. `:forks` controls the
number of independent JVM repetitions per case. Workers run sequentially in
complete passes through the selected cases; no two measurements overlap.

The coordinator uses `-Srepro` to exclude user-level Clojure configuration. Each
worker inherits its exact Java executable, JVM arguments and classpath, including
the dependencies and JVM options in `deps.edn`. Workers prepare and validate only
their selected case and retain the existing measurement loop. The coordinator
performs no measurements. No library implementation is changed by benchmarking.

The default suite prioritizes Quiescent's Pathling usage. Each fixture measures:

| Operation | Timed work |
|---|---|
| `:path-raw` | `(p/path-when data groundable? {:raw-matches true})` |
| `:update-array-list` | `update-paths` with a prebuilt navigation tree and a raw accumulator already filled with replacements |
| `:raw-roundtrip` | Raw scan, replace matches in the same accumulator, and update the original data |

The update-only case excludes scanning and replacement preparation. The roundtrip
includes filling the accumulator with synthetic resolved values. Every invocation
scans the original immutable input; it never scans the previous invocation's output.
No actual task scheduling, waiting, or asynchronous completion is measured.

Fixtures cover scalar inputs, zero/one/many matches, sparse/dense nested mixed
collections, a wide vector with 10,000 matches, deep nesting, and hash maps.
Opaque task-like values use an externally extended Clojure protocol predicate,
matching the form of Quiescent's `groundable?`. This is a synthetic model, **not a
benchmark of Quiescent itself**. Values have stable equality and hashes so set
iteration is reproducible across JVM processes. Saved input fingerprints include
collection types and iteration order, not just a random seed.

## Selecting a run

`bb bench` forwards Clojure `-X` key/value arguments:

```sh
# Show the default cases without measuring or creating results.
bb bench :list? true

# Establish a thorough primary baseline: 30 cases, six JVMs each.
bb bench :forks 6 :label '"Primary baseline"'

# Directional spot-check: full measurement settings, fewer cases and JVMs.
bb bench :forks 2 :fixtures '[:small-one :large-sparse]' :operations '[:path-raw :raw-roundtrip]'

# Faster exploratory measurements; not a full-profile comparison.
bb bench :profile :quick :forks 1 :fixtures '[:small-empty :small-one :small-many]'

# Full measurement of one operation, with an explicit new output directory.
bb bench :fixtures '[:large-sparse]' :operations '[:path-raw]' :output '"benchmarks/results/raw-sparse-before"'

# Less frequent JVM operations, on a smaller selection of fixtures.
bb bench :suite :secondary :fixtures '[:small-many :large-sparse]'

# Compare find and transform against clojure.walk/postwalk on the same fixtures.
bb bench :suite :comparison :fixtures '[:small-many :large-sparse]'

# Exercise all operation and output paths quickly; not a performance baseline.
bb bench :profile :smoke :forks 1 :suite :all :fixtures '[:small-one]'

# Check correctness without benchmarking.
bb bench:check
```

`:suite :all` selects all operations without duplicates. The secondary suite includes vector matches,
vector/function replacements, plain/transducer/early-terminating finds,
`transform-when`, and key-inclusive scans. The comparison suite pairs `:find` with
`:postwalk-find`, and `:transform` with `:postwalk-transform`. Only opaque leaves
match in these fixtures, making postwalk a suitable comparison for this workload;
it is not a general substitute for Pathling's traversal and update semantics.
`:operations` filters within the selected
suite. Unknown options and empty or misspelled selections are errors.

The full profile uses Criterium 0.4.6 defaults: at least 10 seconds of JIT warmup,
60 samples targeting one second each, and bootstrap analysis **in every worker**.
The default 30-case suite with three forks launches 90 measurement JVMs; six
forks launches 180. Allow several hours for a full baseline, including startup,
calibration, GC and analysis. `:list? true` prints the planned measurement count
without creating output or starting workers.

Use filters and fewer forks while developing, keeping the full profile for
comparisons with a full baseline. `:quick` uses Criterium's quick defaults;
`:smoke` has deliberately short measurements. Both are labeled exploratory in
their output. All effective settings are saved; Criterium can extend warmup until
class loading and compilation settle.

`:seed` defaults to `20260914`. `:label` adds a description to the saved options.
Use the same fixture seed, source for the harness, dependency versions, JVM,
hardware, and JVM flags for before/after comparisons.

### Collection-only sequence updates

The opt-in `:sequence-updates` suite measures `:update-function` with navigation
prepared outside timing. It has list, realized lazy-sequence, and queue inputs
of lengths 16, 1,024 and 16,384, with only the collection itself matching. The
replacement callback applies prebuilt metadata, doing constant-size useful work
and producing a result without rebuilding the elements. Each collection type
also has a 1,024-element child-update control that replaces its middle element.
Child-update reconstruction retains the existing list-result behavior.

```sh
bb bench :suite :sequence-updates :list? true
bb bench :suite :sequence-updates :forks 3 :fixtures '[:seq-list-collection-16 :seq-list-collection-1024 :seq-list-collection-16384 :seq-list-child-1024]'
```

These cases are separate from the existing fixtures, including `:suite :all`;
the default primary suite still contains 30 cases. They model collection-only
replacement, not Quiescent's usual opaque-leaf workload. Their fingerprints
include collection representation, metadata and values; lazy inputs are realized
before timing. Compare control and candidate with the same new harness and input
definitions on the established benchmark machine. Existing baseline cases are
retained, but they are not references for these new workloads.

## Saved output

Each invocation creates a new directory under `benchmarks/results/` (or `:output`):

```text
batch.edn                 Options, provenance, schedule, progress and JVM results
summary.md                Per-case variation and links to every measurement
git.patch                 Tracked changes relative to HEAD
sources.zip               Library, benchmark, test and build source snapshot
fork-1/<fixture>/<operation>/
  options.edn             Exact worker options
  console.log             Worker standard output and error
  exit-status             Worker process exit status
  measurement/
    run.edn
    summary.md
    git.patch
    sources.zip
fork-2/...
```

`batch.edn` records `:execution-mode :per-case-jvm`, each process's PID/start time,
its launch command, and the planned execution order. `:active-run` identifies a
running or interrupted worker. Completed child results are accepted only after
checking their selection, options, sample count, correctness status, process
identity and provenance against the coordinator. Fixture fingerprints must also
match between forks. A failed process or inconsistent result stops the batch;
previous completed results are retained.

The summary reports every JVM mean plus each case's median, minimum, maximum,
allocation range and `(max - min) / median` spread. It keeps all repetitions;
there is no outlier deletion or automatic performance threshold. A single fork
cannot estimate between-JVM variability. Within-worker bootstrap intervals
remain available separately and do not capture between-JVM variability.

Criterium subtracts estimated loop overhead before returning samples and
estimates. Nearly empty operations can consequently have finite zero or negative
means. These are retained and flagged; percentage spread is omitted for any case
containing a nonpositive mean. Such estimates do not resolve the operation's cost
and cannot support percentage-based performance claims. Non-finite means remain
errors. The measurement settings and overhead estimation are unchanged.

Every worker's `measurement/` directory contains the original artifact format:

- **`run.edn`**: schema version, status, selected cases, options, timestamps,
  revision and working-tree status, source/harness/compiled-class SHA-256 hashes,
  loaded scanner location, dependency versions, JVM/OS/CPU/GC details, fixture
  specifications/fingerprints, validation status, and per-case measurements.
- **`summary.md`**: a readable table of mean time, bootstrap intervals, and
  allocated bytes per invocation.
- **`git.patch`**: tracked working-tree changes relative to HEAD.
- **`sources.zip`**: library, harness, test, and build source snapshots, including
  untracked files in those locations, so saved results retain the measured source.

Existing output directories are never overwritten. `batch.edn` is atomically
checkpointed before launching and after validating each worker; `run.edn` is
checkpointed by the worker. A measurement error records `:failed` and preserves
earlier results. A killed process may leave `:running`; the coordinator attempts
to terminate its active child on shutdown. Do not reuse an interrupted output
directory. Only a `:complete`, validated full-profile batch with all planned
repetitions is a candidate baseline, and its observed variability still needs review.

Timing retains Criterium's returned samples, execution counts, warmup details,
estimates, and intervals. `:samples` are **overhead-adjusted nanoseconds per batch**,
and estimates such as `:mean` are **seconds per call**. To normalize a sample to
microseconds per call, divide by `:execution-count` and by 1000; **do not subtract
overhead again**. Adjusted samples and `:total-time` can also be negative for
nearly empty operations; they are not unadjusted clock durations. Actual return values are
passed to Criterium's result sink, validated outside timing, then omitted from EDN.
The independent fixture oracle checks traversal order and transformed output;
repeatability checks catch accidental reuse of mutated state.

Allocation is measured separately after timing, using the JVM's
`com.sun.management.ThreadMXBean` current-thread allocated-byte counter and the
same Criterium execution loop. These are approximate **allocated bytes**, not live
heap size or object counts. Empty-loop control samples are saved without
subtraction. JVMs without the counter report `:unsupported`. This scope is
appropriate for synchronous Pathling operations, not for allocations in other
threads. See the [JDK API](https://docs.oracle.com/en/java/javase/25/docs/api/jdk.management/com/sun/management/ThreadMXBean.html).

Results are ignored by Git by default. A reviewed baseline can live in an external
archive, or be copied into `benchmarks/baselines/` and committed with the relevant
harness. Retain the entire batch, including every child result. Keep the same
machine, case selection, harness, toolchain, seed, profile and isolation method
for comparisons. Do not compare isolated results directly with older shared-JVM
results as evidence of an implementation improvement.

Use at least three forks for routine assessment and more for an initial reference
or ambiguous results. Review between-process variability and host activity before
accepting a baseline. Keep representative mixed workloads within cases: isolation
removes accidental history from other benchmarks, while each fixture still tests
its own collection mix. Correctness checks and focused full-profile spot-checks
support iteration; repeat the complete suite for the final assessment. Rerun the
reference alongside a candidate when small or surprising differences need checking.
Run batches sequentially on an otherwise idle machine.

The saved runner replaces the former REPL-only benchmark namespace. For interactive
exploration, start a REPL with `clojure -Srepro -M:dev:bench` after compiling Java,
then call the same runner:

```clojure
(require '[pathling.benchmark :as bench])
(bench/run! {:profile :quick :fixtures [:small-one]})
```

REPL calls share JVM state. Use `bb bench` for per-case isolation and repetition.

For deliberate experiments with the former shared-JVM execution model:

```sh
bb bench:shared :fixtures '[:small-one :deep-one]'
```

This runs all selected cases in one fresh JVM, produces the original `run.edn`
layout, and does not accept `:forks`. It retains the old execution model for
controlled investigations; keep those references separate from isolated batches.

ClojureScript still has its correctness suite (`bb test:cljs`); this runner is
JVM-only, reflecting its higher performance priority.
