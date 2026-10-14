(ns co.multiply.pathling.vector-update-test
  (:require [clojure.test :refer [deftest is testing]]
    [co.multiply.pathling :as p])
  (:import [clojure.lang PersistentVector]
    [java.util AbstractList ArrayList Collection LinkedList]))


(defn- replacement
  [ordinal]
  (case (mod ordinal 4)
    0 nil
    1 false
    [:replacement ordinal]))


(defn- replacement-lists
  [values]
  [[:vector values]
   [:array-list (ArrayList. ^Collection values)]
   [:linked-list (LinkedList. ^Collection values)]])


(deftest positional-vector-updates-at-tail-and-tree-boundaries
  (doseq [size [0 1 31 32 33 63 64 65 1023 1024 1025 1055 1056 1057]
          density [:sparse :dense]]
    (let [data (with-meta (vec (range size)) {:size size :density density})
          indices (if (= :dense density)
                    (vec (range size))
                    (vec (sort (filter #(< -1 % size)
                                 (set [0 30 31 32 63 64 1023 1024 1055 (dec size)])))))
          selected (set indices)
          {:keys [matches nav]} (p/path-when data #(and (integer? %) (contains? selected %)))
          values (mapv replacement (range (count indices)))
          by-index (zipmap indices values)
          expected (mapv #(if (contains? selected %) (by-index %) %) data)]
      (is (= indices (vec matches)) (str size " " density))
      (doseq [[kind replacements] (replacement-lists values)]
        (testing (str "size=" size ", " density ", " kind)
          ;; Retained navigation and supplied replacements can be reused: only
          ;; the per-update cursor advances, never the caller's collection.
          (dotimes [_ 2]
            (let [updated (p/update-paths data nav replacements)]
              (is (= expected updated))
              (is (instance? PersistentVector updated))
              (is (= (meta data) (meta updated)))))
          (is (= values (vec replacements)))))
      (is (= (vec (range size)) data)))))


(deftest positional-vector-removals-preserve-order-and-metadata
  (doseq [size [33 65 1057]
          final-size (distinct [0 1 31 32 33 (dec size) size])]
    (let [data (with-meta (vec (range size)) {:source :removals})
          ;; Keep odd positions first, then even ones, to exercise removals at
          ;; the beginning, middle and end rather than only trimming a suffix.
          kept (set (take final-size (concat (range 1 size 2) (range 0 size 2))))
          values (mapv #(if (kept %) (replacement %) p/REMOVE) (range size))
          expected (filterv #(not (identical? p/REMOVE %)) values)
          {:keys [nav]} (p/path-when data integer?)]
      (doseq [[kind replacements] (replacement-lists values)]
        (testing (str size " -> " final-size ", " kind)
          (let [updated (p/update-paths data nav replacements)]
            (is (= expected updated))
            (is (= final-size (count updated)))
            (is (= {:source :removals} (meta updated)))
            (is (instance? PersistentVector updated)))))
      (is (= (vec (range size)) data)))))


(deftest positional-vector-cursor-crosses-nested-navigation
  (doseq [include-keys? [false true]]
    (let [inner (with-meta [1 2] {:source :inner})
          m (with-meta (array-map 4 5) {:source :map})
          backing [:before 7 8 :after]
          sub (with-meta (subvec backing 1 3) {:source :subvec})
          linked (with-meta (list 10) {:source :list})
          untouched (with-meta [::untouched nil false] {:source :untouched})
          data (with-meta [0 inner 3 m 6 sub 9 linked 11 untouched] {:source :outer})
          expected-matches (if include-keys? [0 1 2 3 5 4 6 7 8 9 10 11] [0 1 2 3 5 6 7 8 9 10 11])
          {:keys [matches nav]} (p/path-when data integer? {:include-keys include-keys? :raw-matches true})
          values (mapv (fn [ordinal value]
                         (case value
                           2 nil
                           3 p/REMOVE
                           7 false
                           10 p/REMOVE
                           [:replacement ordinal]))
                   (range) expected-matches)
          lookup (zipmap expected-matches values)
          expected [(lookup 0) [(lookup 1) nil]
                    {(if include-keys? (lookup 4) 4) (lookup 5)}
                    (lookup 6) [false (lookup 8)] (lookup 9) '() (lookup 11) untouched]]
      (is (= expected-matches (vec matches)))
      (doseq [[kind replacements] (replacement-lists values)]
        (testing (str "include-keys=" include-keys? ", " kind)
          (dotimes [_ 2]
            (let [updated (p/update-paths data nav replacements)]
              (is (= expected updated))
              (is (identical? untouched (peek updated)))
              (is (= {:source :outer} (meta updated)))
              (is (= [{:source :inner} {:source :map} {:source :subvec} {:source :list}]
                     (mapv #(meta (nth updated %)) [1 2 4 6])))))))
      (doseq [[i value] (map-indexed vector values)] (.set ^ArrayList matches i value))
      (is (= expected (p/update-paths data nav matches)))
      (is (= values (vec matches)))
      (is (thrown? IndexOutOfBoundsException (p/update-paths data nav (vec (butlast values)))))
      (is (= expected (p/update-paths data nav (conj values :unused))))
      (is (= [0 [1 2] 3 {4 5} 6 [7 8] 9 '(10) 11 untouched] data))
      (is (= [:before 7 8 :after] backing)))))


(deftest positional-vector-parent-consumes-after-children
  (let [inner [1 2]
        empty-child []
        data [:head inner empty-child :tail]
        {:keys [matches nav]} (p/path-when data (constantly true))
        values [:head-result :one :two :inner-result :empty-result :tail-result :root-result :unused]
        reads (atom [])
        replacements (proxy [AbstractList] []
                       (size [] (count values))
                       (get [i] (swap! reads conj i) (nth values i)))]
    (is (= [:head 1 2 inner empty-child :tail data] matches))
    (dotimes [_ 2]
      (reset! reads [])
      (is (= :root-result (p/update-paths data nav replacements)))
      (is (= (vec (range 7)) @reads)))
    (is (= [:head [1 2] [] :tail] data))))


(deftest vector-function-replacements-receive-values-and-updated-parents
  (let [inner (with-meta [1 2] {:source :inner})
        empty-child (with-meta [] {:source :empty})
        data (with-meta [0 inner empty-child 3] {:source :outer})
        pred #(or (number? %) (vector? %))
        {:keys [nav]} (p/path-when data pred)
        seen (atom [])
        replace-value (fn [x]
                        (swap! seen conj x)
                        (if (number? x)
                          (case x 0 p/REMOVE 1 nil 2 false 3 :three)
                          (conj x :parent)))
        expected [[nil false :parent] [:parent] :three :parent]
        expected-seen [0 1 2 [nil false] [] 3 [[nil false :parent] [:parent] :three]]]
    (doseq [update [#(p/update-paths data nav replace-value)
                   #(p/transform-when data pred replace-value)]]
      (reset! seen [])
      (let [updated (update)]
        (is (= expected updated))
        (is (= expected-seen @seen))
        (is (identical? empty-child (nth @seen 4)))
        (is (= [{:source :inner} {:source :empty} {:source :outer}]
               (mapv meta [(first updated) (second updated) updated])))))
    (is (= [0 [1 2] [] 3] data))))


(deftest scalar-vector-functions-across-tail-and-tree-boundaries
  (doseq [n [0 1 31 32 33 1023 1024 1025]
          sparse? [false true]]
    (let [original (mapv #(+ 1000 (* 3 %)) (range n))
          data (with-meta original {:source :function-boundaries})
          pred #(and (integer? %)
                  (or (not sparse?) (zero? (mod (- % 1000) 9))))
          resolve-value #(case (mod % 4) 0 nil 1 false 2 p/REMOVE (inc %))
          expected (into [] (comp (map #(if (pred %) (resolve-value %) %))
                                 (remove #(identical? p/REMOVE %))) data)
          expected-seen (filterv pred data)
          {:keys [nav]} (p/path-when data pred {:raw-matches true})
          seen (atom [])
          replace-value #(do (swap! seen conj %) (resolve-value %))]
      (doseq [update [#(p/update-paths data nav replace-value)
                     #(p/transform-when data pred replace-value)]
              _ (range 2)]
        (reset! seen [])
        (let [updated (update)]
          (is (= expected updated))
          (is (= expected-seen @seen))
          (is (= (meta data) (meta updated)))
          (is (= original data)))))))


(deftest vector-collection-only-and-no-match-identity
  (doseq [values [[] [1 nil false]]]
    (let [data (with-meta values {:source :original})
          {:keys [nav]} (p/path-when data #(identical? data %))
          seen (atom [])]
      (is (identical? data (p/update-paths data nav #(do (swap! seen conj %) %))))
      (is (identical? data (first @seen)))
      (is (= :whole (p/update-paths data nav [:whole])))
      (is (nil? (p/update-paths data nav [nil])))
      (is (identical? p/REMOVE (p/update-paths data nav [p/REMOVE])))
      (let [found (p/path-when data (constantly false))]
        (is (nil? found))
        (is (identical? data (p/update-paths data (:nav found) [])))))))


(deftest single-child-paths-share-the-cursor-with-multi-child-vectors
  (doseq [depth [0 1 32 128]]
    (let [wrap (fn [x] (reduce (fn [child level] (with-meta [child] {:level level})) x (range depth)))
          leaf (with-meta [:before 42 :after] {:source :leaf})
          nested (wrap leaf)
          untouched [:untouched]
          data (with-meta [0 nested 1 untouched] {:source :outer})
          {:keys [matches nav]} (p/path-when data integer? {:raw-matches true})]
      (is (= [0 42 1] (vec matches)))
      (doseq [value [nil false :replacement p/REMOVE]
              [kind replacements] (replacement-lists [:head value :tail])]
        (testing (str depth ", " value ", " kind)
          (let [expected-leaf (if (identical? p/REMOVE value) [:before :after] [:before value :after])
                updated (p/update-paths data nav replacements)
                actual-leaf (nth (iterate first (second updated)) depth)]
            (is (= [:head (wrap expected-leaf) :tail untouched] updated))
            (is (= {:source :outer} (meta updated)))
            (is (= {:source :leaf} (meta actual-leaf)))
            (is (= (mapv meta (take depth (iterate first nested)))
                   (mapv meta (take depth (iterate first (second updated))))))
            (is (identical? untouched (peek updated))))))
      (is (= [0 (wrap [:before 42 :after]) 1 untouched] data)))))
