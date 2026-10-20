(ns pathling.staged-fixture-test
  (:require [clojure.test :refer [deftest is testing]]
    [co.multiply.pathling :as p]
    [pathling.benchmark :as bench]
    [pathling.fixtures :as fixtures]
    [pathling.staged-fixtures :as staged]))


(deftest staged-operations-preserve-contracts-and-inputs
  (doseq [spec fixtures/fixture-specs]
    (let [{:keys [data matches] :as fixture} (fixtures/make-fixture spec 20260914)
          before (fixtures/describe-data data)]
      (doseq [{:keys [id f validate]} (staged/prepare-cases fixture staged/operations)]
        (testing (str id)
          (dotimes [_ 3] (is (nil? (validate (f)))))
          (when (#{:find :tree-seq-find :collect} (second id))
            (let [invalid (if (and (empty? matches) (= :collect (second id)))
                            {:matches nil}
                            [])]
              (is (thrown? Exception (validate invalid)))))))
      (is (= before (fixtures/describe-data data))))))


(deftest value-only-tree-seq-skips-keys-and-preserves-leaf-identity
  (let [key-leaf (fixtures/->TaskValue 0)
        value (fixtures/->TaskValue 1)
        data (array-map [key-leaf] [nil value] :nested (list value))]
    (is (= [value value] (staged/tree-seq-find data)))
    (is (every? #(identical? value %) (staged/tree-seq-find data)))
    (is (nil? (staged/tree-seq-find {key-leaf :unmatched})))
    (doseq [data [nil false [] {} '() #{}]]
      (is (nil? (staged/tree-seq-find data))))))


(deftest replacements-belong-to-occurrences-and-cursors-belong-to-calls
  (let [a (fixtures/->TaskValue 7)
        equal-a (fixtures/->TaskValue 7)
        data (with-meta [a {:x equal-a :nested (list a)}] {:source :test})
        {:keys [matches nav]} (p/path-when data fixtures/groundable?)
        replacements (staged/batch-replacements matches)
        expected [[3 0 -8] {:x [3 1 -8] :nested (list [3 2 -8])}]
        calls [#(p/update-paths data nav replacements)
               #(staged/postwalk-replace data replacements)]]
    (is (not (identical? a equal-a)))
    (is (= [[3 0 -8] [3 1 -8] [3 2 -8]] replacements))
    (doseq [f calls]
      (dotimes [_ 3]
        (let [result (f)]
          (is (= expected result))
          (is (= (meta data) (meta result)))))
      (let [jobs (mapv (fn [_] (future (f))) (range 4))]
        (is (every? #(= expected %) (mapv deref jobs)))))
    (is (= [a {:x equal-a :nested (list a)}] data))
    (is (every? true? (map identical? [a equal-a a] matches))))
  (let [a (fixtures/->TaskValue 0)
        replacement (fixtures/->TaskValue 99)
        data [a a a]
        replacements [nil false replacement]
        nav (:nav (p/path-when data fixtures/groundable?))]
    ;; Nil/false are replacements, not exhaustion markers. New matching values
    ;; must not be visited again during the same replacement pass.
    (doseq [result [(p/update-paths data nav replacements)
                    (staged/postwalk-replace data replacements)]]
      (is (= replacements result))
      (is (identical? replacement (nth result 2))))))


(deftest empty-collection-allows-skipping-replacement
  (doseq [data [nil false [] {:a [1 2 3]}]]
    (is (nil? (staged/tree-seq-find data)))
    (is (identical? data (staged/postwalk-replace data [])))
    (is (identical? data (p/update-paths data nil [])))))


(deftest batch-processing-is-outside-the-timed-functions
  (let [spec (first (filter #(= :small-many (:id %)) fixtures/fixture-specs))
        cases (staged/prepare-cases (fixtures/make-fixture spec 20260914) staged/operations)]
    (with-redefs [staged/batch-replacements (fn [_] (throw (ex-info "Batch ran inside timing" {})))]
      (doseq [{:keys [f validate]} cases]
        (is (nil? (validate (f))))))))


(deftest staged-suite-is-opt-in-and-keeps-existing-selections
  (is (= 50 (count (bench/select-cases (bench/options {:suite :staged-comparison})))))
  (is (= 30 (count (bench/select-cases (bench/options {})))))
  (is (= 40 (count (bench/select-cases (bench/options {:suite :comparison})))))
  (is (thrown? Exception (bench/select-cases (bench/options {:suite :staged-comparison :operations [:transform]})))))
