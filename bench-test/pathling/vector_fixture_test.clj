(ns pathling.vector-fixture-test
  (:require [clojure.edn :as edn]
    [clojure.java.io :as io]
    [clojure.test :refer [deftest is]]
    [co.multiply.pathling :as p]
    [pathling.benchmark :as bench]
    [pathling.benchmark-suite :as suite]
    [pathling.benchmark-suite-test :as suite-test]
    [pathling.benchmark-test :as support]
    [pathling.fixtures :as fixtures])
  (:import [clojure.lang PersistentVector]))


(deftest sparse-vector-fixture-is-repeatable-and-excludes-scanning-from-update
  (let [spec (first fixtures/vector-update-specs)
        {:keys [data matches expected description] :as fixture} (fixtures/make-fixture spec 20260914)
        before (fixtures/describe-data data)
        positions (keep-indexed #(when (fixtures/groundable? %2) %1) data)
        position (first positions)]
    (is (instance? PersistentVector data))
    (is (= 10000 (count data)))
    (is (= 1 (count matches) (count positions)))
    (is (= description (:description (fixtures/make-fixture spec 20260914))))
    (is (= expected (assoc data position (fixtures/resolve-value (nth data position)))))
    (doseq [{:keys [id f validate]} (fixtures/prepare-cases fixture fixtures/primary-operations)]
      (dotimes [_ 3] (is (nil? (validate (f)))))
      (when (= :update-array-list (second id))
        (with-redefs [p/path-when (fn [& _] (throw (ex-info "Scan entered update-only work" {})))]
          (is (nil? (validate (f))))))
      (when-not (= :path-raw (second id))
        (is (thrown? Exception (validate (assoc expected position :wrong))))))
    (is (= before (fixtures/describe-data data)))))


(deftest sparse-vector-suite-is-opt-in-and-saved
  (is (= 30 (count (bench/select-cases (bench/options {})))))
  (is (= 3 (count (bench/select-cases (bench/options {:suite :vector-updates})))))
  (is (= (* (count fixtures/fixture-specs) (count fixtures/all-operations))
         (count (bench/select-cases (bench/options {:suite :all})))))
  (doseq [opts [{:fixtures [:vector-wide-one]}
                {:suite :vector-updates :fixtures [:wide-many]}
                {:suite :vector-updates :operations [:update-function]}]]
    (is (thrown? Exception (bench/select-cases (bench/options opts)))))
  (let [output (support/temporary-output)
        counter (atom 0)]
    (with-redefs [suite/run-worker! (suite-test/fake-worker counter identity)]
      (is (= :complete (:status (suite/run! {:suite :vector-updates :profile :smoke
                                            :forks 2 :operations [:update-array-list] :output output})))))
    (let [batch (edn/read-string (slurp (io/file output "batch.edn")))]
      (is (= 2 @counter (count (:runs batch))))
      (is (= :passed (:validation batch)))
      (is (= #{[:vector-wide-one :update-array-list]} (set (map :id (:runs batch))))))))
