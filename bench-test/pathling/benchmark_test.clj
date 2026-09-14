(ns pathling.benchmark-test
  (:require [clojure.edn :as edn]
    [clojure.java.io :as io]
    [clojure.test :refer [deftest is testing]]
    [criterium.core :as crit]
    [pathling.benchmark :as bench]
    [pathling.fixtures :as fixtures])
  (:import [java.nio.file Files FileAlreadyExistsException]
    [java.nio.file.attribute FileAttribute]))


(deftest deterministic-fixtures
  (doseq [spec fixtures/fixture-specs]
    (testing (name (:id spec))
      (let [a (fixtures/make-fixture spec 42)
            b (fixtures/make-fixture spec 42)]
        (is (= (:description a) (:description b)))
        (is (= (fixtures/describe-data (:data a)) (fixtures/describe-data (:data b))))
        (is (= (mapv fixtures/resolve-value (:matches a))
               (mapv fixtures/resolve-value (:matches b))))
        (is (= (:match-count spec) (count (:matches a))))))))


(deftest operations-are-correct-and-repeatable
  (doseq [spec fixtures/fixture-specs]
    (let [fixture (fixtures/make-fixture spec 20260914)
          before (fixtures/describe-data (:data fixture))]
      (doseq [{:keys [id f validate]}
              (fixtures/prepare-cases fixture fixtures/all-operations)]
        (testing (str id)
          ;; In particular, repeated raw roundtrips must not reuse already
          ;; replaced accumulators, and update-only cases must not exhaust them.
          (dotimes [_ 3] (is (nil? (validate (f)))))))
      (is (= before (fixtures/describe-data (:data fixture)))))))


(deftest independent-reference-order
  (let [a (fixtures/->TaskValue 1)
        b (fixtures/->TaskValue 2)
        data (array-map :a [a nil] :b (list b))]
    (is (= [a b] (:matches (fixtures/reference-matches data fixtures/groundable? false))))
    (is (= [a :a b :b]
           (:matches (fixtures/reference-matches
                       data #(or (fixtures/groundable? %) (keyword? %)) true))))))


(deftest selection-errors-are-not-silent
  (is (= 30 (count (bench/select-cases (bench/options {})))))
  (is (thrown? Exception (bench/options {:profile :unknown}))))


(deftest invalid-filters
  (doseq [opts [{:fixtures []} {:operations [:typo]} {:fixtures [:typo]}
                {:suite :primary :operations [:transform]} {:seed 1.2}
                {:allocation-samples 0} {:profiel :quick}]]
    (is (thrown? Exception (bench/select-cases (bench/options opts))))))


(deftest ordinary-edn-results
  (let [result (bench/edn-result {:mean [1.0 [0.9 1.1]] :samples '(1 2 3)
                                  :outliers (crit/->OutlierCount 0 1 2 3)
                                  :results [(Object.)]})]
    (is (= result (edn/read-string (pr-str result))))
    (is (not (contains? result :results)))
    (is (= {:low-severe 0 :low-mild 1 :high-mild 2 :high-severe 3} (:outliers result)))))


(defn temporary-output
  []
  (str (.toFile (Files/createTempDirectory "pathling-bench-test-" (make-array FileAttribute 0)))
    "/run"))


(defn fake-timing
  [f _]
  {:mean [1e-6 [0.9e-6 1.1e-6]] :execution-count 2 :sample-count 6
   :samples [2000 2000 2000 2000 2000 2000] :results [(f)]})


(deftest saved-runs-and-failure-checkpoints
  (with-redefs [bench/measure-allocation (fn [& _] {:status :unsupported})]
    (testing "Successful output is readable EDN and cannot be overwritten"
      (let [output (temporary-output)
            opts {:profile :smoke :fixtures [:small-one] :operations [:path-raw]
                  :output output}]
        (with-redefs [crit/benchmark* fake-timing]
          (is (= :complete (:status (bench/run! opts))))
          (let [saved (edn/read-string (slurp (io/file output "run.edn")))]
            (is (= :complete (:status saved)))
            (is (= 1 (count (:results saved))))
            (is (= :passed (:validation saved)))
            (is (seq (get-in saved [:context :source-sha256]))))
          (is (.isFile (io/file output "summary.md")))
          (is (thrown? FileAlreadyExistsException (bench/run! opts))))))
    (testing "An interrupted measurement preserves earlier completed cases"
      (let [output (temporary-output)
            calls (atom 0)]
        (with-redefs [crit/benchmark* (fn [f settings]
                                        (if (= 1 (swap! calls inc))
                                          (fake-timing f settings)
                                          (throw (ex-info "Measurement failed" {}))))]
          (is (thrown? Exception
                (bench/run! {:profile :smoke :fixtures [:small-one]
                             :operations [:path-raw :update-array-list]
                             :output output})))
          (let [saved (edn/read-string (slurp (io/file output "run.edn")))]
            (is (= :failed (:status saved)))
            (is (= 1 (count (:results saved))))
            (is (= "Measurement failed" (get-in saved [:error :message])))))))))
