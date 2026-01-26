(ns co.multiply.pathling.accumulator
  "Mutable accumulator utilities for efficient collection building.
   Uses ArrayList on CLJ, JS array on CLJS."
  #?(:cljs (:require-macros co.multiply.pathling.accumulator))
  #?(:clj (:import [clojure.lang Sequential]
                   [java.util ArrayList])))


(defmacro ^:private if-cljs
  "Helper for switching between ClojureScript and Clojure implementations in macros."
  [then else]
  (if (contains? &env '&env)
    ;; Inside another macro - emit a runtime check
    `(if (:ns ~'&env) ~then ~else)
    ;; Direct use - check now
    (if (:ns &env) then else)))


#?(:cljs (defn arr-push!
           [arr v]
           (.push arr v)
           arr))


(defmacro accumulator
  "Create a new mutable accumulator.
   Optionally accepts an initial-size for preallocation when final size is known.
   In ClojureScript, returns a raw JS array (preallocation hint is ignored)."
  ([]
   (if-cljs
     `(cljs.core/array)
     `(ArrayList.)))
  ([initial-size]
   (if-cljs
     `(cljs.core/array)
     `(ArrayList. (unchecked-int ~initial-size)))))


(defmacro acc-append!
  "Append a single value to the accumulator. Mutates in place, returns the accumulator."
  [acc v]
  (if-cljs
    `(doto ~acc (co.multiply.pathling.accumulator/arr-push! ~v))
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
  (if-cljs
    `(reduce co.multiply.pathling.accumulator/arr-push! ~acc ~many)
    `(doto ~acc (ArrayList/.addAll ^Sequential ~many))))


(defmacro acc-get
  "Get element at index position from the accumulator."
  [acc idx]
  (if-cljs
    `(aget ~acc ~idx)
    `(ArrayList/.get ~acc (unchecked-int ~idx))))


(defmacro acc-set!
  "Set element at index position in the accumulator. Returns the value."
  [acc idx v]
  (if-cljs
    `(doto ~acc (aset ~idx ~v))
    `(doto ~acc (ArrayList/.set  (unchecked-int ~idx) ~v))))


(defmacro acc-size
  "Return the number of elements in the accumulator."
  [acc]
  (if-cljs
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
