(ns pathling.fixtures
  "Deterministic JVM workloads. Synthetic opaque values model Quiescent's
   protocol predicate without depending on its scheduler or on Quiescent itself."
  (:require [clojure.walk :as walk]
    [co.multiply.pathling :as p])
  (:import [java.security MessageDigest]
    [java.util ArrayList Collections Random]))


(defprotocol Groundable
  (groundable? [x]))


(deftype TaskValue [^long id]
  Object
  (equals [_ other]
    (and (instance? TaskValue other) (= id (.-id ^TaskValue other))))
  (hashCode [_] (Long/hashCode id))
  clojure.lang.IHashEq
  (hasheq [_] (hash id)))


;; External protocol extension matches how Quiescent detects task values.
(extend-protocol Groundable
  TaskValue (groundable? [_] true)
  Object (groundable? [_] false)
  nil (groundable? [_] false))


(defn resolve-value
  [^TaskValue x]
  (- -1 (.-id x)))


(def fixture-specs
  [{:id :scalar-empty :shape :mixed :depth 0 :breadth 1 :match-count 0}
   {:id :scalar-one :shape :mixed :depth 0 :breadth 1 :match-count 1}
   {:id :small-empty :shape :mixed :depth 3 :breadth 3 :match-count 0}
   {:id :small-one :shape :mixed :depth 3 :breadth 3 :match-count 1}
   {:id :small-many :shape :mixed :depth 3 :breadth 3 :match-count 27}
   {:id :large-sparse :shape :mixed :depth 5 :breadth 5 :match-count 31}
   {:id :large-dense :shape :mixed :depth 5 :breadth 5 :match-count 1562}
   {:id :wide-many :shape :vector :depth 1 :breadth 10000 :match-count 10000}
   {:id :deep-one :shape :vector :depth 128 :breadth 1 :match-count 1}
   {:id :hash-map-sparse :shape :map :depth 2 :breadth 16 :match-count 8}])


(defn sha256
  [^String s]
  (apply str (map #(format "%02x" (bit-and 0xff %))
               (.digest (MessageDigest/getInstance "SHA-256") (.getBytes s "UTF-8")))))


(defn describe-data
  "EDN representation including collection types and their iteration order."
  [x]
  (cond
    (instance? TaskValue x) [:task (.-id ^TaskValue x)]
    (map? x) [:map (.getName (class x))
              (mapv (fn [[k v]] [(describe-data k) (describe-data v)]) x)]
    (vector? x) [:vector (mapv describe-data x)]
    (set? x) [:set (mapv describe-data x)]
    (seq? x) [:seq (mapv describe-data x)]
    :else [:scalar x]))


(defn reference-matches
  "Independent value-first, postorder traversal. Map keys are tested as whole
   values only when requested, as in Pathling's API. Used outside measurements."
  [data pred include-keys?]
  (let [found (ArrayList.)
        nodes (volatile! 0)]
    (letfn [(visit
              [x]
              (vswap! nodes inc)
              (cond
                (map? x) (doseq [[k v] x]
                           (visit v)
                           (when include-keys?
                             (vswap! nodes inc)
                             (when (pred k) (.add found k))))
                (coll? x) (doseq [v x] (visit v)))
              (when (pred x) (.add found x)))]
      (visit data)
      {:matches (vec found) :nodes @nodes})))


