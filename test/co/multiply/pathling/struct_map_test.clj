(ns co.multiply.pathling.struct-map-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [clojure.xml :as xml]
    [co.multiply.pathling :as p]
    [co.multiply.pathling.accumulator :as acc])
  (:import [clojure.lang PersistentStructMap]
    [java.io ByteArrayInputStream]
    [java.util.concurrent CountDownLatch TimeUnit]))


(def basis (create-struct :a :b))
(def read-a (accessor basis :a))


(defn- example
  []
  (with-meta (assoc (struct-map basis :a 1 :b 2) :extra 3) {:source :struct}))


(deftest struct-discovery-and-identity-roundtrips
  (doseq [include-keys [false true]
          raw? [false true]
          pred [number? keyword? (constantly true)]]
    (let [data (example)
          opts {:include-keys include-keys :raw-matches raw?}
          {:keys [matches nav]} (p/path-when data pred opts)
          values (if raw? (some-> matches acc/accumulator->vec) matches)]
      (is (= values (p/find-when data pred opts)))
      (is (= values (p/find-when data pred (assoc opts :xf (map identity)))))
      (doseq [updated [(p/update-paths data nav identity)
                       (p/update-paths data nav matches)
                       (p/transform-when data pred identity opts)]]
        (is (= data updated))
        (is (instance? PersistentStructMap updated))
        (is (= 1 (read-a updated)))
        (is (= (meta data) (meta updated)))))))


