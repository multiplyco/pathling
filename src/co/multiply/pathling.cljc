(ns co.multiply.pathling
  "Path finding and updating for nested data structures."
  (:require
    [co.multiply.pathling.accumulator :refer [accumulator accumulator->vec]]
    #?(:cljs [co.multiply.pathling.impl :as impl]))
  #?(:clj (:import [co.multiply.pathling
                    Nav Nav$Updatable
                    Replacer FunctionReplacer ListReplacer
                    ScannerMatches ScannerMatchesXf
                    ScannerMatchesKeys ScannerMatchesKeysXf
                    ScannerMatchesNav ScannerMatchesNavKeys
                    Transform TransformKeys]
                   [java.util List])))


;; ## REMOVE sentinel
;; ################################################################################
;; When a transform function returns REMOVE, the element is removed from
;; its parent collection rather than being replaced.
(def REMOVE
  "Sentinel value that signals removal of an element from its parent collection.
   Return this from an `update-paths` transform function to remove the element.

   Behavior by collection type:

   - Maps: the key-value pair is removed (`dissoc`)
   - Sets: the element is not added to the result
   - Vectors/Lists: the element is removed and indices collapse

   Example:

   ```clojure
   (transform-when data number? #(if (neg? %) REMOVE (inc %)))
   ;; Removes negative numbers, increments positive ones
   ```"
  #?(:clj  Nav/REMOVE
     :cljs impl/REMOVE))


;; # Transformations
;; ################################################################################
(defn update-paths
  "Apply replacements to all locations identified in `nav` within data structure.
   Updates are applied depth-first (children before parents).

   The third argument can be either:

   - A function: called with each matched value, returns the replacement
   - A collection (ArrayList/JS array, or vector): values are used sequentially by position

   The collection-based form enables efficient bulk updates where replacements are
   computed externally and must be applied in traversal order. This is useful
   when the same accumulator returned by `path-when` with `:raw-matches true`
   has been mutated in-place with replacement values.

   If the replacement is `REMOVE`, the element is removed from its parent collection:

   - Maps: key-value pair is dissoc'd
   - Sets: element is not added to result
   - Vectors/Lists: element is removed and indices collapse

   Example with function:

   ```clojure
   (let [{:keys [nav]} (path-when data number?)]
     (update-paths data nav inc))
   ```

   Example with accumulator (zero-allocation pattern):

   ```clojure
   (let [{:keys [matches nav]} (path-when data task? {:raw-matches true})]
     ;; mutate matches in-place with results
     (dotimes [i (acc-size matches)]
       (acc-set! matches i (process (acc-get matches i))))
     (update-paths data nav matches))
   ```"
  [data nav f-or-replacements]
  (if nav
    #?(:clj  (let [replacer (cond
                              (instance? Replacer f-or-replacements)
                              f-or-replacements

                              (instance? List f-or-replacements)
                              (ListReplacer. f-or-replacements)

                              :else
                              (FunctionReplacer. f-or-replacements))]
               (.applyUpdates ^Nav$Updatable nav data replacer))
       :cljs (let [replacer (cond
                              (satisfies? impl/IReplacer f-or-replacements)
                              f-or-replacements

                              (array? f-or-replacements)
                              (impl/ArrayReplacer. f-or-replacements 0)

                              (vector? f-or-replacements)
                              (impl/VectorReplacer. f-or-replacements 0)

                              :else
                              (impl/FunctionReplacer. f-or-replacements))]
               (impl/apply-updates nav data replacer)))
    data))


