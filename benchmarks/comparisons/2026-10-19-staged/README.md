# Value-only discovery and staged collective updates — 2026-10-19

This comparison measures read-only finding and the separate collection and
replacement stages of Pathling's collect → batch-process → replace workflow. **User batch processing is excluded from
every measurement.** Reported traversal
totals add the two stage medians; they are not measured end-to-end calls.

All 28 comparisons with positive timings in every JVM favor Pathling in all six
matched rounds and all three balanced blocks. Empty replacement operations have
nonpositive overhead-adjusted estimates in some JVMs; their time ratios and the
corresponding aggregate time ratios remain unresolved. Every observation is kept.

For sparse mixed data, dense mixed data and the wide vector, the derived totals
favor Pathling by **45.36×, 8.92× and 4.83×**, respectively. These compare with the
specific tree-seq + postwalk implementation below, not every possible Clojure
traversal, and are not application-wide speedups.

## Environment and validation

The measured library is **unreleased `b227ede`**, with support base `0b3ca22` plus
the captured staged benchmark changes. All 68 workstation inputs matched the
frozen snapshot before publication edits. The 18 library source hashes and 33
compiled class hashes match the retained implementation; no library code changed.

- Intel Core i5-4590S, four cores / four hardware threads, 16 GiB RAM.
- Ubuntu 26.04.1 LTS, Linux 7.0.0-38-generic, glibc 2.43.
- Amazon Corretto 26.0.2+10-FR, Clojure 1.12.4, Criterium 0.4.6;
  `-Xmx2g -Xss8m`, fixture seed `20260914`.
- Six fresh JVMs for each of five operations on ten inputs: 300 full measurements.
  Collection operations use all six three-arm orders, grouped into three
  forward/reverse blocks. Replacement uses three balanced ABBA/BAAB blocks.
- Full Criterium profile in each worker: at least 10 seconds of warmup, 60 samples
  targeting one second each, overhead adjustment and bootstrap analysis.
  Allocated bytes are measured separately with thread allocation counters.
- Preflight: **119 tests / 7,831 assertions / 0 failures**. All 50 smoke workers,
  300 full workers and **4,247 captured-file checks** passed. The capture completed
  at 23:09:35 Stockholm on October 19 after 8:23:43 and was reviewed on October 20.

This run followed an OS upgrade and reboot. The interrupted earlier attempt is
preserved separately and contributes no observations. The
[October 16 postwalk report](../2026-10-16-postwalk/README.md) used Ubuntu 24.04.5 /
kernel 6.8.0-139. Its direct-transform timings remain a separate comparison;
changes in absolute times across these captures cannot be attributed to code or
to the comparator alone.

## Compared operations

| Stage     | Pathling                                                       | Clojure comparator                                                      |
|-----------|----------------------------------------------------------------|-------------------------------------------------------------------------|
| Find only | `find-when`, vector of matches or nil, no navigation           | Value-only `tree-seq`, filtered into a vector or nil                    |
| Collect   | `path-when`, default vector matches plus navigation            | The same `tree-seq` collection operation                                |
| Replace   | `update-paths` with prepared navigation and replacement vector | `postwalk` rediscovers matches and consumes the same replacement vector |

The same six tree-seq JVM observations per fixture support both find and collect
contrasts. Those comparisons share a baseline; they are not independent
replications. Every operation runs against the same immutable original input and
within an identical recorded harness/runtime context.

The tree-seq comparator follows map values with `vals`, and other collections
with `seq`. It excludes keys and map-entry wrappers and fully realizes its output:

```clojure
(not-empty
  (into [] (filter groundable?)
    (tree-seq coll? #(if (map? %) (vals %) (seq %)) data)))
```

Only opaque leaves match. Preorder enumeration therefore has the same leaf-match
order as Pathling's child-before-parent traversal. The batch function prepares
`[match-count occurrence-index resolved-value]` for each match outside timing.
Both replacement arms receive that same immutable vector. Postwalk creates a
fresh vector iterator inside each timed call and consumes one replacement per
matching occurrence. It still visits map keys/entries, which do not match here.
Neither arm rescans inserted replacements. Empty collection results allow both
arms to skip replacement and return the original input immediately.

