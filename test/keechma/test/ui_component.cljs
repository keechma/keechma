(ns keechma.test.ui-component
  (:require [cljs.test :refer-macros [deftest testing is use-fixtures async]]
            [cljsjs.react.dom]
            [cljsjs.react]
            [reagent.core :as reagent]
            [cljs-react-test.simulate :as sim]
            [dommy.core :as dommy :refer-macros [sel1]]
            [cljs.core.async :refer [<! >! chan close! put! alts! timeout]]
            [keechma.ui-component :as ui]
            [keechma.test.util :refer [make-container]])
  (:require-macros [cljs.core.async.macros :as m :refer [go]]))

(deftest system []
  (testing "System can be built"
    (let [components {:main {:component-deps [:sidebar :users]}
                      :sidebar {:component-deps [:current-user]}
                      :users {:component-deps [:user-profile]}
                      :current-user {}
                      :user-profile {}}
          system (ui/system components)]
      (is (= system {:name :main
                     :component-deps []
                     :components {:sidebar {:name :sidebar
                                            :component-deps []
                                            :components {:current-user {:name :current-user}}}
                                  :users {:name :users
                                          :component-deps []
                                          :components {:user-profile {:name :user-profile}}}}}))))
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

(deftest resolve-dep []
  (= (ui/resolve-component-dep {:component-deps [:sidebar]} {:is-sidebar? true})
     {:component-deps []
      :components {:sidebar {:is-sidebar? true}}})
  (= (ui/resolve-subscription-dep {:subscription-deps [:current-user]} {:current-user :foo-bar})
     {:subscription-deps []
      :subscriptions {:current-user :foo-bar}}))

(deftest nesting-systems-partially-resolving-deps []
  (let [system-a {:main {:component-deps [:menu :logout-button]}
                  :menu {:is-menu? true}
                  :logout-button {:is-logout-button? true}}
        system-b {:main {:component-deps [:layout :footer :sidebar]}
                  :layout (ui/resolve-component-dep
                           {:component-deps [:main-panel :filter-bar]}
                           :main-panel {:is-main-panel? true})
                  :sidebar (ui/system system-a)
                  :filter-bar {:is-filter-bar? true}
                  :footer {}}]
    (= (ui/system system-b)
       {:component-deps []
        :components {:layout {:name :layout
                              :component-deps []
                              :components {:main-panel {:is-main-panel? true}
                                           :filter-bar {:is-filter-bar? true}}}
                     :sidebar {:name :sidebar
                               :component-deps []
                               :components {:menu {:is-menu? true}
                                            :logout-button {:is-logout-button? true}}} 
                     :footer {:name :footer}}})))

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
             (is (= [[:inner :inner-command] "inner-arg"] (take 2 (<! commands-chan))))
             (sim/click submit-node nil)
             (is (= [[:outer :outer-command] "outer-arg"] (take 2 (<! commands-chan))))
             (unmount)
             (done)))))

(deftest subscriptions-without-arguments []
  (let [count-sub (fn [_] 10)
        component (ui/constructor {:subscriptions {:count-sub count-sub}
                                   :renderer (fn [ctx] (ui/subscription ctx :count-sub))})
        renderer (ui/renderer component)]
    (is (= (renderer) 10))))


(deftest subscriptions-with-arguments []
  (let [count-sub (fn [_ count] count)
        component (ui/constructor {:subscriptions {:count-sub count-sub}
                                   :renderer (fn [ctx] (ui/subscription ctx :count-sub [10]))})
        renderer (ui/renderer component)]
    (is (= (renderer) 10))))


(deftest send-command-returns-nil []
  (let [commands-chan (chan)
        component (ui/constructor
                   {:renderer
                    (fn [ctx]
                      (is (= nil (ui/send-command ctx :some-command)))
                      [:div "Test"])})
        system (ui/system {:main component})
        renderer (ui/renderer (assoc system :commands-chan commands-chan))
        [c unmount] (make-container)]
    (reagent/render-component [renderer] c)
    (unmount)))
