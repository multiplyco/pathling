# Controlled comparison with postwalk — 2026-10-16

This capture compares direct `find-when` and `transform-when` with the benchmark's
`clojure.walk/postwalk` implementations. Pathling has lower median time and
allocation in 19 of 20 cases. The exception is transforming a flat vector whose
10,000 leaves all match: Pathling takes **9.59% more time** and allocates **7.07%
more bytes**. Every timing comparison has the same winner in all six adjacent pairs and all
three balanced blocks. This supports the reported directions on these inputs;
it does not establish a universal speedup or an application-wide score.

Library code is the **unreleased `b227ede` implementation**, with benchmark and
documentation support committed as `0b3ca22`. All 63 captured input files match
that support commit. These figures do not describe the 0.2.1
release and are not a before/after comparison with the former M1 README table.

## Workload and method

- Intel Core i5-4590S, four cores / four hardware threads, 16 GiB RAM;
  Ubuntu 24.04.5 LTS, kernel 6.8.0-139-generic.
- Amazon Corretto 26.0.2+10-FR, Clojure 1.12.4, Criterium 0.4.6;
  `-Xmx2g -Xss8m`, fixture seed `20260914`.
- Six fresh, sequential JVMs per implementation/fixture/operation: 240 full
  workers. Three balanced ABBA/BAAB blocks interleave the implementations.
- Each JVM uses the full profile: at least 10 seconds of warmup and 60 timing
  samples targeting one second each, with Criterium's overhead adjustment and
  analysis. Allocation is measured separately through thread allocated-byte
  counters. Both implementations share the same checkout and runtime context.
- Correctness preflight passed 113 tests / 7,589 assertions. All 40 smoke workers,
  240 full workers and 3,397 captured-file checksum checks passed. Captured source,
  compiled classes and fixture fingerprints were validated against the retained
  implementation and fixture references.

Only opaque task-like leaves match. The predicate uses the same externally
extended protocol in both implementations. Both find operations return a vector
of original matching leaves in traversal order, or `nil` when empty. Postwalk
collects with a volatile containing a transient vector, retains each `conj!`
result, and persists once. The callback returns the visited value unchanged,
although postwalk itself rebuilds collections and visits map keys and entries.
Both transforms use the same predicate and leaf replacement function.

