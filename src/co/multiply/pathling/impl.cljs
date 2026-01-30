(ns co.multiply.pathling.impl
  (:require
    [co.multiply.pathling.accumulator :as acc]
    [co.multiply.pathling.helper :as helper :refer [extend-protocol-many]]))


;; # REMOVE sentinel
;; ################################################################################
(def REMOVE
  "Sentinel value that signals removal of an element from its parent collection.
   Return this from an `update-paths` transform function to remove the element.

   Behavior by collection type:

   - Maps: the key-value pair is removed (`dissoc`)
   - Sets: the element is not added to the result
   - Vectors/Lists: the element is removed and indices collapse

   Example:

   ```clojure
   (transform-when data number? #(if (neg? %) REMOVE (inc %)))
   ;; Removes negative numbers, increments positive ones
   ```"
  ::remove)


;; # IReplacer protocol
;; ################################################################################
(defprotocol IReplacer
  "Protocol for computing replacement values during update-paths traversal."
  (-replace [this v]))


(deftype FunctionReplacer [f]
  IReplacer
  (-replace [_ v] (f v)))


(deftype ArrayReplacer [arr ^:mutable idx]
  IReplacer
  (-replace [_ _]
    (let [i idx]
      (set! idx (inc i))
      (aget arr i))))


(deftype VectorReplacer [v ^:mutable idx]
  IReplacer
  (-replace [_ _]
    (let [i idx]
      (set! idx (inc i))
      (nth v i))))


;; # IApplyUpdates protocol
;; ################################################################################
(defprotocol IApplyUpdates
  "Protocol for applying updates through navigation structure"
  (apply-updates [nav data r]))


;; # Shared utilities
;; ################################################################################
(defn vec-remove-indices*
  "Remove elements at specified indices from a vector using subvec splicing.

   Arguments:

   - `v`: the source vector
   - `indices`: an accumulator (ArrayList) of indices to remove, or nil for no removals
   - `cast`: function to convert the result (e.g., `accumulator->vec`)
   - `metadata`: optional metadata to attach to the result

   Constraints (not validated - caller must ensure):

   - indices must be sorted in ascending order
   - indices must be unique (no duplicates)
   - indices must be within bounds `[0, (dec (count v))]`"
  ([v indices cast]
   (vec-remove-indices* v indices cast nil))
  ([v indices cast metadata]
   (if indices
     (let [end          (count v)
           indices      (acc/acc-append! indices end)
           n            (acc/acc-size indices)
           preallocated (acc/accumulator (- end (dec n)))]
       (loop [i   (unchecked-int 1)
              acc (acc/acc-append-many! preallocated
                    (subvec v 0 (acc/acc-get indices 0)))]
         (if (< i n)
           (let [from (inc (acc/acc-get indices (dec i)))
                 to   (acc/acc-get indices i)]
             (recur (unchecked-inc-int i) (acc/acc-append-many! acc (subvec v from to))))
           (with-meta (cast acc) metadata))))
     (with-meta (cast v) metadata))))


;; ################################################################################
;; # CLJS: Clojure-based scanning and navigation
;; ################################################################################
;; On CLJS, we use Clojure deftypes for navigation and protocol-based scanning.


;; ## Navigation types
;; ========================================================================
;; Collection-level: record path through collections
(deftype NavMapEdit [children term])
(deftype NavMapPersistent [children term])
(deftype NavMapStruct [children term])
(deftype NavVecEdit [children term])
(deftype NavVecPersistent [children term])
(deftype NavList [children term])
(deftype NavSetEdit [children term])
(deftype NavSetPersistent [children term])


;; Element-level: record path to specific elements
(deftype NavKey [arg children term])                        ; Map entries
(deftype NavMem [arg children])                             ; Set members
(deftype NavPos [arg children])                             ; Positional (vectors, lists)

;; Terminal: scalar that matched predicate
(deftype NavScalar [])


