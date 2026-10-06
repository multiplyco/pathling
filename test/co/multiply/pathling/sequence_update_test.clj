(ns co.multiply.pathling.sequence-update-test
  (:require [clojure.test :refer [deftest is testing]]
    [co.multiply.pathling :as p])
  (:import [clojure.lang PersistentQueue]
    [java.util ArrayList Collection]))


(def sequence-factories
  [[:list #(apply list %)]
   [:lazy-seq #(map identity %)]
   [:queue #(into PersistentQueue/EMPTY %)]])


(deftest collection-only-update-preserves-original
  (doseq [[kind make-sequence] sequence-factories
          values [[] [1 2 3]]
          include-keys [false true]]
    (testing (str kind ", values=" values ", include-keys=" include-keys)
      (let [data (with-meta (make-sequence values) {:source kind})
            pred #(identical? data %)
            {:keys [matches nav]} (p/path-when data pred {:include-keys include-keys})
            seen (atom [])
            updated (p/update-paths data nav #(do (swap! seen conj %) %))
            replacement (with-meta [:replacement] {:replacement true})]
        (is (= 1 (count matches) (count @seen)))
        (is (identical? data (first matches)))
        (is (identical? data (first @seen)))
        (is (identical? data updated))
        (is (= {:source kind} (meta updated)))
        (doseq [replacer [(constantly replacement) [replacement]
                          (ArrayList. ^Collection [replacement])]]
          (is (identical? replacement (p/update-paths data nav replacer))))
        (is (nil? (p/update-paths data nav [nil])))
        (is (identical? p/REMOVE (p/update-paths data nav [p/REMOVE])))
        (let [changed (p/update-paths data nav #(with-meta % {:updated true}))]
          (is (= values (vec changed)))
          (is (= (class data) (class changed)))
          (is (= {:updated true} (meta changed))))
        (is (= {:source kind} (meta data)))
        (is (= values (vec data)))))))


(deftest collection-only-update-consumes-one-replacement
  (doseq [[kind make-sequence] sequence-factories
          include-keys [false true]]
    (let [a (with-meta (make-sequence [1 2]) {:source :a})
          b (with-meta (make-sequence []) {:source :b})
          data [a :between b]
          pred #(or (identical? a %) (= :between %) (identical? b %))
          {:keys [nav]} (p/path-when data pred {:include-keys include-keys})
          seen (atom [])]
      (testing (str kind ", include-keys=" include-keys)
        (is (= data (p/update-paths data nav #(do (swap! seen conj %) %))))
        (is (= 3 (count @seen)))
        (is (identical? a (first @seen)))
        (is (= :between (second @seen)))
        (is (identical? b (nth @seen 2)))
        (doseq [replacer [[p/REMOVE :middle :last]
                          (ArrayList. ^Collection [p/REMOVE :middle :last])]]
          (is (= [:middle :last] (p/update-paths data nav replacer))))
        (let [{:keys [nav]} (p/path-when data #(identical? a %) {:include-keys include-keys})
              updated (p/update-paths data nav (constantly p/REMOVE))]
          (is (= [:between b] updated))
          (is (identical? b (second updated)))
          (is (= {:source :b} (meta (second updated)))))))))


(deftest sequence-child-updates-still-precede-parent-updates
  (doseq [[kind make-sequence] sequence-factories
          include-keys [false true]]
    (let [data (with-meta (make-sequence [1 2 3]) {:source kind})
          opts {:include-keys include-keys}
          {:keys [nav]} (p/path-when data #(or (= 2 %) (identical? data %)) opts)
          seen (atom [])
          updated (p/update-paths data nav
                    (fn [x]
                      (swap! seen conj x)
                      (if (= 2 x)
                        p/REMOVE
                        (with-meta (conj (vec x) :parent) {:updated true}))))]
      (testing (str kind ", include-keys=" include-keys)
        (is (= [1 3 :parent] updated))
        (is (= {:updated true} (meta updated)))
        (is (= [2 '(1 3)] @seen))
        (is (= {:source kind} (meta (second @seen))))
        (is (not (identical? data (second @seen))))
        (let [{:keys [nav]} (p/path-when data #(= 2 %) opts)]
          (dotimes [_ 2]
            (let [result (p/update-paths data nav [:child])]
              (is (= '(1 :child 3) result))
              (is (list? result))
              (is (= {:source kind} (meta result))))))
        (is (= [1 2 3] (vec data)))
        (is (= {:source kind} (meta data)))))))
