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

(deftest system []
  (testing "System can be built"
    (let [components {:main {:component-deps [:sidebar :users]}
                      :sidebar {:component-deps [:current-user]}
                      :users {:component-deps [:user-profile]}
                      :current-user {}
                      :user-profile {}}
          system (ui/system components)]
      (is (= system {:component-deps [:sidebar :users]
                     :components {:sidebar {:component-deps [:current-user]
                                            :components {:current-user {}}}
                                  :users {:component-deps [:user-profile]
                                          :components {:user-profile {}}}}}))))
  (testing "System throws when missing dependencies"
    (let [incomplete-system {:main {:component-deps [:sidebar]}}]
      (is (thrown? js/Error (ui/system incomplete-system)))))
  (testing "System throws when missing :main"
    (let [incomplete-system {:sidebar {}}]
      (is (thrown? js/Error (ui/system incomplete-system)))))
  (testing "System throws when something depends on :main"
    (let [wrong-system {:main {}
                        :sidebar {:component-deps [:main]}}]
      (is (thrown? js/Error (ui/system wrong-system))))))

(deftest system-rendering-test []
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

        system (ui/system {:main (assoc outer-component :topic :outer)
                           :some-component (assoc inner-component :topic :inner)})

        renderer (ui/renderer (assoc system :commands-chan commands-chan))
 
        [c unmount] (make-container)
        _ (reagent/render-component [renderer] c)
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