;; ## Scannable protocol
;; ========================================================================
(defprotocol Scannable
  (path-when [this matches pred opts] "Scan for matches, building navigation.")
  (scan-when [this add-match pred opts] "Scan for matches without navigation. Returns true if stopped early."))


;; ## Map scanning
;; ========================================================================
(defn- path-map
  [this matches pred {:keys [include-keys] :as opts}]
  (loop [remaining  (seq this)
         did-term-k false
         child-navs nil]
    (if-some [[k v] (first remaining)]
      (let [nav    (path-when v matches pred opts)
            term-k (and include-keys (pred k))]
        (when term-k
          (acc/acc-append! matches k))
        (recur (next remaining) (or did-term-k term-k)
          (if (or nav term-k)
            (acc/acc-init-append! child-navs (NavKey. k nav term-k))
            child-navs)))
      (let [pred-res (pred this)]
        (when pred-res
          (acc/acc-append! matches this))
        (cond
          did-term-k (NavMapPersistent. child-navs pred-res)
          child-navs (if (helper/editable? this)
                       (NavMapEdit. child-navs pred-res)
                       (NavMapPersistent. child-navs pred-res))
          pred-res (NavMapEdit. nil pred-res)
          :else nil)))))


(defn- scan-map
  [this add-match pred {:keys [include-keys] :as opts}]
  (loop [remaining (seq this)]
    (if-some [[k v] (first remaining)]
      (if (scan-when v add-match pred opts)
        true                                                ; early termination
        (if (and include-keys (pred k))
          (if (reduced? (add-match k))
            true
            (recur (next remaining)))
          (recur (next remaining))))
      (when (pred this)
        (reduced? (add-match this))))))


(extend-protocol Scannable
  PersistentArrayMap
  (path-when [this matches pred opts] (path-map this matches pred opts))
  (scan-when [this add-match pred opts] (scan-map this add-match pred opts))

  PersistentHashMap
  (path-when [this matches pred opts] (path-map this matches pred opts))
  (scan-when [this add-match pred opts] (scan-map this add-match pred opts))

  PersistentTreeMap
  (path-when [this matches pred opts] (path-map this matches pred opts))
  (scan-when [this add-match pred opts] (scan-map this add-match pred opts))

  ObjMap
  (path-when [this matches pred opts] (path-map this matches pred opts))
  (scan-when [this add-match pred opts] (scan-map this add-match pred opts)))


;; ## Vector scanning
;; ========================================================================
(defn- path-vector
  [this matches pred opts]
  (let [this-count (count this)]
    (loop [idx        0
           child-navs nil]
      (if (< idx this-count)
        (let [nav (path-when (nth this idx) matches pred opts)]
          (recur (inc idx)
            (if nav
              (acc/acc-init-append! child-navs (NavPos. idx nav))
              child-navs)))
        (let [pred-res (pred this)]
          (when pred-res
            (acc/acc-append! matches this))
          (cond
            child-navs (if (helper/editable? this)
                         (NavVecEdit. child-navs pred-res)
                         (NavVecPersistent. child-navs pred-res))
            pred-res (NavVecEdit. nil pred-res)
            :else nil))))))


(defn- scan-vector
  [this add-match pred opts]
  (let [this-count (count this)]
    (loop [idx 0]
      (if (< idx this-count)
        (if (scan-when (nth this idx) add-match pred opts)
          true
          (recur (inc idx)))
        (when (pred this)
          (reduced? (add-match this)))))))


(extend-protocol Scannable
  PersistentVector
  (path-when [this matches pred opts] (path-vector this matches pred opts))
  (scan-when [this add-match pred opts] (scan-vector this add-match pred opts))

  Subvec
  (path-when [this matches pred opts] (path-vector this matches pred opts))
  (scan-when [this add-match pred opts] (scan-vector this add-match pred opts)))


