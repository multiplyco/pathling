(ns co.multiply.pathling.util
  "Internal utilities for Pathling. Not part of the public API."
  #?(:cljs (:require-macros co.multiply.pathling.util))
  #?(:clj (:import
            [clojure.lang IEditableCollection Sequential]
            [java.util ArrayList])))


;; # Accumulator
;; ################################################################################
;; Mutable accumulator for collection building.
;; Uses ArrayList on CLJ, JS array on CLJS.

#?(:cljs (defn arr-push!
           [arr v]
           (.push arr v)
           arr))


(defmacro accumulator
  "Create a new mutable accumulator.
   Optionally accepts an initial-size for preallocation when final size is known.
   In ClojureScript, returns a raw JS array (preallocation hint is ignored)."
  ([]
   (if (:ns &env)
     `(cljs.core/array)
     `(ArrayList.)))
  ([initial-size]
   (if (:ns &env)
     `(cljs.core/array)
     `(ArrayList. (unchecked-int ~initial-size)))))


(defmacro acc-append!
  "Append a single value to the accumulator. Mutates in place, returns the accumulator."
  [acc v]
  (if (:ns &env)
    `(doto ~acc (co.multiply.pathling.util/arr-push! ~v))
    `(doto ~acc (ArrayList/.add ~v))))


(defmacro acc-init-append!
  "Append a value to the accumulator, lazily initializing if nil.
   Returns the (possibly new) accumulator."
  [acc v]
  `(acc-append! (or ~acc (accumulator)) ~v))


(defmacro acc-append-many!
  "Append all elements from a sequential collection to the accumulator.
   Mutates in place, returns the accumulator."
  [acc many]
  (if (:ns &env)
    `(reduce co.multiply.pathling.util/arr-push! ~acc ~many)
    `(doto ~acc (ArrayList/.addAll ^Sequential ~many))))


(defmacro acc-get
  "Get element at index position from the accumulator."
  [acc idx]
  (if (:ns &env)
    `(aget ~acc ~idx)
    `(ArrayList/.get ~acc (unchecked-int ~idx))))


(defmacro acc-size
  "Return the number of elements in the accumulator."
  [acc]
  (if (:ns &env)
    `(alength ~acc)
    `(ArrayList/.size ~acc)))


(defn accumulator->vec
  "Convert an accumulator to a persistent vector."
  [acc]
  #?(;; Clojure has a very fast path for turning an ArrayList into a vector
     :clj  (vec acc)
     ;; ClojureScript must iterate over its array
     :cljs (into [] acc)))


(defn accumulator->list
  "Convert an accumulator to a list."
  [acc]
  (apply list acc))


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
