# Next implementation steps

Proposed on 2026-09-15, following the correctness fixes in `74f0935` and the
implementation guidance in `0a6c9e9`. The sequence-update change below is implemented;
the remaining items are proposals. Apply the [implementation priorities](README.md#implementation-priorities)
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

## 2. Evaluate native map traversal for raw `path-when`

In [ScannerMatchesNav.java](src/co/multiply/pathling/ScannerMatchesNav.java),
`pathArrayMap` iterates keys and separately looks up each value. `pathHashMap`
traverses entry objects. These costs are incurred while visiting entries,
regardless of whether their values contain matches.

Both concrete map types expose `kvreduce`, which visits keys and values through
their native representation. Evaluate it as an alternative, initially within
the raw `path-when` implementation. It is a candidate, not an established win:
account for callback dispatch and state management, and avoid replacing iterator
allocation with a callback allocation for every map.

Preserve traversal order, original collection identity, nested recursion, and
navigation/replacement correspondence. Include nil keys and hash collisions in
correctness checks. Compare array maps and hash maps separately, then check mixed
structures and dense matches before extending the approach to other scanners.

## 3. Add benchmarks that expose allocation growth

The opaque-leaf [fixture set](bench/pathling/fixtures.clj) includes small zero/one-match
structures and a large sparse structure with 31 matches. It does not provide a
controlled size series with a fixed zero or one match.

Add that series before assessing the broader traversal changes. Keep collection
type and match placement/depth controlled so changes in allocation can be related
to input size. Include mixed structures as well as focused map cases. The
collection-only sequence suite above provides a separate update-only size series.

Measure raw `path-when`, update-only work, and the roundtrip where relevant.
Report execution time, allocated bytes per operation, and variation across JVMs.
Distinguish total allocation from retained navigation size; investigate sustained
GC effects when a timing/allocation tradeoff needs more evidence.

Use the [saved benchmark workflow](benchmarks/README.md). Measure the current
implementation and each candidate with the same harness and settings in the
established baseline environment. New cases need control measurements; retain
the existing baseline and case definitions. Use correctness checks and focused
comparisons during iteration, then rerun the complete suite for the final verdict.