Find order and identity are checked against an independent reference traversal.
Expected transformed values are prepared with postwalk outside measurement;
Pathling's results are checked against that reference. This is a comparison of
these implementations, not every possible optimized postwalk collector.
See the [harness](../../../bench/pathling/fixtures.clj) and
[comparison protocol](../../README.md#comparison-with-postwalk).

## Inputs

Node counts describe Pathling's value traversal. Postwalk additionally visits
map keys and entries. Small and large mixed inputs contain vectors, maps, sets
and lists; shape, leaf placement and traversal fingerprints are deterministic.

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

## Timing

Times are microseconds per complete call. Each cell gives the median of six JVM
means, followed by the observed minimum–maximum JVM mean. Ranges describe this
capture's variation; they are not confidence intervals. The ratio is postwalk's
median divided by Pathling's: values above one favor Pathling. All per-JVM means
were positive; tiny scalar cases are dominated by fixed overhead and should not
be used as headline throughput claims.

| Fixture / operation           |        Pathling µs, median [range] |        Postwalk µs, median [range] | Time ratio |
|-------------------------------|-----------------------------------:|-----------------------------------:|-----------:|
| `scalar-empty` / find         |       0.004544 [0.004541–0.004549] |       0.080630 [0.079258–0.087997] |     17.74× |
| `scalar-empty` / transform    |       0.009059 [0.007085–0.009380] |       0.014826 [0.014418–0.020513] |      1.64× |
| `scalar-one` / find           |       0.010764 [0.009627–0.011228] |       0.107505 [0.106259–0.110547] |      9.99× |
| `scalar-one` / transform      |       0.010915 [0.008777–0.011046] |       0.016822 [0.016648–0.022138] |      1.54× |
| `small-empty` / find          |       0.453828 [0.446684–0.460066] |       9.650948 [9.394150–9.888789] |     21.27× |
| `small-empty` / transform     |       0.505750 [0.499872–0.516139] |       9.587218 [9.421768–9.849386] |     18.96× |
| `small-one` / find            |       0.517852 [0.515431–0.521250] |      9.886631 [9.555552–10.026476] |     19.09× |
| `small-one` / transform       |       0.887925 [0.874740–0.910421] |      9.830182 [9.602531–10.003169] |     11.07× |
| `small-many` / find           |       0.644124 [0.639357–0.665334] |    10.461656 [10.343980–10.589471] |     16.24× |
| `small-many` / transform      |       3.337681 [3.063312–3.442854] |      9.886383 [9.642623–10.099126] |      2.96× |
| `large-sparse` / find         |    52.322877 [48.206624–55.902068] | 840.776155 [817.890984–854.872505] |     16.07× |
| `large-sparse` / transform    |    87.452968 [81.553236–89.314946] | 844.183762 [825.054311–857.614228] |      9.65× |
| `large-dense` / find          | 121.618319 [117.866848–126.021504] | 922.227596 [913.181215–930.240461] |      7.58× |
| `large-dense` / transform     | 396.119263 [394.651321–400.798323] | 923.901853 [869.587014–931.572455] |      2.33× |
| `wide-many` / find            | 294.245286 [290.610215–296.549534] | 556.150990 [552.813819–605.902339] |      1.89× |
| `wide-many` / transform       | 470.363323 [452.539563–518.710043] | 429.202342 [415.534325–441.145226] |      0.91× |
| `deep-one` / find             |       0.927120 [0.893770–1.089315] |    46.301100 [45.265948–46.985878] |     49.94× |
| `deep-one` / transform        |    11.489638 [11.284566–11.616640] |    46.720053 [45.618249–47.796615] |      4.07× |
| `hash-map-sparse` / find      |       4.884481 [4.239294–5.402222] |    71.539130 [70.511284–72.635990] |     14.65× |
| `hash-map-sparse` / transform |       7.102596 [6.193249–7.182294] |    73.466798 [70.567559–74.620925] |     10.34× |

The wide-vector transform costs 470.363 µs versus 429.202 µs by median. Its
Pathling JVM means range from 452.540 to 518.710 µs, while postwalk ranges from
415.534 to 441.145 µs. The mean-of-JVM-means comparison is +10.79% for Pathling.
All six pairs favor postwalk (Pathling costs 2.58–24.83% more time), as do all three
blocks. A dense one-shot transform is therefore an important counterexample to a
blanket performance claim. This workload does not require collective processing
between collecting matches and applying replacements.

Other variable observations are retained: one deep-find JVM takes 1.089 µs versus
0.894–0.934 µs in the other five; one postwalk wide-find JVM takes 605.902 µs
versus 552.814–564.143 µs in the other five. Inspection of their timing samples
shows sustained slower levels, not just a single isolated timing spike. No JVM
or sample was excluded from these summaries, and no specific JIT cause is inferred.

## Allocated bytes

Bytes are per call, rounded to the nearest byte for display. Each implementation's
cell gives its median and the observed minimum–maximum JVM estimate. A median can
fall between allocation levels rather than describe an actual observed level.
Ratios are omitted when either median is below one byte/call. Values shown as zero
are near-zero counter estimates, not proof that every invocation allocates nothing.

| Fixture / operation           | Pathling bytes, median [range] |  Postwalk bytes, median [range] | Byte ratio |
|-------------------------------|-------------------------------:|--------------------------------:|-----------:|
| `scalar-empty` / find         |                        0 [0–0] |                   480 [480–480] |          — |
| `scalar-empty` / transform    |                        0 [0–0] |                      40 [40–40] |          — |
| `scalar-one` / find           |                     64 [64–64] |                   528 [528–528] |      8.25× |
| `scalar-one` / transform      |                        0 [0–1] |                      40 [40–40] |          — |
| `small-empty` / find          |                     24 [24–24] |          14,264 [14,232–14,288] |    594.30× |
| `small-empty` / transform     |                        0 [0–0] |          13,848 [13,848–13,848] |          — |
| `small-one` / find            |                  144 [144–144] |          14,336 [14,336–14,336] |     99.55× |
| `small-one` / transform       |                  976 [952–976] |          13,848 [13,848–13,848] |     14.19× |
| `small-many` / find           |                  584 [584–584] |          14,440 [14,440–14,440] |     24.73× |
| `small-many` / transform      |            5,864 [5,744–5,864] |          13,848 [13,848–13,848] |      2.36× |
| `large-sparse` / find         |            8,376 [2,296–8,376] | 1,139,688 [1,139,688–1,139,688] |    136.07× |
| `large-sparse` / transform    |         45,616 [39,824–45,904] | 1,139,168 [1,139,168–1,139,168] |     24.97× |
| `large-dense` / find          |         39,112 [33,032–39,112] | 1,176,168 [1,176,168–1,176,168] |     30.07× |
| `large-dense` / transform     |      440,792 [434,712–440,792] | 1,174,448 [1,174,448–1,174,448] |      2.66× |
| `wide-many` / find            |      223,544 [223,544–223,544] |       471,992 [471,992–471,992] |      2.11× |
| `wide-many` / transform       |      700,512 [700,512–700,512] |       654,280 [654,280–654,280] |      0.93× |
| `deep-one` / find             |                  144 [144–144] |       106,000 [106,000–106,000] |    736.10× |
| `deep-one` / transform        |         66,576 [66,576–66,576] |       105,512 [105,512–105,512] |      1.58× |
| `hash-map-sparse` / find      |            2,072 [1,800–2,344] |       106,224 [106,224–106,224] |     51.27× |
| `hash-map-sparse` / transform |         13,816 [13,272–13,816] |       105,856 [105,856–105,856] |      7.66× |

Allocation variation is material in several cases:

- Hash-map find has three JVMs at 1,800 bytes and three at 2,344; its 2,072-byte
  median is the midpoint. Hash-map transform has two at 13,272 and four at 13,816.
- Large sparse find has one JVM at 2,296 bytes and five at 8,376. Large dense find
  has two at 33,032 and four at 39,112. These 6,080-byte separations recur in the
  mixed-collection measurements; this clean capture does not diagnose their cause.
- Large sparse transform has two JVMs at 39,824, one at 45,328 and three at 45,904.
  Its 45,616-byte median is also between observed levels. Large dense transform
  has two at 434,712 and four at 440,792.
- Smaller levels in small-one and small-many transforms, and minor differences in
  postwalk small-empty find, are retained in the data. One scalar-one transform
  estimate is about 0.79 bytes/call; this does not establish a fixed allocation size.
- Wide-vector transform allocation is stable across all six JVMs per implementation:
  700,512 bytes for Pathling versus 654,280 for postwalk, a 46,232-byte increase.

These are allocated-byte measurements. They do not measure object counts,
retained heap or the downstream GC impact of long-running mixed workloads.

## Host observations and limits

Recorded external CPU use was 0.85% of the machine at the median ten-second
interval, with a maximum of 5.73%. One hash-map transform worker overlaps that
maximum. Small I/O-wait intervals overlap two adjacent wide-find workers and one
large sparse transform worker. No full worker overlaps recorded swap traffic.
All observations are retained. No profiler, affinity, governor or service changes
were introduced for the measurement.

The wide-transform workers have no recorded I/O-wait or swap overlap, and their
sampled external CPU peaks at 2.53% of the machine. Host sampling cannot rule out
interference or establish the cause of timing variation. Worker windows include
startup, warmup and allocation checks as well as timed samples.

This comparison excludes two-phase collective processing, navigation reuse,
update-only operations, removals, early termination and matching keys or
collections. It does not establish general semantic equivalence with postwalk.
The previous random M1 workload, hardware and measurement method were different;
these numbers replace its documentation role, not its value as a historical
before/after baseline.

[Machine-readable results](results.json) preserve every JVM mean and allocated-byte
estimate, fixture fingerprints, paired rounds and block means. Full original
measurement artifacts and host logs remain archived in the benchmark environment.
