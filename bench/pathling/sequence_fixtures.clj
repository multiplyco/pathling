(ns pathling.sequence-fixtures
  "Opt-in sequence updates with scanning and expected-result construction excluded
   from timing. Existing opaque-leaf fixture definitions remain separate."
  (:require [co.multiply.pathling :as p]
    [pathling.fixtures :as fixtures])
  (:import [clojure.lang PersistentQueue]))


(def fixture-specs
  (vec (for [kind [:list :lazy-seq :queue]
             [target length] [[:collection 16] [:collection 1024] [:collection 16384]
                              [:child 1024]]]
         {:id (keyword (str "seq-" (name kind) "-" (name target) "-" length))
          :collection-kind kind :length length :match-target target :match-count 1})))


(def operations [:update-function])


(defn make-fixture
  [{:keys [collection-kind length match-target] :as spec} seed]
  (let [values (vec (range length))
        data (with-meta (case collection-kind
                          :list (apply list values)
                          :lazy-seq (map identity values)
                          :queue (into PersistentQueue/EMPTY values))
               {:source :sequence-update})
        child-index (quot length 2)
        collection? (= :collection match-target)
        pred (if collection? #(identical? data %) #(= child-index %))
        replacement-meta {:updated true}
        replace-match (if collection? #(with-meta % replacement-meta) (constantly :updated-child))
        expected (if collection?
                   (with-meta data replacement-meta)
                   (with-meta (apply list (assoc values child-index :updated-child)) (meta data)))
        {:keys [matches nav]} (p/path-when data pred)]
    (when-not (and (= 1 (count matches)) (some? nav)
               (if collection? (identical? data (first matches)) (= child-index (first matches))))
      (throw (ex-info "Incorrect sequence fixture navigation" {:spec spec})))
    ;; Fingerprinting realizes lazy inputs before timing. Include type, metadata,
    ;; and values so equal collections with different representations differ.
    {:data data :nav nav :replace-match replace-match :expected expected
     :description (assoc spec :seed seed :node-count (inc length)
                    :sha256 (fixtures/sha256
                              (pr-str [(.getName (class data)) (meta data) (vec data)])))}))


(defn prepare-cases
  [{:keys [data nav replace-match expected description]} selected-operations]
  (mapv (fn [operation]
          (when-not (= :update-function operation)
            (throw (ex-info "Unknown sequence operation" {:operation operation})))
          {:id [(:id description) operation] :fixture description :eligible-matches 1
           :f #(p/update-paths data nav replace-match)
           :validate (fn [actual]
                       (when-not (and (= expected actual)
                                   (= (class expected) (class actual))
                                   (= (meta expected) (meta actual)))
                         (throw (ex-info "Incorrect sequence update result" {:fixture (:id description)}))))})
    selected-operations))