The match oracle is an independent traversal checking order and leaf identity.
The replacement oracle uses postwalk with a precomputed value lookup for these
unique-leaf fixtures; it is not independent of postwalk's collection rebuilding.
Separate tests use literal expected results for equal repeated values, nil/false
replacements, newly matching replacement values, fresh cursors and original input
preservation. Tests also check that batch processing never runs inside timed calls.
See the [implementation](../../../bench/pathling/staged_fixtures.clj),
[tests](../../../bench-test/pathling/staged_fixture_test.clj) and
[protocol](../../README.md#value-only-discovery-and-staged-collective-updates).

## Inputs

These are the original deterministic primary fixtures. Node counts describe
Pathling's value traversal; postwalk replacement also visits keys and entries.
Both implementations must discover the input's shape. There is no `mapv` comparator.

| Fixture           | Shape  | Depth | Breadth | Value nodes | Matches |
|-------------------|--------|------:|--------:|------------:|--------:|
| `scalar-empty`    | scalar |     0 |       1 |           1 |       0 |
| `scalar-one`      | scalar |     0 |       1 |           1 |       1 |
| `small-empty`     | mixed  |     3 |       3 |          40 |       0 |
| `small-one`       | mixed  |     3 |       3 |          40 |       1 |
| `small-many`      | mixed  |     3 |       3 |          40 |      27 |
| `large-sparse`    | mixed  |     5 |       5 |       3,906 |      31 |
| `large-dense`     | mixed  |     5 |       5 |       3,906 |   1,562 |
| `wide-many`       | vector |     1 |  10,000 |      10,001 |  10,000 |
| `deep-one`        | vector |   128 |       1 |         129 |       1 |
| `hash-map-sparse` | map    |     2 |      16 |         273 |       8 |

## Stage timings

Microseconds per call, shown as the median of six JVM means [minimum–maximum JVM
mean]. The range is descriptive, not a confidence interval. Ratios are comparator
median / Pathling median; values above one favor Pathling. Omit ratios when any
contributing timing estimate is nonpositive. Tiny scalar cases are dominated by
fixed overhead and should not be used as headline throughput claims.

| Fixture / stage             |        Pathling µs, median [range] |         Comparator µs, median [range] | Time ratio |
|-----------------------------|-----------------------------------:|--------------------------------------:|-----------:|
| `scalar-empty` / find       |       0.004418 [0.004404–0.006607] |          0.159877 [0.152573–0.166537] |     36.19× |
| `scalar-empty` / collect    |       0.007829 [0.005778–0.007978] |          0.159877 [0.152573–0.166537] |     20.42× |
| `scalar-empty` / replace    |      0.000191 [-0.000112–0.001004] |          0.013536 [0.012719–0.015798] |          — |
| `scalar-one` / find         |       0.010888 [0.010532–0.010966] |          0.182974 [0.177700–0.184636] |     16.81× |
| `scalar-one` / collect      |       0.041006 [0.019813–0.043359] |          0.182974 [0.177700–0.184636] |      4.46× |
| `scalar-one` / replace      |       0.003310 [0.003035–0.003585] |          0.040036 [0.036744–0.044864] |     12.09× |
| `small-empty` / find        |       0.441145 [0.431568–0.450257] |       20.768346 [20.258742–21.405143] |     47.08× |
| `small-empty` / collect     |       0.500872 [0.499528–0.535844] |       20.768346 [20.258742–21.405143] |     41.46× |
| `small-empty` / replace     |      0.000166 [-0.000114–0.000411] |          0.013755 [0.013396–0.014825] |          — |
| `small-one` / find          |       0.490793 [0.477985–0.508139] |       20.806816 [20.299901–21.231597] |     42.39× |
| `small-one` / collect       |       0.614038 [0.609675–0.671687] |       20.806816 [20.299901–21.231597] |     33.89× |
| `small-one` / replace       |       0.237008 [0.203272–0.248718] |          9.358891 [9.230404–9.548771] |     39.49× |
| `small-many` / find         |       0.633546 [0.612595–0.708639] |       21.043848 [20.536824–21.286811] |     33.22× |
| `small-many` / collect      |       1.058230 [1.041771–1.086186] |       21.043848 [20.536824–21.286811] |     19.89× |
| `small-many` / replace      |       2.322185 [2.209440–2.421619] |          9.428731 [9.173667–9.774744] |      4.06× |
| `large-sparse` / find       |    51.156866 [47.189076–53.541548] | 2580.258985 [2552.925342–2626.648157] |     50.44× |
| `large-sparse` / collect    |    61.710675 [57.246492–63.235750] | 2580.258985 [2552.925342–2626.648157] |     41.81× |
| `large-sparse` / replace    |    13.169755 [12.931565–14.206102] |    816.087585 [801.278204–826.880744] |     61.97× |
| `large-dense` / find        | 107.999486 [104.830388–126.626462] | 2648.635405 [2633.650219–2694.376315] |     24.52× |
| `large-dense` / collect     | 134.985147 [133.252852–144.882707] | 2648.635405 [2633.650219–2694.376315] |     19.62× |
| `large-dense` / replace     | 264.417938 [257.705514–265.416094] |    913.050125 [898.381187–929.346463] |      3.45× |
| `wide-many` / find          | 286.060789 [272.066119–289.202729] | 2240.724323 [2219.656502–2310.288183] |      7.83× |
| `wide-many` / collect       | 358.697706 [356.179273–362.428917] | 2240.724323 [2219.656502–2310.288183] |      6.25× |
| `wide-many` / replace       | 195.014812 [174.411077–203.963183] |    435.566400 [427.370959–448.485492] |      2.23× |
| `deep-one` / find           |       0.883248 [0.864566–0.920349] |       50.113233 [47.953964–53.261395] |     56.74× |
| `deep-one` / collect        |       2.758959 [2.725993–2.787827] |       50.113233 [47.953964–53.261395] |     18.16× |
| `deep-one` / replace        |       8.260076 [8.111897–8.347373] |       43.921148 [43.047946–45.718637] |      5.32× |
| `hash-map-sparse` / find    |       4.159319 [4.091732–5.160185] |    105.896010 [104.927874–107.415290] |     25.46× |
| `hash-map-sparse` / collect |       3.141794 [3.129107–3.240356] |    105.896010 [104.927874–107.415290] |     33.71× |
| `hash-map-sparse` / replace |       0.868645 [0.772728–0.906589] |       68.480785 [67.782993–69.506492] |     78.84× |

The wide-vector replacement stage favors Pathling in every pair and block, even
though its means range from 174.411 to 203.963 µs. Postwalk replacement ranges
from 427.371 to 448.485 µs. This differs from the earlier direct-transform case:
collection is now a separate required stage and replacement navigation is already
available. The workload also uses precomputed occurrence-specific replacements.
It does not overturn the earlier dense direct-transform result.

Variable JVMs are retained. A sparse hash-map find takes 5.160 µs and 2,344 bytes,
versus 4.092–4.178 µs and 1,800 bytes in the other five. A dense mixed find takes
126.626 µs versus 104.830–111.267 µs in the other five. Inspection of all six
sample distributions in these cases and wide replacement shows sustained JVM
levels, not merely isolated timing spikes. This uninstrumented capture does not
establish their JIT or host cause. Ratios based on mean JVM times agree with the
reported direction in every resolved case as well.

## Allocated bytes per stage

Medians and observed ranges are rounded to bytes for display. Counter estimates
near zero are not proof that every invocation allocates nothing. Ratios are
omitted when either median is below one byte. Midpoint medians can lie between
observed allocation levels; all fractional observations remain in results.json.

| Fixture / stage             | Pathling bytes, median [range] | Comparator bytes, median [range] | Byte ratio |
|-----------------------------|-------------------------------:|---------------------------------:|-----------:|
| `scalar-empty` / find       |                        0 [0–1] |                    680 [680–680] |          — |
| `scalar-empty` / collect    |                        0 [0–1] |                    680 [680–680] |          — |
| `scalar-empty` / replace    |                        0 [0–0] |                          0 [0–0] |          — |
| `scalar-one` / find         |                     64 [64–70] |                    728 [728–728] |     11.37× |
| `scalar-one` / collect      |                  208 [143–208] |                    728 [728–728] |      3.50× |
| `scalar-one` / replace      |                        0 [0–0] |                    128 [128–128] |          — |
| `small-empty` / find        |                     24 [24–24] |           40,752 [40,752–40,752] |   1697.90× |
| `small-empty` / collect     |                     48 [48–48] |           40,752 [40,752–40,752] |    848.98× |
| `small-empty` / replace     |                        0 [0–0] |                          0 [0–0] |          — |
| `small-one` / find          |                  144 [144–144] |           40,800 [40,800–40,800] |    283.33× |
| `small-one` / collect       |                  616 [616–616] |           40,800 [40,800–40,800] |     66.23× |
| `small-one` / replace       |                  600 [576–600] |           13,936 [13,936–13,936] |     23.23× |
| `small-many` / find         |                  584 [584–584] |           40,904 [40,904–40,904] |     70.04× |
| `small-many` / collect      |            2,960 [2,960–2,960] |           40,904 [40,904–40,904] |     13.82× |
| `small-many` / replace      |            3,584 [3,584–3,584] |           13,936 [13,936–13,936] |      3.89× |
| `large-sparse` / find       |            8,376 [2,296–8,376] |  5,081,764 [5,081,752–5,081,776] |    606.70× |
| `large-sparse` / collect    |         20,064 [13,984–20,064] |  5,081,764 [5,081,752–5,081,776] |    253.28× |
| `large-sparse` / replace    |         25,816 [25,816–26,008] |  1,138,536 [1,138,536–1,138,536] |     44.10× |
| `large-dense` / find        |         39,112 [33,032–39,112] |  5,090,048 [5,090,048–5,090,072] |    130.14× |
| `large-dense` / collect     |      175,096 [169,016–175,096] |  5,090,048 [5,090,048–5,090,072] |     29.07× |
| `large-dense` / replace     |      262,544 [262,544–262,544] |  1,138,992 [1,138,992–1,138,992] |      4.34× |
| `wide-many` / find          |      223,544 [223,544–223,544] |  4,800,468 [4,800,456–4,800,480] |     21.47× |
| `wide-many` / collect       |      632,624 [632,624–632,624] |  4,800,468 [4,800,456–4,800,480] |      7.59× |
| `wide-many` / replace       |         54,600 [54,600–54,600] |        417,440 [417,440–417,440] |      7.65× |
| `deep-one` / find           |                  144 [144–144] |           91,864 [91,840–91,864] |    637.94× |
| `deep-one` / collect        |         16,616 [16,616–16,616] |           91,864 [91,840–91,864] |      5.53× |
| `deep-one` / replace        |         50,200 [50,200–50,200] |        105,600 [105,600–105,600] |      2.10× |
| `hash-map-sparse` / find    |            1,800 [1,800–2,344] |        225,704 [225,680–225,704] |    125.39× |
| `hash-map-sparse` / collect |            1,192 [1,192–1,192] |        225,704 [225,680–225,704] |    189.35× |
| `hash-map-sparse` / replace |            1,864 [1,624–1,864] |        105,800 [105,800–105,800] |     56.76× |

Several Pathling mixed-collection scans retain 6,080-byte-separated allocation
levels: sparse find is 2,296 / 8,376 bytes, sparse collect 13,984 / 20,064, dense
find 33,032 / 39,112, and dense collect 169,016 / 175,096. Sparse replacement is
25,816 / 26,008 bytes; hash replacement is 1,624 / 1,864; small-one replacement
is 576 / 600. Tree-seq often varies by 24 bytes between JVMs. For example, its
wide-vector estimates are 4,800,456 / 4,800,480 bytes, with a median of 4,800,468.
Fractional scalar estimates and all mode frequencies are preserved, not treated
as fixed object sizes. Allocation measurements do not diagnose their cause.

The tree-seq pipeline is allocation-heavy on these inputs: on the wide vector it
allocates about 4.80 MB per find versus 0.224 MB for `find-when`. Avoiding collection
reconstruction does not by itself imply cheap enumeration. These observations
compare the specific fully realized pipeline above, not all ways to write a
read-only Clojure traversal. No comparison with the old postwalk-find numbers is
inferred across the OS/harness boundary.

## Derived traversal totals

For each implementation, add the median collection time to the median replacement
time; do the same for allocated bytes. Batch processing and replacement preparation
are excluded. This is a sum of independently measured stage estimates, not a
measured end-to-end call, a median of paired sums or a model of inter-stage cache
and whole-workflow GC effects. It is not an application-wide aggregate across
fixtures. Unresolved empty-replacement timings suppress the corresponding total
time ratio even though the absolute stage estimates are retained.

| Fixture           | Pathling µs | Comparator µs | Time ratio | Pathling bytes | Comparator bytes | Byte ratio |
|-------------------|------------:|--------------:|-----------:|---------------:|-----------------:|-----------:|
| `scalar-empty`    |    0.008021 |      0.173412 |          — |              0 |              680 |          — |
| `scalar-one`      |    0.044316 |      0.223010 |      5.03× |            208 |              856 |      4.12× |
| `small-empty`     |    0.501038 |     20.782101 |          — |             48 |           40,752 |    848.95× |
| `small-one`       |    0.851046 |     30.165707 |     35.45× |          1,216 |           54,736 |     45.01× |
| `small-many`      |    3.380415 |     30.472579 |      9.01× |          6,544 |           54,840 |      8.38× |
| `large-sparse`    |   74.880430 |   3396.346569 |     45.36× |         45,880 |        6,220,300 |    135.58× |
| `large-dense`     |  399.403085 |   3561.685530 |      8.92× |        437,640 |        6,229,040 |     14.23× |
| `wide-many`       |  553.712518 |   2676.290723 |      4.83× |        687,224 |        5,217,908 |      7.59× |
| `deep-one`        |   11.019035 |     94.034381 |      8.53× |         66,816 |          197,464 |      2.96× |
| `hash-map-sparse` |    4.010439 |    174.376795 |     43.48× |          3,056 |          331,504 |    108.48× |

For the wide vector, collection costs 358.698 µs with Pathling versus 2,240.724 µs
with tree-seq; replacement costs 195.015 µs versus 435.566 µs. Their sums are
553.713 µs and 2,676.291 µs (4.83×). Sparse mixed totals are 74.880 µs versus
3,396.347 µs (45.36×), and dense mixed totals 399.403 µs versus 3,561.686 µs (8.92×).
Derived allocated-byte totals for these same inputs favor Pathling by 7.59×,
135.58× and 14.23×, respectively. These exclude allocations by the batch function.

## Host observations and limits

No package transaction or reboot overlapped the capture; the thermal service was
not restarted, and no swap traffic was recorded. External CPU use was 0.25% of
the machine at the median ten-second interval, with an 8.45% capture-wide peak.
There were periodic synchronization-process bursts: 43 intervals over 5% external
CPU overlapped 46 full-worker envelopes, including interval boundaries. Three
other workers overlapped 1–2% I/O wait. Every observation remains in the results.
All 28 resolved comparisons still agree in every round and block.

Those worker envelopes include startup, warmup, timing and allocation checks;
observed overlap does not prove an effect on specific samples. Host sampling
cannot exclude shorter interference. All-core frequency samples cannot identify
the benchmark core. No profiler, CPU policy or service changes were introduced
for measurement. Timing variation, allocation modes, small scalar estimates and
the shared tree-seq baseline limit how broadly the results should be interpreted.

[Machine-readable results](results.json) retain all 300 unique JVM observations,
fixture fingerprints, per-case statistics, paired rounds, block means and derived
stage totals. Original raw samples, source snapshots and host logs remain archived
in the benchmark environment. No new measurement was started during review.
