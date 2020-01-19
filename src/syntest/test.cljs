(ns syntest.test
  (:require [promesa.core :as p]
            [cljs.core.async :refer [<! timeout]]
            [cljs.test :refer-macros [async is]]
            [syntest.util :as util :refer [promise->chan]])
  (:require-macros [cljs.core.async.macros :refer [go-loop]]))

(defn run-async [actions]
  (async done
         (go-loop [a actions
                   last-el nil]
           (<! (timeout 1))
           (if (seq a)
             (let [next-a (first a)
                   action-res (next-a last-el)
                   res (<! (promise->chan (p/promise action-res)))]
               (cond
                 (instance? util/Ok res) (do
                                           (is true (:message res))
                                           (recur (rest a) (:result res)))
                 (instance? util/Error res) (do
                                              (is (= true (:message res)))
                                              (done))
                 (and (seq res) (= :error (first res))) (do
                                                          (is (= true (last res)))
                                                          (done))
                 :else (recur (rest a) last-el)))
             (done)))))
