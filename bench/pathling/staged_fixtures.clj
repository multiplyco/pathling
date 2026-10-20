(ns pathling.staged-fixtures
  "Read-only discovery and separate collect/replace measurements for opaque
   leaves. Batch processing is prepared outside timing and shared by both arms."
  (:require [clojure.walk :as walk]
    [co.multiply.pathling :as p]
    [pathling.fixtures :as fixtures]))


(def operations [:find :tree-seq-find :collect :replace :postwalk-replace])


(defn tree-seq-find
  "Collect value-only matches, preserving original leaf identity and nil-on-empty.
   This comparator is for opaque leaves, not collection or key predicates."
  [data]
  (not-empty
    (into [] (filter fixtures/groundable?)
      (tree-seq coll? #(if (map? %) (vals %) (seq %)) data))))


(defn batch-replacements
  "Occurrence-specific results that depend on the total match count. This models
   user processing, but is never called inside a measured operation."
  [matches]
  (let [n (count matches)]
    (mapv (fn [i value] [n i (fixtures/resolve-value value)])
      (range n) matches)))


(defn postwalk-replace
  "Rediscover opaque matching leaves and consume a precomputed replacement vector.
   The fixtures have no matching keys. The vector must contain one replacement
   per occurrence in traversal order. A fresh iterator belongs to each call."
  [data replacements]
  ;; Collection has already established whether there is any work to do, just
  ;; as Pathling's nil navigation permits an immediate return on empty matches.
  (if (seq replacements)
    (let [cursor (.iterator ^Iterable replacements)]
      (walk/postwalk (fn [value]
                      (if (fixtures/groundable? value) (.next cursor) value))
        data))
    data))


(defn prepare-cases
  [{:keys [data matches description]} selected-operations]
  (when-let [unknown (seq (remove (set operations) selected-operations))]
    (throw (ex-info "Unknown staged comparison operations" {:operations unknown})))
  (let [collected (p/path-when data fixtures/groundable?)
        nav (:nav collected)
        replacements (batch-replacements matches)
        ;; Primary fixtures have unique matching leaves. Lookup is independent
        ;; of either measured replacement cursor; tests cover repeated values
        ;; separately against literal expected results.
        by-value (zipmap matches replacements)
        _ (when-not (= (count by-value) (count matches))
            (throw (ex-info "Staged fixture oracle requires unique leaves" {})))
        expected (walk/postwalk #(if (fixtures/groundable? %) (by-value %) %) data)
        check-matches
        (fn [actual]
          (when-not (and (= (not-empty matches) actual)
                     (if (seq matches) (vector? actual) (nil? actual))
                     (every? true? (map identical? matches actual)))
            (throw (ex-info "Incorrect staged match order, identity or result shape"
                     {:fixture (:id description)}))))
        check-replacement
        (fn [actual]
          (when-not (= expected actual)
            (throw (ex-info "Incorrect staged replacement result"
                     {:fixture (:id description)})))
          (when (and (empty? matches) (not (identical? data actual)))
            (throw (ex-info "Empty replacement must return original input" {}))))
        check-collection
        (fn [actual]
          (check-matches (:matches actual))
          (when-not (if (seq matches) (some? (:nav actual)) (nil? actual))
            (throw (ex-info "Incorrect staged navigation result" {})))
          (check-replacement (p/update-paths data (:nav actual) replacements)))
        entries {:find [#(p/find-when data fixtures/groundable?) check-matches]
                 :tree-seq-find [#(tree-seq-find data) check-matches]
                 :collect [#(p/path-when data fixtures/groundable?) check-collection]
                 :replace [#(p/update-paths data nav replacements) check-replacement]
                 :postwalk-replace [#(postwalk-replace data replacements) check-replacement]}]
    (check-collection collected)
    (check-matches (tree-seq-find data))
    (check-replacement (postwalk-replace data replacements))
    (mapv (fn [operation]
            (let [[f validate] (entries operation)]
              {:id [(:id description) operation] :fixture description
               :eligible-matches (count matches) :f f :validate validate}))
      selected-operations)))