;; ## Sequential scanning (lists, lazy seqs, etc.)
;; ========================================================================
(defn- path-sequential
  [this matches pred opts]
  (loop [idx        0
         remaining  (seq this)
         child-navs nil]
    (if-some [[elem & more] remaining]
      (let [nav (path-when elem matches pred opts)]
        (recur (inc idx) more
          (if nav
            (acc/acc-init-append! child-navs (NavPos. idx nav))
            child-navs)))
      (let [pred-res (pred this)]
        (when pred-res
          (acc/acc-append! matches this))
        (cond
          child-navs (NavList. child-navs pred-res)
          pred-res (NavList. nil pred-res)
          :else nil)))))


(defn- scan-sequential
  [this add-match pred opts]
  (loop [remaining (seq this)]
    (if-some [[elem & more] remaining]
      (if (scan-when elem add-match pred opts)
        true
        (recur more))
      (when (pred this)
        (reduced? (add-match this))))))


(extend-protocol-many Scannable
  [List EmptyList LazySeq IndexedSeq RSeq Cons ChunkedCons ChunkedSeq Range Repeat Cycle Iterate]
  (path-when [this matches pred opts] (path-sequential this matches pred opts))
  (scan-when [this add-match pred opts] (scan-sequential this add-match pred opts)))


;; ## Set scanning
;; ========================================================================
(defn- path-set
  [this matches pred opts]
  (loop [remaining  (seq this)
         child-navs nil]
    (if-some [[elem & more] remaining]
      (let [nav (path-when elem matches pred opts)]
        (recur more
          (if nav
            (acc/acc-init-append! child-navs (NavMem. elem nav))
            child-navs)))
      (let [pred-res (pred this)]
        (when pred-res
          (acc/acc-append! matches this))
        (cond
          child-navs (if (helper/editable? this)
                       (NavSetEdit. child-navs pred-res)
                       (NavSetPersistent. child-navs pred-res))
          pred-res (NavSetEdit. nil pred-res)
          :else nil)))))


(defn- scan-set
  [this add-match pred opts]
  (loop [remaining (seq this)]
    (if-some [[elem & more] remaining]
      (if (scan-when elem add-match pred opts)
        true
        (recur more))
      (when (pred this)
        (reduced? (add-match this))))))


(extend-protocol Scannable
  PersistentHashSet
  (path-when [this matches pred opts] (path-set this matches pred opts))
  (scan-when [this add-match pred opts] (scan-set this add-match pred opts))

  PersistentTreeSet
  (path-when [this matches pred opts] (path-set this matches pred opts))
  (scan-when [this add-match pred opts] (scan-set this add-match pred opts)))


;; ## Scalar scanning
;; ========================================================================
(defn- path-scalar
  [this matches pred _opts]
  (when (pred this)
    (acc/acc-append! matches this)
    (NavScalar.)))


(defn- scan-scalar
  [this add-match pred]
  (when (pred this)
    (reduced? (add-match this))))


(extend-protocol Scannable
  nil
  (path-when [this matches pred opts] (path-scalar this matches pred opts))
  (scan-when [this add-match pred _opts] (scan-scalar this add-match pred))

  default
  (path-when [this matches pred opts] (path-scalar this matches pred opts))
  (scan-when [this add-match pred _opts] (scan-scalar this add-match pred)))


;; ## IApplyUpdates implementations for CLJS nav types
;; ========================================================================
(defn- strip-nav-keys-from-map
  [m nav-keys]
  (let [n (acc/acc-size nav-keys)]
    (loop [i 0, m m]
      (if (< i n)
        (let [^NavKey nav-key (acc/acc-get nav-keys i)]
          (recur (inc i) (dissoc m (.-arg nav-key))))
        m))))


(defn- strip-nav-mems-from-set!
  [s nav-mems]
  (let [n (acc/acc-size nav-mems)]
    (loop [i 0, s s]
      (if (< i n)
        (let [^NavMem nav-mem (acc/acc-get nav-mems i)]
          (recur (inc i) (disj! s (.-arg nav-mem))))
        s))))


