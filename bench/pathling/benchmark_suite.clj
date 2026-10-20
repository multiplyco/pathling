(ns pathling.benchmark-suite
  "Sequential, per-case JVM forks with retained results and between-JVM summaries."
  (:refer-clojure :exclude [run!])
  (:require [clojure.edn :as edn]
    [clojure.java.io :as io]
    [pathling.benchmark :as bench])
  (:import [java.lang.management ManagementFactory]
    [java.time Instant]
    [java.util Locale]))


(defn options
  [requested]
  (let [forks (get requested :forks 3)]
    (when-not (and (integer? forks) (pos? forks) (<= forks Long/MAX_VALUE))
      (throw (ex-info "Fork count must be a positive integer" {:forks forks})))
    (assoc (bench/options (dissoc requested :forks)) :forks forks)))


(defn schedule
  "Complete passes through the selection, with one fresh JVM per case per pass."
  [opts]
  (vec (for [fork (range 1 (inc (:forks opts)))
             {:keys [spec operation]} (bench/select-cases opts)]
         {:fork fork :id [(:id spec) operation]
          :directory (str "fork-" fork "/" (name (:id spec)) "/" (name operation))})))


(defn worker-command
  "Reuse the coordinator's Java executable, JVM arguments and classpath exactly.
   Arguments are passed directly to ProcessBuilder, without a shell."
  [options-file]
  (into [(.getPath (io/file (System/getProperty "java.home") "bin" "java"))]
    (concat (.getInputArguments (ManagementFactory/getRuntimeMXBean))
      ["-cp" (System/getProperty "java.class.path")
       "clojure.main" "-m" "pathling.benchmark" (str options-file)])))


(defn run-worker!
  [options-file console-file]
  (let [command (worker-command options-file)
        process (.start (doto (ProcessBuilder. ^java.util.List command)
                          (.redirectErrorStream true)
                          (.redirectOutput (io/file console-file))))
        stop (fn [] (when (.isAlive process) (.destroyForcibly process)))
        hook (Thread. ^Runnable stop)]
    (.addShutdownHook (Runtime/getRuntime) hook)
    (try
      {:exit-status (.waitFor process) :pid (.pid process) :command command}
      (finally
        (stop)
        (try (.removeShutdownHook (Runtime/getRuntime) hook)
          (catch IllegalStateException _ nil))))))


