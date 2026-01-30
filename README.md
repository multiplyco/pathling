# Pathling

[![Clojars Project](https://img.shields.io/clojars/v/co.multiply/pathling.svg)](https://clojars.org/co.multiply/pathling)
[![cljdoc](https://cljdoc.org/badge/co.multiply/pathling)](https://cljdoc.org/d/co.multiply/pathling)

Find and transform values in nested data structures.

## Requirements

**Clojure:**

- JDK 21+ (JDK 25+ recommended)
- Clojure 1.12+

**ClojureScript:**

- Any

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

(def data {:items  [{:type :task, :name "Write docs"}
                    {:type :note, :name "Remember milk"}
                    {:type :task, :name "Fix bug"}]
           :nested {:deep {:type :task, :name "Review PR"}}})

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

To give some kind of comparison to `postwalk` in particular:

| Operation      | Pathling | Postwalk | Diff      |
|----------------|----------|----------|-----------|
| find           | 211 µs   | 1.92 ms  | 9x        |
| - objects/call | 138      | 5,246    | 38x fewer |
| - bytes/call   | 7.1 KB   | 176 KB   | 25x fewer |
| transform      | 305 µs   | 1.95 ms  | 6x        |
| - objects/call | 407      | 5,240    | 13x fewer |
| - bytes/call   | 21.4 KB  | 174 KB   | 8x fewer  |

Measured on an M1 in Clojure, on a randomly generated structure 6 levels deep, with a branch factor of 5 at each level.
The data structure consists of vectors, maps, and sets. This results in ~10,000 nodes, out of which ~300 are
matches. Criterium and YourKit were used to estimate performance and allocation count.

Pathling's performance scales with match count rather than structure size. The speedup advantage grows as matches
become sparser relative to the overall structure. Postwalk always visits every node regardless of how many match.

Note that efficiency claims are mostly about Clojure. Less attention has been given to the ClojureScript equivalent, and
it could be improved from its current state.

Key properties:

- **Targeted updates**: Build a navigation structure, then apply multiple updates efficiently as a separate step
- **Stack-safe**: Recursion depth scales with structure depth, not match count (10,000+ matches won't overflow)
- **Allocation-friendly**: Object allocations scale with matches, not structure size
- **Transducer support**: `find-when` accepts transducers for transformation, filtering, and early termination
- **`REMOVE` sentinel**: Conditionally remove elements during transformation
- **Metadata preservation**: Collection metadata survives transformations
- **Cross-platform**: Works in both Clojure and ClojureScript

## API

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

## Zero-Allocation Update Pattern

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

## Metadata Preservation

Collection metadata is preserved through transformations:

```clojure
(let [data (with-meta {:a 1 :b 2} {:version 1})]
  (meta (p/transform-when data number? inc)))
;=> {:version 1}
```

## Collection Type Support

Pathling handles all standard Clojure collections:

- **Maps**: hash-map, array-map, sorted-map, struct-map
- **Vectors**: regular vectors and subvec
- **Sets**: hash-set, sorted-set
- **Sequences**: lists, lazy seqs, ranges, etc.
- **Scalars**: primitives, nil, and opaque values (treated as leaves)

Sorted collections (sorted-map, sorted-set) preserve their type and comparator through transformations.

## License

Eclipse Public License 2.0. Copyright (c) 2025 Multiply. See [LICENSE](LICENSE).

Authored by [@eneroth](https://github.com/eneroth)