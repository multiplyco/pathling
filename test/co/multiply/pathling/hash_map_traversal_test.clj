(ns co.multiply.pathling.hash-map-traversal-test
  (:require [clojure.test :refer [deftest is testing]]
    [co.multiply.pathling :as p])
  (:import [clojure.lang PersistentHashMap]
    [java.util ArrayList Collection Map$Entry]
    [java.util.concurrent Callable CountDownLatch Executors TimeUnit]))


(deftype HashKey [id ^int code]
  Object
  (equals [_ other]
    (and (instance? HashKey other)
      (= id (.-id ^HashKey other)) (= code (.-code ^HashKey other))))
  (hashCode [_] code)
  clojure.lang.IHashEq
  (hasheq [_] code))


(deftype Leaf [id])


(defn- hash-map-of
  [entries]
  (into PersistentHashMap/EMPTY entries))


(defn- entry-pairs
  [^Iterable m]
  ;; The old scanner's Java entry iterator is the order oracle, independently
  ;; of the candidate's native key/value reduction.
  (mapv (fn [^Map$Entry entry] [(.getKey entry) (.getValue entry)])
    (iterator-seq (.iterator m))))


(defn- reference-visits
  [x]
  (vec (concat (mapcat reference-visits
                 (cond (map? x) (map second (entry-pairs x))
                   (coll? x) x
                   :else []))
         [x])))


