(ns pathling.sequence-fixture-test
  (:require [clojure.edn :as edn]
    [clojure.java.io :as io]
    [clojure.test :refer [deftest is testing]]
    [co.multiply.pathling :as p]
    [pathling.benchmark :as bench]
    [pathling.benchmark-suite :as suite]
    [pathling.benchmark-suite-test :as suite-test]
    [pathling.benchmark-test :as support]
    [pathling.fixtures :as fixtures]
    [pathling.sequence-fixtures :as sequences]))


(deftest sequence-fixtures-are-deterministic-and-repeatable
  (doseq [spec sequences/fixture-specs]
    (testing (str (:id spec))
      (let [fixture (sequences/make-fixture spec 20260914)
            {:keys [data description]} fixture
            before [(class data) (meta data) (vec data)]
            {:keys [f validate eligible-matches]} (first (sequences/prepare-cases fixture sequences/operations))]
        (is (= description (:description (sequences/make-fixture spec 20260914))))
        (is (= (:length spec) (count data)))
        (is (= 1 eligible-matches))
        (with-redefs [p/path-when (fn [& _] (throw (ex-info "Scanning entered timed work" {})))]
          (dotimes [_ 3] (is (nil? (validate (f))))))
        (is (= before [(class data) (meta data) (vec data)]))
        (is (thrown? Exception (validate (with-meta (f) {:wrong true}))))))))


(deftest sequence-fixtures-check-values-types-and-metadata
  (doseq [kind [:list :lazy-seq :queue]]
    (let [spec {:id :test-sequence :collection-kind kind :length 3 :match-target :child :match-count 1}
          fixture (sequences/make-fixture spec 42)
          {:keys [f validate]} (first (sequences/prepare-cases fixture sequences/operations))
          result (f)]
      (is (= '(0 :updated-child 2) result))
      (is (list? result))
      (is (= {:source :sequence-update} (meta result)))
      (is (thrown? Exception (validate (with-meta [0 :updated-child 2] (meta result)))))
      (is (thrown? Exception (validate (with-meta '(0 :wrong 2) (meta result)))))))
  (let [descriptions (for [kind [:list :lazy-seq :queue]]
                       (:description (sequences/make-fixture
                                       {:id :test-sequence :collection-kind kind :length 3
                                        :match-target :collection :match-count 1} 42)))]
    (is (= 3 (count (set (map :sha256 descriptions)))))))


(deftest sequence-suite-is-opt-in-and-saved-through-the-normal-runner
  (is (= 30 (count (bench/select-cases (bench/options {})))))
  (is (= (* (count fixtures/fixture-specs) (count fixtures/all-operations))
         (count (bench/select-cases (bench/options {:suite :all})))))
  (is (= 12 (count (bench/select-cases (bench/options {:suite :sequence-updates})))))
  (doseq [opts [{:fixtures [:seq-list-collection-16]}
                {:suite :sequence-updates :fixtures [:small-one]}
                {:suite :sequence-updates :operations [:path-raw]}]]
    (is (thrown? Exception (bench/select-cases (bench/options opts)))))
  (let [output (support/temporary-output)
        counter (atom 0)
        opts {:suite :sequence-updates :profile :smoke :forks 2
              :fixtures [:seq-list-collection-16 :seq-list-child-1024] :output output}]
    (with-redefs [suite/run-worker! (suite-test/fake-worker counter identity)]
      (is (= :complete (:status (suite/run! opts)))))
    (let [batch (edn/read-string (slurp (io/file output "batch.edn")))]
      (is (= 4 @counter (count (:runs batch))))
      (is (= :passed (:validation batch)))
      (is (= #{[:seq-list-collection-16 :update-function] [:seq-list-child-1024 :update-function]}
             (set (map :id (:runs batch)))))
      (is (= #{:collection :child} (set (map #(get-in % [:fixture :match-target]) (:runs batch))))))))
