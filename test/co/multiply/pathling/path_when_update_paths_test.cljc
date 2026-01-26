(ns co.multiply.pathling.path-when-update-paths-test
  "Tests for path-when and update-paths functionality."
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]]
    [co.multiply.pathling :as p]
    [co.multiply.pathling.accumulator :refer [acc-get acc-set! acc-size]])
  #?(:clj (:import [java.util ArrayList])))


;; path-when / update-paths tests
;; #######################################

(deftest path-when-basic-test
  (testing "Find and update numbers in nested structure"
    (let [data [1 {:a 2} {:b #{3 {:c 4}}}]
          {:keys [matches nav]} (p/path-when data number?)]
      ;; Finds all numbers
      (is (= [1 2 3 4] matches))
      ;; Can update all found numbers
      (is (= [2 {:a 3} {:b #{4 {:c 5}}}]
             (p/update-paths data nav inc))))))


(deftest path-when-empty-test
  (testing "No matches returns nil and unchanged data"
    (let [data   [:a :b :c]
          result (p/path-when data number?)]
      (is (nil? result))
      ;; Update-paths returns data unchanged when nav is nil
      (is (= [:a :b :c]
             (p/update-paths data (:nav result) inc))))))


(deftest path-when-scalar-test
  (testing "Top-level scalar match"
    (let [{:keys [matches nav]} (p/path-when 42 number?)]
      (is (= [42] matches))
      ;; Can update the scalar itself
      (is (= 43 (p/update-paths 42 nav inc)))))

  (testing "Non-matching scalar"
    (let [result (p/path-when :keyword number?)]
      (is (nil? result))
      ;; Returns unchanged when nav is nil
      (is (= :keyword (p/update-paths :keyword (:nav result) inc)))))

  (testing "Nil value"
    (let [{:keys [matches nav]} (p/path-when nil nil?)]
      (is (= [nil] matches))
      (is (some? nav))                                      ; Nav exists
      ;; Can transform nil
      (is (= :replaced (p/update-paths nil nav (constantly :replaced)))))

    (let [result (p/path-when nil number?)]
      (is (nil? result)))))


(deftest path-when-nested-match-test
  (testing "Nested matches - both container and contents (depth-first order)"
    (let [data [{:a {:b 1}}]
          {:keys [matches nav]} (p/path-when data map?)]
      ;; Depth-first: deeper matches come before shallower ones
      (is (= [{:b 1} {:a {:b 1}}] matches))
      ;; Both maps are updated
      (is (= [{:a {:b 1, :updated true}, :updated true}]
             (p/update-paths data nav #(assoc % :updated true)))))))


(deftest path-when-map-replacement-test
  (testing "Map-based replacement strategy"
    (let [data         {:x 1 :y 2 :z 3}
          {:keys [matches nav]} (p/path-when data number?)
          replacements (zipmap matches [:one :two :three])]
      (is (= {:x :one :y :two :z :three}
             (p/update-paths data nav #(get replacements %)))))))


(deftest path-when-large-scale-test
  (testing "Stack-safe with 10,000 matches"
    (let [data   (vec (range 10000))
          {:keys [matches nav]} (p/path-when data number?)
          result (p/update-paths data nav inc)]
      (is (= 10000 (count matches)))
      (is (= (vec (range 1 10001)) result))
      (is (= 0 (first data)))
      (is (= 1 (first result)))
      (is (= 9999 (last data)))
      (is (= 10000 (last result))))))


(deftest path-when-sequential-test
  (testing "Lists - type is preserved"
    (let [data   '(1 2 3)
          {:keys [matches nav]} (p/path-when data number?)
          result (p/update-paths data nav inc)]
      (is (= [1 2 3] matches))
      (is (= '(2 3 4) result))
      (is (list? result))))

  (testing "Nested lists and vectors - each type preserved"
    (let [data   '(1 [2 3] {:a 4})
          {:keys [matches nav]} (p/path-when data number?)
          result (p/update-paths data nav inc)]
      (is (= [1 2 3 4] matches))
      (is (= '(2 [3 4] {:a 5}) result))
      (is (list? result))
      (is (vector? (second result)))))

  (testing "Lazy sequences - realized and converted to list"
    (let [data   (map inc [0 1 2])                          ; Lazy seq
          {:keys [matches nav]} (p/path-when data number?)
          result (p/update-paths data nav inc)]
      (is (= [1 2 3] matches))
      (is (= '(2 3 4) result))
      (is (seq? result)))))


(deftest path-when-falsy-elements-test
  (testing "Vectors with nil elements"
    (let [data   [1 nil 3]
          {:keys [matches nav]} (p/path-when data number?)
          result (p/update-paths data nav inc)]
      (is (= [1 3] matches))
      (is (= [2 nil 4] result))))

  (testing "Lists with false elements"
    (let [data   '(true false nil 42)
          {:keys [matches nav]} (p/path-when data number?)
          result (p/update-paths data nav inc)]
      (is (= [42] matches))
      (is (= '(true false nil 43) result))))

  (testing "Maps with nil values"
    (let [data   {:a 1 :b nil :c 3}
          {:keys [matches nav]} (p/path-when data number?)
          result (p/update-paths data nav inc)]
      (is (= [1 3] matches))
      (is (= {:a 2 :b nil :c 4} result))))

  (testing "Sets with nil and false"
    (let [data   #{1 nil false 3}
          {:keys [matches nav]} (p/path-when data number?)
          result (p/update-paths data nav inc)]
      (is (= 2 (count matches)))
      (is (contains? (set matches) 1))
      (is (contains? (set matches) 3))
      (is (= #{2 nil false 4} result)))))


(deftest path-when-empty-collections-test
  (testing "Empty collections as values"
    (let [data   {:a [] :b {} :c #{}}
          result (p/path-when data number?)]
      (is (nil? result))))

  (testing "Nested empty collections"
    (let [data   [1 [] {:a {}} #{2}]
          {:keys [matches nav]} (p/path-when data number?)
          result (p/update-paths data nav inc)]
      (is (= [1 2] matches))
      (is (= [2 [] {:a {}} #{3}] result))))

  (testing "Empty collection is preserved through update"
    (let [data   {:numbers [1 2] :empty []}
          {:keys [matches nav]} (p/path-when data number?)
          result (p/update-paths data nav inc)]
      (is (= [1 2] matches))
      (is (= {:numbers [2 3] :empty []} result)))))


(deftest path-when-set-test
  (testing "Sets as primary scan target"
    (let [data   #{1 2 3}
          {:keys [matches nav]} (p/path-when data number?)
          result (p/update-paths data nav inc)]
      (is (= 3 (count matches)))
      (is (= #{2 3 4} result))))

  (testing "Nested sets"
    (let [data   #{1 #{2 3} 4}
          {:keys [matches nav]} (p/path-when data number?)
          result (p/update-paths data nav inc)]
      (is (= 4 (count matches)))
      (is (contains? result 2))
      (is (contains? result 5))
      (is (contains? result #{3 4}))))

  (testing "Sets with mixed types"
    (let [data   #{:a 1 "string" 2 :b}
          {:keys [matches nav]} (p/path-when data number?)
          result (p/update-paths data nav inc)]
      (is (= 2 (count matches)))
      (is (contains? (set matches) 1))
      (is (contains? (set matches) 2))
      (is (= #{:a 2 "string" 3 :b} result)))))


;; metadata preservation tests
;; #######################################

(deftest metadata-preservation-test
  (testing "Map metadata is preserved"
    (let [data   (with-meta {:a 1 :b 2} {:type :special})
          {:keys [nav]} (p/path-when data number?)
          result (p/update-paths data nav inc)]
      (is (= {:a 2 :b 3} result))
      (is (= {:type :special} (meta result)))))

  (testing "Vector metadata is preserved"
    (let [data   (with-meta [1 2 3] {:version 1})
          {:keys [nav]} (p/path-when data number?)
          result (p/update-paths data nav inc)]
      (is (= [2 3 4] result))
      (is (= {:version 1} (meta result)))))

  (testing "List metadata is preserved"
    (let [data   (with-meta '(1 2 3) {:source :db})
          {:keys [nav]} (p/path-when data number?)
          result (p/update-paths data nav inc)]
      (is (= '(2 3 4) result))
      (is (= {:source :db} (meta result)))))

  (testing "Set metadata is preserved"
    (let [data   (with-meta #{1 2 3} {:validated true})
          {:keys [nav]} (p/path-when data number?)
          result (p/update-paths data nav inc)]
      (is (= #{2 3 4} result))
      (is (= {:validated true} (meta result)))))

  (testing "Nested metadata is preserved"
    (let [data   {:outer (with-meta [1 2 3] {:inner true})}
          {:keys [nav]} (p/path-when data number?)
          result (p/update-paths data nav inc)]
      (is (= {:outer [2 3 4]} result))
      (is (= {:inner true} (meta (:outer result))))))

  #?(:clj (testing "PersistentStructMap is converted and metadata preserved"
            (let [s      (create-struct :a :b)
                  data   (with-meta (struct-map s :a 1 :b 2) {:legacy true})
                  {:keys [nav]} (p/path-when data number?)
                  result (p/update-paths data nav inc)]
              ;; Should work without throwing ClassCastException
              (is (= {:a 2 :b 3} result))
              ;; Metadata should be preserved through conversion
              (is (= {:legacy true} (meta result)))))))


(deftest path-when-nil-in-middle-test
  (testing "Sequential with nil in middle position"
    (let [data   '(1 nil 3)
          {:keys [matches nav]} (p/path-when data number?)
          result (p/update-paths data nav inc)]
      (is (= [1 3] matches))
      (is (= '(2 nil 4) result))))

  (testing "Set with nil in any position"
    (let [data   #{1 nil 3}
          {:keys [matches nav]} (p/path-when data number?)
          result (p/update-paths data nav inc)]
      (is (= 2 (count matches)))
      (is (every? #{1 3} matches))
      (is (= #{2 nil 4} result))))

  (testing "Map with nil as first value"
    (let [data   {:a nil :b 2 :c 3}
          {:keys [matches nav]} (p/path-when data number?)
          result (p/update-paths data nav inc)]
      (is (= [2 3] matches))
      (is (= {:a nil :b 3 :c 4} result)))))


;; path-when with include-keys tests
;; #######################################

(deftest path-when-include-keys-basic-test
  (testing "Find and collect keys"
    (let [data   {:a 1 :b 2 :c 3}
          {:keys [matches nav]} (p/path-when data keyword? {:include-keys true})]
      (is (= 3 (count matches)))
      (is (every? keyword? matches))
      (is (every? #{:a :b :c} matches))
      ;; Nav can update the keys
      (is (some? nav))))

  (testing "Find values only (default behavior)"
    (let [data   {:a 1 :b 2 :c 3}
          {:keys [matches]} (p/path-when data number?)]
      (is (= [1 2 3] matches))))

  (testing "Find both keys and values with same predicate"
    (let [data   {1 :one 2 :two 3 :three}
          {:keys [matches]} (p/path-when data #(or (number? %) (keyword? %)) {:include-keys true})]
      (is (= 6 (count matches)))
      (is (= 3 (count (filter number? matches))))
      (is (= 3 (count (filter keyword? matches)))))))


(deftest path-when-include-keys-update-test
  (testing "Transform map keys"
    (let [data   {:a 1 :b 2 :c 3}
          {:keys [nav]} (p/path-when data keyword? {:include-keys true})
          result (p/update-paths data nav name)]
      (is (= {"a" 1 "b" 2 "c" 3} result))))

  (testing "Transform only specific keys"
    (let [data   {:a 1 :b 2 :c 3}
          {:keys [nav]} (p/path-when data #(= % :b) {:include-keys true})
          result (p/update-paths data nav (constantly :updated))]
      (is (= {:a 1 :updated 2 :c 3} result))))

  (testing "Remove keys using REMOVE"
    (let [data   {:a 1 :b 2 :c 3}
          {:keys [nav]} (p/path-when data #(= % :b) {:include-keys true})
          result (p/update-paths data nav (constantly p/REMOVE))]
      (is (= {:a 1 :c 3} result)))))


(deftest path-when-include-keys-nested-test
  (testing "Nested maps with keys"
    (let [data   {:outer {:inner 1}}
          {:keys [matches nav]} (p/path-when data keyword? {:include-keys true})]
      (is (= 2 (count matches)))
      (is (every? keyword? matches))
      (is (every? #{:outer :inner} matches))
      ;; Transform nested keys
      (let [result (p/update-paths data nav name)]
        (is (= {"outer" {"inner" 1}} result)))))

  (testing "Deep nesting key transformation"
    (let [data   {:a {:b {:c {:d 1}}}}
          {:keys [nav]} (p/path-when data keyword? {:include-keys true})
          result (p/update-paths data nav name)]
      (is (= {"a" {"b" {"c" {"d" 1}}}} result))))

  (testing "Nested maps in vectors"
    (let [data   [{:a 1} {:b 2} {:c 3}]
          {:keys [matches nav]} (p/path-when data keyword? {:include-keys true})]
      (is (= 3 (count matches)))
      (is (every? #{:a :b :c} matches))
      (let [result (p/update-paths data nav name)]
        (is (= [{"a" 1} {"b" 2} {"c" 3}] result))))))


(deftest path-when-include-keys-large-scale-test
  (testing "Stack-safe with 1000 keys"
    (let [data   (into {} (map (fn [n] [(keyword (str "key" n)) n])) (range 1000))
          {:keys [matches nav]} (p/path-when data keyword? {:include-keys true})]
      (is (= 1000 (count matches)))
      (is (every? keyword? matches))
      ;; Can update all keys
      (let [result (p/update-paths data nav name)]
        (is (= 1000 (count result)))
        (is (every? string? (keys result)))))))


;; raw-matches and list-based replacement tests
;; #######################################

(defn- accumulator?
  "Check if x is an accumulator (ArrayList on CLJ, array on CLJS)."
  [x]
  #?(:clj  (instance? ArrayList x)
     :cljs (array? x)))


(deftest raw-matches-test
  (testing ":raw-matches returns accumulator instead of vector"
    (let [data   [1 {:a 2} {:b #{3 {:c 4}}}]
          {:keys [matches nav]} (p/path-when data number? {:raw-matches true})]
      (is (accumulator? matches))
      (is (= [1 2 3 4] (vec matches)))
      ;; Can still use with function-based update
      (is (= [2 {:a 3} {:b #{4 {:c 5}}}]
             (p/update-paths data nav inc)))))

  (testing "List-based replacement with accumulator"
    (let [data   [1 {:a 2} {:b #{3 {:c 4}}}]
          {:keys [matches nav]} (p/path-when data number? {:raw-matches true})]
      ;; Mutate accumulator in place with replacement values
      (dotimes [i (acc-size matches)]
        (acc-set! matches i (* 10 (acc-get matches i))))
      ;; Pass the accumulator directly to update-paths
      (is (= [10 {:a 20} {:b #{30 {:c 40}}}]
             (p/update-paths data nav matches)))))

  (testing "Zero-allocation pattern - same accumulator reused"
    (let [data      {:x 1 :y 2 :z 3}
          {:keys [matches nav]} (p/path-when data number? {:raw-matches true})
          original  matches]                                ; Same object reference
      ;; Mutate in place
      (dotimes [i (acc-size matches)]
        (acc-set! matches i (str "value-" (acc-get matches i))))
      ;; Verify same accumulator instance
      (is (identical? original matches))
      ;; Use for replacement
      (is (= {:x "value-1" :y "value-2" :z "value-3"}
             (p/update-paths data nav matches)))))

  (testing "List replacement with REMOVE"
    (let [data   [1 2 3 4 5]
          {:keys [matches nav]} (p/path-when data number? {:raw-matches true})]
      ;; Replace evens with REMOVE
      (dotimes [i (acc-size matches)]
        (let [v (acc-get matches i)]
          (acc-set! matches i (if (even? v) p/REMOVE (* 10 v)))))
      (is (= [10 30 50]
             (p/update-paths data nav matches)))))

  (testing "List replacement preserves depth-first order"
    (let [data   [{:a 1} {:b {:c 2}} 3]
          {:keys [matches nav]} (p/path-when data number? {:raw-matches true})]
      ;; Verify order is depth-first
      (is (= [1 2 3] (vec matches)))
      ;; Replace with labeled values
      (dotimes [i (acc-size matches)]
        (acc-set! matches i (keyword (str "pos-" i))))
      (is (= [{:a :pos-0} {:b {:c :pos-1}} :pos-2]
             (p/update-paths data nav matches)))))

  (testing "Empty result with raw-matches"
    (let [result (p/path-when [:a :b :c] number? {:raw-matches true})]
      (is (nil? result))))

  (testing "Combining raw-matches with include-keys"
    (let [data   {:a 1 :b 2}
          {:keys [matches nav]} (p/path-when data keyword? {:raw-matches true
                                                            :include-keys true})]
      (is (accumulator? matches))
      (is (= 2 (acc-size matches)))
      ;; Mutate and apply
      (dotimes [i (acc-size matches)]
        (acc-set! matches i (-> (acc-get matches i) name str/upper-case)))
      (is (= {"A" 1 "B" 2}
             (p/update-paths data nav matches)))))

  (testing "Vector of replacements"
    (let [data   [1 {:a 2} 3]
          {:keys [matches nav]} (p/path-when data number?)
          replacements (mapv #(* 10 %) matches)]
      ;; matches is a vector (default behavior)
      (is (vector? matches))
      ;; Can pass a vector directly as replacements
      (is (= [10 {:a 20} 30]
             (p/update-paths data nav replacements))))))
