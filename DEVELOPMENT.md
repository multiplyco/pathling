# Development notes

This records the correctness and JVM optimization work completed through `b227ede`.
The final comparison against the original baseline validated all 30 primary cases
and four additional controls, with six fresh JVMs per version/case. The accepted
changes substantially improve the intended map/vector workloads; small deep-scan
and transducer costs remain documented tradeoffs. This completes that optimization
round, not the scope of the next release. Release notes accumulate under Unreleased.

The work began on 2026-09-15 following the correctness fixes in `74f0935`, with
vector-update proposals added on 2026-10-09. Broader sequence reconstruction and
extending native traversal to other scanners remain optional follow-up work.
Apply the [implementation priorities](README.md#implementation-priorities)
when evaluating them.

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
