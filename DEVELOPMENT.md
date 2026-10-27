# Development notes

This records the correctness and JVM optimization work completed through `b227ede`.
The final comparison against the original baseline validated all 30 primary cases
and four additional controls, with six fresh JVMs per version/case. The accepted
changes substantially improve the intended map/vector workloads; small deep-scan
and transducer costs remain documented tradeoffs. These optimizations and the
subsequent correctness hardening are covered by the [1.0.0 release notes](CHANGELOG.md).

The [controlled postwalk comparison](benchmarks/comparisons/2026-10-16-postwalk/README.md)
is also complete: 240 full workers, 40 smoke checks and all captured-file checks
passed. The refreshed README reports both the gains and the dense wide-vector
transform case where postwalk wins. This closes the pending comparison work;
the [custom traversal proposal](plans/custom-traversal.md) is deferred development.
An additional [staged comparison](benchmarks/README.md#value-only-discovery-and-staged-collective-updates)
now measures value-only `tree-seq` discovery and separate collection/replacement
costs. User batch processing is excluded, and derived totals add stage estimates.
The [completed post-reboot capture](benchmarks/comparisons/2026-10-19-staged/README.md)
validated 300 full workers, 50 smoke checks and 4,247 captured-file checks. All
28 timing comparisons with resolvable estimates favor Pathling in every paired
round and block; two empty-replacement cases remain unresolved. Derived traversal
totals favor Pathling by 45.36× on sparse mixed data, 8.92× on dense mixed data
and 4.83× on the wide vector, excluding user batch work. The OS upgrade is an
explicit environment boundary; interrupted and earlier measurements stay separate.
This completes the benchmark-only extension without changing the library.

Subsequent release hardening fixes CLJS collection fallback and removal-sentinel
behavior, checks both development and advanced compilation, and documents the
public traversal/replacement contracts. JVM struct maps now expose their keys
consistently across scanners: value edits and unchanged base keys preserve the
struct, while renaming or removing a base key converts the affected struct to
an ordinary map. Extension edits preserve the basis. These changes have correctness
coverage, including `clojure.xml` elements; the saved performance reports still
describe their original measured snapshots, not these subsequent changes.

A focused JVM spillover check covered six non-struct controls with 48 full
measurements. It found no broad regression, but a 2.93% median slowdown in sparse
hash-map scanning prompted a 24-measurement confirmation with a deep-scan control.
The confirmation used six fresh JVMs per version/case: hash-map scanning was
1.45% faster by median and 0.60% faster by mean, with three pairs faster and three
slower. Deep scanning was essentially flat (0.49% faster by median), and allocation
was unchanged in both cases. The initial and confirmation captures used different
host configurations and remain separate; the earlier slowdown did not reproduce
in the current configuration, and its cause remains unresolved.
This completes the focused JVM spillover check without establishing a speedup or
measuring CLJS performance.

The work began on 2026-09-15 following the correctness fixes in `74f0935`, with
vector-update proposals added on 2026-10-09. Broader sequence reconstruction and
extending native traversal to other scanners remain optional follow-up work.

## Correctness checks

`bb test` runs the JVM tests, release workflow tests, and the ClojureScript browser suite in both development and
advanced compilation modes.
For individual checks, use `bb test:clj`, `bb test:cljs`, or `bb test:cljs:release`. `bb bench:check` additionally checks
the benchmark fixtures and harness without running performance measurements.

## Benchmarks

Run `bb bench` to compile, check correctness, and save per-case JVM benchmark
results. The default suite prioritizes raw `path-when` and `update-paths` usage.
See [benchmark commands and output format](benchmarks/README.md) for focused runs,
quick checks, and retaining baselines.

## Implementation guide

The [Java package documentation](src/co/multiply/pathling/package-info.java) maps the scanner families, explains
the navigation/update boundary, and records the ownership and traversal contracts shared by the specialized
implementations.

## Preparing and publishing a release

Development checks require JDK 21+, Clojure CLI, Babashka, Bun, Chrome and cljstyle.
Install the browser-test dependencies with `bun install --frozen-lockfile`.
Browser tests use `127.0.0.1:9876` (or the next available port), independently of
application `PORT`, `IP` and `LISTEN_ADDR` variables inherited from the shell.

Run `bb release:check` to check formatting, run the JVM, CLJS development/advanced
and release-workflow tests, check the benchmark fixtures/harness, and build the
JAR. This task runs no performance measurements and publishes nothing. It accepts
uncommitted changes so a proposed release can be checked before committing.
`bb test:build` runs just the release-workflow tests, including real subprocess
checks in a temporary Git repository. Tagging, pushing and Clojars deployment
are replaced in the tests; the project's Git refs and remote repositories are
not modified.

For publication, set the version in `build.clj` and the README installation
example, date the changelog entry, and commit the release changes. Then run
`bb deploy` with the Clojars credentials in
`CLOJARS_DEPLOY_MAVEN_USERNAME` and `CLOJARS_DEPLOY_MAVEN_PASSWORD`.

`bb deploy` requires a clean checkout, runs the checks and build, verifies that
the checkout and HEAD have not changed, creates and pushes the version tag at
that checked commit, and deploys the same JAR without rebuilding it. A failed
check, build, tag or push stops subsequent steps. `bb tag` performs the same
validation but only creates and pushes the tag; it is not a prerequisite for
`bb deploy`. The corresponding `clojure -T:build` tasks enforce the same checks.

Tag pushing and Clojars deployment are separate operations. If publication fails
after creating or pushing a tag, inspect the local/remote state before retrying;
the tasks do not overwrite existing tags or undo a completed publication.

## 1. Implemented: avoid materializing sequences for collection-only updates

In [Nav.java](src/co/multiply/pathling/Nav.java), `SeqNav.applyUpdates` now checks
for child updates before copying the input into an `ArrayList`. A collection-only
match goes directly to the replacement callback with the original collection.
The child-update and sequence reconstruction logic is unchanged.

This removes unnecessary work proportional to sequence length when only the
collection itself matched. Broader sequence reconstruction changes remain deferred.

JVM tests cover empty and nonempty lists, lazy sequences, and queues, including
identity, metadata, replacement order, removal, and child-before-parent updates.
The opt-in [`:sequence-updates` suite](benchmarks/README.md#collection-only-sequence-updates)
measures metadata replacements at several lengths, with navigation prepared
outside timing and a child-update control for each collection type. Existing
baseline cases remain unchanged. These collection-only cases are useful for
Pathling generally; Quiescent's usual workload matches opaque leaves instead.

## 2. Implemented: native hash-map and array-map traversal

In [ScannerMatchesNav.java](src/co/multiply/pathling/ScannerMatchesNav.java),
`pathHashMap` now uses `kvreduce` with one callback per scan, shared through
recursive traversal. Each map's child navigation is carried in the reduction
accumulator. This scanner serves both raw and vector match outputs.

The array-map path replaces key iteration and repeated value lookup with
native `kvreduce`, reusing the existing per-scan callback. Its accumulator keeps
nested map state separate. Only the array-map path in this scanner changes;
broader scanner changes require their own measurements.

This change is retained after separate baseline/candidate comparisons on
Clojure 1.12.4 and pinned 1.13.0-alpha9. Clojure 1.13 allows keyword array maps to
grow to 64 entries; tests cover ordinary persistent, transient and reader
construction around both the 8/9 and 64/65 boundaries. The opt-in
`:array-map-traversal` suite records actual map classes and traversal fingerprints,
with zero, one and dense matches, mixed keys and explicit hash-map controls.
Do not interpret changes between Clojure versions as gains from this incision.

The dedicated array-map cases improved in every matched pair and measurement
block on their respective runtimes. Dense eight-keyword scans on 1.12.4 improved
in all ten pairs across the initial comparison and confirmation. On 1.13,
64-keyword scans took about 45% less time with one match and 69% less with no
matches; dense scan/update roundtrips took about 12% less. Allocation in the
direct fixtures was unchanged, so these results primarily support CPU savings.

A flagged dense hash-map control did not show a consistent slowdown on recheck.
Keeping all ten JVMs per version across both captures gives a 0.5% lower median
and 0.15% higher mean, with slow JVMs on both sides. This supports retaining the
change without claiming exact equivalence or a reliable hash-map speedup.

Regression coverage preserves traversal order, original collection identity,
nested recursion, navigation/replacement correspondence, nil and colliding keys,
metadata, reentrant predicates and concurrent scans. Compare array maps and hash
maps separately, then check mixed structures and dense matches before extending
the approach to other scanners.

## 3. Implemented: benchmarks that expose allocation growth

The opaque-leaf [fixture set](bench/pathling/fixtures.clj) includes small zero/one-match
structures and a large sparse structure with 31 matches. It does not provide a
controlled size series with a fixed zero or one match.

The opt-in [`:sparse-scaling` suite](benchmarks/README.md#fixed-zeroone-match-scaling)
adds that controlled series: repeated array maps, hash maps and mixed blocks at
64, 1,024 and 16,384 leaves, with zero or one match. Collection types, block layouts,
depths and match placement stay fixed within each family. The collection-only
sequence suite above provides a separate update-only size series.

The scaling suite measures raw `path-when`, update-only work, and the roundtrip.
Review execution time, allocated bytes per operation, and variation across JVMs.
Distinguish total allocation from retained navigation size; investigate sustained
GC effects when a timing/allocation tradeoff needs more evidence.

Use the [saved benchmark workflow](benchmarks/README.md). Measure the current
implementation and each candidate with the same harness and settings in the
established baseline environment. New cases need control measurements; retain
the existing baseline and case definitions. Use correctness checks and focused
comparisons during iteration, then rerun the complete suite for the final verdict.

## 4. Vector updates: two separate optimization steps

The generic `VecEdit.applyUpdates` loop reads each affected original element,
dispatches through child navigation, and writes the replacement with transient
`assocN`. Treat reducing that dispatch/lookup work and changing reconstruction
as separate candidates so their effects can be measured independently.

### Step 1: positional scalar replacements

For `ListReplacer`, the original value is ignored: each match consumes the next
supplied replacement. When a vector child has `Nav.Scalar` navigation, the
positional loop now calls `ListReplacer.next()` directly instead of calling
`v.nth` and `Scalar.applyUpdates`. Function replacements still receive the original
value through the generic loop.

The positional loop remains available for single-child vectors. A candidate that
required more than one child-navigation entry was evaluated and rejected: it
did not remove the deep-update cost and lost performance on shallow one-match
updates. Its added test and benchmark controls are retained.

Extracting both loops into private helpers restored recursive inlining and
recovered most of the deep-update cost, but slowed function replacements.
Compiler diagnostics found variable callback inlining: some JVMs retain a call to
`Replacer.replace` or `IFn.invoke` inside the loop. Both step 1 and the helpers can
take those less optimized paths, so the helper layout alone is not a proven cause.

Giving the function helper a `FunctionReplacer` parameter did not fix that
regression. It exposed the concrete target to the compiler, but the tested JVMs
still declined to inline the callback chain and clean timings did not improve.

The retained implementation extracts only the positional loop. The generic function
loop stays in `applyUpdates`, alongside the outer replacer test, to preserve more
of step 1's compilation context. The positional scalar shortcut remains available
for single-match vectors. Loop order, transient reconstruction, removals and final
parent replacement are unchanged. No JVM inlining limits are overridden.

Compiler checks confirm recursive inlining with this smaller entry point, and
measurements show a repeatable deep-update improvement. Function-update results
remain variable between JVM batches, including a ranking reversal of unchanged
controls. This layout is retained without claiming a function-performance fix.

Keep transient assignment, delayed removals, metadata restoration and the final
parent replacement in their existing order. Direct and nested matches must share
one replacement cursor, including map-key matches and collection-only matches.
The API, input immutability and repeatability of retained navigation must remain
unchanged. This primarily targets CPU overhead; reconstruction still allocates.

The JVM [vector update tests](test/co/multiply/pathling/vector_update_test.clj)
cover dense/sparse scalar matches, vector tail/tree boundaries, nil/false
replacements, removals down to 0/1/31/32/33 elements, mixed scalar/nested navigation,
raw accumulator reuse, collection-only matches, child-before-parent callbacks,
untouched-subtree identity, short/trailing replacement lists and the
function-replacement fallback. They also
exercise persistent-vector, ArrayList, LinkedList and custom Java List replacements.
The opt-in [`:vector-updates` control](benchmarks/README.md#sparse-vector-updates)
adds a wide vector with one matching leaf to measure the single-child tradeoff.
The single-child gate and typed function helper are not retained.

### Direct scalar function replacements

The function loop calls `r.replace(original)` directly for scalar navigation.
It reads the original vector element once for both scalar and nested matches; function
replacements require that value. Nested navigation still uses `applyUpdates`.
This removes the shared scalar wrapper from the hot callback path and gives the
loop its own call-site profile without changing the positional helper.

This change is retained after modest measured improvements in wide, deep and
sparse function updates, with unchanged allocation in those cases. Callback
inlining still varies between JVMs; these results do not establish a fixed speedup
or resolve every comparison with the earlier layout. Regression coverage checks
callback order, original values, removals, metadata and repeatability, including
sparse matches and vector tail/tree boundaries.

### Step 2: sequential construction for fully replaced vectors

For ordinary `PersistentVector` values larger than 32 elements, the updater
checks that navigation covers every position in order with direct scalar matches.
It completes this check before consuming any positional replacement. Eligible
vectors are built sequentially with transient `conj`; `REMOVE` values are skipped
during construction, avoiding the separate removal pass.

Tail-sized vectors, sparse or nested navigation, and other vector implementations
use the existing updater. The size gate keeps single-match/deep paths out of the
eligibility scan. Replacement order, metadata and terminal parent replacement are
preserved. No navigation fields or scanner logic are changed.

This change is retained after comparison with the preceding direct-scalar
implementation using the same harness. Wide positional updates took about 45% less
time, scan/update roundtrips 17% less, and removal-heavy cases 64–84% less by median.
All four matched JVM pairs and both measurement blocks favored these cases.
Removal-heavy allocation fell 88–99.9%; allocation without removals was unchanged.

The accepted tradeoffs are small consistent costs on the single-match vector
control (about 1.8%) and mixed dense updates (about 1.2%). Deep updates showed no
consistent regression, and compiler checks retained recursive inlining. Other
controls remain subject to JVM variation; the results do not establish a universal
speedup or an optimal size threshold. The opt-in dense-vector benchmark suite
covers removals, the 32/33-element boundary and late nested-child fallback.

Profiles identify candidate work to remove; their sample shares are not predicted
speedups. Keep the existing regression comparison's source snapshot separate from
new implementation candidates.
