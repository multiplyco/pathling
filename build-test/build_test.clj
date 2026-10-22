(ns build-test
  (:require
    [build :as build]
    [clojure.java.io :as io]
    [clojure.test :refer [deftest is testing]]
    [clojure.tools.build.api :as b]
    [deps-deploy.deps-deploy :as deploy])
  (:import
    [java.nio.file Files]
    [java.nio.file.attribute FileAttribute]))


(defn- exercise
  "Replace every external action so release failures can be tested without publishing."
  [entry {:keys [fail-command dirty-before? dirty-after? changed-head? jar-error?]}]
  (let [events (atom [])
        status-reads (atom 0)
        head-reads (atom 0)]
    (with-redefs [b/process
                  (fn [{:keys [command-args]}]
                    (swap! events conj command-args)
                    (let [out (cond
                                (= command-args ["git" "status" "--porcelain" "--untracked-files=all"])
                                (when (if (= 1 (swap! status-reads inc)) dirty-before? dirty-after?)
                                  " M src/changed.cljc\n")

                                (= command-args ["git" "rev-parse" "HEAD"])
                                (if (and (> (swap! head-reads inc) 1) changed-head?) "new-head" "checked-head"))]
                      ;; tools.build omits :out when captured stdout is empty.
                      (cond-> {:exit (if (= fail-command command-args) 1 0)}
                        out (assoc :out out))))
                  build/jar
                  (fn [_]
                    (swap! events conj [:jar])
                    (when jar-error?
                      (throw (ex-info "Build failed" {}))))
                  deploy/deploy
                  (fn [opts] (swap! events conj [:deploy (:artifact opts)]))]
      (let [error (binding [*out* (java.io.StringWriter.)]
                    (try (entry nil) nil (catch Exception e e)))]
        {:events @events :error error}))))


(defn- publication-events
  [events]
  (filterv #(or (= :deploy (first %))
              (and (= "git" (first %)) (#{"tag" "push"} (second %))))
    events))


(deftest validation-build-and-publication-order
  (let [{:keys [events error]} (exercise build/deploy {})
        relevant (filterv #(or (= "bb" (first %)) (= :jar (first %))
                             (seq (publication-events [%]))) events)]
    (is (nil? error))
    (is (= [["bb" "format:check"] ["bb" "test"] ["bb" "bench:check"]
            [:jar]
            ["git" "tag" (str "v" build/version) "checked-head"]
            ["git" "push" "origin" (str "refs/tags/v" build/version)]
            [:deploy build/jar-file]]
           relevant))
    (is (= 1 (count (filter #{[:jar]} events))) "Publish the built artifact without rebuilding")))


(deftest checks-can-run-before-committing-and-never-publish
  (let [{:keys [events error]} (exercise build/check-release {:dirty-before? true})]
    (is (nil? error))
    (is (= [:jar] (last events)))
    (is (empty? (publication-events events)))
    (is (not-any? #(= "git" (first %)) events))))


(deftest validation-failures-stop-before-building-or-publishing
  (doseq [task ["format:check" "test" "bench:check"]]
    (testing task
      (let [{:keys [events error]} (exercise build/deploy {:fail-command ["bb" task]})]
        (is (= 1 (:exit (ex-data error))))
        (is (= ["bb" task] (last events)))
        (is (not-any? #{[:jar]} events))
        (is (empty? (publication-events events)))))))


(deftest build-failure-stops-before-publishing
  (let [{:keys [events error]} (exercise build/deploy {:jar-error? true})]
    (is (= "Build failed" (ex-message error)))
    (is (= [:jar] (last events)))
    (is (empty? (publication-events events)))))


(deftest publication-requires-the-same-clean-checkout-throughout
  (doseq [entry [build/tag build/deploy]
          scenario [{:dirty-before? true} {:dirty-after? true} {:changed-head? true}]]
    (testing (str scenario)
      (let [{:keys [events error]} (exercise entry scenario)]
        (is (instance? clojure.lang.ExceptionInfo error))
        (is (empty? (publication-events events)))
        (when (:dirty-before? scenario)
          (is (not-any? #(= "bb" (first %)) events)))))))


(deftest git-failures-stop-the-release
  (doseq [command [["git" "status" "--porcelain" "--untracked-files=all"]
                   ["git" "rev-parse" "HEAD"]
                   ["git" "tag" (str "v" build/version) "checked-head"]
                   ["git" "push" "origin" (str "refs/tags/v" build/version)]]]
    (testing (str command)
      (let [{:keys [events error]} (exercise build/deploy {:fail-command command})]
        (is (= 1 (:exit (ex-data error))))
        (is (= command (last events)))
        (is (not-any? #(= :deploy (first %)) events))))))


(deftest standalone-tag-also-validates-without-deploying
  (let [{:keys [events error]} (exercise build/tag {})]
    (is (nil? error))
    (is (some #{["bb" "test"]} events))
    (is (some #{[:jar]} events))
    (is (= ["git" "push" "origin" (str "refs/tags/v" build/version)] (last events)))
    (is (not-any? #(= :deploy (first %)) events))))


(deftest real-git-output-and-clean-checkout
  (let [dir (str (Files/createTempDirectory "pathling-build-test-" (make-array FileAttribute 0)))]
    (try
      (b/with-project-root dir
        (doseq [command [["git" "init" "--quiet"]
                         ["git" "-c" "user.name=Pathling build test"
                          "-c" "user.email=build-test@example.invalid"
                          "-c" "commit.gpgsign=false" "-c" "core.hooksPath=.git/no-hooks"
                          "commit" "--quiet" "--allow-empty" "-m" "Test fixture"]]]
          (let [result (b/process {:command-args command :out :capture :err :capture})]
            (is (zero? (:exit result)) (pr-str result))))
        (let [revision (#'build/git-output "rev-parse" "HEAD")
              checked (atom 0)]
          (is (re-matches #"[0-9a-f]{40,64}" revision))
          (is (= "" (#'build/git-output "status" "--porcelain" "--untracked-files=all")))
          (is (= revision (#'build/clean-revision)))
          (with-redefs [build/check-release (fn [_] (swap! checked inc))]
            (is (= revision (#'build/prepare-release)))
            (is (= 1 @checked))))
        (let [error (try
                      (#'build/git-output "rev-parse" "--verify" "--quiet" "refs/heads/missing-fixture-ref")
                      nil
                      (catch clojure.lang.ExceptionInfo e e))]
          (is (= 1 (:exit (ex-data error)))))
        (spit (io/file dir "untracked.txt") "pending change")
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Commit or remove working-tree changes"
              (#'build/clean-revision))))
      (finally
        (b/delete {:path dir})))))
