(ns ashiba.util)

(defn update-values [m f & args]
  (reduce (fn [r [k v]] (assoc r k (apply f v args))) {} m))

(defn in? 
  "true if seq contains elm"
  [seq elm]  
  (some #(= elm %) seq))

(defn without [list val]
  (into [] (remove (fn [ls-val]
                     (= val ls-val)) list)))