(defn validate-worker!
  "Reject partial, mismatched or stale child artifacts before accepting a fork."
  [run opts ctx id pid]
  (let [result (first (:results run))
        timing (:timing result)
        settings (bench/profiles (:profile opts))
        checks {:complete (= :complete (:status run))
                :validation (= :passed (:validation run) (:validation result))
                :single-case (= [id] (:selected-cases run) (mapv :id (:results run)))
                :options (= opts (:options run))
                :context (= ctx (:context run))
                :settings (= settings (:measurement-options run))
                :samples (= (:samples settings) (:sample-count timing) (count (:samples timing)))
                :execution-count (and (integer? (:execution-count timing)) (pos? (:execution-count timing)))
                :mean (let [m (first (:mean timing))]
                        ;; Criterium subtracts estimated loop overhead. A no-op
                        ;; can legitimately have a finite zero/negative estimate.
                        (and (number? m) (Double/isFinite (double m))))
                :process (and (= pid (get-in run [:process :pid]))
                           (string? (get-in run [:process :started-at])))
                :allocation (contains? #{:measured :unsupported} (get-in result [:allocation :status]))}]
    (when-not (every? identity (vals checks))
      (throw (ex-info "Invalid benchmark worker result" {:case id :checks checks})))
    result))


(defn- median
  [xs]
  (let [v (vec (sort xs)) n (count v) middle (quot n 2)]
    (if (odd? n) (nth v middle) (/ (+ (nth v (dec middle)) (nth v middle)) 2.0))))


(defn case-summaries
  [runs]
  (mapv (fn [id]
          (let [rows (filterv #(= id (:id %)) runs)
                means (mapv :mean-seconds rows)
                middle (median means)
                unavailable (cond
                              (some #(not (pos? %)) means) :nonpositive-estimate
                              (= 1 (count rows)) :insufficient-forks)]
            {:id id :forks (count rows) :mean-seconds means
             :median-seconds middle :min-seconds (apply min means) :max-seconds (apply max means)
             :spread-unavailable-reason unavailable
             :spread-percent (when-not unavailable
                               (* 100.0 (/ (- (apply max means) (apply min means)) middle)))
             :allocation-bytes (mapv :allocation-bytes rows)}))
    (distinct (map :id runs))))


(defn- fmt
  [pattern & args]
  (String/format Locale/ROOT pattern (to-array args)))


(defn summary
  [batch]
  (str "# Pathling isolated JVM benchmarks\n\n"
    "Status: `" (name (:status batch)) "`. Profile: `" (name (get-in batch [:options :profile]))
    "`. Forks per case: " (get-in batch [:options :forks]) ".\n\n"
    "Completed " (count (:runs batch)) " / " (count (:schedule batch)) " fresh JVM measurements.\n\n"
    (when-let [active (:active-run batch)]
      (str "Current or interrupted worker: fork " (:fork active) ", `" (:id active)
        "`; log: `" (:directory active) "/console.log`.\n\n"))
    "Commit: `" (get-in batch [:context :git :commit]) "`. "
    (when (seq (get-in batch [:context :git :status])) "Working tree had changes; sources and patch are saved. ")
    "\n\n"
    (when (not= :full (get-in batch [:options :profile]))
      "Exploratory profile; these timings are not a performance baseline.\n\n")
    "Statistics summarize independent JVM means, in microseconds per call. "
    "Spread is (max - min) / median; it is descriptive, not a confidence interval or pass/fail threshold. "
    "A single fork cannot establish between-JVM variability. All repetitions and Criterium samples are retained. "
    "Criterium already subtracts estimated loop overhead from samples and estimates. "
    "Zero/negative means indicate that this adjustment cannot resolve the operation's cost; "
    "they are preserved, with percentage spread omitted for any case containing one.\n\n"
    "| Fixture | Operation | Forks | Median µs | Min µs | Max µs | Spread % | Bytes/call range |\n"
    "|---|---|---:|---:|---:|---:|---:|---:|\n"
    (apply str
      (for [{:keys [id forks median-seconds min-seconds max-seconds spread-percent
                    spread-unavailable-reason allocation-bytes]}
              (:cases batch)]
        (fmt "| %s | %s | %d | %.6f | %.6f | %.6f | %s | %s |\n"
          (name (first id)) (name (second id)) forks
          (* 1e6 median-seconds) (* 1e6 min-seconds) (* 1e6 max-seconds)
          (cond
            spread-percent (fmt "%.2f" spread-percent)
            (= :nonpositive-estimate spread-unavailable-reason) "n/a (nonpositive estimate)"
            :else "—")
          (if (every? number? allocation-bytes)
            (fmt "%.2f–%.2f" (apply min allocation-bytes) (apply max allocation-bytes))
            "unsupported in one or more forks"))))
    "\n## Individual JVM measurements\n\n"
    "Each linked run retains within-JVM bootstrap intervals, allocation controls, and raw timing samples.\n\n"
    "| Fork | Fixture | Operation | Mean µs | Result |\n"
    "|---:|---|---|---:|---|\n"
    (apply str (for [{:keys [fork id mean-seconds directory]} (:runs batch)]
                 (fmt "| %d | %s | %s | %.6f | [Details](%s/measurement/summary.md) |\n"
                   fork (name (first id)) (name (second id)) (* 1e6 mean-seconds) directory)))))


(defn- checkpoint!
  [directory state]
  (swap! state assoc :cases (case-summaries (:runs @state)))
  (bench/write-edn! (io/file directory "batch.edn") @state)
  (spit (io/file directory "summary.md") (summary @state)))


(defn run!
  "Run each selected case in :forks (default 3) fresh JVMs, sequentially.
   Other options match pathling.benchmark/run!. bb bench checks correctness once."
  [requested]
  (let [opts (options requested)
        plan (schedule opts)]
    (if (:list? opts)
      (do
        (doseq [id (distinct (map :id plan))] (prn id))
        (println (:forks opts) "forks per case;" (count plan) "fresh JVM measurements"))
      (let [ctx (bench/context)
            directory (bench/create-output! opts ctx)
            state (atom {:schema-version 1 :execution-mode :per-case-jvm
                         :status :running :started-at (str (Instant/now))
                         :process (bench/process-identity) :options opts :context ctx
                         :measurement-options (bench/profiles (:profile opts))
                         :schedule plan :runs []})]
        (println "Saving" (count plan) "isolated JVM measurements to" (.getPath directory))
        (checkpoint! directory state)
        (try
          (bench/save-sources! directory ctx)
          (doseq [[i {:keys [fork id] :as entry}] (map-indexed vector plan)]
            (let [worker-directory (io/file directory (:directory entry))
                  options-file (io/file worker-directory "options.edn")
                  child-options (-> (dissoc opts :forks :list?)
                                  (assoc :fixtures [(first id)] :operations [(second id)]
                                    :output (.getAbsolutePath (io/file worker-directory "measurement"))))
                  active (assoc entry :started-at (str (Instant/now)))]
              (io/make-parents options-file)
              (bench/write-edn! options-file child-options)
              (swap! state assoc :active-run active)
              (checkpoint! directory state)
              (println (str "[" (inc i) "/" (count plan) "]") "fork" fork id)
              (flush)
              (let [{:keys [exit-status pid command]} (run-worker! options-file (io/file worker-directory "console.log"))]
                (spit (io/file worker-directory "exit-status") (str exit-status "\n"))
                (when-not (zero? exit-status)
                  (throw (ex-info "Benchmark worker failed" {:case id :fork fork :exit-status exit-status})))
                (let [run (edn/read-string (slurp (io/file (:output child-options) "run.edn")))
                      result (validate-worker! run child-options ctx id pid)
                      earlier (filterv #(= id (:id %)) (:runs @state))]
                  (when (some #(= (:process run) (:process %)) (:runs @state))
                    (throw (ex-info "Worker JVM was reused" {:case id :fork fork})))
                  (when (and (seq earlier)
                          (not= (:fixture result) (:fixture (first earlier))))
                    (throw (ex-info "Fixture changed between forks" {:case id :fork fork})))
                  (swap! state update :runs conj
                    (assoc active :finished-at (str (Instant/now))
                      :process (:process run) :command command :exit-status exit-status
                      :validation :passed :fixture (:fixture result)
                      :mean-seconds (first (get-in result [:timing :mean]))
                      :interval-seconds (second (get-in result [:timing :mean]))
                      :allocation-status (get-in result [:allocation :status])
                      :allocation-bytes (get-in result [:allocation :mean-bytes-per-call])))
                  (swap! state dissoc :active-run)
                  (checkpoint! directory state)
                  (println "Completed:" (fmt "%.6f µs/call" (* 1e6 (first (get-in result [:timing :mean])))))
                  (flush)))))
          (swap! state assoc :status :complete :validation :passed :finished-at (str (Instant/now)))
          (checkpoint! directory state)
          (println "Saved" (str (io/file directory "batch.edn")))
          (println "Summary:" (str (io/file directory "summary.md")))
          {:output (.getPath directory) :status :complete :jvms (count (:runs @state))}
          (catch Exception e
            (swap! state assoc :status :failed :finished-at (str (Instant/now))
              :error {:class (.getName (class e)) :message (.getMessage e) :details (ex-data e)})
            (checkpoint! directory state)
            (throw e)))))))
