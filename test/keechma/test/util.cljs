(ns keechma.test.util
  (:require [cljs-react-test.utils :as tu]))

(defn make-container []
  (let [c (tu/new-container!)]
    [c (fn []
         (tu/unmount! c)
         (.removeChild (.-body js/document) c))]))
