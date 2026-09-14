(ns pathling.benchmark-suite-test
  (:require [clojure.edn :as edn]
    [clojure.java.io :as io]
    [clojure.test :refer [deftest is testing]]
    [criterium.core :as crit]
    [pathling.benchmark :as bench]
    [pathling.benchmark-suite :as suite]
    [pathling.benchmark-test :as support])
  (:import [java.nio.file FileAlreadyExistsException]))


(defn read-edn
  [file]
  (edn/read-string (slurp file)))


(defn fake-worker
  "Exercise actual worker serialization/validation while replacing process startup
   and expensive timing. A separate smoke run checks real process isolation."
  [counter mutate]
  (fn [options-file console-file]
    (let [n (swap! counter inc)
          opts (read-edn options-file)
          pid (+ 100000 n)]
      (spit console-file "Test worker\n")
      (with-redefs [crit/benchmark* support/fake-timing
                    bench/measure-allocation (fn [& _] {:status :unsupported})]
        (bench/run! opts))
      (let [file (io/file (:output opts) "run.edn")]
        (bench/write-edn! file
          (mutate (assoc (read-edn file) :process {:pid pid :started-at (str "test-" n)}))))
      {:pid pid :exit-status 0 :command ["test-worker" (str options-file)]})))


(def smoke-options
  {:profile :smoke :fixtures [:small-one]
   :operations [:path-raw :update-array-list] :forks 2})


(deftest fork-options-and-round-robin-schedule
  (is (= 3 (:forks (suite/options {}))))
  (is (= 180 (count (suite/schedule (suite/options {:forks 6})))))
  (is (= [[1 [:small-one :path-raw]] [1 [:small-one :update-array-list]]
          [2 [:small-one :path-raw]] [2 [:small-one :update-array-list]]]
         (mapv (juxt :fork :id) (suite/schedule (suite/options smoke-options)))))
  (doseq [opts [{:forks 0} {:forks -1} {:forks 1.5} {:forks nil}
                {:fork 2} {:operations [:typo]}]]
    (is (thrown? Exception (suite/schedule (suite/options opts)))))
  (let [output (support/temporary-output)]
    (suite/run! (assoc smoke-options :list? true :output output))
    (is (not (.exists (io/file output))))))


(deftest independent-forks-are-retained-and-summarized
  (let [output (str (support/temporary-output) " with spaces")
        counter (atom 0)
        opts (assoc smoke-options :output output :label "A quoted \"label\"")]
    (with-redefs [suite/run-worker! (fake-worker counter identity)]
      (is (= {:output output :status :complete :jvms 4} (suite/run! opts)))
      (let [batch (read-edn (io/file output "batch.edn"))]
        (is (= :complete (:status batch)))
        (is (= :per-case-jvm (:execution-mode batch)))
        (is (= :passed (:validation batch)))
        (is (= 4 (count (:runs batch)) @counter))
        (is (= 4 (count (set (map :process (:runs batch))))))
        (is (= [2 2] (mapv :forks (:cases batch))))
        (is (= [0.0 0.0] (mapv :spread-percent (:cases batch))))
        (is (not (contains? batch :active-run)))
        (doseq [{:keys [id directory]} (:runs batch)]
          (let [child (read-edn (io/file output directory "measurement/run.edn"))]
            (is (= [id] (:selected-cases child)))
            (is (= "A quoted \"label\"" (get-in child [:options :label])))
            (is (.isFile (io/file output directory "console.log"))))))
      (is (.isFile (io/file output "sources.zip")))
      (is (.isFile (io/file output "summary.md")))
      (is (thrown? FileAlreadyExistsException (suite/run! opts)))
      (is (= 4 @counter)))))


(deftest failed-process-stops-batch-and-preserves-completed-forks
  (let [output (support/temporary-output)
        counter (atom 0)
        worker (fake-worker counter identity)]
    (with-redefs [suite/run-worker! (fn [opts log]
                                      (if (zero? @counter)
                                        (worker opts log)
                                        (do (swap! counter inc)
                                          {:exit-status 17 :pid 123 :command ["failed-worker"]})))]
      (is (thrown-with-msg? Exception #"Benchmark worker failed"
            (suite/run! (assoc smoke-options :output output)))))
    (let [batch (read-edn (io/file output "batch.edn"))]
      (is (= :failed (:status batch)))
      (is (= 1 (count (:runs batch))))
      (is (= 2 @counter))
      (is (= 17 (get-in batch [:error :details :exit-status])))
      (is (= [:small-one :update-array-list] (get-in batch [:active-run :id])))
      (is (= "17\n" (slurp (io/file output (get-in batch [:active-run :directory]) "exit-status")))))))


