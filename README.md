# Pathling

[![Clojars Project](https://img.shields.io/clojars/v/co.multiply/pathling.svg)](https://clojars.org/co.multiply/pathling)
[![cljdoc](https://cljdoc.org/badge/co.multiply/pathling)](https://cljdoc.org/d/co.multiply/pathling)

Find and transform values in nested data structures.

## Requirements

**Clojure:**

- JDK 21+ (JDK 25+ recommended)
- Clojure 1.12+

**ClojureScript:**

- Tested with ClojureScript 1.12.134 in development and advanced compilation modes.

## Installation

```clojure
;; deps.edn
co.multiply/pathling {:mvn/version "0.2.1"}
```

## Why Pathling?

Pathling is designed to be efficient at _two-phase_ updates of a Clojure data structure. Take this scenario:

- You don't know exactly where the values are in the data structure, but you can identify them with a predicate,
- You need to handle the values collectively,
- You need to put transformed values back where you originally found them.

In this scenario, Pathling is an appropriate solution. It will efficiently find the values for you, and give them to you
in a vector (appended depth-first). It will also give you a navigation object which you can use to do targeted updates
to the values within the original data structure, wherever they were found.

For example, consider finding all elements matching a certain criteria in a data structure, where you want to give them
a label like "1 out of n". Perhaps you don't know (or perhaps don't care about) how many there are, or where exactly
they are. In this case, you'd want to collect them all, do some transformation on them collectively, and then put them
back.

```clojure
(require '[co.multiply.pathling :as p])

(def data {:items  [{:type :task  :name "Write docs"}
                    {:type :note  :name "Remember milk"}
                    {:type :task  :name "Fix bug"}]
           :nested {:deep {:type :task  :name "Review PR"}}})

;; Find all tasks, wherever they are
(def result (p/path-when data #(= :task (:type %))))

(:matches result)
;=> [{:type :task, :name "Write docs"}
;    {:type :task, :name "Fix bug"}
;    {:type :task, :name "Review PR"}]

;; Label them "1 of 3", "2 of 3", etc.
(let [{:keys [matches nav]} result
      n            (count matches)
      replacements (into [] (map-indexed
                              (fn [i task]
                                (assoc task :label (str (inc i) " of " n))))
                     matches)]
  (p/update-paths data nav replacements))
;=> {:items [{:type :task, :name "Write docs", :label "1 of 3"}
;            {:type :note, :name "Remember milk"}
;            {:type :task, :name "Fix bug", :label "2 of 3"}]
;    :nested {:deep {:type :task, :name "Review PR", :label "3 of 3"}}}
```

By efficient means:

- It's pretty quick at scanning through a data structure.
- It tries hard to not do unnecessary allocations as part of reading.
- Once a navigation object has been constructed, transforming the values at the original locations is very fast.

Performance depends on collection shape, match density and the task. The comparisons below measure the **unreleased
`b227ede` implementation**, with Clojure 1.12.4, Corretto 26.0.2 and Criterium's full profile on an
Intel Core i5-4590S. Each operation/fixture has six fresh JVM measurements in balanced orders. Times and allocated
bytes are per call; timing figures are medians of JVM means. Ratios are comparator / Pathling, so values above one
favor Pathling. The figures describe these implementations and inputs, not the published 0.2.1 release.

**Finding values.** `find-when` versus value-only `tree-seq`, fully collected into a vector or nil. Measured on
2026-10-19, using Ubuntu 26.04.1 / kernel 7.0.0-38 after the system upgrade:

| Input        | Pathling µs | tree-seq µs | Time ratio | Pathling bytes | tree-seq bytes |
|--------------|------------:|------------:|-----------:|---------------:|---------------:|
| Large sparse |        51.2 |      2580.3 |     50.44× |          8,376 |      5,081,764 |
| Large dense  |       108.0 |      2648.6 |     24.52× |         39,112 |      5,090,048 |
| Wide vector  |       286.1 |      2240.7 |      7.83× |        223,544 |      4,800,468 |

**Transforming independently.** Complete `transform-when` versus complete `postwalk`, including discovery and
reconstruction. Measured on 2026-10-16, using Ubuntu 24.04.5 / kernel 6.8.0-139 before the upgrade:

| Input        | Pathling µs | Postwalk µs | Time ratio | Pathling bytes | Postwalk bytes |
|--------------|------------:|------------:|-----------:|---------------:|---------------:|
| Large sparse |        87.5 |       844.2 |      9.65× |         45,616 |      1,139,168 |
| Large dense  |       396.1 |       923.9 |      2.33× |        440,792 |      1,174,448 |
| Wide vector  |       470.4 |       429.2 |      0.91× |        700,512 |        654,280 |

The dense, flat vector is an important counterexample: this direct transform takes about **9.6% more time and
7.1% more bytes with Pathling**. Postwalk wins in all six pairs. Neither implementation assumes a flat input;
postwalk's sequential reconstruction fits the shape it encounters here.

**Collecting, processing collectively, and replacing.** Measured on 2026-10-19. Pathling uses `path-when` and
`update-paths`; the comparator uses value-only `tree-seq` and postwalk with a replacement cursor. Collection and
replacement are timed separately. Both replacement stages receive the same prepared vector, and **user batch
processing is excluded**. The total is the sum of stage medians, not a measured end-to-end call:

| Input / stage                | Pathling µs | Comparator µs | Time ratio |
|------------------------------|------------:|--------------:|-----------:|
| Large sparse / collect       |        61.7 |        2580.3 |     41.81× |
| Large sparse / replace       |        13.2 |         816.1 |     61.97× |
| Large sparse / derived total |        74.9 |        3396.3 |     45.36× |
| Large dense / collect        |       135.0 |        2648.6 |     19.62× |
| Large dense / replace        |       264.4 |         913.1 |      3.45× |
| Large dense / derived total  |       399.4 |        3561.7 |      8.92× |
| Wide vector / collect        |       358.7 |        2240.7 |      6.25× |
| Wide vector / replace        |       195.0 |         435.6 |      2.23× |
| Wide vector / derived total  |       553.7 |        2676.3 |      4.83× |

Totals use unrounded stage estimates. The comparator's collect rows are tree-seq; replace rows are postwalk.
Every staged row above favors Pathling in all six matched rounds and all three blocks. On the wide vector,
retained navigation now serves a required collective workflow; this is a different task from direct transformation.

Large mixed inputs have 3,906 value nodes and 31 / 1,562 matches respectively. The wide vector has 10,000 leaves,
all matching. Only opaque leaves match. Tree-seq excludes map keys/entries; postwalk visits them. The captures
have different OS/harness environments: compare implementations within each capture, not absolute times across
them. Allocation varies between JVMs, and medians can fall between observed levels.

The [staged report](benchmarks/comparisons/2026-10-19-staged/README.md) and
[direct postwalk report](benchmarks/comparisons/2026-10-16-postwalk/README.md) include all ten fixtures, stage
allocation, observed ranges, every JVM result and methodology. Neither measures object counts, retained heap,
user batch costs or long-running GC effects. The derived totals also exclude inter-stage cache/GC effects.

Scanning visits the structure to find matches. Subsequent updates follow the recorded navigation, avoiding a full
rescan; their cost also depends on rebuilding the affected collections. Sparse matches can therefore give a larger
advantage over postwalk, which visits every node on each update.

Note that efficiency claims are mostly about Clojure. Less attention has been given to the ClojureScript equivalent, and
it could be improved from its current state.

Key properties:

- **Targeted updates**: Build a navigation structure, then apply multiple updates efficiently as a separate step
- **Bounded by nesting depth**: Stack usage follows structure depth, not match count; very deep inputs can overflow
- **Allocation-friendly**: Navigation is retained only for matches and their ancestors; traversal and updates can also
  allocate
- **Transducer support**: `find-when` accepts transducers for transformation, filtering, and early termination
- **`REMOVE` sentinel**: Conditionally remove elements during transformation
- **Metadata preservation**: Collection metadata survives transformations
- **Cross-platform**: Works in both Clojure and ClojureScript

## API

The supported public API is the documented Clojure/ClojureScript surface, including the accumulator helpers.
All Java types and members in Pathling's implementation are internal, even when declared `public`; they are not
supported API or extension points and carry no Java source or binary compatibility guarantee.

The `:nav` returned by `path-when` is opaque. Retain it and pass it unchanged to `update-paths` with the scanned input.
Do not construct or mutate its internals or depend on its concrete type, fields, representation, or serialization.
The raw match accumulator is intentionally mutable through the documented accumulator operations; navigation is not.

### `path-when`

Find all values matching a predicate, returning both matches and a navigation structure for updates.

```clojure
(require '[co.multiply.pathling :as p])

(def data [1 {:a 2} {:b #{3 {:c 4}}}])

(p/path-when data number?)
;=> {:matches [1 2 3 4]
;    :nav <navigation-structure>}

;; No matches returns nil
(p/path-when [:a :b :c] number?)
;=> nil
```

Options:

- `:include-keys` - When true, also match map keys (default: false)
- `:raw-matches` - When true, return the internal mutable accumulator instead of a vector (default: false)

Traversal visits children before their collection. With `:include-keys`, each entry's value subtree comes before
its key; the key is tested as a whole rather than recursively traversed. Predicates see the original values and
collections. Map and set order follows the input collection, so do not assume identical order across runtimes or
collection implementations.

### `update-paths`

Apply replacements to all locations identified by a navigation structure.

The third argument can be either a function or a collection of replacement values:

```clojure
;; With a function
(let [{:keys [nav]} (p/path-when data number?)]
  (p/update-paths data nav inc))
;=> [2 {:a 3} {:b #{4 {:c 5}}}]

;; With a collection of replacements (applied in traversal order)
(let [{:keys [matches nav]} (p/path-when data number?)]
  (p/update-paths data nav (mapv #(* 10 %) matches)))
;=> [10 {:a 20} {:b #{30 {:c 40}}}]
```

When using a collection, values are consumed sequentially in the same depth-first order that `path-when` found them.
This enables patterns where you transform matches externally and pass the results back.

Provide a vector or raw accumulator for portable positional replacement. A missing replacement throws when it is
consumed; trailing replacements are ignored. A `nil` navigation returns the original data without reading any
replacements. Do not mutate a replacement accumulator concurrently with an update.

Function replacements receive children before their parent, and see each matched parent with its child updates
already applied. The replacement function's result is used as supplied, including its metadata.

If updated map entries produce the same key, the last surviving updated entry in traversal order wins. An updated
entry can also overwrite an untouched entry at its new key. Sets collapse equal replacement values. Sorted
collections use their comparator's notion of equivalent keys or members.

### `find-when`

Find values without building navigation (more efficient for read-only operations). Supports transducers for
transformation, filtering, and early termination.

```clojure
(p/find-when data number?)
;=> [1 2 3 4]

;; With transducer
(p/find-when data number? (map inc))
;=> [2 3 4 5]

;; Early termination - stops scanning after finding 2 matches
(p/find-when data number? (take 2))
;=> [1 2]

;; Stateful transducers work too
(p/find-when [1 2 3 4 5 6] number? (partition-all 2))
;=> [[1 2] [3 4] [5 6]]

;; Composing transducers
(p/find-when data number? (comp (filter even?) (map (partial * 10))))
;=> [20 40]

;; "Pagination": skip 2, take 2
(p/find-when (vec (range 10)) number? (comp (drop 2) (take 2)))
;=> [2 3]

;; With options map
(p/find-when {:a 1 :b 2} keyword? {:include-keys true :xf (map name)})
;=> ["a" "b"]
```

The `pred` argument filters at scan time (in tight loops), while transducers process matches. You could achieve the
equivalent effect with e.g. `(find-when data (constantly true) (filter pred))`. Filtering up-front, before engaging
the transducer machinery, is ultimately faster for the case where you don't want to pipe the entire structure through
the transducer (which ought to be most cases).

If no transducer is given, a more efficient method of collecting matches is used.

### `transform-when`

Produces a navigation object internally, then applies the transformation given. Does not collect matches.

Utility function for convenience. Pathling isn't necessarily the most efficient alternative if you don't need to handle
the intermediate collection of matches. It might be, if the matches are sparse. Measure, if performance matters.

```clojure
(p/transform-when data number? inc)
;=> [2 {:a 3} {:b #{4 {:c 5}}}]

;; Transform map keys
(p/transform-when {:a 1 :b 2} keyword? name {:include-keys true})
;=> {"a" 1 "b" 2}
```

## The REMOVE Sentinel

Return `REMOVE` from a transform function to remove elements from their parent collection.

```clojure
;; Remove negative numbers, increment positive ones
(p/transform-when [1 -2 3 -4 5] number?
  (fn [n]
    (if (neg? n)
      p/REMOVE
      (inc n))))
;=> [2 4 6]

;; Filter maps from a vector
(p/transform-when [{:keep true} {:keep false} {:keep true}]
  #(and (map? %) (not (:keep %)))
  (constantly p/REMOVE))
;=> [{:keep true} {:keep true}]
```

Behavior by collection type:

- **Maps**: key-value pair is dissoc'd
- **Sets**: element is not added to result
- **Vectors/Lists**: element is removed and indices collapse

Removing a struct-map base entry converts that struct to an ordinary map; see the collection rules below.
When the root itself is removed, the result is `REMOVE`, since there is no parent collection to remove it from.
Use `p/REMOVE` directly: it is an opaque sentinel, not a keyword or a serialized value.
It is reserved for replacement results. Storing it as ordinary input data is unsupported and is not validated.

## Reusing the Match Accumulator

For performance-critical code, the `:raw-matches` option returns the internal mutable accumulator (`ArrayList` on JVM,
JS array on ClojureScript) instead of converting to a vector. You can mutate this accumulator in-place with replacement
values and pass it directly to `update-paths`:

```clojure
(require '[co.multiply.pathling.accumulator :refer [acc-get acc-set! acc-size]])

(let [{:keys [matches nav]} (p/path-when data number? {:raw-matches true})]
  ;; Mutate the accumulator in-place
  (dotimes [i (acc-size matches)]
    (acc-set! matches i (* 10 (acc-get matches i))))
  ;; Pass the same accumulator to update-paths
  (p/update-paths data nav matches))
```

This eliminates the intermediate vector allocation and is useful when processing large numbers of matches. The
accumulator macros (`acc-get`, `acc-set!`, `acc-size`) work cross-platform.
`acc-set!` mutates the element and returns the accumulator. Reusing it avoids the replacement-vector allocation;
reconstructing updated persistent collections still allocates.

## Metadata Preservation

Collection metadata is preserved through transformations:

```clojure
(let [data (with-meta {:a 1 :b 2} {:version 1})]
  (meta (p/transform-when data number? inc)))
;=> {:version 1}
```

## Collection Type Support

Pathling traverses these Clojure and ClojureScript collections:

- **Maps**: hash-map, array-map, sorted-map, records, and JVM struct maps
- **Vectors**: regular vectors, subvec, and standalone map entries (as two-element vectors)
- **Sets**: hash-set, sorted-set
- **Sequences**: lists, lazy seqs, ranges, queues, `keys`, `vals`, and map sequences
- **Scalars**: primitives, nil, and opaque values (treated as leaves)

Sorted collections (sorted-map, sorted-set) preserve their type and comparator through transformations.
Sequence child updates reconstruct a list, even when the input was a lazy sequence, range or queue. A collection-only
replacement receives the original collection. Standalone map entries may become vectors. Host arrays and objects
are opaque leaves; accepting a raw JS array as *replacements* does not make it a traversable input collection.

Records are scanned as maps on both platforms. Value-only updates preserve their record type. Removing a declared
record field, or matching and transforming record keys, can produce an ordinary map.

JVM struct maps preserve their basis, type and metadata for value-only updates and unchanged keys. Renaming or
removing a base key converts only that affected struct to an ordinary map, retaining metadata. Extension entries
can be renamed or removed without conversion. `find-when`, `path-when`, and `transform-when` all honor `:include-keys`
for struct maps. For example, changing a `clojure.xml` element's `:tag` value preserves its struct type; renaming or
removing the `:tag` field converts it.

## Correctness checks

`bb test` runs the JVM tests and the ClojureScript browser suite in both development and advanced compilation modes.
For individual checks, use `bb test:clj`, `bb test:cljs`, or `bb test:cljs:release`. `bb bench:check` additionally checks
the benchmark fixtures and harness without running performance measurements.

## Implementation priorities

Pathling backs Quiescent, where library overhead should be small and predictable relative to application work.
Correctness and performance take priority over code deduplication. Reducing allocation is a first-class objective:
temporary garbage can impose GC work and latency elsewhere in the application, beyond the measured call.

Prefer specialized traversal using each collection's existing representation and native access mechanisms. Avoid
converting collections into a common representation merely to share an algorithm. Use general traversal as a fallback
for other supported types; introduce conversion where semantics require it or measurements justify the tradeoff.
The possibility that the JVM will optimize away generality is not, by itself, a reason to introduce it. Preserve
collection identity and traversal semantics when specializing.

Quiescent's main workload is finding a few matches in a potentially large structure. The design targets are:

- A full scan must visit the structure, but work per nonmatch should be minimal.
- Avoid temporary traversal objects for subtrees without matches, not just retained navigation for those subtrees.
- Make allocation depend chiefly on matches and the navigation connecting them, sharing common ancestors where possible.
- Follow recorded paths during updates, accounting for the cost of rebuilding affected collections.

These are targets, not promises of constant overhead or allocation-free traversal. Collection shape, match depth,
sequence realization, and rebuilding collections also affect cost. Stack usage follows nesting depth and is distinct
from heap allocation that contributes to GC pressure.

Evaluate execution time, allocated bytes per operation, and variability together. Neither the fastest isolated timing
nor the lowest allocation count automatically wins; make tradeoffs using representative workloads, examining sustained
GC effects when needed. Include large structures with zero or few matches to expose costs that grow with the haystack.

Prioritize the JVM implementation and Quiescent's `(p/path-when v type/groundable? {:raw-matches true})` followed by
`update-paths`. Other operations, including `transform-when`, matter but are less frequent. ClojureScript should retain
API compatibility and pursue the same efficiency goals, with lower optimization priority than the JVM implementation.

See [development notes](DEVELOPMENT.md) for completed optimization work, measured tradeoffs and follow-up directions.

## Benchmarks

Run `bb bench` to compile, check correctness, and save per-case JVM benchmark
results. The default suite prioritizes raw `path-when` and `update-paths` usage.
See [benchmark commands and output format](benchmarks/README.md) for focused runs,
quick checks, and retaining baselines.

## Implementation guide

The [Java package documentation](src/co/multiply/pathling/package-info.java) maps the scanner families, explains
the navigation/update boundary, and records the ownership and traversal contracts shared by the specialized
implementations.

## License

Eclipse Public License 2.0. Copyright (c) 2025 Multiply. See [LICENSE](LICENSE).

Authored by [@eneroth](https://github.com/eneroth)
