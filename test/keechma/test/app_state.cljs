(ns keechma.test.app-state
  (:require [cljs.test :refer-macros [deftest is async]] 
            [cljs-react-test.utils :as tu]
            [cljs-react-test.simulate :as sim]
            [dommy.core :as dommy :refer-macros [sel1]]
            [cljs.core.async :refer [<! >! chan close! put! alts! timeout]]
            [keechma.app-state :as app-state]
            [keechma.controller :as controller]
            [keechma.ui-component :as ui]
            [keechma.app-state.react-native-router :as rn-router])
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
    (app-state/stop! (:inner-app app-db))
    app-db))

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

(defrecord RedirectController []
  controller/IController
  (start [this app-db _]
    (controller/redirect this {:foo "bar"})
    app-db))

(deftest redirect-from-controller []
  (let [[c unmount] (make-container)
        app (app-state/start!
             {:html-element c
              :controllers {:redirect (->RedirectController)}
              :components {:main {:renderer (fn [_] [:div])}}})]
    (async done
           (go
             (<! (timeout 100))
             (is (= (.. js/window -location -hash) "#!?foo=bar"))
             (set! (.-hash js/location) "")
             (app-state/stop! app done)))))

(deftest redirect-from-component []
  (let [[c unmount] (make-container)
        app (app-state/start!
             {:html-element c 
              :components {:main {:renderer
                                  (fn [ctx]
                                    [:button
                                     {:on-click #(ui/redirect ctx {:baz "qux"})}
                                     "click"])}}})
        button-node (sel1 c [:button])]
    (async done
           (go
             (<! (timeout 100))
             (sim/click button-node nil)
             (<! (timeout 100))
             (is (= (.. js/window -location -hash) "#!?baz=qux"))
             (set! (.-hash js/location) "")
             (app-state/stop! app done)))))


(defrecord ClickController []
  controller/IController
  (handler [_ app-state-atom in-chan _]
    (controller/dispatcher app-state-atom in-chan
                           {:inc-counter (fn [app-state-atom]
                                           (swap! app-state-atom assoc-in [:kv :counter]
                                                  (inc (get-in @app-state-atom [:kv :counter]))))})))

(deftest state-restore []
  (let [[c unmount] (make-container)
        app-definition {:html-element c
                        :controllers {:click (->ClickController)}
                        :components {:main {:renderer
                                            (fn [ctx]
                                              [:div
                                               [:h1 (or @(ui/subscription ctx :counter) 0)]
                                               [:button
                                                {:on-click #(ui/send-command ctx :inc-counter)} "INC COUNTER"]])
                                            :topic :click
                                            :subscription-deps [:counter]}}
                        :subscriptions {:counter (fn [app-state-atom]
                                                   (reaction
                                                    (get-in @app-state-atom [:kv :counter])))}}
        app-v1 (app-state/start! app-definition)
        h1-v1 (sel1 c [:h1])]
    (async done
           (go
             (is (= (.-innerText h1-v1) "0"))
             (sim/click (sel1 c [:button]) nil)
             (<! (timeout 10))
             (is (= (.-innerText h1-v1) "1"))
             (app-state/stop! app-v1)
             (<! (timeout 10))
             (let [app-v2 (app-state/start! app-definition)
                   h1-v2 (sel1 c [:h1])]
               (is (= (.-innerText h1-v2) "0"))
               (app-state/stop! app-v2)
               (<! (timeout 10))
               (let [app-v3 (app-state/start! app-definition)
                     h1-v3 (sel1 c [:h1])]
                 (app-state/restore-app-db app-v1 app-v3)
                 (<! (timeout 50))
                 (is (= (.-innerText h1-v3) "1"))
                 (sim/click (sel1 c [:button]) nil)
                 (<! (timeout 10))
                 (is (= (.-innerText h1-v3) "2"))
                 (app-state/stop! app-v3)
                 (done)))))))

(defrecord ReactNativeController [route-atom]
  controller/IController
  (params [this route]
    (reset! (:route-atom this) route)
    true)
  (handler [this app-db-atom in-chan _]
    (controller/dispatcher app-db-atom in-chan
                           {:route-changed (fn [app-db-atom value]
                                             (reset! (:route-atom this) value))})))

(deftest react-native-router []
  (let [route-atom (atom nil)
        app-definition {:controllers {:main (->ReactNativeController route-atom)}
                        :components {:main {:renderer (fn [ctx])}}
                        :router :react-native}]
    (async done
           (go
             (is (= nil @route-atom))
             (app-state/start! app-definition false)
             (<! (timeout 10))
             (is (= {:index 0
                     :key :init
                     :routes [{:key :init}]}
                    @route-atom))
             (rn-router/navigate! :push {:key :foo})
             (<! (timeout 10))
             (is (= {:index 1
                     :key :foo
                     :routes [{:key :init}
                              {:key :foo}]}
                    @route-atom))
             (rn-router/navigate! :push {:key :bar})
             (<! (timeout 10))
             (is (= {:index 2
                     :key :bar
                     :routes [{:key :init}
                              {:key :foo}
                              {:key :bar}]}
                    @route-atom))
             (rn-router/navigate! :pop)
             (<! (timeout 10))
             (is (= {:index 1
                     :key :foo
                     :routes [{:key :init}
                              {:key :foo}]}
                    @route-atom))
             (rn-router/navigate! :home)
             (<! (timeout 10))
             (is (= {:index 0
                     :key :init
                     :routes [{:key :init}]}
                    @route-atom))
             (rn-router/navigate! :pop)
             (<! (timeout 10))
             (is (= {:index 0
                     :key :init
                     :routes [{:key :init}]}
                    @route-atom))
             (done)))))


(defrecord ControllerA [kv-state-atom]
  controller/IController
  (params [_ _] true)
  (handler [_ app-db-atom _ _]
    (swap! app-db-atom assoc-in [:kv :foo] :bar)
    (reset! kv-state-atom (:kv @app-db-atom))))

(defrecord ControllerB [kv-state-atom]
  controller/IController
  (params [_ _] true)
  (start [_ _ app-db]
    (assoc-in app-db [:kv :start] :value))
  (handler [_ app-db-atom _ _]
    (js/setTimeout (fn []
                     (swap! app-db-atom assoc-in [:kv :baz] :qux)
                     (reset! kv-state-atom (:kv @app-db-atom))) 10)))

(deftest controller-kv-test []
  (let [kv-state-atom (atom nil)
        app-definition {:controllers {:a (->ControllerA kv-state-atom)
                                      :b (->ControllerB kv-state-atom)}
                        :components {:main {:renderer (fn [ctx])}}}] 
    (async done
           (go
             (app-state/start! app-definition false)
             (<! (timeout 1))
             (is (= @kv-state-atom {:foo :bar}))
             (<! (timeout 20))
             (is (= @kv-state-atom {:foo :bar :baz :qux :start :value}))
             (done)))))

(defrecord ControllerChangeNumber []
  controller/IController
  (params [_ _] true)
  (start [_ _ app-db]
    (assoc-in app-db [:kv :number] 42)))

(deftest subscriptions-test []
  (let [[c unmount] (make-container)
        reaction-call-count (atom 0)
        app-definition {:html-element c
                        :controllers {:change-number (->ControllerChangeNumber)}
                        :components {:main {:renderer
                                            (fn [ctx]
                                              [:div.main-subscriptions
                                               [(ui/component ctx :sub-component)]
                                               [(ui/component ctx :sub-component)]])
                                            :component-deps [:sub-component]}
                                     :sub-component {:renderer
                                                     (fn [ctx]
                                                       (fn []
                                                         [:div @(ui/subscription ctx :number)]))
                                                     :subscription-deps [:number]}}
                        :subscriptions {:number (fn [app-db-atom]
                                                  (swap! reaction-call-count inc)
                                                  (reaction
                                                   (get-in @app-db-atom [:kv :number])))}}
         app (app-state/start! app-definition)]
    (async done
           (go
             (<! (timeout 16))
             (is (= "<div>42</div><div>42</div>" (.-innerHTML (sel1 c [:.main-subscriptions]))))
             (swap! (:app-db app) assoc-in [:kv :number] 43)
             (<! (timeout 16))
             (is (= "<div>43</div><div>43</div>" (.-innerHTML (sel1 c [:.main-subscriptions]))))
             (is (= 1 @reaction-call-count))
             (app-state/stop! app)
             (unmount)
             (done)))))

(deftest subscriptions-with-args-test []
  (let [[c unmount] (make-container)
        reaction-call-count (atom 0)
        app-definition {:html-element c
                        :controllers {:change-number (->ControllerChangeNumber)}
                        :components {:main {:renderer
                                            (fn [ctx]
                                              [:div.main-subscriptions
                                               [(ui/component ctx :sub-component) 1]
                                               [(ui/component ctx :sub-component) 2]])
                                            :component-deps [:sub-component]}
                                     :sub-component {:renderer
                                                     (fn [ctx add]
                                                       (fn [add]
                                                         [:div @(ui/subscription ctx :number [add])]))
                                                     :subscription-deps [:number]}}
                        :subscriptions {:number (fn [app-db-atom add]
                                                  (swap! reaction-call-count inc)
                                                  (reaction
                                                   (+ add (get-in @app-db-atom [:kv :number]))))}}
         app (app-state/start! app-definition)]
    (async done
           (go
             (<! (timeout 16))
             (is (= "<div>43</div><div>44</div>" (.-innerHTML (sel1 c [:.main-subscriptions]))))
             (swap! (:app-db app) assoc-in [:kv :number] 43)
             (<! (timeout 16))
             (is (= "<div>44</div><div>45</div>" (.-innerHTML (sel1 c [:.main-subscriptions]))))
             (is (= 2 @reaction-call-count))
             (app-state/stop! app)
             (unmount)
             (done)))))

(deftest subscriptions-form-1-test []
  (let [[c unmount] (make-container)
        reaction-call-count (atom 0)
        app-definition {:html-element c
                        :controllers {:change-number (->ControllerChangeNumber)}
                        :components {:main {:renderer
                                            (fn [ctx]
                                              [:div.main-subscriptions
                                               [(ui/component ctx :sub-component)]
                                               [(ui/component ctx :sub-component)]])
                                            :component-deps [:sub-component]}
                                     :sub-component {:renderer
                                                     (fn [ctx]
                                                       [:div @(ui/subscription ctx :number)])
                                                     :subscription-deps [:number]}}
                        :subscriptions {:number (fn [app-db-atom]
                                                  (swap! reaction-call-count inc)
                                                  (reaction
                                                   (get-in @app-db-atom [:kv :number])))}}
         app (app-state/start! app-definition)]
    (async done
           (go
             (<! (timeout 16))
             (is (= "<div>42</div><div>42</div>" (.-innerHTML (sel1 c [:.main-subscriptions]))))
             (swap! (:app-db app) assoc-in [:kv :number] 43)
             (<! (timeout 16))
             (is (= "<div>43</div><div>43</div>" (.-innerHTML (sel1 c [:.main-subscriptions]))))
             (<! (timeout 16))
             (is (= 1 @reaction-call-count))
             (app-state/stop! app)
             (unmount)
             (done)))))


(deftest subscriptions-with-args-form-1-test []
  (let [[c unmount] (make-container)
        reaction-call-count (atom 0)
        app-definition {:html-element c
                        :controllers {:change-number (->ControllerChangeNumber)}
                        :components {:main {:renderer
                                            (fn [ctx]
                                              [:div.main-subscriptions
                                               [(ui/component ctx :sub-component) 1]
                                               [(ui/component ctx :sub-component) 2]])
                                            :component-deps [:sub-component]}
                                     :sub-component {:renderer
                                                     (fn [ctx add]
                                                       [:div @(ui/subscription ctx :number [add])])
                                                     :subscription-deps [:number]}}
                        :subscriptions {:number (fn [app-db-atom add]
                                                  (swap! reaction-call-count inc)
                                                  (reaction
                                                   (+ add (get-in @app-db-atom [:kv :number]))))}}
         app (app-state/start! app-definition)]
    (async done
           (go
             (<! (timeout 16))
             (is (= "<div>43</div><div>44</div>" (.-innerHTML (sel1 c [:.main-subscriptions]))))
             (swap! (:app-db app) assoc-in [:kv :number] 43)
             (<! (timeout 16))
             (is (= "<div>44</div><div>45</div>" (.-innerHTML (sel1 c [:.main-subscriptions]))))
             (is (= 2 @reaction-call-count))
             (app-state/stop! app)
             (unmount)
             (done)))))

(deftest cached-route-subscription
  (let [[c unmount] (make-container)
        renderer-call-count (atom 0)
        app-definition {:html-element c
                        :controllers {}
                        :components {:main {:renderer
                                            (fn [ctx]
                                              (fn []
                                                (let [current-route (:data @(ui/current-route ctx))
                                                      _ (swap! renderer-call-count inc)]
                                                  [:div.main-route "FOO = " (:foo current-route)])))}}}
         app (app-state/start! app-definition)]
    (async done
           (go
             (<! (timeout 16))
             (is (= "FOO =" (.-innerText (sel1 c [:.main-route]))))
             (set! (.-hash js/location) "#!?foo=foo")
             (<! (timeout 16))
             (is (= "FOO = foo" (.-innerText (sel1 c [:.main-route]))))
             (swap! (:app-db app) assoc-in [:kv :foo] "bar")
             (<! (timeout 16))
             (is (= 2 @renderer-call-count))
             (app-state/stop! app)
             (unmount)
             (set! (.-hash js/location) "")
             (<! (timeout 16))
             (done)))))

(deftest cached-route-subscription-form-1
  (let [[c unmount] (make-container)
        renderer-call-count (atom 0)
        app-definition {:html-element c
                        :controllers {}
                        :components {:main {:renderer
                                            (fn [ctx]
                                              (let [current-route (:data @(ui/current-route ctx))
                                                    _ (swap! renderer-call-count inc)]
                                                [:div "FOO = " (:foo current-route)]))}}}
         app (app-state/start! app-definition)]
    (async done
           (go
             (<! (timeout 16))
             (is (= "FOO =" (.-innerText c)))
             (set! (.-hash js/location) "#!?foo=foo")
             (<! (timeout 16))
             (is (= "FOO = foo" (.-innerText c)))
             (swap! (:app-db app) assoc-in [:kv :foo] "bar")
             (<! (timeout 16))
             (is (= 2 @renderer-call-count))
             (app-state/stop! app)
             (unmount)
             (set! (.-hash js/location) "")
             (<! (timeout 16))
             (done)))))