(defn- strip-nav-mems-from-set
  [s nav-mems]
  (let [n (acc/acc-size nav-mems)]
    (loop [i 0, s s]
      (if (< i n)
        (let [^NavMem nav-mem (acc/acc-get nav-mems i)]
          (recur (inc i) (disj s (.-arg nav-mem))))
        s))))


(extend-type NavMapEdit
  IApplyUpdates
  (apply-updates [this data r]
    (let [children (.-children this)
          term     (.-term this)
          updated  (if children
                     (helper/with-transient [m data]
                       (let [n (acc/acc-size children)]
                         (loop [i 0, m m]
                           (if (< i n)
                             (let [^NavKey nav-key (acc/acc-get children i)
                                   k               (.-arg nav-key)
                                   child-nav       (.-children nav-key)
                                   result          (apply-updates child-nav (get data k) r)]
                               (recur (inc i)
                                 (if (identical? result REMOVE)
                                   (dissoc! m k)
                                   (assoc! m k result))))
                             m))))
                     data)]
      (if term (-replace r updated) updated))))


(extend-type NavMapPersistent
  IApplyUpdates
  (apply-updates [this data r]
    (let [children (.-children this)
          term     (.-term this)
          updated  (if children
                     (let [n (acc/acc-size children)]
                       (loop [i 0, m (strip-nav-keys-from-map data children)]
                         (if (< i n)
                           (let [^NavKey nav-key (acc/acc-get children i)
                                 old-k           (.-arg nav-key)
                                 new-k           (if (.-term nav-key) (-replace r old-k) old-k)
                                 child-nav       (.-children nav-key)
                                 result          (if child-nav
                                                   (apply-updates child-nav (get data old-k) r)
                                                   (get data old-k))]
                             (recur (inc i)
                               (if (or (identical? new-k REMOVE) (identical? result REMOVE))
                                 m
                                 (assoc m new-k result))))
                           m)))
                     data)]
      (if term (-replace r updated) updated))))


(extend-type NavMapStruct
  IApplyUpdates
  (apply-updates [this data r]
    (let [children (.-children this)
          term     (.-term this)
          updated  (if children
                     (let [n (acc/acc-size children)]
                       (loop [i 0, m data]
                         (if (< i n)
                           (let [^NavKey nav-key (acc/acc-get children i)
                                 k               (.-arg nav-key)
                                 child-nav       (.-children nav-key)
                                 result          (apply-updates child-nav (get data k) r)]
                             (recur (inc i)
                               (if (identical? result REMOVE)
                                 (dissoc m k)
                                 (assoc m k result))))
                           m)))
                     data)]
      (if term (-replace r updated) updated))))


(extend-type NavVecEdit
  IApplyUpdates
  (apply-updates [this data r]
    (let [children (.-children this)
          term     (.-term this)
          updated  (if children
                     (let [n (acc/acc-size children)]
                       (loop [i 0, v (transient data), removals nil]
                         (if (< i n)
                           (let [^NavPos nav-pos (acc/acc-get children i)
                                 idx             (.-arg nav-pos)
                                 child-nav       (.-children nav-pos)
                                 result          (apply-updates child-nav (get data idx) r)]
                             (if (identical? result REMOVE)
                               (recur (inc i) v (acc/acc-init-append! removals idx))
                               (recur (inc i) (assoc! v idx result) removals)))
                           (vec-remove-indices* (persistent! v) removals acc/accumulator->vec (meta data)))))
                     data)]
      (if term (-replace r updated) updated))))


