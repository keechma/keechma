(ns syntest.util
  (:require [cljs.core.async :refer [timeout <! chan put!]]
            [promesa.core :as p]
            [clojure.string :refer [lower-case join split]]
            [cljsjs.jquery]))

(defrecord Ok [message result selector])
(defrecord Error [message selector])

(defn promise->chan [promise]
  (let [p-chan (chan)]
    (->> promise
         (p/map (fn [res]
                  (if (instance? Ok res)
                    (put! p-chan res)
                    (put! p-chan [:ok res]))))
         (p/error (fn [err] 
                    (let [err-record (:error (.-data err))]
                      (if err-record
                        (put! p-chan err-record)
                        (put! p-chan [:error err]))))))
    p-chan))

(defn base-selector [node-name classes]
  (if (empty? classes)
    node-name
    (str node-name "." (join "." (split classes #" ")))))

(defn el-id [el]
  (let [id (.attr (.$ js/window el) "id")]
    (if-not (empty? id)
      (str "#" id)
      nil)))

(defn el->selector
  ([el] (el->selector el true))
  ([el calculate-index?]
   (if (string? el)
     el
     (let [id (.attr (.$ js/window el) "id") 
           node-name (lower-case (.-nodeName (.get el 0)))
           classes (.attr (.$ js/window el) "class")]
       (if id 
         id
         (let [selectors
               (loop [parent (.parent (.$ js/window el))
                      selector (list (base-selector node-name classes))]
                 (if (and parent (not= (lower-case (.-nodeName (.get parent 0))) "html"))
                   (if-let [parent-id (el-id parent)]
                     (conj selector parent-id)
                     (recur (.parent (.$ js/window parent)) selector))
                   selector))
               selector (join " " selectors)]
           (if calculate-index?
             (str selector "[" (.index (.$ js/window selector) el) "]")
             selector)))))))
