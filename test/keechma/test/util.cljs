(ns keechma.test.util
  (:require [react-dom :as react-dom]))


(defn click
  ([dom-node] (click dom-node nil))
  ([dom-node _]
   (.click dom-node)))

(defn unmount!
  "Unmounts the React Component at a node"
  [n]
  (.unmountComponentAtNode react-dom n))

(defn- container-div []
  (let [id (str "container-" (gensym))
        node (.createElement js/document "div")]
    (set! (.-id node) id)
    [node id]))

(defn insert-container! [container]
  (.appendChild (.-body js/document) container))

(defn new-container! []
  (let [[n s] (container-div)]
    (insert-container! n)
    (.getElementById js/document s)))

(defn make-container []
  (let [c (new-container!)]
    [c (fn []
         (unmount! c)
         (.removeChild (.-body js/document) c))]))
