(ns pathling.dense-vector-fixtures
  "Opt-in positional reconstruction cases, including removals and a late nested
   child. Navigation, replacements and independent expected results are prepared
   outside measured update-only work."
  (:require [co.multiply.pathling :as p]
    [pathling.fixtures :as fixtures])
  (:import [java.util ArrayList]))


(def fixture-specs
  [{:id :dense-vector-32 :length 32 :shape :flat :removals :none}
   {:id :dense-vector-33 :length 33 :shape :flat :removals :none}
   {:id :dense-vector-remove-half :length 10000 :shape :flat :removals :alternating}
   {:id :dense-vector-remove-all :length 10000 :shape :flat :removals :all}
   {:id :dense-vector-keep-32 :length 10000 :shape :flat :removals :keep-last-32}
   {:id :dense-vector-late-nested :length 10000 :shape :late-nested :removals :none}])


(def operations [:update-array-list])


(defn- describe
  [x]
  (cond
    (identical? p/REMOVE x) ::remove
    (vector? x) [:vector (.getName (class x)) (meta x) (mapv describe x)]
    :else x))


(defn make-fixture
  [{:keys [length shape removals] :as spec} seed]
  (let [data (with-meta (if (= :late-nested shape)
                         (conj (vec (range (dec length)))
                           (with-meta [(dec length) length] {:source :nested}))
                         (vec (range length)))
               {:source :dense-vector})
        {:keys [matches nodes]} (fixtures/reference-matches data integer? false)
        n (count matches)
        values (mapv (fn [i]
                       (if (case removals
                             :none false
                             :alternating (even? i)
                             :all true
                             :keep-last-32 (< i (- n 32)))
                         p/REMOVE
                         (case (mod i 3) 0 nil 1 false (str "replacement-" i))))
                 (range n))
        by-value (zipmap matches values)
        ;; Independent reconstruction of the original vector tree. Replacements
        ;; are returned as values and are never traversed again.
        expected (letfn [(visit [x]
                           (if (vector? x)
                             (with-meta (into [] (comp (map visit)
                                                      (remove #(identical? p/REMOVE %))) x)
                               (meta x))
                             (by-value x)))]
                   (visit data))
        raw (p/path-when data integer? {:raw-matches true})
        ^ArrayList replacements (:matches raw)]
    (when-not (= matches (vec replacements))
      (throw (ex-info "Incorrect dense-vector fixture navigation" {:spec spec})))
    (doseq [[i value] (map-indexed vector values)] (.set replacements i value))
    {:data data :nav (:nav raw) :replacements replacements :expected expected
     :description (assoc spec :seed seed :match-count n :node-count nodes
                    :sha256 (fixtures/sha256 (pr-str [(describe data) (describe values) (describe expected)])))}))


(defn prepare-cases
  [{:keys [data nav replacements expected description]} selected-operations]
  (mapv (fn [operation]
          (when-not (= :update-array-list operation)
            (throw (ex-info "Unknown dense-vector operation" {:operation operation})))
          {:id [(:id description) operation] :fixture description
           :eligible-matches (:match-count description)
           :f #(p/update-paths data nav replacements)
           :validate (fn [actual]
                       (when-not (and (= expected actual)
                                   (= (describe expected) (describe actual)))
                         (throw (ex-info "Incorrect dense-vector update result"
                                  {:fixture (:id description)}))))})
    selected-operations))
