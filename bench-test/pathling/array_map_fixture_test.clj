(ns pathling.array-map-fixture-test
  (:require [clojure.edn :as edn]
    [clojure.java.io :as io]
    [clojure.test :refer [deftest is testing]]
    [co.multiply.pathling :as p]
    [pathling.array-map-fixtures :as arrays]
    [pathling.benchmark :as bench]
    [pathling.benchmark-suite :as suite]
    [pathling.benchmark-suite-test :as suite-test]
    [pathling.benchmark-test :as support]
    [pathling.fixtures :as fixtures])
  (:import [clojure.lang PersistentArrayMap PersistentHashMap]))


(deftest array-map-fixtures-check-runtime-types-and-independent-results
  (doseq [{:keys [width key-kind density] :as spec} arrays/fixture-specs]
    (testing (str (:id spec))
      (let [spec (assoc spec :block-count 4)
            {:keys [data matches expected description] :as fixture} (arrays/make-fixture spec 20260914)
            before (fixtures/describe-data data)
            limit (if (and (= :keywords key-kind) (>= (:minor *clojure-version*) 13)) 64 8)
            expected-class (if (or (= :hash key-kind) (> width limit)) PersistentHashMap PersistentArrayMap)]
        (is (= description (:description (arrays/make-fixture spec 20260914))))
        (is (= {(.getName expected-class) 4} (:map-classes description)))
        (is (every? #(= expected-class (class %)) data))
        (is (= (case density :none 0 :one 1 :all (* 4 width)) (count matches) (:match-count description)))
        (when (= :one density)
          (is (= (- (* 4 width)) (fixtures/resolve-value (first matches)))))
        (doseq [{:keys [id f validate eligible-matches]} (arrays/prepare-cases fixture arrays/operations)]
          (is (= (count matches) eligible-matches))
          (dotimes [_ 2] (is (nil? (validate (f)))))
          (if (= :path-raw (second id))
            (when (seq matches) (is (thrown? Exception (validate (assoc (f) :nav nil)))))
            (do
              (is (thrown? Exception (validate (with-meta expected {:wrong true}))))
              (is (thrown? Exception (validate (assoc expected 0 {}))))))
          (when (= :update-array-list (second id))
            (with-redefs [p/path-when (fn [& _] (throw (ex-info "Scan entered update-only work" {})))]
              (is (nil? (validate (f)))))))
        (is (= before (fixtures/describe-data data)))))))


(deftest array-map-suite-is-opt-in-and-saved
  (is (= 30 (count (bench/select-cases (bench/options {})))))
  (is (= (* 3 (count arrays/fixture-specs))
         (count (bench/select-cases (bench/options {:suite :array-map-traversal})))))
  (doseq [opts [{:fixtures [:am-keywords-64-one]}
                {:suite :array-map-traversal :operations [:find]}]]
    (is (thrown? Exception (bench/select-cases (bench/options opts)))))
  (let [output (support/temporary-output) counter (atom 0)]
    (with-redefs [suite/run-worker! (suite-test/fake-worker counter identity)]
      (is (= :complete (:status (suite/run! {:suite :array-map-traversal :profile :smoke
                                            :fixtures [:am-keywords-64-one] :operations [:path-raw]
                                            :forks 2 :output output})))))
    (let [batch (edn/read-string (slurp (io/file output "batch.edn")))]
      (is (= 2 @counter (count (:runs batch))))
      (is (= :passed (:validation batch)))
      (is (= #{[:am-keywords-64-one :path-raw]} (set (map :id (:runs batch))))))))
