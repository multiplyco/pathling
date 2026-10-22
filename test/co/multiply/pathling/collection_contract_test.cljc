(ns co.multiply.pathling.collection-contract-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [co.multiply.pathling :as p]
    [co.multiply.pathling.accumulator :as acc]))


(defrecord Example [value])


(defn- raw-values
  [values]
  (let [result (acc/accumulator)]
    (doseq [value values]
      (acc/acc-append! result value))
    result))


(defn- sequence-cases
  []
  (let [hash-entries (seq (hash-map :a 1 :b 2))]
    [[:integer-range (range 1 4) [1 2 3] '(2 3 4)]
     [:keys (keys (array-map 1 :a 2 :b)) [1 2] '(2 3)]
     [:vals (vals (array-map :a 1 :b 2)) [1 2] '(2 3)]
     [:array-map-seq (seq (array-map :a 1 :b 2)) [1 2] '([:a 2] [:b 3])]
     [:hash-map-seq hash-entries (mapv val hash-entries)
      (map (fn [[k v]] [k (inc v)]) hash-entries)]
     [:sorted-map-seq (seq (sorted-map :a 1 :b 2)) [1 2] '([:a 2] [:b 3])]
     [:reverse-sorted-map-seq (rseq (sorted-map :a 1 :b 2)) [2 1] '([:b 3] [:a 2])]
     [:vector-seq (seq [1 2 3]) [1 2 3] '(2 3 4)]]))


(deftest nonempty-sequence-implementations
  (doseq [[label input numbers expected] (sequence-cases)
          include-keys [false true]
          raw? [false true]]
    (testing (str label ", include-keys=" include-keys ", raw=" raw?)
      (let [data (with-meta input {:source label})
            opts {:include-keys include-keys :raw-matches raw?}
            {:keys [matches nav]} (p/path-when data number? opts)
            values (if raw? (acc/accumulator->vec matches) matches)]
        (is (= numbers values))
        (is (= numbers (p/find-when data number? opts)))
        (is (= numbers (p/find-when data number? (assoc opts :xf (map identity)))))
        (is (= data (p/update-paths data nav matches)))
        (doseq [updated [(p/update-paths data nav inc)
                         (p/update-paths data nav (mapv inc numbers))
                         (p/transform-when data number? inc opts)]]
          (is (= expected updated))
          (is (list? updated))
          (is (= {:source label} (meta updated))))
        (is (identical? data (p/transform-when data (constantly false) identity opts)))
        (is (identical? data (first (p/find-when data #(identical? data %) opts))))
        (is (= numbers (p/find-when data number? opts)))))))


(deftest map-entries-are-two-element-vectors
  (let [entry (first (array-map 1 2))]
    (doseq [include-keys [false true]]
      (let [opts {:include-keys include-keys}
            {:keys [matches nav]} (p/path-when entry number? opts)]
        (is (= [1 2] matches))
        (is (= matches (p/find-when entry number? opts)))
        (is (= matches (p/find-when entry number? (assoc opts :xf (map identity)))))
        (is (= [10 20] (p/update-paths entry nav [10 20])))
        (is (= [2 3] (p/transform-when entry number? inc opts)))
        (is (= [2] (p/transform-when entry #(= 1 %) (constantly p/REMOVE) opts)))
        (is (vector? (p/update-paths entry nav [10 20])))))))


(deftest record-values-preserve-record-type-and-metadata
  (let [data (with-meta (assoc (->Example [1 nil false]) :extra 2) {:source :record})
        expected (assoc data :value [2 nil false] :extra 3)]
    (doseq [include-keys [false true]
            raw? [false true]]
      (let [opts {:include-keys include-keys :raw-matches raw?}
            {:keys [matches nav]} (p/path-when data number? opts)]
        (is (= [1 2] (if raw? (acc/accumulator->vec matches) matches)))
        (is (= [1 2] (p/find-when data number? opts)))
        (is (= [1 2] (p/find-when data number? (assoc opts :xf (map identity)))))
        (doseq [updated [(p/update-paths data nav inc)
                         (p/update-paths data nav [2 3])
                         (p/transform-when data number? inc opts)]]
          (is (= expected updated))
          (is (record? updated))
          (is (= (meta data) (meta updated))))
        (is (= data (p/update-paths data nav matches)))))
    (is (= {"value" [1 nil false] "extra" 2}
           (p/transform-when data keyword? name {:include-keys true})))
    (let [updated (p/transform-when data #(= [1 nil false] %) (constantly p/REMOVE))]
      (is (= {:extra 2} updated))
      (is (not (record? updated)))
      (is (= (meta data) (meta updated))))
    (is (= [1 nil false] (:value data)))))


(deftest fallback-sequences-stop-visiting-after-reduction
  (doseq [[label data numbers _] (sequence-cases)
          include-keys [false true]]
    (testing (str label ", include-keys=" include-keys)
      (let [seen (atom [])
            completed (atom 0)
            xf (fn [rf]
                 (let [step ((take 1) rf)]
                   (fn
                     ([] (step))
                     ([result] (swap! completed inc) (step result))
                     ([result value] (step result value)))))
            result (p/find-when data
                     #(do (swap! seen conj %) (number? %))
                     {:include-keys include-keys :xf xf})]
        (is (= [(first numbers)] result))
        (is (= 1 @completed))
        (is (= (first numbers) (last @seen)))
        (is (= [(first numbers)] (filterv number? @seen)))))))


(deftest removal-sentinel-does-not-collide-with-keyword-data
  (let [value :co.multiply.pathling.impl/remove
        data [value {value value} #{value} (list value)]]
    (doseq [include-keys [false true]
            raw? [false true]
            pred [keyword? (constantly true)]]
      (let [opts {:include-keys include-keys :raw-matches raw?}
            {:keys [matches nav]} (p/path-when data pred opts)]
        (is (= data (p/transform-when data pred identity opts)))
        (is (= data (p/update-paths data nav identity)))
        (is (= data (p/update-paths data nav matches)))))
    (is (= [nil false]
           (p/transform-when [1 nil false 2] number? (constantly p/REMOVE))))
    (is (identical? p/REMOVE (p/transform-when 1 number? (constantly p/REMOVE))))))


(deftest positional-replacement-length-and-reuse
  (doseq [make-values [vec raw-values]]
    (let [data [1 {:x 2} 3]
          {:keys [nav]} (p/path-when data number?)]
      (is (thrown? #?(:clj IndexOutOfBoundsException :cljs js/Error)
            (p/update-paths data nav (make-values [nil false]))))
      (is (= [nil {:x false}]
             (p/update-paths data nav (make-values [nil false p/REMOVE]))))
      (is (= [10 {:x 20} 30]
             (p/update-paths data nav (make-values [10 20 30 :unused]))))
      (is (identical? data (p/update-paths data nil (make-values []))))
      (is (= [1 {:x 2} 3] data)))))


(deftest accumulator-set-returns-the-accumulator
  (let [values (raw-values [:before])]
    (is (identical? values (acc/acc-set! values 0 :after)))
    (is (= [:after] (acc/accumulator->vec values)))))


(deftest key-collisions-follow-update-order
  (doseq [make-map [array-map hash-map sorted-map]]
    (let [data (with-meta (make-map :a 1 :b 2 :c 3) {:source :collision})
          opts {:include-keys true}
          {:keys [matches nav]} (p/path-when data keyword? opts)
          expected {:same (get data (last matches))}]
      (doseq [updated [(p/update-paths data nav (vec (repeat (count matches) :same)))
                       (p/transform-when data keyword? (constantly :same) opts)]]
        (is (= expected updated))
        (is (= (meta data) (meta updated))))
      (is (= {:a 2 :c 3} (p/transform-when data #{:b} (constantly :a) opts)))
      (is (= #{7} (p/transform-when #{1 2 3} number? (constantly 7)))))))


(deftest host-collections-remain-opaque-inputs
  (let [data (raw-values [1 2])]
    (is (nil? (p/find-when data number?)))
    (is (nil? (p/path-when data number?)))
    (is (identical? data (p/transform-when data number? inc)))
    (is (identical? data (first (p/find-when data #(identical? data %)))))
    (is (= :whole (p/transform-when data #(identical? data %) (constantly :whole))))))
