(ns build
  (:require
    [clojure.string :as str]
    [clojure.test :as test]
    [clojure.tools.build.api :as b]
    [deps-deploy.deps-deploy :as deploy]))


(def lib 'co.multiply/pathling)
(def version "1.0.0")
(def class-dir "target/classes")
(def jar-file (format "target/%s-%s.jar" (name lib) version))
(def basis (delay (b/create-basis {:project "deps.edn"})))


(defn clean
  [_]
  (b/delete {:path "target"}))


(defn compile-java
  "Compile Java sources. Requires Java 21+."
  [_]
  (b/javac {:src-dirs   ["src"]
            :class-dir  class-dir
            :basis      @basis
            :javac-opts ["--release" "21"]}))


(def scm-url "https://github.com/multiplyco/pathling")


(defn jar
  [_]
  (clean nil)
  (compile-java nil)
  (b/write-pom {:class-dir class-dir
                :lib       lib
                :version   version
                :basis     @basis
                :src-dirs  ["src"]
                :scm       {:url                 scm-url
                            :connection          (str "scm:git:" scm-url)
                            :developerConnection (str "scm:git:" scm-url)
                            :tag                 (str "v" version)}
                :pom-data  [[:licenses
                             [:license
                              [:name "Eclipse Public License 2.0"]
                              [:url "https://www.eclipse.org/legal/epl-2.0/"]]]]})
  (b/copy-dir {:src-dirs   ["src"]
               :target-dir class-dir})
  (b/jar {:class-dir class-dir
          :jar-file  jar-file})
  (println "Built:" jar-file))


(defn install
  [_]
  (jar nil)
  (b/install {:basis     @basis
              :lib       lib
              :version   version
              :jar-file  jar-file
              :class-dir class-dir})
  (println "Installed:" lib version))


(defn version-str
  "Print the current version string."
  [_]
  (println version))


(defn test-build
  "Test release orchestration without publishing or changing this checkout's Git refs."
  [_]
  (load-file "build-test/build_test.clj")
  (let [{:keys [fail error] :as result} (test/run-tests 'build-test)]
    (when (pos? (+ fail error))
      (throw (ex-info "Release workflow tests failed" result)))))


(defn- process!
  [opts]
  (let [{:keys [exit] :as result} (b/process opts)]
    (when-not (zero? exit)
      (throw (ex-info "Release command failed"
               {:command (:command-args opts) :exit exit})))
    result))


(defn- git-output
  [& args]
  (let [{:keys [out]} (process! {:command-args (into ["git"] args)
                                 :out :capture})]
    ;; tools.build omits :out for empty stdout, including a clean git status.
    (str/trim (or out ""))))


(defn check-release
  "Run correctness and formatting checks, then build the release JAR.
   Does not publish or change Git refs; uncommitted changes can be checked."
  [_]
  (doseq [task ["format:check" "test" "bench:check"]]
    (process! {:command-args ["bb" task]}))
  (jar nil))


(defn- clean-revision
  []
  (when-not (str/blank? (git-output "status" "--porcelain" "--untracked-files=all"))
    (throw (ex-info "Commit or remove working-tree changes before releasing" {})))
  (git-output "rev-parse" "HEAD"))


(defn- prepare-release
  []
  (let [revision (clean-revision)]
    (check-release nil)
    (when-not (= revision (clean-revision))
      (throw (ex-info "HEAD changed during release checks; run them again" {})))
    revision))


(defn- push-tag
  [revision]
  (let [tag (str "v" version)]
    (process! {:command-args ["git" "tag" tag revision]})
    (process! {:command-args ["git" "push" "origin" (str "refs/tags/" tag)]})
    (println "Tagged and pushed:" tag)))


(defn tag
  "Validate and build from a clean checkout, then create and push the version tag."
  [_]
  (push-tag (prepare-release)))


(defn deploy
  "Validate and build, push the version tag, and deploy the checked JAR to Clojars."
  [_]
  (push-tag (prepare-release))
  (deploy/deploy {:installer  :remote
                  :artifact   jar-file
                  :pom-file   (str class-dir "/META-INF/maven/co.multiply/pathling/pom.xml")
                  :repository {"clojars" {:url      "https://clojars.org/repo"
                                          :username (System/getenv "CLOJARS_DEPLOY_MAVEN_USERNAME")
                                          :password (System/getenv "CLOJARS_DEPLOY_MAVEN_PASSWORD")}}}))
