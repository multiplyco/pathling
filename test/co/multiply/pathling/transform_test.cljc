(ns co.multiply.pathling.transform-test
  "Tests for transform-when functionality including REMOVE sentinel."
  (:require
    [clojure.test :refer [deftest is testing]]
    [co.multiply.pathling :as p])
  #?(:clj (:import
            [clojure.lang PersistentTreeMap PersistentTreeSet])))


;; transform-when with include-keys tests
;; #######################################

(deftest transform-when-include-keys-basic-test
  (testing "Transform keys only"
    (let [data   {:a 1 :b 2 :c 3}
          result (p/transform-when data keyword? name {:include-keys true})]
      (is (= {"a" 1 "b" 2 "c" 3} result))))

  (testing "Transform values only (default behavior)"
    (let [data   {:a 1 :b 2 :c 3}
          result (p/transform-when data number? inc)]
      (is (= {:a 2 :b 3 :c 4} result))
      ;; Keys unchanged
      (is (every? keyword? (keys result)))))

  (testing "Transform both keys and values"
    (let [data   {:a 1 :b 2 :c 3}
          result (-> data
                   (p/transform-when keyword? name {:include-keys true})
                   (p/transform-when number? inc))]
      (is (= {"a" 2 "b" 3 "c" 4} result)))))


(deftest transform-when-include-keys-nested-test
  (testing "Nested maps with key transformation"
    (let [data   {:a {:b 1 :c 2}}
          result (p/transform-when data keyword? name {:include-keys true})]
      (is (= {"a" {"b" 1 "c" 2}} result))))

  (testing "Deep nesting"
    (let [data   {:a {:b {:c {:d 1}}}}
          result (p/transform-when data keyword? name {:include-keys true})]
      (is (= {"a" {"b" {"c" {"d" 1}}}} result))))

  (testing "Nested maps in vectors"
    (let [data   [{:a 1} {:b 2} {:c 3}]
          result (p/transform-when data keyword? name {:include-keys true})]
      (is (= [{"a" 1} {"b" 2} {"c" 3}] result)))))


(deftest transform-when-include-keys-large-scale-test
  (testing "Stack-safe with 1000 maps, all keys transformed"
    (let [data   (into {} (map (fn [n] [(keyword (str "key" n)) n])) (range 1000))
          result (p/transform-when data keyword? name {:include-keys true})]
      (is (= 1000 (count result)))
      ;; All keys should be strings now
      (is (every? string? (keys result)))
      ;; Values unchanged
      (is (= (set (vals data)) (set (vals result)))))))


