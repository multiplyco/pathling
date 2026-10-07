(ns pathling.hash-map-fixture-test
  (:require [clojure.edn :as edn]
    [clojure.java.io :as io]
    [clojure.test :refer [deftest is testing]]
    [pathling.benchmark :as bench]
    [pathling.benchmark-suite :as suite]
    [pathling.benchmark-suite-test :as suite-test]
    [pathling.benchmark-test :as support]
    [pathling.fixtures :as fixtures])
  (:import [clojure.lang PersistentHashMap]))


(deftest dense-hash-map-fixtures-have-matching-leaves-and-repeatable-operations
  (doseq [spec fixtures/hash-map-traversal-specs]
    (testing (str (:id spec))
      (let [{:keys [data matches description] :as fixture} (fixtures/make-fixture spec 20260914)
            nodes (tree-seq map? vals data)
            maps (filter map? nodes)
            leaves (remove map? nodes)
            before (fixtures/describe-data data)]
        (is (= description (:description (fixtures/make-fixture spec 20260914))))
        (is (= (:match-count spec) (count matches) (count leaves)))
        (is (every? fixtures/groundable? leaves))
        (is (every? #(and (instance? PersistentHashMap %) (= 32 (count %))) maps))
        (doseq [{:keys [f validate eligible-matches]} (fixtures/prepare-cases fixture fixtures/primary-operations)]
          (is (= (:match-count spec) eligible-matches))
          (dotimes [_ 3] (is (nil? (validate (f))))))
        (is (= before (fixtures/describe-data data)))))))


(deftest dense-hash-map-suite-is-opt-in-and-saved
  (is (= 30 (count (bench/select-cases (bench/options {})))))
  (is (= (* (count fixtures/fixture-specs) (count fixtures/all-operations))
         (count (bench/select-cases (bench/options {:suite :all})))))
  (is (= 54 (count (bench/select-cases (bench/options {:suite :sparse-scaling})))))
  (is (= 6 (count (bench/select-cases (bench/options {:suite :hash-map-traversal})))))
  (doseq [opts [{:fixtures [:hash-map-dense-flat]}
                {:suite :hash-map-traversal :fixtures [:small-one]}
                {:suite :hash-map-traversal :operations [:find]}]]
    (is (thrown? Exception (bench/select-cases (bench/options opts)))))
  (let [output (support/temporary-output)
        counter (atom 0)
        opts {:suite :hash-map-traversal :profile :smoke :forks 1
              :operations [:path-raw] :output output}]
    (with-redefs [suite/run-worker! (suite-test/fake-worker counter identity)]
      (is (= :complete (:status (suite/run! opts)))))
    (let [batch (edn/read-string (slurp (io/file output "batch.edn")))]
      (is (= 2 @counter (count (:runs batch))))
      (is (= :passed (:validation batch)))
      (is (= #{[:hash-map-dense-flat :path-raw] [:hash-map-dense-nested :path-raw]}
             (set (map :id (:runs batch))))))))