(defn make-fixture
  [{:keys [shape depth breadth match-count] :as spec} seed]
  (let [rng (Random. (long seed))
        leaf-count (reduce * 1 (repeat depth breadth))
        ids (ArrayList. ^java.util.Collection (vec (range leaf-count)))
        _ (Collections/shuffle ids rng)
        selected (set (take match-count ids))
        cursor (volatile! -1)
        build (fn build
                [d]
                (if (zero? d)
                  (let [id (vswap! cursor inc)]
                    (if (contains? selected id) (TaskValue. id) (str "value-" id)))
                  (let [kind (if (= shape :mixed)
                               (nth [:vector :map :set :list] (.nextInt rng 4))
                               shape)
                        children (mapv (fn [_] (build (dec d))) (range breadth))]
                    (case kind
                      :vector children
                      :map (into (array-map)
                             (map-indexed (fn [i v] [(keyword (str "k" i)) v]) children))
                      :set (set children)
                      :list (apply list children)))))
        data (build depth)
        {:keys [matches nodes]} (reference-matches data groundable? false)
        ;; Only opaque leaves match: postwalk is an independent update oracle
        ;; for these fixtures (it is not a general Pathling semantic oracle).
        expected (walk/postwalk #(if (groundable? %) (resolve-value %) %) data)]
    (when-not (= match-count (count matches))
      (throw (ex-info "Fixture lost or gained matches" {:spec spec :actual (count matches)})))
    {:data data :matches matches :expected expected
     :description (assoc spec :seed seed :node-count nodes
                    :sha256 (sha256 (pr-str (describe-data data))))}))


(def primary-operations [:path-raw :update-array-list :raw-roundtrip])


(def secondary-operations
  [:path-vector :update-vector :update-function :find :find-xf :find-take
   :transform :path-keys :find-keys :find-keys-xf])


(def comparison-operations [:find :postwalk-find :transform :postwalk-transform])


(def all-operations
  (vec (distinct (concat primary-operations secondary-operations comparison-operations))))


(defn- postwalk-find
  [data]
  (let [matches (volatile! [])]
    (walk/postwalk (fn [x]
                     (when (groundable? x) (vswap! matches conj x))
                     x)
      data)
    @matches))


(defn- fail!
  [message details]
  (throw (ex-info message details)))


(defn prepare-cases
  "Construct operations and independent validators. Scanning and replacement
   preparation for update-only cases happen here, outside measured functions."
  [{:keys [data matches expected description]} operations]
  (let [{:keys [nav] :as raw} (p/path-when data groundable? {:raw-matches true})
        ;; The same raw accumulator is filled with synthetic completed results,
        ;; then reused read-only by update-paths, as after Quiescent completion.
        ^ArrayList replacements (:matches raw)
        resolved (mapv resolve-value matches)
        pred-keys #(or (groundable? %) (keyword? %))
        resolve-key-or-value #(if (groundable? %) (resolve-value %) (name %))
        keyed-matches (:matches (reference-matches data pred-keys true))
        keyed-expected (walk/postwalk #(if (pred-keys %) (resolve-key-or-value %) %) data)
        path-validator
        (fn [wanted raw? keyed? result]
          (when-not (and (= wanted (vec (:matches result)))
                      (= (empty? wanted) (nil? result))
                      (or (empty? wanted)
                        (and (some? (:nav result))
                          (if raw?
                            (instance? ArrayList (:matches result))
                            (vector? (:matches result))))))
            (fail! "Incorrect path result" {:fixture (:id description)}))
          (when-not (= (if keyed? keyed-expected expected)
                       (p/update-paths data (:nav result)
                         (if keyed? resolve-key-or-value resolve-value)))
            (fail! "Incorrect navigation in path result" {:fixture (:id description)})))
        equal-validator
        (fn [wanted actual]
          (when-not (= wanted actual)
            (fail! "Incorrect benchmark result" {:fixture (:id description)})))
        _ (path-validator matches true false raw)
        _ (when replacements
            (dotimes [i (.size replacements)] (.set replacements i (nth resolved i))))
        _ (equal-validator expected (p/update-paths data nav replacements))
        roundtrip
        (fn []
          (if-let [{:keys [matches nav]} (p/path-when data groundable? {:raw-matches true})]
            (let [^ArrayList acc matches]
              (dotimes [i (.size acc)]
                (.set acc i (resolve-value (.get acc i))))
              (p/update-paths data nav acc))
            data))
        entries
        {:path-raw [#(p/path-when data groundable? {:raw-matches true})
                    #(path-validator matches true false %)]
         :update-array-list [#(p/update-paths data nav replacements)
                             #(equal-validator expected %)]
         :raw-roundtrip [roundtrip #(equal-validator expected %)]
         :path-vector [#(p/path-when data groundable?) #(path-validator matches false false %)]
         :update-vector [#(p/update-paths data nav resolved) #(equal-validator expected %)]
         :update-function [#(p/update-paths data nav resolve-value) #(equal-validator expected %)]
         :find [#(p/find-when data groundable?) #(equal-validator (not-empty matches) %)]
         :find-xf [#(p/find-when data groundable? (map resolve-value))
                   #(equal-validator (not-empty resolved) %)]
         :find-take [#(p/find-when data groundable? (take 1))
                     #(equal-validator (not-empty (vec (take 1 matches))) %)]
         :transform [#(p/transform-when data groundable? resolve-value)
                     #(equal-validator expected %)]
         :postwalk-find [#(postwalk-find data) #(equal-validator matches %)]
         :postwalk-transform [#(walk/postwalk (fn [x] (if (groundable? x) (resolve-value x) x)) data)
                              #(equal-validator expected %)]
         :path-keys [#(p/path-when data pred-keys {:include-keys true :raw-matches true})
                     #(path-validator keyed-matches true true %)]
         :find-keys [#(p/find-when data pred-keys {:include-keys true})
                     #(equal-validator (not-empty keyed-matches) %)]
         :find-keys-xf [#(p/find-when data pred-keys {:include-keys true :xf (take 1)})
                        #(equal-validator (not-empty (vec (take 1 keyed-matches))) %)]}]
    (mapv (fn [op]
            (let [[f validate] (or (entries op)
                                 (fail! "Unknown operation" {:operation op}))]
              {:id [(:id description) op] :fixture description
               :eligible-matches (count (if (#{:path-keys :find-keys :find-keys-xf} op)
                                          keyed-matches matches))
               :f f :validate validate}))
      operations)))
