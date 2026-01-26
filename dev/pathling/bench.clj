(ns pathling.bench
  "Benchmarks for Pathling.

   Run from REPL:
     (require '[pathling.bench :as bench])
     (bench/run-all)

   Or run individual benchmarks:
     (bench/bench-path-when)
     (bench/bench-update-paths)
     (bench/bench-find-when)
     (bench/bench-transform-when)

   Compare against naive clojure.walk implementations:
     (bench/compare-find)
     (bench/compare-transform)"
  (:require
    [clojure.walk :as walk]
    [co.multiply.pathling :as p]
    [criterium.core :as crit]))


;; Data Generation
;; ================================================================================

(defn generate-tree
  "Generate a nested data structure with scattered target values.

   Options:
   - :depth     - Maximum nesting depth (default 5)
   - :breadth   - Children per node (default 4)
   - :match-pct - Percentage of leaves that are targets (default 10)"
  ([] (generate-tree {}))
  ([{:keys [depth breadth match-pct]
     :or   {depth 5, breadth 4, match-pct 10}}]
   (letfn [(make-leaf []
             (if (< (rand-int 100) match-pct)
               {:type :target, :id (rand-int 10000)}
               {:type :other, :value (rand-int 1000)}))
           (make-node [d]
             (if (<= d 1)
               (make-leaf)
               (let [children (repeatedly breadth #(make-node (dec d)))]
                 (case (rand-int 3)
                   0 (vec children)
                   1 (zipmap (map #(keyword (str "k" %)) (range)) children)
                   2 (set children)))))]
     (make-node depth))))


(defn count-targets
  "Count :target nodes in data for verification."
  [data]
  (count (p/find-when data #(= :target (:type %)))))


;; Benchmark Utilities
;; ================================================================================

(defonce data-atom (atom nil))
(defonce nav-atom (atom nil))

(defn setup!
  "Generate test data. Call before running benchmarks.

   Presets:
   - :small  - ~100 nodes, ~10 targets
   - :medium - ~1,000 nodes, ~100 targets
   - :large  - ~10,000 nodes, ~1,000 targets
   - :huge   - ~100,000 nodes, ~10,000 targets"
  ([] (setup! :medium))
  ([preset]
   (let [opts (case preset
                :small {:depth 4, :breadth 3, :match-pct 10}
                :medium {:depth 5, :breadth 4, :match-pct 10}
                :large {:depth 6, :breadth 5, :match-pct 10}
                :huge {:depth 7, :breadth 6, :match-pct 10}
                preset)
         data (generate-tree opts)
         {:keys [nav]} (p/path-when data #(= :target (:type %)))]
     (reset! data-atom data)
     (reset! nav-atom nav)
     (println "Generated data with" (count-targets data) "targets")
     (println "Data structure depth:" (:depth opts) "breadth:" (:breadth opts)))))


;; Benchmarks
;; ================================================================================

(defn bench-path-when
  "Benchmark path-when (finding + building navigation)."
  []
  (let [data @data-atom]
    (assert data "Call (setup!) first")
    (println "\n=== Benchmarking path-when ===")
    (crit/bench (do (p/path-when data #(= :target (:type %))) nil))))


(defn bench-update-paths
  "Benchmark update-paths (applying transform via navigation)."
  []
  (let [data @data-atom
        nav  @nav-atom]
    (assert nav "Call (setup!) first")
    (println "\n=== Benchmarking update-paths ===")
    (crit/bench (do (p/update-paths data nav #(assoc % :processed true)) nil))))


(defn bench-find-when
  "Benchmark find-when (finding without navigation)."
  []
  (let [data @data-atom]
    (assert data "Call (setup!) first")
    (println "\n=== Benchmarking find-when ===")
    (crit/bench (do (p/find-when data #(= :target (:type %))) nil))))


(defn bench-transform-when
  "Benchmark transform-when (find + transform in one step)."
  []
  (let [data @data-atom]
    (assert data "Call (setup!) first")
    (println "\n=== Benchmarking transform-when ===")
    (crit/bench (do (p/transform-when data #(= :target (:type %)) #(assoc % :processed true)) nil))))


(defn run-all
  "Run all benchmarks with default medium dataset."
  ([] (run-all :medium))
  ([preset]
   (setup! preset)
   (bench-find-when)
   (bench-path-when)
   (bench-update-paths)
   (bench-transform-when)))


;; Naive implementations using clojure.walk
;; ================================================================================

(defn naive-find
  "Find all values matching pred using postwalk. Baseline for comparison."
  [data pred]
  (let [acc (volatile! [])]
    (walk/postwalk
      (fn [x]
        (when (pred x) (vswap! acc conj x))
        x)
      data)
    @acc))


(defn naive-transform
  "Transform all values matching pred using postwalk. Baseline for comparison."
  [data pred tf]
  (walk/postwalk
    #(if (pred %) (tf %) %)
    data))


;; Postwalk Benchmarks
;; ================================================================================

(defn bench-postwalk-find
  "Benchmark naive postwalk-based find."
  []
  (let [data @data-atom]
    (assert data "Call (setup!) first")
    (println "\n=== Benchmarking postwalk find ===")
    (crit/bench (do (naive-find data #(= :target (:type %))) nil))))


(defn bench-postwalk-transform
  "Benchmark naive postwalk-based transform."
  []
  (let [data @data-atom]
    (assert data "Call (setup!) first")
    (println "\n=== Benchmarking postwalk transform ===")
    (crit/bench (do (naive-transform data #(= :target (:type %)) #(assoc % :processed true)) nil))))


;; Comparison Benchmarks
;; ================================================================================

(defn compare-find
  "Compare find-when vs naive postwalk-based find."
  []
  (println "\n=== Comparing find implementations ===")
  (bench-find-when)
  (bench-postwalk-find))


(defn compare-transform
  "Compare transform-when vs naive postwalk-based transform."
  []
  (println "\n=== Comparing transform implementations ===")
  (bench-transform-when)
  (bench-postwalk-transform))


(defn compare-all
  "Run all comparison benchmarks."
  []
  (compare-find)
  (compare-transform))


(defn quick-compare
  "Quick non-rigorous comparison."
  ([] (quick-compare :medium))
  ([preset]
   (setup! preset)
   (let [data @data-atom
         pred #(= :target (:type %))
         tf   #(assoc % :processed true)
         n    100]
     (println "\n=== Quick comparison (100 iterations each) ===\n")
     (println "find-when:")
     (time (dotimes [_ n] (p/find-when data pred)))
     (println "naive-find:")
     (time (dotimes [_ n] (naive-find data pred)))
     (println "\ntransform-when:")
     (time (dotimes [_ n] (p/transform-when data pred tf)))
     (println "naive-transform:")
     (time (dotimes [_ n] (naive-transform data pred tf))))))


;; Quick sanity check (not a full benchmark)
;; ================================================================================

(defn quick-test
  "Run a quick sanity check without full Criterium warmup."
  ([] (quick-test :medium))
  ([preset]
   (setup! preset)
   (let [data @data-atom
         nav  @nav-atom]
     (println "\n=== Quick timing (not statistically rigorous) ===\n")
     (println "find-when:")
     (time (dotimes [_ 100] (p/find-when data #(= :target (:type %)))))
     (println "\npath-when:")
     (time (dotimes [_ 100] (p/path-when data #(= :target (:type %)))))
     (println "\nupdate-paths:")
     (time (dotimes [_ 100] (p/update-paths data nav #(assoc % :processed true))))
     (println "\ntransform-when:")
     (time (dotimes [_ 100] (p/transform-when data #(= :target (:type %)) #(assoc % :processed true)))))))


(comment
  ;; Quick sanity check (fast)
  (quick-test :medium)

  ;; Compare against naive postwalk implementations
  (quick-compare :medium)                                   ; Fast comparison
  (compare-all)                                             ; Full Criterium comparison
  (compare-find)                                            ; Just find comparison
  (compare-transform)                                       ; Just transform comparison

  ;; Full benchmarks with Criterium
  (run-all :huge)

  ;; Or run individually
  ; :small, :medium, :large, :huge
  (setup! :large)
  (setup! :huge)

  (bench-postwalk-find)
  (bench-find-when)
  (bench-path-when)
  (bench-update-paths)
  (bench-postwalk-transform)
  (bench-transform-when)

  (let [data (mapv (fn [n] {:n n}) (range 10000))]
    (println "`data`: (mapv (fn [n] {:n n}) (range 10000))")
    (println "Vector of 10000 maps; all of them matches.")
    (do
      (println "Scan -----------------------")
      (crit/quick-bench (p/path-when data number?)))
    (let [{:keys [matches nav]} (p/path-when data number?)]
      (println "Update -----------------------")
      (crit/quick-bench (p/update-paths data nav inc)))
    (do
      (println "Scan + Update -----------------------")
      (crit/quick-bench
        (let [{:keys [matches nav]} (p/path-when data number?)]
          (p/update-paths data nav inc)))))


  (let [data (mapv (fn [n] {:n n}) (range 10000))]
    (println "`data`: (mapv (fn [n] {:n n}) (range 10000))")
    (println "Vector of 10000 maps; all of them matches.")
    (do
      (println "Scan -----------------------")
      (crit/quick-bench (p/path-when data number?)))
    (let [{:keys [matches nav]} (p/path-when data number?)
          replacements (mapv inc matches)]
      (println "Update -----------------------")
      (crit/quick-bench (p/update-paths data nav replacements)))
    (do
      (println "Scan + Update -----------------------")
      (crit/quick-bench
        (let [{:keys [matches nav]} (p/path-when data number?)
              replacements (mapv inc matches)]
          (p/update-paths data nav replacements)))))

  (let [data (mapv (fn [n] {:n n}) (range 10000))]
    (crit/bench (p/transform-when data number? inc)))

  #__)