(defn path-when
  "Find all values in nested data structure matching predicate.

   Returns map with:

   - `:matches` - Vector of matching values in depth-first order (or ArrayList if `:raw-matches true`)
   - `:nav` - Navigation structure for updating matches (use with `update-paths`)

   Returns `nil` if no matches found.

   Options:

   - `:include-keys` - When true, also match map keys (default: false)
   - `:raw-matches` - When true, return matches as mutable ArrayList instead of vector.
                      Enables zero-allocation update pattern: mutate the ArrayList in-place
                      with replacement values, then pass it directly to `update-paths`.

   Examples:

   ```clojure
   (path-when [1 {:a 2} {:b #{3 {:c 4}}}] number?)
   ;=> {:matches [1 2 3 4], :nav ...}

   (path-when [:a :b :c] number?)
   ;=> nil

   ;; Zero-allocation pattern
   (let [{:keys [matches nav]} (path-when data task? {:raw-matches true})]
     (dotimes [i (.size matches)]
       (.set matches i (process (.get matches i))))
     (update-paths data nav matches))
   ```"
  ([data pred]
   (path-when data pred nil))
  ([data pred opts]
   (let [matches (accumulator)]
     (when-some [nav #?(:clj (if (:include-keys opts)
                               (ScannerMatchesNavKeys/pathWhen data matches pred)
                               (ScannerMatchesNav/pathWhen data matches pred))
                        :cljs (impl/path-when data matches pred opts))]
       {:matches (if (:raw-matches opts)
                   matches
                   (accumulator->vec matches))
        :nav     nav}))))


(defn find-when
  "Find all values in nested data structure matching predicate.

   Returns vector of matching values in depth-first order, or `nil` if no matches.

   The third parameter can be either:

   - A transducer to apply to matches (e.g., `(map inc)`, `(take 5)`, `(filter pos?)`)
   - An options map with keys:
     - `:xf` - Transducer to apply to matches
     - `:include-keys` - When true, also match and collect map keys (default: false)

   Supports early termination with transducers like `(take n)`.

   Examples:

   ```clojure
   (find-when [1 {:a 2} {:b #{3 {:c 4}}}] number?)
   ;=> [1 2 3 4]

   (find-when [1 {:a 2} {:b #{3 {:c 4}}}] number? (map inc))
   ;=> [2 3 4 5]

   (find-when [1 {:a 2} {:b #{3 {:c 4}}}] number? (take 2))
   ;=> [1 2]

   (find-when {:foo :bar :baz :qux} keyword? {:include-keys true})
   ;=> [:bar :foo :qux :baz]  ; order varies
   ```"
  ([data pred]
   #?(:clj  (ScannerMatches/matchesWhen data pred)
      :cljs (impl/matches-when data pred)))
  ([data pred xf-or-opts]
   (let [opts (if (map? xf-or-opts) xf-or-opts {:xf xf-or-opts})
         xf   (:xf opts)]
     #?(:clj  (if (:include-keys opts)
                (if xf
                  (ScannerMatchesKeysXf/matchesWhen data pred xf)
                  (ScannerMatchesKeys/matchesWhen data pred))
                (if xf
                  (ScannerMatchesXf/matchesWhen data pred xf)
                  (ScannerMatches/matchesWhen data pred)))
        :cljs (impl/matches-when data pred xf-or-opts)))))


(defn transform-when
  "Transform all instances of items matching `pred`.

   If `tf` returns `REMOVE`, the element is removed from its parent collection.
   Returns data unchanged if no matches found.

   Options:

   - `:include-keys` - When true, also transform map keys that match `pred` (default: false)

   Examples:

   ```clojure
   (transform-when data number? inc)
   (transform-when data map? #(assoc % :processed true))
   (transform-when {:a 1 :b 2} keyword? name {:include-keys true})
   ;=> {\"a\" 1, \"b\" 2}
   (transform-when [1 -2 3 -4] number? #(if (neg? %) REMOVE (inc %)))
   ;=> [2 4]
   ```"
  ([data pred tf]
   (transform-when data pred tf nil))
  ([data pred tf opts]
   #?(:clj  (if (:include-keys opts)
              (TransformKeys/transformWhen data pred tf)
              (Transform/transformWhen data pred tf))
      :cljs (let [nav (impl/path-when data (accumulator) pred opts)]
              (if nav
                (impl/apply-updates nav data (impl/FunctionReplacer. tf))
                data)))))
