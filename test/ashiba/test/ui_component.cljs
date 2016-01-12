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
  (let [c (tu/new-container!)]
    [c #(tu/unmount! c)]))

(deftest reify-component-test []
  (let [commands-chan (chan)
        outer-component (ui/constructor
                         {:component-deps [:some-component]
                          :renderer
                          (fn [ctx]
                            [:div
                             [:input {:type :submit
                                      :on-click #(ui/send-command ctx :outer-command "outer-arg")}]
                             [(ui/component ctx :some-component)]])})
        inner-render (fn [ctx]
                       [:button {:on-click #(ui/send-command ctx :inner-command "inner-arg")}])
        inner-component (ui/constructor {:renderer inner-render})
        reified-inner (ui/reify-component inner-component
                                          {:topic :inner
                                           :commands-chan commands-chan})
        reified-outer (ui/reify-component outer-component
                                          {:topic :outer
                                           :commands-chan commands-chan
                                           :components {:some-component reified-inner}})
        [c unmount] (make-container)
        _ (reagent/render-component [reified-outer] c)
        button-node (sel1 c [:button])
        submit-node (sel1 c [:input])]
    (async done
           (go
             (sim/click button-node nil)
             (is (= [[:inner :inner-command] "inner-arg"] (<! commands-chan)))
             (sim/click submit-node nil)
             (is (= [[:outer :outer-command] "outer-arg"] (<! commands-chan)))
             (unmount)
             (done)))))