(deftest value-updates-and-extension-edits-preserve-the-struct
  (let [data (example)]
    (doseq [include-keys [false true]]
      (let [opts {:include-keys include-keys}
            {:keys [nav]} (p/path-when data number? opts)]
        (doseq [updated [(p/update-paths data nav inc)
                         (p/update-paths data nav [2 3 4])
                         (p/transform-when data number? inc opts)]]
          (is (= {:a 2 :b 3 :extra 4} updated))
          (is (instance? PersistentStructMap updated))
          (is (= 2 (read-a updated)))
          (is (= (meta data) (meta updated))))
        (doseq [[values expected converted?]
                [[[10 p/REMOVE 30] {:a 10 :extra 30} true]
                 [[10 20 p/REMOVE] {:a 10 :b 20} false]
                 [[p/REMOVE p/REMOVE p/REMOVE] {} true]]]
          (let [updated (p/update-paths data nav values)]
            (is (= expected updated))
            (is (= converted? (not (instance? PersistentStructMap updated))))
            (is (= (meta data) (meta updated)))))))
    (doseq [[pred tf expected]
            [[#{:extra} (constantly :renamed) {:a 1 :b 2 :renamed 3}]
             [#{:extra} (constantly p/REMOVE) {:a 1 :b 2}]
             [#{3} (constantly p/REMOVE) {:a 1 :b 2}]
             [#{:extra} (constantly :a) {:a 3 :b 2}]]]
      (let [opts {:include-keys true}
            {:keys [nav]} (p/path-when data pred opts)]
        (doseq [updated [(p/update-paths data nav tf)
                         (p/transform-when data pred tf opts)]]
          (is (= expected updated))
          (is (instance? PersistentStructMap updated))
          (is (= (:a expected) (read-a updated)))
          (is (= (meta data) (meta updated))))))
    (is (identical? data (p/transform-when data (constantly false) identity)))))


(deftest base-key-edits-convert-only-the-affected-struct
  (let [data (example)
        untouched (example)
        opts {:include-keys true}]
    (doseq [[pred tf expected]
            [[#{:a} (constantly :renamed) {:renamed 1 :b 2 :extra 3}]
             [#{:a} (constantly p/REMOVE) {:b 2 :extra 3}]
             [#{1} (constantly p/REMOVE) {:b 2 :extra 3}]]]
      (let [{:keys [matches nav]} (p/path-when data pred opts)]
        (doseq [updated [(p/update-paths data nav tf)
                         (p/update-paths data nav (mapv tf matches))
                         (p/transform-when data pred tf opts)]]
          (is (= expected updated))
          (is (not (instance? PersistentStructMap updated)))
          (is (= (meta data) (meta updated))))))
    (let [input [data untouched]
          {:keys [nav]} (p/path-when input #(identical? data %) opts)
          output (p/update-paths input nav (constantly p/REMOVE))]
      (is (= [untouched] output))
      (is (identical? untouched (first output))))
    (is (= {:a 1 :b 2 :extra 3} data))))


(deftest struct-key-swaps-collisions-and-removals-share-one-cursor
  (let [data (example)
        input [data :tail]
        opts {:include-keys true :raw-matches true}]
    (doseq [[replacements expected converted?]
            [[[10 :b 20 :a 30 :extra :end] {:b 10 :a 20 :extra 30} true]
             [[10 :same 20 :same 30 :same :end] {:same 30} true]
             [[p/REMOVE :a 20 :b 30 :extra :end] {:b 20 :extra 30} true]
             [[10 :a 20 :b p/REMOVE :extra :end] {:a 10 :b 20} false]
             [[10 :b p/REMOVE :b 30 :extra :end] {:b 10 :extra 30} true]]]
      (let [{:keys [matches nav]} (p/path-when input #(or (keyword? %) (number? %)) opts)]
        (is (= [1 :a 2 :b 3 :extra :tail] (acc/accumulator->vec matches)))
        (doseq [[i value] (map-indexed vector replacements)]
          (acc/acc-set! matches i value))
        (dotimes [_ 2]
          (let [[updated tail] (p/update-paths input nav matches)]
            (is (= expected updated))
            (is (= :end tail))
            (is (= converted? (not (instance? PersistentStructMap updated))))
            (is (= (meta data) (meta updated)))))
        (is (thrown? IndexOutOfBoundsException
              (p/update-paths input nav (pop replacements))))))
    (let [{:keys [nav]} (p/path-when data number?)
          seen (atom 0)]
      (is (thrown-with-msg? Exception #"callback"
            (p/update-paths data nav
              #(if (= 2 (swap! seen inc)) (throw (Exception. "callback")) (inc %)))))
      (is (= {:a 10 :b 20 :extra 30} (p/update-paths data nav [10 20 30]))))))


(deftest struct-parent-callback-sees-the-converted-child-result
  (let [data (with-meta (struct-map basis :a [1] :b 2) {:source :parent})
        opts {:include-keys true}
        pred #(or (number? %) (keyword? %) (map? %))
        tf (fn [x]
             (cond
               (number? x) (inc x)
               (= :a x) :renamed
               (map? x) (assoc x :parent true)
               :else x))
        {:keys [nav]} (p/path-when data pred opts)]
    (doseq [updated [(p/update-paths data nav tf)
                     (p/transform-when data pred tf opts)]]
      (is (= {:renamed [2] :b 3 :parent true} updated))
      (is (not (instance? PersistentStructMap updated)))
      (is (= (meta data) (meta updated))))))


(deftest nil-and-equal-base-keys
  (let [data (struct-map (create-struct nil 1) nil :nil-value 1 :number-value)]
    (is (instance? PersistentStructMap
          (p/transform-when data number? bigint {:include-keys true})))
    (is (= {nil :number-value}
           (p/transform-when data #(= 1 %) (constantly nil) {:include-keys true})))
    (let [updated (p/transform-when data nil? (constantly :renamed) {:include-keys true})]
      (is (= {:renamed :nil-value 1 :number-value} updated))
      (is (not (instance? PersistentStructMap updated))))))


(deftest clojure-xml-keeps-element-structs-until-their-fields-change
  (let [data (xml/parse (ByteArrayInputStream. (.getBytes "<root><child/></root>" "UTF-8")))
        child (first (:content data))
        renamed (p/transform-when data #{:child} (constantly :renamed))
        removed (p/transform-when data #{:attrs} (constantly p/REMOVE) {:include-keys true})]
    (is (instance? PersistentStructMap data))
    (is (instance? PersistentStructMap child))
    (is (= :renamed (:tag (first (:content renamed)))))
    (is (instance? PersistentStructMap renamed))
    (is (instance? PersistentStructMap (first (:content renamed))))
    (is (= {:tag :root :content [{:tag :child :content nil}]} removed))
    (is (not (instance? PersistentStructMap removed)))
    (is (not (instance? PersistentStructMap (first (:content removed)))))
    (is (= :child (:tag child)))))


(deftest retained-struct-navigation-is-reentrant-and-concurrent
  (let [data (example)
        {:keys [nav]} (p/path-when data #(or (keyword? %) (number? %)) {:include-keys true})
        nested (atom nil)
        outer (p/update-paths data nav
                (fn [value]
                  (when (= value 1)
                    (reset! nested (p/update-paths data nav [10 :renamed 20 :b 30 :extra])))
                  value))]
    (is (= data outer))
    (is (instance? PersistentStructMap outer))
    (is (= {:renamed 10 :b 20 :extra 30} @nested))
    (let [ready (CountDownLatch. 2)
          run (fn [tf]
                (future
                  (p/update-paths data nav
                    (fn [value]
                      (when (= value 1)
                        (.countDown ready)
                        (when-not (.await ready 5 TimeUnit/SECONDS)
                          (throw (ex-info "Concurrent updates did not meet" {}))))
                      (tf value)))))
          jobs [(run #(cond (number? %) (+ 9 %) (= :a %) :renamed :else %))
                (run #(cond (number? %) (* 100 %) (= :extra %) :renamed-extra :else %))]
          [a b] (mapv #(deref % 10000 ::timeout) jobs)]
      (is (= {:renamed 10 :b 11 :extra 12} a))
      (is (not (instance? PersistentStructMap a)))
      (is (= {:a 100 :b 200 :renamed-extra 300} b))
      (is (instance? PersistentStructMap b)))
    (is (= {:a 1 :b 2 :extra 3} data))))
