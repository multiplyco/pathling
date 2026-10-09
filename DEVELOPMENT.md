# Next implementation steps

Proposed on 2026-09-15, following the correctness fixes in `74f0935` and the
implementation guidance in `0a6c9e9`, with vector-update proposals added on
2026-10-09. Sequence updates, hash-map traversal and the supporting scaling
suite are implemented; remaining candidates are described below.
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

## 2. Native map traversal: hash maps implemented, array maps proposed

In [ScannerMatchesNav.java](src/co/multiply/pathling/ScannerMatchesNav.java),
`pathHashMap` now uses `kvreduce` with one callback per scan, shared through
recursive traversal. Each map's child navigation is carried in the reduction
accumulator. This scanner serves both raw and vector match outputs.

`pathArrayMap` still iterates keys and separately looks up each value. Those
lookups occur regardless of whether the values contain matches.

Evaluate native array-map reduction separately. Account for callback dispatch
and state management, and avoid replacing iterator allocation with a callback
allocation for every map. Broader scanner changes require their own measurements.

Preserve traversal order, original collection identity, nested recursion, and
navigation/replacement correspondence. Include nil keys and hash collisions in
correctness checks. Compare array maps and hash maps separately, then check mixed
structures and dense matches before extending the approach to other scanners.

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

### Step 1: positional scalar replacements (implemented candidate)

For `ListReplacer`, the original value is ignored: each match consumes the next
supplied replacement. When a vector child has `Nav.Scalar` navigation, the
positional loop now calls `ListReplacer.next()` directly instead of calling
`v.nth` and `Scalar.applyUpdates`. The positional loop is selected once per vector,
keeping the ordinary child-update path for nested navigation. Function replacements
still receive the original value through the generic loop.

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
Performance validation of this candidate remains pending.

### Step 2: sequential construction for fully replaced vectors (deferred)

When every vector position is a direct scalar match and replacements are supplied
positionally, evaluate building a new vector sequentially with transient `conj`
instead of repeatedly assigning into a transient copy of the original. Skip
`REMOVE` values during construction to avoid a subsequent removal pass.

Require complete positional coverage; sparse updates should retain structural
sharing through the current updater. Preserve replacement consumption order,
metadata and terminal parent replacement. Evaluate construction cost and allocated
bytes rather than assuming this path saves either. Do not combine it with step 1.

Measure step 1 against its immediate predecessor using the same harness. Include
wide dense updates, sparse updates, mixed nesting and function replacements.
Profiles identify candidate work to remove; their sample shares are not predicted
speedups. Keep the existing regression comparison's source snapshot separate from
new implementation candidates.
