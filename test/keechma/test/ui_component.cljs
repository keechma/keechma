(ns keechma.test.ui-component
  (:require [cljs.test :refer-macros [deftest testing is use-fixtures async]]
            [cljsjs.react.dom]
            [cljsjs.react]
            [reagent.core :as reagent]
            [reagent.dom :as reagent-dom]
            [dommy.core :as dommy :refer-macros [sel1]]
            [cljs.core.async :refer [<! >! chan close! put! alts! timeout]]
            [keechma.ui-component :as ui]
            [keechma.test.util :refer [make-container click]])
  (:require-macros [cljs.core.async.macros :as m :refer [go go-loop]]))

(deftest system []
  (testing "System can be built"
    (let [components {:main {:component-deps [:sidebar :users]}
                      :sidebar {:component-deps [:current-user]}
                      :users {:component-deps [:user-profile]}
                      :current-user {}
                      :user-profile {}}
          system (dissoc (ui/system components) :keechma.ui-component/system)]
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
                  :sidebar (dissoc (ui/system system-a) :keechma.ui-component/system)
                  :filter-bar {:is-filter-bar? true}
                  :footer {}}]
    (= (dissoc (ui/system system-b) :keechma.ui-component/system)
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
                             [:input 
                              {:type :submit
                               :on-click #(ui/send-command ctx :outer-command "outer-arg")}]
                             [(ui/component ctx :some-component)]])})
        inner-component (ui/constructor
                         {:renderer (fn [ctx]
                                      [:button 
                                       {:on-click #(ui/send-command ctx :inner-command "inner-arg")}
                                       "Click Me!"])})

        system (ui/system {:main (assoc outer-component :topic :outer)
                           :some-component (assoc inner-component :topic :inner)})

        renderer (ui/renderer (assoc system :commands-chan commands-chan))
 
        [c unmount] (make-container)
        _ (reagent-dom/render [renderer] c)
        button-node (sel1 c [:button])
        submit-node (sel1 c [:input])]
    (async done
           (go 
             (click button-node)
             (is (= [[:inner :inner-command] "inner-arg"] (take 2 (<! commands-chan))))
             (click submit-node)
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
    (reagent-dom/render [renderer] c)
    (close! commands-chan)
    (unmount)))


(deftest send-command-with-and-without-topic []
  (let [commands (atom [])
        commands-chan (chan)
        component (ui/constructor
                   {:topic :foo
                    :renderer
                    (fn [ctx]
                      (ui/send-command ctx :some-command)
                      (ui/send-command ctx [:bar :some-command])
                      [:div "Test"])})
        system (ui/system {:main component})
        renderer (ui/renderer (assoc system :commands-chan commands-chan))
        [c unmount] (make-container)]
    
    (async done
           (reagent-dom/render [renderer] c)
           (go-loop []
             (let [cmd (<! commands-chan)]
               (swap! commands conj (first cmd))
               (if (not= 2 (count @commands))
                 (recur)
                 (do
                   (is (= @commands
                          [[:foo :some-command]
                           [:bar :some-command]]))
                   (close! commands-chan)
                   (unmount)
                   (done))))))))

(deftest resolved-component-wont-be-overriden
  (let [components {:main {:component-deps [:sidebar :users]
                           :components {:users :faux-users}}
                    :sidebar {:component-deps [:current-user]}
                    :users {:component-deps [:user-profile]}
                    :faux-users {:component-deps [:users]}
                    :current-user {}
                    :user-profile {}}
        system (ui/system components)]
    (is (= (dissoc system :keechma.ui-component/system)
           {:name :main
            :component-deps []
            :components {:sidebar {:component-deps []
                                   :name :sidebar
                                   :components {:current-user {:name :current-user}}}
                         :users {:component-deps []
                                 :name :faux-users
                                 :components {:users {:component-deps []
                                                      :name :users
                                                      :components {:user-profile {:name :user-profile}}}}}}}))))
