(ns pathling.scaling-fixtures
  "Fixed-depth, fixed-layout inputs with zero or one opaque leaf match. Size
   grows by repeating blocks, independently of match count and placement."
  (:require [pathling.fixtures :as fixtures])
  (:import [clojure.lang PersistentHashMap]))


(def operations fixtures/primary-operations)


(def fixture-specs
  (vec (for [family [:array-maps :hash-maps :mixed]
             leaf-count [64 1024 16384]
             match-count [0 1]]
         {:id (keyword (str "scale-" (name family) "-" leaf-count "-" match-count))
          :family family :leaf-count leaf-count :match-count match-count})))


;; Assign leaves in the concrete map's traversal order, so the single match is
;; always the first leaf visited. Keys and map width are identical at every size.
(def ^:private hash-map-keys
  (vec (keys (into PersistentHashMap/EMPTY (map #(vector % nil) (range 16))))))


(defn- hash-block
  [leaves]
  (into PersistentHashMap/EMPTY (map vector hash-map-keys leaves)))


(defn- block
  [family leaves]
  (case family
    :array-maps (into (array-map) (map vector (range 8) leaves))
    :hash-maps (hash-block leaves)
    :mixed (array-map :vector (into [] (subvec leaves 0 4))
             :list (apply list (subvec leaves 4 8))
             :set (set (subvec leaves 8 16))
             :map (hash-block (subvec leaves 16 32)))))


(defn make-fixture
  [{:keys [family leaf-count match-count] :as spec} seed]
  (let [width (case family :array-maps 8 :hash-maps 16 :mixed 32)
        _ (when-not (and (pos-int? leaf-count) (zero? (mod leaf-count width))
                      (#{0 1} match-count))
            (throw (ex-info "Invalid scaling fixture" {:spec spec})))
        one? (= 1 match-count)
        plain-leaves (vec (range leaf-count))
        leaves (if one? (assoc plain-leaves 0 (fixtures/->TaskValue 0)) plain-leaves)
        build (fn [values]
                (mapv #(block family (subvec values % (+ % width)))
                  (range 0 leaf-count width)))
        data (build leaves)
        matches (if one? [(first leaves)] [])
        ;; Construct the expected replacement from the known leaf position,
        ;; independently of both Pathling's navigation and its update routines.
        expected (if one? (build (assoc plain-leaves 0 -1)) data)
        reference (fixtures/reference-matches data fixtures/groundable? false)
        match-path (when one?
                     (case family
                       :array-maps [0 0]
                       :hash-maps [0 (first hash-map-keys)]
                       :mixed [0 :vector 0]))]
    (when-not (= matches (:matches reference))
      (throw (ex-info "Incorrect scaling fixture matches" {:spec spec})))
    {:data data :matches matches :expected expected
     :description (assoc spec :seed seed :layout-version 1
                    :block-width width :block-count (quot leaf-count width)
                    :leaf-depth (if (= :mixed family) 3 2)
                    :match-path match-path :match-leaf-index (when one? 0)
                    :node-count (:nodes reference)
                    :sha256 (fixtures/sha256 (pr-str (fixtures/describe-data data))))}))


(defn prepare-cases
  [{:keys [data matches] :as fixture} selected-operations]
  (when-let [unknown (seq (remove (set operations) selected-operations))]
    (throw (ex-info "Unknown scaling operations" {:operations unknown})))
  (mapv (fn [{:keys [id validate] :as benchmark}]
          (assoc benchmark :validate
            (fn [actual]
              (validate actual)
              (when (and (empty? matches) (not= :path-raw (second id))
                      (not (identical? data actual)))
                (throw (ex-info "No-match operation changed collection identity" {:id id}))))))
    (fixtures/prepare-cases fixture selected-operations)))