(deftest transform-when-subvector-test
  (testing "Transform SubVector (created by subvec)"
    (let [original    [1 2 3 4 5 6 7 8 9 10]
          ;; Create a SubVector - these don't support transient
          subvec-data (subvec original 2 8)                 ; [3 4 5 6 7 8]
          result      (p/transform-when subvec-data number? inc)]
      (is (= [4 5 6 7 8 9] result))))

  (testing "Transform nested structure containing SubVector"
    (let [original [1 2 3 4 5 6 7 8]
          data     {:numbers (subvec original 1 5)          ; [2 3 4 5]
                    :meta    "test"}
          result   (p/transform-when data number? #(* % 2))]
      (is (= {:numbers [4 6 8 10] :meta "test"} result)))))


(deftest transform-when-sorted-map-test
  (testing "Transform sorted-map values"
    (let [data   (sorted-map :a 1 :b 2 :c 3)
          result (p/transform-when data number? inc)]
      (is (= {:a 2 :b 3 :c 4} result))
      ;; Should remain a sorted-map
      #?(:clj (is (instance? PersistentTreeMap result)))))

  (testing "Transform sorted-map keys with include-keys"
    (let [data   (sorted-map :a 1 :b 2 :c 3)
          result (p/transform-when data keyword? name {:include-keys true})]
      (is (= {"a" 1 "b" 2 "c" 3} result))
      ;; Type changes when keys change (keys determine sort order)
      (is (map? result))))

  (testing "path-when with sorted-map"
    (let [data   (sorted-map :a 1 :b 2 :c 3)
          {:keys [matches nav]} (p/path-when data number?)
          result (p/update-paths data nav #(* % 10))]
      (is (= [1 2 3] matches))
      (is (= {:a 10 :b 20 :c 30} result))
      #?(:clj (is (instance? PersistentTreeMap result))))))


(deftest transform-when-sorted-set-test
  (testing "Transform sorted-set values"
    (let [data   (sorted-set 1 2 3 4 5)
          result (p/transform-when data number? inc)]
      (is (= #{2 3 4 5 6} result))
      ;; Should remain a sorted-set
      #?(:clj (is (instance? PersistentTreeSet result)))
      ;; Should maintain sort order
      (is (= [2 3 4 5 6] (seq result)))))

  (testing "Transform sorted-set with custom comparator"
    (let [data   (sorted-set-by > 5 3 7 1 9)                ; Descending order
          result (p/transform-when data number? inc)]
      (is (= #{2 4 6 8 10} result))
      #?(:clj (is (instance? PersistentTreeSet result)))
      ;; Should maintain descending order
      (is (= [10 8 6 4 2] (seq result)))))

  (testing "path-when with sorted-set"
    (let [data   (sorted-set 5 3 9 1 7)
          {:keys [matches nav]} (p/path-when data number?)
          result (p/update-paths data nav #(* % 2))]
      (is (= 5 (count matches)))
      (is (= #{2 6 10 14 18} result))
      #?(:clj (is (instance? PersistentTreeSet result)))
      ;; Should maintain sorted order
      (is (= [2 6 10 14 18] (seq result))))))


#?(:clj (deftest struct-map-keys-ignored-test
          (testing "Struct map keys are never transformed, even with include-keys"
            (let [s      (create-struct :a :b)
                  data   (struct-map s :a 1 :b 2)
                  ;; Try to transform keys - should be ignored for struct maps
                  result (p/transform-when data keyword? name {:include-keys true})]
              ;; Keys remain as keywords (not transformed to strings)
              (is (= {:a 1 :b 2} result))
              (is (every? keyword? (keys result)))))

          (testing "Struct map values can still be transformed"
            (let [s      (create-struct :a :b)
                  data   (struct-map s :a 1 :b 2)
                  result (p/transform-when data number? inc)]
              (is (= {:a 2 :b 3} result))))

          (testing "path-when with struct map ignores keys"
            (let [s    (create-struct :a :b)
                  data (struct-map s :a 1 :b 2)
                  {:keys [matches]} (p/path-when data keyword? {:include-keys true})]
              ;; Only values (numbers) would match, NOT keys - but we're looking for keywords
              ;; So no matches at all since keys are ignored
              (is (nil? matches))))

          (testing "path-when with struct map finds values"
            (let [s    (create-struct :a :b)
                  data (struct-map s :a 1 :b 2)
                  {:keys [matches]} (p/path-when data number?)]
              ;; Values should still be found
              (is (= 2 (count matches)))
              (is (every? number? matches))))

          (testing "Nested struct maps with include-keys"
            (let [s      (create-struct :x :y)
                  data   {:outer (struct-map s :x 1 :y 2)}
                  result (p/transform-when data keyword? name {:include-keys true})]
              ;; Outer map key :outer is transformed
              ;; But struct map keys :x and :y are NOT transformed
              (is (= {"outer" {:x 1 :y 2}} result))
              (is (string? (first (keys result))))
              (is (every? keyword? (keys (get result "outer"))))))))


;; REMOVE sentinel tests
;; #######################################

(deftest remove-from-vector-test
  (testing "Remove single element from vector"
    (let [data   [1 2 3 4 5]
          result (p/transform-when data #(= % 3) (constantly p/REMOVE))]
      (is (= [1 2 4 5] result))))

  (testing "Remove multiple elements from vector"
    (let [data   [1 2 3 4 5 6]
          result (p/transform-when data #(and (number? %) (even? %)) (constantly p/REMOVE))]
      (is (= [1 3 5] result))))

  (testing "Remove all elements from vector"
    (let [data   [1 2 3]
          result (p/transform-when data number? (constantly p/REMOVE))]
      (is (= [] result))))

  (testing "Vector metadata preserved after removal"
    (let [data   (with-meta [1 2 3 4 5] {:version 1})
          result (p/transform-when data #(and (number? %) (even? %)) (constantly p/REMOVE))]
      (is (= [1 3 5] result))
      (is (= {:version 1} (meta result))))))


(deftest remove-from-list-test
  (testing "Remove single element from list"
    (let [data   '(1 2 3 4 5)
          result (p/transform-when data #(= % 3) (constantly p/REMOVE))]
      (is (= '(1 2 4 5) result))
      (is (list? result))))

  (testing "Remove multiple elements from list"
    (let [data   '(1 2 3 4 5 6)
          result (p/transform-when data #(and (number? %) (even? %)) (constantly p/REMOVE))]
      (is (= '(1 3 5) result))
      (is (list? result))))

  (testing "List metadata preserved after removal"
    (let [data   (with-meta '(1 2 3 4 5) {:source :db})
          result (p/transform-when data #(and (number? %) (even? %)) (constantly p/REMOVE))]
      (is (= '(1 3 5) result))
      (is (= {:source :db} (meta result))))))


(deftest remove-from-map-test
  (testing "Remove single key-value pair from map"
    (let [data   {:a 1 :b 2 :c 3}
          result (p/transform-when data #(= % 2) (constantly p/REMOVE))]
      (is (= {:a 1 :c 3} result))))

  (testing "Remove multiple key-value pairs from map"
    (let [data   {:a 1 :b 2 :c 3 :d 4}
          result (p/transform-when data #(and (number? %) (even? %)) (constantly p/REMOVE))]
      (is (= {:a 1 :c 3} result))))

  (testing "Map metadata preserved after removal"
    (let [data   (with-meta {:a 1 :b 2 :c 3} {:type :config})
          result (p/transform-when data #(and (number? %) (even? %)) (constantly p/REMOVE))]
      (is (= {:a 1 :c 3} result))
      (is (= {:type :config} (meta result))))))


(deftest remove-from-set-test
  (testing "Remove single element from set"
    (let [data   #{1 2 3 4 5}
          result (p/transform-when data #(= % 3) (constantly p/REMOVE))]
      (is (= #{1 2 4 5} result))))

  (testing "Remove multiple elements from set"
    (let [data   #{1 2 3 4 5 6}
          result (p/transform-when data #(and (number? %) (even? %)) (constantly p/REMOVE))]
      (is (= #{1 3 5} result))))

  (testing "Set metadata preserved after removal"
    (let [data   (with-meta #{1 2 3 4 5} {:validated true})
          result (p/transform-when data #(and (number? %) (even? %)) (constantly p/REMOVE))]
      (is (= #{1 3 5} result))
      (is (= {:validated true} (meta result))))))


(deftest remove-nested-test
  (testing "Remove from nested vector"
    (let [data   {:items [1 2 3 4 5]}
          result (p/transform-when data #(and (number? %) (even? %)) (constantly p/REMOVE))]
      (is (= {:items [1 3 5]} result))))

  (testing "Remove from deeply nested structure"
    (let [data   {:level1 {:level2 {:values [1 2 3 4 5]}}}
          result (p/transform-when data #(and (number? %) (> % 3)) (constantly p/REMOVE))]
      (is (= {:level1 {:level2 {:values [1 2 3]}}} result))))

  (testing "Mixed removals across collection types"
    (let [data   {:vec  [1 2 3]
                  :list '(4 5 6)
                  :set  #{7 8 9}}
          result (p/transform-when data #(and (number? %) (zero? (mod % 3))) (constantly p/REMOVE))]
      (is (= {:vec [1 2] :list '(4 5) :set #{7 8}} result)))))


(deftest remove-conditional-test
  (testing "Conditionally remove or transform"
    (let [data   [1 2 3 4 5]
          result (p/transform-when data number?
                   (fn [n]
                     (if (even? n)
                       p/REMOVE
                       (* n 10))))]
      (is (= [10 30 50] result)))))


(deftest remove-large-scale-test
  (testing "Remove many elements from large vector"
    (let [data   (vec (range 1000))
          result (p/transform-when data #(and (number? %) (even? %)) (constantly p/REMOVE))]
      (is (= 500 (count result)))
      (is (every? odd? result))))

  (testing "Remove sparse elements from large vector"
    (let [data   (vec (range 1000))
          ;; Remove only elements divisible by 100
          result (p/transform-when data #(and (number? %) (zero? (mod % 100))) (constantly p/REMOVE))]
      (is (= 990 (count result)))
      (is (not-any? #(zero? (mod % 100)) result)))))


;; VecRemover optimization path tests
;; Tests both the direct array adoption path (≤32 elements) and transient path (>32 elements)
;; #######################################

(deftest vec-remover-adopt-path-test
  (testing "Removal resulting in ≤32 elements uses direct array adoption"
    ;; Start with 40 elements, remove 10 to get 30 (≤32)
    (let [data   (vec (range 40))
          result (p/transform-when data #(and (number? %) (< % 10)) (constantly p/REMOVE))]
      (is (= 30 (count result)))
      (is (= (vec (range 10 40)) result))))

  (testing "Removal resulting in exactly 32 elements"
    (let [data   (vec (range 40))
          result (p/transform-when data #(and (number? %) (< % 8)) (constantly p/REMOVE))]
      (is (= 32 (count result)))
      (is (= (vec (range 8 40)) result))))

  (testing "Small vector removal preserves metadata"
    (let [data   (with-meta (vec (range 20)) {:tag :small})
          result (p/transform-when data #(and (number? %) (even? %)) (constantly p/REMOVE))]
      (is (= 10 (count result)))
      (is (every? odd? result))
      (is (= {:tag :small} (meta result)))))

  (testing "Remove all but one element"
    (let [data   (vec (range 10))
          result (p/transform-when data #(and (number? %) (pos? %)) (constantly p/REMOVE))]
      (is (= [0] result)))))


(deftest vec-remover-transient-path-test
  (testing "Removal resulting in >32 elements uses transient path"
    ;; Start with 100 elements, remove 10 to get 90 (>32)
    (let [data   (vec (range 100))
          result (p/transform-when data #(and (number? %) (< % 10)) (constantly p/REMOVE))]
      (is (= 90 (count result)))
      (is (= (vec (range 10 100)) result))))

  (testing "Removal resulting in exactly 33 elements"
    (let [data   (vec (range 40))
          result (p/transform-when data #(and (number? %) (< % 7)) (constantly p/REMOVE))]
      (is (= 33 (count result)))
      (is (= (vec (range 7 40)) result))))

  (testing "Large vector removal preserves metadata"
    (let [data   (with-meta (vec (range 100)) {:tag :large})
          result (p/transform-when data #(and (number? %) (even? %)) (constantly p/REMOVE))]
      (is (= 50 (count result)))
      (is (every? odd? result))
      (is (= {:tag :large} (meta result)))))

  (testing "Remove sparse elements from large vector"
    (let [data   (vec (range 100))
          ;; Remove every 10th element, leaving 90
          result (p/transform-when data #(and (number? %) (zero? (mod % 10))) (constantly p/REMOVE))]
      (is (= 90 (count result)))
      (is (not-any? #(zero? (mod % 10)) result)))))
