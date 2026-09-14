(ns pathling.benchmark
  "Saved JVM benchmarks. Prefer `bb bench` to rebuild and test before measuring."
  (:refer-clojure :exclude [run!])
  (:require [clojure.edn :as edn]
    [clojure.java.io :as io]
    [clojure.java.shell :as shell]
    [clojure.pprint :as pprint]
    [clojure.string :as str]
    [clojure.walk :as walk]
    [criterium.core :as crit]
    [pathling.fixtures :as fixtures])
  (:import [com.sun.management ThreadMXBean]
    [java.lang.management ManagementFactory]
    [java.nio.file AtomicMoveNotSupportedException CopyOption Files StandardCopyOption]
    [java.security MessageDigest]
    [java.time Instant ZoneOffset]
    [java.time.format DateTimeFormatter]
    [java.util Locale UUID]
    [java.util.zip ZipEntry ZipOutputStream]))


(def profiles
  {:full crit/*default-benchmark-opts*
   :quick crit/*default-quick-bench-opts*
   ;; Smoke exercises the real measurement and serialization paths. It must
   ;; never be used to draw performance conclusions.
   :smoke (merge crit/*default-quick-bench-opts*
            {:samples 6 :warmup-jit-period 100000000
             :target-execution-time 10000000 :bootstrap-size 100
             :max-gc-attempts 2 :overhead 0})})


(def default-options
  {:profile :full :suite :primary :seed 20260914
   :allocation-samples 5})


(defn options
  "Validate options before creating output or running any measurements."
  [opts]
  (let [allowed #{:profile :suite :seed :fixtures :operations :output :label
                  :allocation-samples :list?}
        opts (merge default-options opts)]
    (when-let [unknown (seq (remove allowed (keys opts)))]
      (throw (ex-info "Unknown benchmark options" {:options unknown})))
    (when-not (contains? profiles (:profile opts))
      (throw (ex-info "Profile must be :full, :quick, or :smoke" {})))
    (when-not (#{:primary :secondary :comparison :all} (:suite opts))
      (throw (ex-info "Suite must be :primary, :secondary, :comparison, or :all" {})))
    (when-not (and (integer? (:seed opts))
                (<= Long/MIN_VALUE (:seed opts) Long/MAX_VALUE))
      (throw (ex-info "Seed must fit in a Java long" {})))
    (when-not (and (integer? (:allocation-samples opts))
                (pos? (:allocation-samples opts)))
      (throw (ex-info "Allocation sample count must be a positive integer" {})))
    (doseq [k [:output :label]]
      (when (and (contains? opts k) (not (and (string? (opts k)) (seq (opts k)))))
        (throw (ex-info "Output and label must be nonempty strings" {:option k}))))
    (doseq [k [:fixtures :operations]]
      (when (and (contains? opts k)
              (not (and (vector? (opts k)) (seq (opts k)) (every? keyword? (opts k)))))
        (throw (ex-info "Filters must be nonempty vectors of keywords" {:option k}))))
    opts))


(defn select-cases
  [opts]
  (let [ops (case (:suite opts)
              :primary fixtures/primary-operations
              :secondary fixtures/secondary-operations
              :comparison fixtures/comparison-operations
              :all fixtures/all-operations)
        select (fn [available requested label]
                 (when-let [unknown (seq (remove (set available) requested))]
                   (throw (ex-info "Unknown or unavailable benchmark selection"
                            {:kind label :unknown unknown :available available})))
                 (if requested (filterv (set requested) available) available))
        ids (select (mapv :id fixtures/fixture-specs) (:fixtures opts) :fixtures)
        ops (select ops (:operations opts) :operations)]
    (vec (for [spec fixtures/fixture-specs
               :when ((set ids) (:id spec))
               op ops]
           {:spec spec :operation op}))))


(defn- git
  [& args]
  (let [{:keys [exit out err]} (apply shell/sh "git" args)]
    (when-not (zero? exit)
      (throw (ex-info "Cannot record Git revision" {:args args :error err})))
    (str/trimr out)))


(defn- file-hash
  [file]
  (apply str (map #(format "%02x" (bit-and 0xff %))
               (.digest (MessageDigest/getInstance "SHA-256")
                 (Files/readAllBytes (.toPath (io/file file)))))))


(defn- file-hashes
  [paths]
  (into (sorted-map)
    (for [path paths
          ^java.io.File f (file-seq (io/file path))
          :when (.isFile f)
          :when (not= ".DS_Store" (.getName f))]
      [(.getPath f) (file-hash f)])))


(defn- context
  []
  (let [runtime (ManagementFactory/getRuntimeMXBean)
        cpu (try (shell/sh "sysctl" "-n" "machdep.cpu.brand_string")
              (catch java.io.IOException _ nil))
        basis-path (System/getProperty "clojure.basis")
        basis (when basis-path (edn/read-string (slurp basis-path)))]
    {:git {:commit (git "rev-parse" "HEAD")
           :description (git "describe" "--tags" "--always" "--dirty")
           :status (git "status" "--porcelain")}
     :source-sha256 (file-hashes ["src"])
     :harness-sha256 (file-hashes ["bench" "bench-test" "test" "tests.edn" "deps.edn" "bb.edn" "build.clj"])
     :class-sha256 (file-hashes ["target/classes"])
     :loaded-scanner (str (.getResource ^Class co.multiply.pathling.ScannerMatchesNav
                            "ScannerMatchesNav.class"))
     :libraries (into (sorted-map)
                  (map (fn [[lib coord]]
                         [lib (select-keys coord [:mvn/version :git/sha :local/root])]))
                  (:libs basis))
     :environment {:clojure-version (clojure-version)
                   :criterium-resource (str (io/resource "criterium/core.clj"))
                   :java (into {} (map (fn [k] [k (System/getProperty k)]))
                           ["java.version" "java.vendor" "java.vm.name" "java.vm.version"])
                   :jvm-options (vec (.getInputArguments runtime))
                   :os (into {} (map (fn [k] [k (System/getProperty k)]))
                         ["os.name" "os.arch" "os.version"])
                   :cpu-model (when (= 0 (:exit cpu)) (str/trim (:out cpu)))
                   :available-processors (.availableProcessors (Runtime/getRuntime))
                   :max-heap-bytes (.maxMemory (Runtime/getRuntime))
                   :gc (mapv #(.getName ^java.lang.management.GarbageCollectorMXBean %)
                         (ManagementFactory/getGarbageCollectorMXBeans))}}))


(defn- allocation-bean
  []
  (let [bean (ManagementFactory/getThreadMXBean)]
    (when (and (instance? ThreadMXBean bean)
            (.isThreadAllocatedMemorySupported ^ThreadMXBean bean))
      (when-not (.isThreadAllocatedMemoryEnabled ^ThreadMXBean bean)
        (.setThreadAllocatedMemoryEnabled ^ThreadMXBean bean true))
      bean)))


(defn- allocated-batch
  [^ThreadMXBean bean f iterations]
  (let [before (.getCurrentThreadAllocatedBytes bean)]
    ;; Use Criterium's exact execution loop, including its result sink.
    (crit/execute-expr iterations f)
    (let [after (.getCurrentThreadAllocatedBytes bean)]
      (when (or (neg? before) (neg? after))
        (throw (ex-info "Thread allocation counter unavailable" {})))
      (/ (double (- after before)) iterations))))


(defn measure-allocation
  [f execution-count sample-count]
  (if-let [bean (allocation-bean)]
    (let [iterations (max 1 (min 100000 execution-count))
          control (fn [] nil)
          _ (crit/execute-expr iterations control)
          controls (mapv (fn [_] (allocated-batch bean control iterations)) (range sample-count))
          samples (mapv (fn [_] (allocated-batch bean f iterations)) (range sample-count))]
      {:status :measured :method :thread-mx-bean :scope :current-thread
       :batch-iterations iterations :samples-bytes-per-call samples
       :mean-bytes-per-call (/ (reduce + samples) (count samples))
       :control-bytes-per-call controls :control-subtracted? false})
    {:status :unsupported :method :thread-mx-bean}))


(defn edn-result
  "Drop retained operation results (including mutable Java navigation objects)
   and normalize Criterium's records and sequences into ordinary EDN."
  [result]
  (walk/postwalk (fn [x]
                   (cond (record? x) (into {} x)
                         (sequential? x) (vec x)
                         :else x))
    (dissoc result :results)))


(defn- edn-string
  [data]
  (binding [*print-length* nil *print-level* nil *print-meta* false]
    (with-out-str (pprint/pprint data))))


(defn- archive-sources!
  [directory ctx]
  (with-open [zip (ZipOutputStream. (io/output-stream (io/file directory "sources.zip")))]
    (doseq [^String path (sort (concat (keys (:source-sha256 ctx)) (keys (:harness-sha256 ctx))))]
      (.putNextEntry zip (ZipEntry. path))
      (io/copy (io/file path) zip)
      (.closeEntry zip))))


(defn- write-edn!
  [file data]
  (let [temporary (io/file (str file ".tmp"))
        target (.toPath (io/file file))]
    (spit temporary (edn-string data))
    (try
      (Files/move (.toPath temporary) target
        (into-array CopyOption [StandardCopyOption/REPLACE_EXISTING
                                StandardCopyOption/ATOMIC_MOVE]))
      (catch AtomicMoveNotSupportedException _
        (Files/move (.toPath temporary) target
          (into-array CopyOption [StandardCopyOption/REPLACE_EXISTING]))))))


(defn- format-root
  [pattern & args]
  (String/format Locale/ROOT pattern (to-array args)))


(defn summary
  [run]
  (str "# Pathling JVM benchmark\n\n"
    "Status: `" (name (:status run)) "`. Profile: `" (name (get-in run [:options :profile])) "`.\n\n"
    (when (not= :full (get-in run [:options :profile]))
      "Exploratory run. Use the full profile and repeated fresh JVM runs for performance decisions.\n\n")
    "Commit: `" (get-in run [:context :git :commit]) "`. "
    (when (seq (get-in run [:context :git :status])) "Working tree had changes; source hashes and git.patch are saved. ")
    "\n\nMean time and Criterium's bootstrap interval are in microseconds per call. "
    "Allocation includes the measurement loop; its control samples are in run.edn.\n\n"
    "| Fixture | Operation | Matches | Mean µs | Interval µs | Bytes/call |\n"
    "|---|---|---:|---:|---:|---:|\n"
    (apply str
      (for [{:keys [id eligible-matches timing allocation]} (:results run)
            :let [[mean [lo hi]] (:mean timing)]]
        (format-root "| %s | %s | %d | %.3f | %.3f–%.3f | %s |\n"
          (name (first id)) (name (second id)) eligible-matches
          (* mean 1e6) (* lo 1e6) (* hi 1e6)
          (if-let [b (:mean-bytes-per-call allocation)]
            (format-root "%.1f" b) "unsupported"))))))


(defn- checkpoint!
  [directory run]
  (write-edn! (io/file directory "run.edn") run)
  (spit (io/file directory "summary.md") (summary run)))


(defn run!
  "Options: :profile (:full default, :quick, :smoke), :suite (:primary default,
   :secondary, :comparison, :all), :fixtures [...], :operations [...], :seed, :output (new
   directory), :label, :allocation-samples, :list?. See benchmarks/README.md."
  [requested]
  (let [opts (options requested)
        selected (select-cases opts)]
    (if (:list? opts)
      (doseq [{:keys [spec operation]} selected] (prn [(:id spec) operation]))
      (let [ctx (context)
            stamp (.format (.withZone (DateTimeFormatter/ofPattern "yyyyMMdd'T'HHmmss'Z'") ZoneOffset/UTC)
                    (Instant/now))
            directory (io/file (or (:output opts)
                                 (str "benchmarks/results/" stamp "-"
                                   (subs (get-in ctx [:git :commit]) 0 8) "-"
                                   (subs (str (UUID/randomUUID)) 0 8))))
            _ (io/make-parents directory)
            _ (Files/createDirectory (.toPath directory) (make-array java.nio.file.attribute.FileAttribute 0))
            settings (profiles (:profile opts))
            state (atom {:schema-version 1 :fixture-version 1 :status :running
                         :started-at (str (Instant/now)) :options opts
                         :context ctx :measurement-options settings
                         :selected-cases (mapv (fn [{:keys [spec operation]}] [(:id spec) operation]) selected)
                         :results []})]
        (println "Saving" (count selected) "cases to" (.getPath directory)
          "using profile" (:profile opts))
        (spit (io/file directory "git.patch") (git "diff" "HEAD" "--binary" "--" "."))
        (archive-sources! directory ctx)
        (checkpoint! directory @state)
        (try
          ;; Prepare and validate every selected case before recording timings.
          ;; Validation is repeated afterwards to catch state carried between calls.
          (let [cases (vec (mapcat
                             (fn [group]
                               (fixtures/prepare-cases
                                 (fixtures/make-fixture (:spec (first group)) (:seed opts))
                                 (mapv :operation group)))
                             (partition-by (comp :id :spec) selected)))]
            (doseq [{:keys [f validate]} cases]
              (dotimes [_ 2] (validate (f))))
            (swap! state assoc :validation :passed)
            (checkpoint! directory @state)
            (doseq [[i {:keys [id fixture eligible-matches f validate]}] (map-indexed vector cases)]
              (println (str "[" (inc i) "/" (count cases) "]") id)
              (flush)
              (let [raw-timing (binding [crit/*max-gc-attempts* (:max-gc-attempts settings)]
                                 (crit/benchmark* f settings))
                    _ (doseq [result (:results raw-timing)] (validate result))
                    timing (edn-result raw-timing)
                    allocation (measure-allocation f (:execution-count timing) (:allocation-samples opts))]
                (validate (f))
                (swap! state update :results conj
                  {:id id :fixture fixture :eligible-matches eligible-matches :validation :passed
                   :timing timing :allocation allocation})
                (checkpoint! directory @state))))
          (swap! state assoc :status :complete :finished-at (str (Instant/now)))
          (checkpoint! directory @state)
          (println "Saved" (str (io/file directory "run.edn")))
          (println "Summary:" (str (io/file directory "summary.md")))
          {:output (.getPath directory) :status :complete :cases (count (:results @state))}
          (catch Exception e
            (swap! state assoc :status :failed :finished-at (str (Instant/now))
              :error {:class (.getName (class e)) :message (.getMessage e)})
            (checkpoint! directory @state)
            (throw e)))))))
