(ns co.multiply.pathling.array-map-traversal-test
  (:require [clojure.edn :as edn]
    [clojure.test :refer [deftest is testing]]
    [co.multiply.pathling :as p])
  (:import [clojure.lang PersistentArrayMap PersistentHashMap]
    [java.util ArrayList Collection LinkedList Map$Entry]
    [java.util.concurrent Callable CountDownLatch Executors TimeUnit]))


(deftype Leaf [id])


(deftype CollisionKey [id]
  Object
  (equals [_ other] (and (instance? CollisionKey other) (= id (.-id ^CollisionKey other))))
  (hashCode [_] 42)
  clojure.lang.IHashEq
  (hasheq [_] 42))


(defn- pairs [^Iterable m]
  (mapv (fn [^Map$Entry entry] [(.getKey entry) (.getValue entry)])
    (iterator-seq (.iterator m))))


(defn- visits [x]
  (vec (concat (mapcat visits (cond (map? x) (map second (pairs x))
                              (coll? x) x :else [])) [x])))


(defn- build [kind entries]
  (case kind
    :array (apply array-map (mapcat identity entries))
    :assoc (reduce (fn [m [k v]] (assoc m k v)) {} entries)
    :into (into {} entries)))


(defn- maps []
  (for [n [0 1 8 9 16 32 63 64 65]
        keys-kind [:keywords :mixed]
        construction [:array :assoc :into]]
    (let [entries (mapv (fn [i]
                          [(if (= :keywords keys-kind) (keyword (str "k" i))
                             (case i 0 nil 1 false 2 "key" (CollisionKey. i)))
                           (Leaf. i)]) (range n))
          label [n keys-kind construction]]
      [label (with-meta (build construction entries) {:source label})])))


(deftest array-map-order-original-values-and-collection-matches
  (doseq [[label m] (maps) raw? [false true]]
    (testing (str label ", raw=" raw?)
      (let [expected (visits m)
            seen (atom [])
            {:keys [matches nav]} (p/path-when m #(do (swap! seen conj %) ::truthy)
                                   {:raw-matches raw?})]
        (is (= expected @seen (vec matches)))
        (is (every? true? (map identical? expected @seen)))
        (is (every? true? (map identical? expected matches)))
        (is (if raw? (instance? ArrayList matches) (vector? matches)))
        (is (= m (p/update-paths m nav identity)))
        (is (nil? (p/path-when m (constantly false) {:raw-matches raw?})))))))


(defn- replacement [^Leaf x]
  (case (mod (.-id x) 4) 0 p/REMOVE 1 nil 2 false [:resolved (.-id x)]))