(extend-type NavVecPersistent
  IApplyUpdates
  (apply-updates [this data r]
    (let [children (.-children this)
          term     (.-term this)
          updated  (if children
                     (let [n (acc/acc-size children)]
                       (loop [i 0, v data, removals nil]
                         (if (< i n)
                           (let [^NavPos nav-pos (acc/acc-get children i)
                                 idx             (.-arg nav-pos)
                                 child-nav       (.-children nav-pos)
                                 result          (apply-updates child-nav (get v idx) r)]
                             (if (identical? result REMOVE)
                               (recur (inc i) v (acc/acc-init-append! removals idx))
                               (recur (inc i) (assoc v idx result) removals)))
                           (vec-remove-indices* v removals acc/accumulator->vec (meta data)))))
                     data)]
      (if term (-replace r updated) updated))))


(extend-type NavList
  IApplyUpdates
  (apply-updates [this data r]
    (let [children (.-children this)
          term     (.-term this)
          updated  (if children
                     (let [data-vec (vec data)
                           n        (acc/acc-size children)
                           [v removals]
                           (loop [i 0, v (transient data-vec), removals nil]
                             (if (< i n)
                               (let [^NavPos nav-pos (acc/acc-get children i)
                                     idx             (.-arg nav-pos)
                                     child-nav       (.-children nav-pos)
                                     result          (apply-updates child-nav (get data-vec idx) r)]
                                 (if (identical? result REMOVE)
                                   (recur (inc i) v (acc/acc-init-append! removals idx))
                                   (recur (inc i) (assoc! v idx result) removals)))
                               [(persistent! v) removals]))]
                       (vec-remove-indices* v removals acc/accumulator->list (meta data)))
                     data)]
      (if term (-replace r updated) updated))))


(extend-type NavSetEdit
  IApplyUpdates
  (apply-updates [this data r]
    (let [children (.-children this)
          term     (.-term this)
          updated  (if children
                     (helper/with-transient [s data]
                       (let [n (acc/acc-size children)]
                         (loop [i 0, s (strip-nav-mems-from-set! s children)]
                           (if (< i n)
                             (let [^NavMem nav-mem (acc/acc-get children i)
                                   result          (apply-updates (.-children nav-mem) (.-arg nav-mem) r)]
                               (recur (inc i)
                                 (if (identical? result REMOVE)
                                   s
                                   (conj! s result))))
                             s))))
                     data)]
      (if term (-replace r updated) updated))))


(extend-type NavSetPersistent
  IApplyUpdates
  (apply-updates [this data r]
    (let [children (.-children this)
          term     (.-term this)
          updated  (if children
                     (let [n (acc/acc-size children)]
                       (loop [i 0, s (strip-nav-mems-from-set data children)]
                         (if (< i n)
                           (let [^NavMem nav-mem (acc/acc-get children i)
                                 result          (apply-updates (.-children nav-mem) (.-arg nav-mem) r)]
                             (recur (inc i)
                               (if (identical? result REMOVE)
                                 s
                                 (conj s result))))
                           s)))
                     data)]
      (if term (-replace r updated) updated))))


(extend-type NavScalar
  IApplyUpdates
  (apply-updates [_ data r]
    (-replace r data)))


;; ## Public API for find-when
;; ========================================================================
(defn matches-when
  "Scan a data structure for values matching the predicate.
   Returns vector of matches or `nil` if no matches.

   Supports transducers via `:xf` option for transformation and early termination."
  ([data pred]
   (matches-when data pred nil))
  ([data pred xf-or-opts]
   (let [opts      (if (map? xf-or-opts) xf-or-opts {:xf xf-or-opts})
         xf        (:xf opts)
         matches   (acc/accumulator)
         step-fn   (fn step
                     ([acc] acc)
                     ([acc x] (acc/acc-append! acc x)))
         rf        (if xf (xf step-fn) step-fn)
         add-match (fn [x] (rf matches x))]
     (scan-when data add-match pred opts)
     (rf matches)
     (when-not (zero? (acc/acc-size matches))
       (acc/accumulator->vec matches)))))
