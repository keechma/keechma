(ns keechma.test.app-state
  (:require [cljs.test :refer-macros [deftest is async]] 
            [cljs-react-test.utils :as tu]
            [cljs-react-test.simulate :as sim]
            [dommy.core :as dommy :refer-macros [sel1]]
            [cljs.core.async :refer [<! >! chan close! put! alts! timeout]]
            [keechma.app-state :as app-state]
            [keechma.controller :as controller]
            [keechma.ui-component :as ui])
  (:require-macros [cljs.core.async.macros :as m :refer [go alt!]]
                   [reagent.ratom :refer [reaction]]))

(defn make-container []
  (let [c (tu/new-container!)]
    [c #(tu/unmount! c)]))

(deftest empty-start-stop []
  (let [[c unmount] (make-container)
        app (app-state/start!
             {:html-element c
              :components {:main {:renderer (fn [_]
                                             [:div "HELLO WORLD"])}}})]
    (async done
           (go
             (<! (timeout 100))
             (is (= (.-innerText c) "HELLO WORLD"))
             (app-state/stop! app done)))))

(defrecord AppStartController [inner-app]
  controller/IController
  (params [_ _] true)
  ;; When the controller is started, start the inner app and save
  ;; it's definition inside the app-db
  (start [this params app-db] 
    (assoc app-db :inner-app (app-state/start! (:inner-app this) false)))
  ;; When the controller is stopped, stop the inner app
  (stop [this params app-db]
    (app-state/stop! (:inner-app app-db))))

(deftest multiple-apps []
  (let [[c unmount] (make-container)
        ;; definition of the inner app
        inner-app
        {:components {:main {:renderer (fn [_] [:div "INNER APP"])}}} 
        ;; renderer function of the main app
        ;; it gets the inner app's main component from the app
        ;; state and renders it
        outer-app-renderer 
        (fn [ctx]
          (let [inner-app-sub (ui/subscription ctx :inner-app)]
            (fn []
              (let [inner-app (:main-component @inner-app-sub)]
                [(or inner-app :div)]))))
        ;; get the inner app from the app-db
        inner-app-sub
        (fn [app-db]
          (reaction
           (:inner-app @app-db)))
        ;; definition of the outer app
        outer-app (app-state/start!
                     {:controllers {:main (->AppStartController inner-app)}
                      :html-element c
                      :components {:main {:renderer outer-app-renderer
                                          :subscription-deps [:inner-app]}}
                      :subscriptions {:inner-app inner-app-sub}})]
    (async done
           (go
             (<! (timeout 100))
             (is (= (.-innerText c) "INNER APP"))
             (app-state/stop! outer-app done)))))