(deftest array-map-navigation-replacements-removal-and-reuse
  (doseq [[label m] (maps)]
    (testing (str label)
      (let [original (visits m)
            wanted (mapv second (pairs m))
            {:keys [matches nav]} (p/path-when m #(instance? Leaf %) {:raw-matches true})
            values (mapv replacement wanted)
            expected (into {} (keep (fn [[k v]] (let [v (replacement v)]
                                                  (when-not (identical? p/REMOVE v) [k v])))) (pairs m))]
        (is (= wanted (vec matches)))
        (doseq [replacer [replacement values (ArrayList. ^Collection values) (LinkedList. ^Collection values)]]
          (dotimes [_ 2]
            (let [actual (p/update-paths m nav replacer)]
              (is (= expected actual))
              (is (= (meta m) (meta actual))))))
        (when matches
          (doseq [[i v] (map-indexed vector values)] (.set ^ArrayList matches i v))
          (is (= expected (p/update-paths m nav matches)))
          (is (= values (vec matches)))
          (is (thrown? IndexOutOfBoundsException (p/update-paths m nav (pop values))))
          (is (= expected (p/update-paths m nav (conj values ::unused)))))
        (is (= original (visits m)))))))


(deftest array-map-construction-boundaries
  (let [limit (if (>= (:minor *clojure-version*) 13) 64 8)]
    (doseq [n [0 1 8 9 16 32 63 64 65] kind [:assoc :into :reader]]
      (let [entries (mapv #(vector (keyword (str "k" %)) %) (range n))
            m (if (= :reader kind)
                (edn/read-string (str "{" (apply str (map (fn [[k v]] (str k " " v " ")) entries)) "}"))
                (build kind entries))]
        (is (= (if (<= n limit) PersistentArrayMap PersistentHashMap) (class m)))
        (is (= (mapv second (pairs m)) (vec (:matches (p/path-when m integer?)))))))))


(deftest array-map-nesting-shared-cursor-and-parent-order
  (let [inner (with-meta (apply array-map (mapcat #(vector (keyword (str "k" %)) (Leaf. %)) (range 64))) {:source :inner})
        hashed (with-meta (into PersistentHashMap/EMPTY [[nil (Leaf. 64)] [:other (Leaf. 65)]]) {:source :hashed})
        untouched [::untouched]
        data (with-meta (array-map :inner inner :hashed hashed :untouched untouched) {:source :outer})
        pred #(or (instance? Leaf %) (identical? inner %) (identical? data %))
        {:keys [matches nav]} (p/path-when data pred {:raw-matches true})
        expected-matches (filterv pred (visits data))
        parents (atom [])
        result (p/update-paths data nav
                 (fn [x]
                   (if (instance? Leaf x) (replacement x)
                     (do (swap! parents conj x) (assoc x ::parent true)))))
        child-expected (into {} (keep (fn [[k v]] (let [v (replacement v)]
                                                  (when-not (identical? p/REMOVE v) [k v])))) (pairs inner))]
    (is (= expected-matches (vec matches)))
    (is (= child-expected (first @parents)))
    (is (= (assoc child-expected ::parent true) (:inner (second @parents))))
    (is (= [{:source :inner} {:source :outer}] (mapv meta @parents)))
    (is (= {:other nil} (:hashed result)))
    (is (= {:source :hashed} (meta (:hashed result))))
    (is (identical? untouched (:untouched result)))
    (let [values (mapv #(cond (identical? data %) :root-result
                             (identical? inner %) :inner-result
                             :else (replacement %)) expected-matches)]
      (dotimes [_ 2] (is (= :root-result (p/update-paths data nav values))))
      (is (thrown? IndexOutOfBoundsException (p/update-paths data nav (pop values))))))
  (let [m (with-meta (array-map :x nil :y false) {:source :collection})
        {:keys [nav]} (p/path-when m #(identical? m %))]
    (is (identical? m (p/update-paths m nav identity)))))


(deftest array-map-reentrancy-exceptions-and-concurrent-scans
  (let [data (array-map :a 1 :b (array-map nil 2))
        seen (atom [])
        pred (fn [x] (when (number? x)
                       (swap! seen conj (vec (:matches (p/path-when (array-map :x 7 :y 8) number?))))
                       true))
        failure (ex-info "predicate failed" {})]
    (is (= [1 2] (vec (:matches (p/path-when data pred)))))
    (is (= [[7 8] [7 8]] @seen))
    (is (identical? failure (try (p/path-when data (fn [_] (throw failure))) (catch Exception e e)))))
  (let [pool (Executors/newFixedThreadPool 2) entered (CountDownLatch. 2)]
    (try
      (let [jobs (mapv (fn [value]
                        (.submit pool ^Callable
                          (fn []
                            (let [data (array-map nil value :nested (array-map :leaf (inc value)))
                                  first? (atom true)
                                  pred (fn [x]
                                         (when (number? x)
                                           (when (compare-and-set! first? true false)
                                             (.countDown entered)
                                             (when-not (.await entered 5 TimeUnit/SECONDS)
                                               (throw (ex-info "Concurrent scan did not enter" {}))))
                                           true))
                                  {:keys [matches nav]} (p/path-when data pred {:raw-matches true})]
                              [(vec matches) (p/update-paths data nav #(vector :resolved %))])))) [10 100])]
        (is (= [[[10 11] {nil [:resolved 10] :nested {:leaf [:resolved 11]}}]
                [[100 101] {nil [:resolved 100] :nested {:leaf [:resolved 101]}}]]
               (mapv #(.get % 10 TimeUnit/SECONDS) jobs))))
      (finally (.shutdownNow pool)))))