(deftest invalid-child-artifacts-cannot-enter-a-baseline
  (doseq [[label mutate]
          [["incomplete" #(assoc % :status :running)]
           ["changed source" #(assoc-in % [:context :source-sha256 "src/changed"] "different")]
           ["wrong fixture selection" #(assoc % :selected-cases [[:deep-one :path-raw]])]
           ["missing sample" #(update-in % [:results 0 :timing :samples] pop)]
           ["wrong process" #(assoc-in % [:process :pid] 1)]
           ["NaN estimate" #(assoc-in % [:results 0 :timing :mean 0] ##NaN)]
           ["infinite estimate" #(assoc-in % [:results 0 :timing :mean 0] ##Inf)]]]
    (testing label
      (let [output (support/temporary-output)
            counter (atom 0)]
        (with-redefs [suite/run-worker! (fake-worker counter mutate)]
          (is (thrown-with-msg? Exception #"Invalid benchmark worker result"
                (suite/run! (assoc smoke-options :output output)))))
        (let [batch (read-edn (io/file output "batch.edn"))]
          (is (= :failed (:status batch)))
          (is (empty? (:runs batch)))
          (is (= 1 @counter)))))))


(deftest changed-fixture-between-forks-stops-batch
  (let [output (support/temporary-output)
        counter (atom 0)]
    (with-redefs [suite/run-worker! (fake-worker counter
                                     #(if (= 2 @counter)
                                        (assoc-in % [:results 0 :fixture :sha256] "changed") %))]
      (is (thrown-with-msg? Exception #"Fixture changed between forks"
            (suite/run! (assoc smoke-options :output output :operations [:path-raw])))))
    (let [batch (read-edn (io/file output "batch.edn"))]
      (is (= :failed (:status batch)))
      (is (= 1 (count (:runs batch)))))))


(deftest summaries-describe-between-jvm-variation
  (let [row (fn [seconds bytes] {:id [:deep-one :path-raw]
                                :mean-seconds seconds :allocation-bytes bytes})
        summary (first (suite/case-summaries [(row 1.0 12) (row 3.0 14) (row 2.0 12) (row 2.0 12)]))]
    (is (= [1.0 3.0 2.0 2.0] (:mean-seconds summary)))
    (is (= 2.0 (:median-seconds summary)))
    (is (= 100.0 (:spread-percent summary)))
    (is (= [12 14 12 12] (:allocation-bytes summary)))
    (is (nil? (:spread-percent (first (suite/case-summaries [(row 1.0 nil)])))))))


(deftest overhead-adjusted-estimates-can-cross-zero
  (let [output (support/temporary-output)
        counter (atom 0)
        means [-1.1e-10 0.0 1.1e-10]]
    (with-redefs [suite/run-worker! (fake-worker counter
                                     (fn [run]
                                       (let [mean (nth means (dec @counter))]
                                         (-> run
                                           (assoc-in [:results 0 :timing :mean] [mean [mean mean]])
                                           (assoc-in [:results 0 :timing :samples] (vec (repeat 6 (* mean 2 1e9))))))))]
      (is (= :complete (:status (suite/run! {:profile :smoke :forks 3
                                            :fixtures [:scalar-empty] :operations [:update-array-list]
                                            :output output})))))
    (let [batch (read-edn (io/file output "batch.edn"))
          row (first (:cases batch))]
      (is (= means (mapv :mean-seconds (:runs batch)) (:mean-seconds row)))
      (is (= 0.0 (:median-seconds row)))
      (is (nil? (:spread-percent row)))
      (is (= :nonpositive-estimate (:spread-unavailable-reason row)))
      (is (re-find #"n/a \(nonpositive estimate\)" (slurp (io/file output "summary.md"))))))
  (doseq [means [[-2.0 -1.0] [-1.0 3.0 4.0] [0.0 0.0]]]
    (let [row (first (suite/case-summaries (mapv #(hash-map :id [:empty :update] :mean-seconds %) means)))]
      (is (nil? (:spread-percent row)))
      (is (= :nonpositive-estimate (:spread-unavailable-reason row))))))