(defn- map-fixtures
  []
  (let [entries (fn [codes]
                  (mapv (fn [id code] [(HashKey. id code) (Leaf. id)]) (range) codes))
        collision-entries (entries (repeat 6 42))
        collision-map (hash-map-of collision-entries)]
    (mapv (fn [[label m]] [label (with-meta m {:source label})])
      [[:empty PersistentHashMap/EMPTY]
       [:nil-only (hash-map-of [[nil (Leaf. -1)]])]
       [:nil-values (hash-map-of [[nil nil] [:value nil]])]
       [:bitmap (hash-map-of (entries [0 1 7 12]))]
       [:array-node (hash-map-of (entries (range 32)))]
       [:deep-array-node (hash-map-of (entries (map #(bit-shift-left % 10) (range 32))))]
       [:collision collision-map]
       [:collision-reinsert (assoc (dissoc collision-map (ffirst collision-entries))
                             (ffirst collision-entries) (Leaf. 0))]
       [:nil-and-collision (assoc collision-map nil (Leaf. -1))]
       [:nested (hash-map-of [[nil collision-map]
                              [:child (hash-map-of (entries (range 32)))]])]])))


(deftest native-hash-map-order-and-original-values
  (doseq [[label m] (map-fixtures)
          raw? [false true]]
    (testing (str label ", raw=" raw?)
      (let [data [m (array-map :child m) (sorted-map :child m) (list m)
                  (subvec [:before m] 1) (hash-set m)]
            expected (reference-visits data)
            visited (atom [])
            {:keys [matches nav]} (p/path-when data
                                   #(do (swap! visited conj %) ::truthy)
                                   {:raw-matches raw?})]
        (is (= expected @visited (vec matches)))
        (is (every? true? (map identical? expected @visited)))
        (is (every? true? (map identical? expected matches)))
        (is (if raw? (instance? ArrayList matches) (vector? matches)))
        (is (= data (p/update-paths data nav identity)))
        (is (nil? (p/path-when data (constantly false) {:raw-matches raw?})))))))


(defn- replace-leaf
  [^Leaf leaf]
  (case (mod (.-id leaf) 3)
    0 p/REMOVE
    1 nil
    [:resolved (.-id leaf)]))


(defn- reference-update
  [x]
  (cond
    (instance? Leaf x) (replace-leaf x)
    (map? x) (with-meta
               (reduce (fn [result [k v]]
                         (let [value (reference-update v)]
                           (if (identical? p/REMOVE value) result (assoc result k value))))
                 (empty x) (entry-pairs x))
               (meta x))
    :else x))


(deftest hash-map-navigation-replacement-order-removal-and-metadata
  (doseq [[label m] (map-fixtures)
          raw? [false true]]
    (let [untouched (with-meta [:unchanged] {:keep true})
          data (with-meta (hash-map-of [[nil m] [:untouched untouched] [:tail (Leaf. 101)]])
                 {:source :outer})
          original (reference-visits data)
          expected-matches (filterv #(instance? Leaf %) original)
          {:keys [matches nav]} (p/path-when data #(instance? Leaf %) {:raw-matches raw?})
          replacements (mapv replace-leaf expected-matches)
          expected (reference-update data)]
      (testing (str label ", raw=" raw?)
        (is (= expected-matches (vec matches)))
        (doseq [replacer [replace-leaf replacements (ArrayList. ^Collection replacements)]]
          (dotimes [_ 2]
            (let [updated (p/update-paths data nav replacer)]
              (is (= expected updated))
              (is (= {:source :outer} (meta updated)))
              (is (= (meta m) (meta (get updated nil))))
              (is (identical? untouched (:untouched updated))))))
        (when raw?
          (doseq [[i value] (map-indexed vector replacements)]
            (.set ^ArrayList matches i value))
          (is (= expected (p/update-paths data nav matches))))
        (is (= original (reference-visits data)))))))


(deftest hash-map-collection-only-and-child-before-parent
  (doseq [[_ data] (map-fixtures)]
    (let [{:keys [matches nav]} (p/path-when data #(identical? data %) {:raw-matches true})
          seen (atom [])]
      (is (= 1 (count matches)))
      (is (identical? data (first matches)))
      (is (identical? data (p/update-paths data nav #(do (swap! seen conj %) %))))
      (is (identical? data (first @seen)))
      (is (= (meta data) (meta (first @seen))))))
  (let [inner (with-meta (hash-map-of [[nil (Leaf. 0)] [:keep (Leaf. 1)]]) {:source :inner})
        data (with-meta (hash-map-of [[nil inner] [:tail (Leaf. 2)]]) {:source :outer})
        {:keys [nav]} (p/path-when data #(or (instance? Leaf %) (map? %)))
        parents (atom [])
        updated (p/update-paths data nav
                  (fn [x]
                    (if (instance? Leaf x)
                      (if (zero? (.-id ^Leaf x)) p/REMOVE (+ 10 (.-id ^Leaf x)))
                      (do (swap! parents conj x) (assoc x :parent-updated true)))))]
    (is (= {nil {:keep 11 :parent-updated true} :tail 12 :parent-updated true} updated))
    (is (= [{:keep 11} {nil {:keep 11 :parent-updated true} :tail 12}] @parents))
    (is (= [{:source :inner} {:source :outer}] (mapv meta @parents)))
    (is (= {:source :inner} (meta (get updated nil))))
    (is (= {:source :outer} (meta updated)))))


(deftest hash-map-scans-are-reentrant-and-propagate-exceptions
  (let [data (hash-map-of [[nil (Leaf. 0)] [:child (hash-map-of [[:a (Leaf. 1)]])]])
        expected (filterv #(instance? Leaf %) (reference-visits data))
        nested-results (atom [])
        pred (fn [x]
               (when (instance? Leaf x)
                 (swap! nested-results conj
                   (vec (:matches (p/path-when (hash-map-of [[nil 7] [:b 8]]) number? {:raw-matches true}))))
                 true))]
    (is (= expected (vec (:matches (p/path-when data pred {:raw-matches true})))))
    (is (= [[7 8] [7 8]] @nested-results))
    (let [failure (ex-info "predicate failed" {})]
      (is (identical? failure
            (try (p/path-when data (fn [_] (throw failure)))
                 (catch Exception e e)))))
    (is (= expected (vec (:matches (p/path-when data #(instance? Leaf %) {:raw-matches true})))))))


(deftest concurrent-hash-map-scans-own-their-state
  (let [pool (Executors/newFixedThreadPool 2)
        entered (CountDownLatch. 2)]
    (try
      (let [jobs (mapv (fn [value]
                         (.submit pool
                           ^Callable
                           (fn []
                             (let [data (hash-map-of [[nil value] [:nested (hash-map-of [[:leaf (inc value)]])]])
                                   first? (atom true)
                                   pred (fn [x]
                                          (when (number? x)
                                            (when (compare-and-set! first? true false)
                                              (.countDown entered)
                                              (when-not (.await entered 5 TimeUnit/SECONDS)
                                                (throw (ex-info "Concurrent scan did not enter" {}))))
                                            true))
                                   {:keys [matches nav]} (p/path-when data pred {:raw-matches true})]
                               [(vec matches) (p/update-paths data nav #(vector :resolved %))]))))
                   [10 100])]
        (is (= [[[10 11] {nil [:resolved 10] :nested {:leaf [:resolved 11]}}]
                [[100 101] {nil [:resolved 100] :nested {:leaf [:resolved 101]}}]]
               (mapv #(.get % 10 TimeUnit/SECONDS) jobs))))
      (finally (.shutdownNow pool)))))
