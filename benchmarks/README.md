# JVM benchmarks

Run from the repository root:

```sh
bb bench
```

This compiles Java, runs the library and benchmark correctness tests, then starts
a **fresh JVM** with the benchmark dependencies and JVM options in `deps.edn`.
The command uses `-Srepro` to exclude user-level Clojure configuration.
No library implementation is changed by benchmarking.

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

# Faster exploratory run of the most common small-input cases.
bb bench :profile :quick :fixtures '[:small-empty :small-one :small-many]'

# Full measurement of one operation, with an explicit new output directory.
bb bench :fixtures '[:large-sparse]' :operations '[:path-raw]' :output '"benchmarks/results/raw-sparse-before"'

# Less frequent JVM operations, on a smaller selection of fixtures.
bb bench :suite :secondary :fixtures '[:small-many :large-sparse]'

# Compare find and transform against clojure.walk/postwalk on the same fixtures.
bb bench :suite :comparison :fixtures '[:small-many :large-sparse]'

# Exercise all operation and output paths quickly; not a performance baseline.
bb bench :profile :smoke :suite :all :fixtures '[:small-one]'

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
60 samples targeting one second each, and bootstrap analysis. The default 30-case
suite therefore takes **at least about 35 minutes**, plus calibration, GC, and
analysis. Use filters while developing. `:quick` uses Criterium's quick defaults;
`:smoke` has deliberately short measurements. Both are labeled exploratory in
their output. All effective settings are saved; Criterium can extend warmup until
class loading and compilation settle.

`:seed` defaults to `20260914`. `:label` adds a description to the saved options.
Use the same fixture seed, source for the harness, dependency versions, JVM,
hardware, and JVM flags for before/after comparisons.

## Saved output

Each invocation creates a new directory under `benchmarks/results/` containing:

- **`run.edn`**: schema version, status, selected cases, options, timestamps,
  revision and working-tree status, source/harness/compiled-class SHA-256 hashes,
  loaded scanner location, dependency versions, JVM/OS/CPU/GC details, fixture
  specifications/fingerprints, validation status, and per-case measurements.
- **`summary.md`**: a readable table of mean time, bootstrap intervals, and
  allocated bytes per invocation.
- **`git.patch`**: tracked working-tree changes relative to HEAD.
- **`sources.zip`**: library, harness, test, and build source snapshots, including
  untracked files in those locations, so saved results retain the measured source.

Existing output directories are never overwritten. `run.edn` is atomically
checkpointed after every completed case. A measurement error records `:failed`
and preserves earlier results; a killed process may leave `:running`. Only a
`:complete` full-profile run is a candidate baseline.

Timing retains raw Criterium samples, execution counts, warmup details, estimates,
and intervals. Sample durations are **nanoseconds per batch**, and estimates such
as `:mean` are **seconds per call**, following Criterium. Actual return values are
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

Results are ignored by Git by default. To retain a reviewed baseline, copy its
directory into `benchmarks/baselines/` and commit it along with the relevant
harness. Keep multiple full runs from separate JVM invocations, ideally at least
three per revision, on an otherwise idle machine. Assess between-process
variability as well as within-run intervals; no automatic pass/fail threshold is
implied. Run measurements sequentially, never concurrently.

The saved runner replaces the former REPL-only benchmark namespace. For interactive
exploration, start a REPL with `clojure -Srepro -M:dev:bench` after compiling Java,
then call the same runner:

```clojure
(require '[pathling.benchmark :as bench])
(bench/run! {:profile :quick :fixtures [:small-one]})
```

REPL calls share JVM state; use `bb bench` for fresh-process measurements.

ClojureScript still has its correctness suite (`bb test:cljs`); this runner is
JVM-only, reflecting its higher performance priority.
