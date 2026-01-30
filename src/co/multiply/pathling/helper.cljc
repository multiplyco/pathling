(ns co.multiply.pathling.helper
  "Internal utilities for Pathling. Not part of the public API."
  #?(:cljs (:require-macros co.multiply.pathling.helper))
  #?(:clj (:import [clojure.lang IEditableCollection])))


;; # Editable check
;; ################################################################################
(defn editable?
  "Check if a collection supports transient operations (implements `IEditableCollection`).
   Regular maps, vectors, and sets return true. Sorted collections and `subvec` return false."
  [v]
  #?(:clj  (instance? IEditableCollection v)
     :cljs (implements? IEditableCollection v)))


;; # Collection utilities
;; ################################################################################
(defmacro forv
  "Like `for`, but returns a vector. No bells and whistles."
  [[bind-to coll] & body]
  `(mapv (fn [~bind-to] ~@body) ~coll))


(defmacro with-transient
  "Execute body with a transient version of the collection, then `persistent!`.
   Preserves metadata from the original collection."
  [[k v] & body]
  `(let [~k ~v
         metadata# (meta ~k)]
     (-> (let [~k (transient ~k)]
           ~@body)
       (persistent!)
       (with-meta metadata#))))


(defn cohorts
  "Gather elements into piles by collecting elements matched by `pred`, and
   all elements succeeding it, until the next match of `pred`.

   The predicate function receives two arguments:

   - `item`: The current element being evaluated
   - `prev-match`: The previous element that matched the predicate (or `nil` for the first match)

   The first entry will contain all items leading up to the first match of `pred`.
   Empty if `pred` is matched on the first element."
  [pred coll]
  (loop [coll           coll
         prev-match     nil
         current-cohort (transient [])
         collector      (transient [])]
    (if-let [item (first coll)]
      (if (pred item prev-match)
        (recur
          (rest coll)
          item
          (transient [item])
          (conj! collector (persistent! current-cohort)))
        (recur
          (rest coll)
          prev-match
          (conj! current-cohort item)
          collector))
      (-> collector
        (conj! (persistent! current-cohort))
        (persistent!)))))


;; # Protocol extension
;; ################################################################################
(defmacro extend-protocol-many
  "Extend a protocol to multiple types with the same implementation.
   Supports multiple type groups, each with their own method implementations.

   Usage:

   ```clojure
   (extend-protocol-many Scannable
     [List EmptyList LazySeq]
     (method1 [this] ...)
     (method2 [this x] ...)

     [PersistentArrayMap PersistentHashMap]
     (method1 [this] ...)
     (method2 [this x] ...))
   ```"
  [protocol & groups]
  (let [chrts (cohorts (fn [v _prev] (vector? v)) groups)]
    `(extend-protocol ~protocol
       ~@(into [] (comp cat cat)
           (forv [[types & impls] chrts]
             (forv [type types]
               `(~type ~@impls)))))))
