(ns co.multiply.pathling.conformance-test
  "Contracts shared by the specialized scanners and update implementations."
  (:require
    [clojure.test :refer [deftest is testing]]
    [co.multiply.pathling :as p]
    [co.multiply.pathling.accumulator :refer [acc-set! accumulator->vec]]))


(defn- empty-queue
  []
  #?(:clj clojure.lang.PersistentQueue/EMPTY
     :cljs (.-EMPTY cljs.core/PersistentQueue)))


(deftest empty-sequential-test
  (doseq [[label make-empty] [[:list (constantly '())]
                              [:lazy-seq #(lazy-seq nil)]
                              [:filtered-seq #(filter odd? [2 4])]
                              [:range #(range 0)]
                              [:queue empty-queue]]
          include-keys [false true]]
    (testing (str label ", include-keys=" include-keys)
      (let [data (with-meta (make-empty) {:source label})
            opts {:include-keys include-keys}]
        (is (nil? (p/find-when data nil? opts)))
        (is (nil? (p/find-when data nil? (assoc opts :xf (map identity)))))
        (is (nil? (p/path-when data nil? opts)))
        (is (identical? data (p/transform-when data nil? (constantly :wrong) opts)))
        (testing "The empty collection itself is still visited"
          (let [pred #(identical? data %)
                {:keys [matches nav]} (p/path-when data pred opts)]
            (is (identical? data (first (p/find-when data pred opts))))
            (is (identical? data (first matches)))
            (is (identical? data (first (p/find-when data pred (assoc opts :xf (map identity))))))
            (is (= :replaced (p/update-paths data nav [:replaced])))
            (is (= :replaced (p/transform-when data pred (constantly :replaced) opts)))))
        (testing "Real nil elements remain distinct from empty collections"
          (let [nested [data nil (list nil) false]
                {:keys [matches nav]} (p/path-when nested nil? (assoc opts :raw-matches true))]
            (is (= [nil nil] (accumulator->vec matches)))
            (acc-set! matches 0 :first)
            (acc-set! matches 1 :second)
            (let [updated (p/update-paths nested nav matches)]
              (is (= [data :first (list :second) false] updated))
              (is (= {:source label} (meta (first updated)))))
            (is (= [data :replaced (list :replaced) false]
                   (p/transform-when nested nil? (constantly :replaced) opts)))))))))


(deftest sequential-original-collection-test
  (doseq [[label data] [[:list (list 1 2)]
                        [:lazy-seq (map identity [1 2])]
                        [:queue (conj (empty-queue) 1 2)]]
          include-keys [false true]]
    (testing (str label ", include-keys=" include-keys)
      (let [opts {:include-keys include-keys}
            pred #(identical? data %)
            {:keys [matches nav]} (p/path-when data pred opts)]
        (is (identical? data (first matches)))
        (is (identical? data (first (p/find-when data pred opts))))
        (is (identical? data (first (p/find-when data pred (assoc opts :xf (map identity))))))
        (is (= :whole (p/update-paths data nav [:whole])))
        (is (= :whole (p/transform-when data pred (constantly :whole) opts)))
        (is (= [1 2] (p/find-when data number? opts)))
        (is (= [1 2] (:matches (p/path-when data number? opts))))))))


(deftest key-value-replacement-order-test
  (doseq [[label make-map] [[:array-map array-map] [:hash-map hash-map] [:sorted-map sorted-map]]
          data [(make-map :a :b) (make-map :outer (make-map :inner 1))]
          raw? [false true]]
    (testing (str label ", raw-matches=" raw? ", data=" data)
      (let [opts {:include-keys true :raw-matches raw?}
            {:keys [matches nav]} (p/path-when data keyword? opts)
            original-matches (if raw? (accumulator->vec matches) matches)
            replacements {:a :new-a :b :new-b :outer :new-outer :inner :new-inner}
            expected (if (contains? data :a) {:new-a :new-b} {:new-outer {:new-inner 1}})]
        (is (= (if (contains? data :a) [:b :a] [:inner :outer]) original-matches))
        (is (= data (p/update-paths data nav matches)))
        (let [seen (atom [])]
          (is (= data (p/update-paths data nav #(do (swap! seen conj %) %))))
          (is (= original-matches @seen)))
        (let [seen (atom [])]
          (is (= data (p/transform-when data keyword? #(do (swap! seen conj %) %) opts)))
          (is (= original-matches @seen)))
        (if raw?
          (do
            (doseq [[i value] (map-indexed vector original-matches)]
              (acc-set! matches i (replacements value)))
            (is (= expected (p/update-paths data nav matches))))
          (is (= expected (p/update-paths data nav (mapv replacements matches)))))))))


(deftest key-value-collisions-and-removals-test
  (doseq [make-map [array-map hash-map sorted-map]
          replacements [{:a :c :b :B :c :a :d :D :tail :end}
                        {:a p/REMOVE :b :B :c :C :d :D :tail :end}
                        {:a :A :b p/REMOVE :c :C :d :D :tail :end}]]
    (let [data [(with-meta (make-map :a :b :c :d) {:source :map}) :tail]
          opts {:include-keys true :raw-matches true}
          {:keys [matches nav]} (p/path-when data keyword? opts)
          original-matches (accumulator->vec matches)
          expected [(if (some #(identical? p/REMOVE %) (vals replacements))
                      {:C :D}
                      {:c :B :a :D}) :end]]
      (doseq [[i value] (map-indexed vector original-matches)]
        (acc-set! matches i (replacements value)))
      (let [updated (p/update-paths data nav matches)]
        (is (= expected updated))
        (is (= {:source :map} (meta (first updated)))))
      (is (= expected (p/transform-when data keyword? replacements opts))))))


(defn- wrapping-transducer
  [rf]
  ;; A transducer may replace its reduction state between steps.
  (let [unwrap #(if (and (map? %) (contains? % ::acc)) (::acc %) %)]
    (fn
      ([] (rf))
      ([state] (rf (unwrap state)))
      ([state value]
       (let [index (get state ::index 0)]
         {::acc (rf (unwrap state) (+ value index))
          ::index (inc index)})))))


(deftest original-predicate-updated-parent-test
  (doseq [include-keys [false true]]
    (let [data {:a [1]}
          opts {:include-keys include-keys}
          pred #(or (number? %) (= [1] %))
          tf #(if (number? %) (inc %) (conj % :parent))
          {:keys [matches nav]} (p/path-when data pred opts)]
      (is (= [1 [1]] matches))
      (is (= matches (p/find-when data pred opts)))
      (is (= {:a [2 :parent]} (p/update-paths data nav tf)))
      (is (= {:a [2 :parent]} (p/transform-when data pred tf opts))))))


(deftest transducer-completion-result-types-test
  (doseq [include-keys [false true]
          [completion expected] [[nil nil] [[] nil] ['() nil]
                                 ['(:done) [:done]] [[:done] [:done]]
                                 [#?(:clj (object-array [:done]) :cljs #js [:done]) [:done]]]]
    (let [xf (halt-when #(= 2 %) (fn [_ _] completion))]
      (is (= expected (p/find-when [1 2 3] number? {:include-keys include-keys :xf xf})))))
  (doseq [include-keys [false true]
          completion [false 0 :stopped "scalar"]]
    (is (thrown-with-msg? #?(:clj IllegalArgumentException :cljs js/Error)
                          #"find-when transducer must complete to a collection or nil"
          (p/find-when [1 2 3] number?
            {:include-keys include-keys
             :xf (halt-when #(= 2 %) (fn [_ _] completion))})))))


(deftest transducer-state-and-completion-test
  (doseq [include-keys [false true]]
    (let [opts {:include-keys include-keys}]
      (testing "The state returned by each reduction step reaches the next step"
        (is (= [2 4 6] (p/find-when [1 2 3] number?
                         (assoc opts :xf (comp wrapping-transducer (map inc)))))))
      (testing "Reduced state reaches completion and its result is used"
        (let [xf (halt-when #(= 3 %) (fn [result _] (vec (reverse result))))
              visited (atom [])]
          (is (= (into [] xf [1 2 3 4])
                 (p/find-when [1 [2 3] 4] #(do (swap! visited conj %) (number? %))
                   (assoc opts :xf xf))))
          (is (= [1 2 3] @visited))))
      (testing "Completion runs once, including when no values match"
        (doseq [data [[] [1 2 3]]]
          (let [completed (atom 0)
                xf (fn [rf]
                     (fn
                       ([] (rf))
                       ([result]
                        (swap! completed inc)
                        (rf result)
                        [:completed])
                       ([result input] (rf result input))))]
            (is (= [:completed] (p/find-when data number? (assoc opts :xf xf))))
            (is (= 1 @completed)))))
      (testing "An empty completion result becomes nil"
        (is (nil? (p/find-when [1 2 3] number?
                    (assoc opts :xf (halt-when #(= 2 %) (fn [_ _] []))))))))))
