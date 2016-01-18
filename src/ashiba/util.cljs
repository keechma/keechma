(ns ashiba.util
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
