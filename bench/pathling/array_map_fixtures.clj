(ns pathling.array-map-fixtures
  "Ordinary map construction around array-map promotion boundaries. Descriptions
   record actual runtime collection types; compare implementations within each
   runtime, since promotion and traversal order can differ across runtimes."
  (:require [pathling.fixtures :as fixtures])
  (:import [clojure.lang PersistentHashMap]))


(def operations fixtures/primary-operations)


(def fixture-specs
  (vec (for [key-kind [:keywords :mixed :hash]
             width (if (= :hash key-kind) [8 64] [1 8 9 16 32 63 64 65])
             density [:none :one :all]]
         {:id (keyword (str "am-" (name key-kind) "-" width "-" (name density)))
          :key-kind key-kind :width width :density density :block-count 128})))


(defn- key-at [kind i]
  (if (= :mixed kind)
    (case i 0 nil 1 false (if (even? i) (str "key-" i) i))
    (keyword (str "k" i))))


(defn make-fixture
  [{:keys [key-kind width density block-count] :as spec} seed]
  (when-not (and (#{:keywords :mixed :hash} key-kind) (pos-int? width)
             (#{:none :one :all} density) (pos-int? block-count))
    (throw (ex-info "Invalid array-map fixture" {:spec spec})))
  (let [leaf-count (* width block-count)
        selected? (case density :none (constantly false) :one #(= % (dec leaf-count)) :all (constantly true))
        build (fn [resolve?]
                (with-meta
                  (mapv (fn [block]
                          (with-meta
                            (into (if (= :hash key-kind) PersistentHashMap/EMPTY {})
                              (map (fn [i]
                                     (let [id (+ (* block width) i)]
                                       [(key-at key-kind i)
                                        (if (selected? id)
                                          (if resolve? (- -1 id) (fixtures/->TaskValue id))
                                          id)]))) (range width))
                            {:block block}))
                    (range block-count)) {:source :array-map-fixture}))
        data (build false)
        ;; Expected leaf values come from their construction IDs, not Pathling.
        expected (if (= :none density) data (build true))
        {:keys [matches nodes]} (fixtures/reference-matches data fixtures/groundable? false)
        wanted-count (case density :none 0 :one 1 :all leaf-count)
        classes (into (sorted-map) (frequencies (map #(.getName (class %)) data)))]
    (when-not (and (= wanted-count (count matches)) (every? #(= width (count %)) data))
      (throw (ex-info "Incorrect array-map fixture construction" {:spec spec})))
    {:data data :matches matches :expected expected
     :description (assoc spec :seed seed :layout-version 1 :construction :into
                    :leaf-count leaf-count :match-count wanted-count :node-count nodes
                    :map-classes classes
                    :sha256 (fixtures/sha256 (pr-str [(fixtures/describe-data data)
                                                     (fixtures/describe-data expected)])))}))


(defn prepare-cases
  [{:keys [data matches] :as fixture} selected-operations]
  (when-let [unknown (seq (remove (set operations) selected-operations))]
    (throw (ex-info "Unknown array-map operations" {:operations unknown})))
  (mapv (fn [{:keys [id validate] :as benchmark}]
          (assoc benchmark :validate
            (fn [actual]
              (validate actual)
              (when-not (= :path-raw (second id))
                (when-not (and (= (meta data) (meta actual))
                           (= (mapv meta data) (mapv meta actual))
                           (or (seq matches) (identical? data actual)))
                  (throw (ex-info "Array-map metadata or identity changed" {:id id})))))))
    (fixtures/prepare-cases fixture selected-operations)))
