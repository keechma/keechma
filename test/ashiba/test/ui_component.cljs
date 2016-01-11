(ns ashiba.test.ui-component
  (:require [cljs.test :refer-macros [deftest testing is use-fixtures async]]
            [cljs-react-test.utils :as tu]
            [cljs-react-test.simulate :as sim]
            [dommy.core :as dommy :refer-macros [sel1]]
            [reagent.core :as reagent]
            [cljs.core.async :refer [<! >! chan close! put! alts! timeout]]
            [ashiba.ui-component :as ui])
  (:require-macros [cljs.core.async.macros :as m :refer [go]]))


(defn make-container []
  (let [c (tu/new-container!)
        unmount (fn []
                  (tu/unmount! c))]
    [c unmount]))

(def outer-component 
  (ui/constructor {:component-deps [:some-component]
                   :renderer (fn [ctx]
                               [:div
                                [:h1 "TEST HERE"]
                                [(ui/component ctx :some-component)]])}))

(defn inner-render [ctx]
  (let [on-click (fn [e]
                   (ui/send-command ctx :inner-command "arg")
                   (.preventDefault e))]
    [:button {:on-click on-click} "CLICK ME"]))

(def inner-component
  (ui/constructor {:renderer inner-render}))

(deftest reify-component-test []
  (let [commands-chan (chan)
        reified-inner (ui/reify-component inner-component {:topic :inner
                                                           :commands-chan commands-chan})
        reified-outer (ui/reify-component outer-component {:topic :outer
                                                           :commands-chan commands-chan
                                                           :components {:some-component reified-inner}})
        [c unmount] (make-container)
        _ (reagent/render-component [reified-outer] c)
        node (sel1 c [:button])]
    (async done
           (go
             (sim/click node nil)
             (is (= [[:inner :inner-command] "arg"] (<! commands-chan)))
             (unmount)
             (done)))))


