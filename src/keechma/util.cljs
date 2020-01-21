(ns keechma.util
  (:require [cljs.core.async :refer [chan close! put!]]))

(defn update-values [m f & args]
  (reduce (fn [r [k v]] (assoc r k (apply f v args))) {} m))

(defn in? 
  "true if seq contains elm"
  [seq elm]  
  (some #(= elm %) seq))

(defn without [list val]
  (into [] (remove (fn [ls-val]
                     (= val ls-val)) list)))

(defn animation-frame
  "Return a channel which will close on the nth next animation frame."
  ([] (animation-frame 1))
  ([n] (animation-frame n (chan 1)))
  ([n out]
   (js/window.requestAnimationFrame
    (fn [timestamp]
      (if (= n 1)
        (do
          (put! out timestamp)
          (close! out))
        (animation-frame (dec n) out))))
   out))

(defn dissoc-in
  "Dissociates an entry from a nested associative structure returning a new
  nested structure. keys is a sequence of keys. Any empty maps that result
  will not be present in the new structure."
  [m [k & ks :as keys]]
  (if ks
    (if-let [nextmap (get m k)]
      (let [newmap (dissoc-in nextmap ks)]
        (if (seq newmap)
          (assoc m k newmap)
          (dissoc m k)))
      m)
    (dissoc m k)))

(defn index-of [coll item]
  (loop [c coll 
         idx 0]
    (if-let [first-item (first c)]
      (if (= first-item item) 
        idx
        (recur (rest c) (inc idx)))
      nil)))


(defn keechma-ex-info
  ([message anomaly] (keechma-ex-info message anomaly {}))
  ([message anomaly props]
   (ex-info message (assoc props 
                           :keechma.anomalies/category anomaly
                           :keechma.anomalies/message message))))
