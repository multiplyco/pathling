(ns co.multiply.pathling.find-test
  "Tests for find-when functionality including transducers and include-keys."
  (:require
    [clojure.test :refer [deftest is testing]]
    [co.multiply.pathling :as p]))


;; find-when tests
;; #######################################

(deftest find-when-basic-test
  (testing "Find numbers in nested structure"
    (let [data   [1 {:a 2} {:b #{3 {:c 4}}}]
          result (p/find-when data number?)]
      (is (= [1 2 3 4] result))))

  (testing "Find with transducer"
    (let [data   [1 {:a 2} {:b #{3 {:c 4}}}]
          result (p/find-when data number? (map inc))]
      (is (= [2 3 4 5] result)))))


(deftest find-when-empty-test
  (testing "No matches returns nil"
    (let [data   [:a :b :c]
          result (p/find-when data number?)]
      (is (nil? result)))))


(deftest find-when-scalar-test
  (testing "Top-level scalar match"
    (let [result (p/find-when 42 number?)]
      (is (= [42] result))))

  (testing "Top-level scalar with transducer"
    (let [result (p/find-when 42 number? (map inc))]
      (is (= [43] result))))

  (testing "Non-matching scalar"
    (let [result (p/find-when :keyword number?)]
      (is (nil? result))))

  (testing "Nil value"
    (let [result (p/find-when nil nil?)]
      (is (= [nil] result)))

    (let [result (p/find-when nil number?)]
      (is (nil? result)))))


(deftest find-when-nested-test
  (testing "Nested matches - depth-first order"
    (let [data   [{:a {:b 1}}]
          result (p/find-when data map?)]
      ;; Depth-first: deeper matches come before shallower ones
      (is (= [{:b 1} {:a {:b 1}}] result)))))


(deftest find-when-transducer-test
  (testing "Transducer with map inc"
    (let [data   {:x 1 :y 2 :z 3}
          result (p/find-when data number? (map inc))]
      (is (= [2 3 4] result))))

  (testing "Transducer with keyword extraction"
    (let [data   [{:id 1 :name "a"} {:id 2 :name "b"}]
          result (p/find-when data map? (map :id))]
      (is (= [1 2] result))))

  (testing "Transducer with complex function"
    (let [data   [1 2 3 4 5]
          result (p/find-when data #(and (number? %) (even? %)) (map #(* % 10)))]
      (is (= [20 40] result)))))


;; Comprehensive transducer tests
;; #######################################

(deftest find-when-early-termination-test
  (testing "take 1 stops after first match"
    (let [data   [1 2 3 4 5]
          result (p/find-when data number? (take 1))]
      (is (= [1] result))))

  (testing "take 3 stops after 3 matches"
    (let [data   [1 2 3 4 5 6 7 8 9 10]
          result (p/find-when data number? (take 3))]
      (is (= [1 2 3] result))))

  (testing "take with nested structure"
    (let [data   {:a 1 :b {:c 2 :d {:e 3 :f 4}} :g 5}
          result (p/find-when data number? (take 2))]
      ;; Should get first 2 numbers in depth-first order
      (is (= 2 (count result)))
      (is (every? number? result))))

  (testing "take 0 returns nil"
    (let [data   [1 2 3]
          result (p/find-when data number? (take 0))]
      (is (nil? result))))

  (testing "take more than available matches"
    (let [data   [1 2 3]
          result (p/find-when data number? (take 100))]
      (is (= [1 2 3] result))))

  (testing "take-while stops at first non-matching"
    (let [data   [1 2 3 4 5 6 7 8 9 10]
          result (p/find-when data number? (take-while #(< % 5)))]
      (is (= [1 2 3 4] result))))

  (testing "take-while with no matches"
    (let [data   [10 20 30]
          result (p/find-when data number? (take-while #(< % 5)))]
      (is (nil? result))))

  (testing "drop-while skips initial matches"
    (let [data   [1 2 3 4 5 6]
          result (p/find-when data number? (drop-while #(< % 4)))]
      (is (= [4 5 6] result))))

  (testing "drop skips first n matches"
    (let [data   [1 2 3 4 5]
          result (p/find-when data number? (drop 2))]
      (is (= [3 4 5] result))))

  (testing "drop all returns nil"
    (let [data   [1 2 3]
          result (p/find-when data number? (drop 10))]
      (is (nil? result)))))


(deftest find-when-stateful-transducers-test
  (testing "partition-all groups matches"
    (let [data   [1 2 3 4 5 6 7]
          result (p/find-when data number? (partition-all 3))]
      (is (= [[1 2 3] [4 5 6] [7]] result))))

  (testing "partition-all with exact multiple"
    (let [data   [1 2 3 4 5 6]
          result (p/find-when data number? (partition-all 2))]
      (is (= [[1 2] [3 4] [5 6]] result))))

  (testing "partition-all with single element groups"
    (let [data   [1 2 3]
          result (p/find-when data number? (partition-all 1))]
      (is (= [[1] [2] [3]] result))))

  (testing "partition-by groups consecutive matches"
    (let [data   [1 1 2 2 2 3 1 1]
          result (p/find-when data number? (partition-by identity))]
      (is (= [[1 1] [2 2 2] [3] [1 1]] result))))

  (testing "dedupe removes consecutive duplicates"
    (let [data   [1 1 2 2 2 3 3 1 1]
          result (p/find-when data number? (dedupe))]
      (is (= [1 2 3 1] result))))

  (testing "distinct removes all duplicates"
    (let [data   [1 2 1 3 2 4 3 5]
          result (p/find-when data number? (distinct))]
      (is (= [1 2 3 4 5] result))))

  (testing "distinct with no duplicates"
    (let [data   [1 2 3 4 5]
          result (p/find-when data number? (distinct))]
      (is (= [1 2 3 4 5] result))))

  (testing "distinct returns nil when all duplicates removed"
    (let [data   [1 1 1]
          result (p/find-when data number? (comp (distinct) (drop 1)))]
      (is (nil? result)))))


(deftest find-when-filtering-transducers-test
  (testing "filter within transducer"
    (let [data   [1 2 3 4 5 6 7 8 9 10]
          result (p/find-when data number? (filter even?))]
      (is (= [2 4 6 8 10] result))))

  (testing "remove within transducer"
    (let [data   [1 2 3 4 5 6 7 8 9 10]
          result (p/find-when data number? (remove even?))]
      (is (= [1 3 5 7 9] result))))

  (testing "keep applies fn and filters nils"
    (let [data   [1 2 3 4 5]
          result (p/find-when data number? (keep #(when (even? %) (* % 10))))]
      (is (= [20 40] result))))

  (testing "keep-indexed includes index"
    (let [data   [10 20 30 40 50]
          result (p/find-when data number? (keep-indexed #(when (even? %1) %2)))]
      (is (= [10 30 50] result))))

  (testing "filter returns nil when nothing passes"
    (let [data   [1 3 5 7 9]
          result (p/find-when data number? (filter even?))]
      (is (nil? result)))))


(deftest find-when-transducer-composition-test
  (testing "comp map and filter"
    (let [data   [1 2 3 4 5 6 7 8 9 10]
          result (p/find-when data number? (comp (filter even?) (map #(* % 10))))]
      (is (= [20 40 60 80 100] result))))

  (testing "comp filter and take"
    (let [data   [1 2 3 4 5 6 7 8 9 10]
          result (p/find-when data number? (comp (filter even?) (take 3)))]
      (is (= [2 4 6] result))))

  (testing "comp map, filter, and take"
    (let [data   [1 2 3 4 5 6 7 8 9 10]
          result (p/find-when data number? (comp (map inc)
                                                 (filter even?)
                                                 (take 3)))]
      ;; [1 2 3 4 5...] -> map inc -> [2 3 4 5 6...] -> filter even? -> [2 4 6...] -> take 3
      (is (= [2 4 6] result))))

  (testing "comp with partition-all"
    (let [data   [1 2 3 4 5 6 7 8 9]
          result (p/find-when data number? (comp (filter odd?) (partition-all 2)))]
      (is (= [[1 3] [5 7] [9]] result))))

  (testing "comp drop and take (pagination)"
    (let [data   [1 2 3 4 5 6 7 8 9 10]
          result (p/find-when data number? (comp (drop 3) (take 4)))]
      (is (= [4 5 6 7] result))))

  (testing "triple composition with early termination"
    (let [data   {:a 1 :b 2 :c {:d 3 :e 4} :f 5 :g {:h 6 :i 7}}
          result (p/find-when data number? (comp (map #(* % 2))
                                                 (filter #(> % 5))
                                                 (take 2)))]
      ;; Numbers in depth-first: depends on map ordering
      ;; Result should have exactly 2 elements, each > 5 after doubling
      (is (= 2 (count result)))
      (is (every? #(> % 5) result)))))


(deftest find-when-transducer-large-scale-test
  (testing "early termination is efficient on large data"
    ;; This tests that we actually stop scanning early
    (let [data   (vec (range 100000))
          result (p/find-when data number? (take 5))]
      (is (= [0 1 2 3 4] result))))

  (testing "partition-all on large data"
    (let [data   (vec (range 100))
          result (p/find-when data number? (partition-all 10))]
      (is (= 10 (count result)))
      (is (every? #(= 10 (count %)) result))))

  (testing "distinct on large data with duplicates"
    (let [;; Create data with many duplicates
          data   (vec (for [_ (range 100)] (rand-int 20)))
          result (p/find-when data number? (distinct))]
      ;; Should have at most 20 unique values
      (is (<= (count result) 20))
      (is (= (count result) (count (set result))))))

  (testing "complex composition on large nested structure"
    (let [data   (vec (for [i (range 100)]
                        {:id i :values (vec (range i (+ i 5)))}))
          ;; Find all numbers, take evens, double them, take first 50
          result (p/find-when data number? (comp (filter even?)
                                                 (map #(* % 2))
                                                 (take 50)))]
      (is (= 50 (count result)))
      (is (every? even? result)))))


(deftest find-when-transducer-include-keys-test
  (testing "take with include-keys"
    (let [data   {:a 1 :b 2 :c 3}
          result (p/find-when data keyword? {:include-keys true :xf (take 2)})]
      (is (= 2 (count result)))
      (is (every? keyword? result))))

  (testing "map name with include-keys"
    (let [data   {:a 1 :b 2 :c 3}
          result (p/find-when data keyword? {:include-keys true :xf (map name)})]
      (is (= 3 (count result)))
      (is (every? string? result))))

  (testing "filter and take with include-keys"
    (let [data   {:abc 1 :ab 2 :a 3 :abcd 4}
          result (p/find-when data keyword?
                   {:include-keys true
                    :xf           (comp (filter #(> (count (name %)) 2))
                                        (take 2))})]
      (is (= 2 (count result)))
      (is (every? #(> (count (name %)) 2) result))))

  (testing "partition-all with include-keys"
    (let [data   {:a 1 :b 2 :c 3 :d 4}
          result (p/find-when data keyword? {:include-keys true :xf (partition-all 2)})]
      (is (= 2 (count result)))
      (is (every? #(= 2 (count %)) result))
      (is (every? keyword? (flatten result))))))


(deftest find-when-large-scale-test
  (testing "Stack-safe with 10,000 matches"
    (let [data   (vec (range 10000))
          result (p/find-when data number?)]
      (is (= 10000 (count result)))
      (is (= (vec (range 10000)) result))))

  (testing "Stack-safe with 10,000 matches and transducer"
    (let [data   (vec (range 10000))
          result (p/find-when data number? (map inc))]
      (is (= 10000 (count result)))
      (is (= 0 (first data)))
      (is (= 1 (first result)))
      (is (= 9999 (last data)))
      (is (= 10000 (last result))))))


(deftest find-when-collection-types-test
  (testing "Lists"
    (let [data   '(1 2 3)
          result (p/find-when data number?)]
      (is (= [1 2 3] result))))

  (testing "Lists with transducer"
    (let [data   '(1 2 3)
          result (p/find-when data number? (map inc))]
      (is (= [2 3 4] result))))

  (testing "Sets"
    (let [data   #{1 2 3}
          result (p/find-when data number?)]
      (is (= 3 (count result)))
      (is (every? #{1 2 3} result))))

  (testing "Sets with transducer"
    (let [data   #{1 2 3}
          result (p/find-when data number? (map inc))]
      (is (= 3 (count result)))
      (is (every? #{2 3 4} result))))

  (testing "Nested mixed collections"
    (let [data   '(1 [2 3] {:a 4} #{5})
          result (p/find-when data number?)]
      (is (= [1 2 3 4 5] result)))))


(deftest find-when-falsy-elements-test
  (testing "Vectors with nil elements"
    (let [data   [1 nil 3]
          result (p/find-when data number?)]
      (is (= [1 3] result))))

  (testing "Lists with false elements"
    (let [data   '(true false nil 42)
          result (p/find-when data number?)]
      (is (= [42] result))))

  (testing "Maps with nil values"
    (let [data   {:a 1 :b nil :c 3}
          result (p/find-when data number?)]
      (is (= [1 3] result))))

  (testing "Sets with nil and false"
    (let [data   #{1 nil false 3}
          result (p/find-when data number?)]
      (is (= 2 (count result)))
      (is (every? #{1 3} result)))))


(deftest find-when-empty-collections-test
  (testing "Empty collections as values"
    (let [data   {:a [] :b {} :c #{}}
          result (p/find-when data number?)]
      (is (nil? result))))

  (testing "Nested empty collections"
    (let [data   [1 [] {:a {}} #{2}]
          result (p/find-when data number?)]
      (is (= [1 2] result))))

  (testing "Empty collection preserved"
    (let [data   {:numbers [1 2] :empty []}
          result (p/find-when data number?)]
      (is (= [1 2] result)))))


(deftest find-when-efficiency-test
  (testing "find-when more efficient than path-when for just matches"
    ;; This test just validates behavior equivalence
    ;; Actual efficiency measured via benchmarks
    (let [data                [1 {:a 2} {:b #{3 {:c 4}}}]
          find-result         (p/find-when data number?)
          path-result-matches (:matches (p/path-when data number?))]
      (is (= find-result path-result-matches)))))


(deftest find-when-nil-in-middle-test
  (testing "Sequential with nil in middle position"
    (let [data   '(1 nil 3)
          result (p/find-when data number?)]
      (is (= [1 3] result))))

  (testing "Set with nil in any position"
    (let [data   #{1 nil 3}
          result (p/find-when data number?)]
      (is (= 2 (count result)))
      (is (every? #{1 3} result))))

  (testing "Map with nil as first value (keys are sorted)"
    ;; Keys will be sorted, so we need to ensure nil is encountered early
    (let [data   {:a nil :b 2 :c 3}
          result (p/find-when data number?)]
      (is (= [2 3] result)))))


;; find-when with include-keys tests
;; #######################################

(deftest find-when-include-keys-basic-test
  (testing "Find keys only"
    (let [data   {:a 1 :b 2 :c 3}
          result (p/find-when data keyword? {:include-keys true})]
      (is (= 3 (count result)))
      (is (every? keyword? result))
      (is (every? #{:a :b :c} result))))

  (testing "Find values only (default behavior)"
    (let [data   {:a 1 :b 2 :c 3}
          result (p/find-when data number?)]
      (is (= [1 2 3] result))))

  (testing "Find both keys and values with same predicate"
    (let [data   {1 :one 2 :two 3 :three}
          result (p/find-when data #(or (number? %) (keyword? %)) {:include-keys true})]
      (is (= 6 (count result)))
      (is (= 3 (count (filter number? result))))
      (is (= 3 (count (filter keyword? result)))))))


(deftest find-when-include-keys-with-xf-test
  (testing "Transform keys before collecting"
    (let [data   {:a 1 :b 2 :c 3}
          result (p/find-when data keyword? {:include-keys true :xf (map name)})]
      (is (= 3 (count result)))
      (is (every? string? result))
      (is (every? #{"a" "b" "c"} result))))

  (testing "Transform values and keys differently"
    (let [data        {:a 1 :b 2}
          keys-result (p/find-when data keyword? {:include-keys true :xf (map name)})
          vals-result (p/find-when data number? {:xf (map inc)})]
      (is (= #{"a" "b"} (set keys-result)))
      (is (= [2 3] vals-result))))

  (testing "Transducer without include-keys"
    (let [data   {:a 1 :b 2 :c 3}
          result (p/find-when data keyword? (map name))]
      ;; Without include-keys, only values are scanned (none match keyword?)
      (is (nil? result)))))


(deftest find-when-include-keys-nested-test
  (testing "Nested maps with keys"
    (let [data   {:outer {:inner 1}}
          result (p/find-when data keyword? {:include-keys true})]
      (is (= 2 (count result)))
      (is (every? keyword? result))
      (is (every? #{:outer :inner} result))))

  (testing "Deep nesting"
    (let [data   {:a {:b {:c {:d 1}}}}
          result (p/find-when data keyword? {:include-keys true})]
      (is (= 4 (count result)))
      (is (every? keyword? result))))

  (testing "Nested maps in vectors"
    (let [data   [{:a 1} {:b 2} {:c 3}]
          result (p/find-when data keyword? {:include-keys true})]
      (is (= 3 (count result)))
      (is (every? #{:a :b :c} result)))))


(deftest find-when-include-keys-large-scale-test
  (testing "Stack-safe with 1000 keys"
    (let [data   (into {} (map (fn [n] [(keyword (str "key" n)) n])) (range 1000))
          result (p/find-when data keyword? {:include-keys true})]
      (is (= 1000 (count result)))
      (is (every? keyword? result)))))
