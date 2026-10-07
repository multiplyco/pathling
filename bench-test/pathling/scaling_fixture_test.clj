(ns pathling.scaling-fixture-test
  (:require [clojure.edn :as edn]
    [clojure.java.io :as io]
    [clojure.test :refer [deftest is testing]]
    [co.multiply.pathling :as p]
    [pathling.benchmark :as bench]
    [pathling.benchmark-suite :as suite]
    [pathling.benchmark-suite-test :as suite-test]
    [pathling.benchmark-test :as support]
    [pathling.fixtures :as fixtures]
    [pathling.scaling-fixtures :as scaling])
  (:import [clojure.lang PersistentArrayMap PersistentHashMap]))


(defn- leaf-locations
  [data]
  (letfn [(visit [path x]
            (if (coll? x)
              (mapcat (fn [[k v]] (visit (conj path k) v))
                (if (map? x) x (map-indexed vector x)))
              [{:path path :value x}]))]
    (vec (visit [] data))))


(deftest scaling-layout-match-position-and-fingerprints
  (doseq [{:keys [family leaf-count match-count] :as spec} scaling/fixture-specs]
    (testing (str (:id spec))
      (let [{:keys [data matches expected description]} (scaling/make-fixture spec 20260914)
            locations (leaf-locations data)
            matched (filterv #(fixtures/groundable? (:value %)) locations)
            blocks (count data)]
        (is (= description (:description (scaling/make-fixture spec 20260914))))
        (is (= leaf-count (count locations)))
        (is (= (set (range leaf-count))
               (set (map #(if (fixtures/groundable? (:value %)) 0 (:value %)) locations))))
        (is (= match-count (count matches) (count matched)))
        (is (= #{(:leaf-depth description)} (set (map #(count (:path %)) locations))))
        (is (= (case family
                 :array-maps (+ 1 blocks leaf-count)
                 :hash-maps (+ 1 blocks leaf-count)
                 :mixed (+ 1 (* 5 blocks) leaf-count))
               (:node-count description)))
        (if (= 1 match-count)
          (do
            (is (= (first locations) (first matched)))
            (is (= (:match-path description) (:path (first matched))))
            (is (identical? (first matches) (get-in data (:match-path description))))
            (is (= -1 (get-in expected (:match-path description)))))
          (do
            (is (nil? (:match-path description)))
            (is (identical? data expected))))
        (case family
          :array-maps (is (every? #(and (instance? PersistentArrayMap %) (= 8 (count %))) data))
          :hash-maps (is (every? #(and (instance? PersistentHashMap %) (= 16 (count %))) data))
          :mixed (is (every? #(and (instance? PersistentArrayMap %)
                               (= [:vector :list :set :map] (vec (keys %)))
                               (vector? (:vector %)) (= 4 (count (:vector %)))
                               (list? (:list %)) (= 4 (count (:list %)))
                               (set? (:set %)) (= 8 (count (:set %)))
                               (instance? PersistentHashMap (:map %)) (= 16 (count (:map %))))
                       data))))))
  (let [descriptions (map #(:description (scaling/make-fixture % 42)) scaling/fixture-specs)]
    (is (= 18 (count (set (map :sha256 descriptions)))))))


(deftest scaling-operations-are-correct-repeatable-and-validate-results
  (doseq [spec scaling/fixture-specs]
    (let [{:keys [data matches expected description] :as fixture} (scaling/make-fixture spec 42)
          before (fixtures/describe-data data)]
      (doseq [{:keys [id f validate eligible-matches]} (scaling/prepare-cases fixture scaling/operations)]
        (testing (str id)
          (is (= (:match-count spec) eligible-matches))
          (dotimes [_ 3] (is (nil? (validate (f)))))
          (when (= :update-array-list (second id))
            (with-redefs [p/path-when (fn [& _] (throw (ex-info "Scanning entered update-only timing" {})))]
              (is (nil? (validate (f)))))
            (if (empty? matches)
              (is (thrown? Exception (validate (into [] data))))
              (is (thrown? Exception (validate (assoc-in expected (:match-path description) :wrong))))))
          (when (and (= :path-raw (second id)) (seq matches))
            (is (thrown? Exception (validate nil)))
            (is (thrown? Exception (validate (assoc (f) :nav nil)))))))
      (is (= before (fixtures/describe-data data))))))


(deftest scaling-suite-selection-and-saved-worker-pipeline
  (is (= 30 (count (bench/select-cases (bench/options {})))))
  (is (= (* (count fixtures/fixture-specs) (count fixtures/all-operations))
         (count (bench/select-cases (bench/options {:suite :all})))))
  (is (= 54 (count (bench/select-cases (bench/options {:suite :sparse-scaling})))))
  (is (= 162 (count (suite/schedule (suite/options {:suite :sparse-scaling})))))
  (doseq [opts [{:fixtures [:scale-array-maps-64-0]}
                {:suite :sparse-scaling :fixtures [:small-one]}
                {:suite :sparse-scaling :operations [:find]}]]
    (is (thrown? Exception (bench/select-cases (bench/options opts)))))
  (let [output (support/temporary-output)
        counter (atom 0)
        opts {:suite :sparse-scaling :profile :smoke :forks 2
              :fixtures [:scale-array-maps-64-0 :scale-hash-maps-64-1 :scale-mixed-64-1]
              :operations [:path-raw :raw-roundtrip] :output output}]
    (with-redefs [suite/run-worker! (suite-test/fake-worker counter identity)]
      (is (= :complete (:status (suite/run! opts)))))
    (let [batch (edn/read-string (slurp (io/file output "batch.edn")))]
      (is (= 12 @counter (count (:runs batch))))
      (is (= :passed (:validation batch)))
      (is (= #{:array-maps :hash-maps :mixed} (set (map #(get-in % [:fixture :family]) (:runs batch)))))
      (is (= #{0 1} (set (map #(get-in % [:fixture :match-count]) (:runs batch))))))))
