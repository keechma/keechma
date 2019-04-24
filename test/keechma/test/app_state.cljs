(ns keechma.test.app-state
  (:require [cljs.test :refer-macros [deftest is async]] 
            [cljsjs.react.dom]
            [cljsjs.react]
            [cljs-react-test.simulate :as sim]
            [dommy.core :as dommy :refer-macros [sel1]]
            [cljs.core.async :refer [<! >! chan close! put! alts! timeout]]
            [keechma.app-state :as app-state]
            [keechma.controller :as controller]
            [keechma.ui-component :as ui]
            [keechma.app-state.react-native-router :as rn-router]
            [keechma.app-state.history-router :as history-router]
            [keechma.app-state.core :refer [reg-on-start reg-on-stop]]
            [keechma.ssr :as ssr]
            [keechma.test.util :refer [make-container]]
            [clojure.string :as str]
            [keechma.app-state.memory-router :as memory-router])
  (:require-macros [cljs.core.async.macros :as m :refer [go go-loop alt!]]
                   [reagent.ratom :refer [reaction]]))



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
             (app-state/stop! app (fn []
                                    (unmount)
                                    (done)))))))

(deftest route-processor []  
  (let [[c unmount] (make-container)
        current-hash (.-hash (.-location js/window))
        _ (set! (.-hash (.-location js/window)) "!page1")
        app (app-state/start!
             {:html-element c
              :routes [":page"]
              :route-processor (fn [route]
                                 (update-in route [:data :page] #(str/upper-case (or % ""))))
              :components {:main {:renderer (fn [_]
                                              [:div "HELLO WORLD"])}}})
        app-db-atom (:app-db app)]
    (async done
           (go
             (<! (timeout 20))
             (is (= "PAGE1" (get-in @app-db-atom [:route :data :page])))
             (set! (.-hash (.-location js/window)) "!page2")
             (<! (timeout 20))
             (is (= "PAGE2" (get-in @app-db-atom [:route :data :page])))
             (app-state/stop! app (fn []
                                    (unmount)
                                    (set! (.-hash (.-location js/window)) current-hash)
                                    (done)))))))
(defrecord RerouteController [log])

(defmethod controller/params RerouteController [_ route-params] true)

(defmethod controller/start RerouteController [this params app-db]
  (swap! (:log this) #(conj % [:reroute :start params]))
  app-db)

(defrecord LoginController [log])

(defmethod controller/params LoginController [_ route-params]
  (when (= "login" (get-in route-params [:data :page]))
    :login))

(defmethod controller/start LoginController [this params app-db]
  (swap! (:log this) #(conj % [:login :start params]))
  app-db)

(defmethod controller/stop LoginController [this params app-db]
  (swap! (:log this) #(conj % [:login :stop params]))
  app-db)

(defrecord DashboardController [log])

(defmethod controller/params DashboardController [_ route-params]
  (when (= "dashboard" (get-in route-params [:data :page]))
    :dashboard))

(defmethod controller/start DashboardController [this params app-db]
  (swap! (:log this) #(conj % [:dashboard :start params]))
  app-db)

(defmethod controller/stop DashboardController [this params app-db]
  (swap! (:log this) #(conj % [:dashboard :stop params]))
  app-db)


(deftest reroute
  (let [[c unmount] (make-container)
        current-hash (.-hash (.-location js/window))
        _ (set! (.-hash (.-location js/window)) "!dashboard")
        log (atom [])
        app (app-state/start!
             {:html-element c
              :routes [":page"]
              :controllers {:reroute (->RerouteController log)
                            :dashboard (->DashboardController log)
                            :login (->LoginController log)}
              :route-processor (fn [route app-db]
                                 (let [user (get-in app-db [:kv :user])]
                                   (if user
                                     route
                                     (assoc-in route [:data :page] "login"))))
              :components {:main {:renderer (fn [_]
                                              [:div "HELLO WORLD"])}}})
        app-db-atom (:app-db app)]
    (async done
           (go
             (<! (timeout 20))
             (is (= "login" (get-in @app-db-atom [:route :data :page])))
             (swap! app-db-atom assoc-in [:kv :user] {:id 1})
             (controller/reroute (get-in @app-db-atom [:internal :running-controllers :reroute]))
             (<! (timeout 20))
             (is (= "dashboard" (get-in @app-db-atom [:route :data :page])))
             (app-state/stop! app (fn []
                                    (is (= [[:reroute :start true]
                                            [:login :start :login]
                                            [:login :stop :login]
                                            [:dashboard :start :dashboard]
                                            [:dashboard :stop :dashboard]]
                                           @log))
                                    (unmount)
                                    (set! (.-hash (.-location js/window)) current-hash)
                                    (done)))))))

(deftest reroute-from-ui
  (let [[c unmount] (make-container)
        current-hash (.-hash (.-location js/window))
        _ (set! (.-hash (.-location js/window)) "!dashboard")
        log (atom [])
        ui-reroute (atom identity)
        app (app-state/start!
             {:html-element c
              :routes [":page"]
              :controllers {:reroute (->RerouteController log)
                            :dashboard (->DashboardController log)
                            :login (->LoginController log)}
              :route-processor (fn [route app-db]
                                 (let [user (get-in app-db [:kv :user])]
                                   (if user
                                     route
                                     (assoc-in route [:data :page] "login"))))
              :components {:main {:renderer (fn [ctx]
                                              (reset! ui-reroute #(ui/reroute ctx))
                                              [:div "HELLO WORLD"])}}})
        app-db-atom (:app-db app)]
    (async done
           (go
             (<! (timeout 20))
             (is (= "login" (get-in @app-db-atom [:route :data :page])))
             (swap! app-db-atom assoc-in [:kv :user] {:id 1})
             (@ui-reroute)
             (<! (timeout 20))
             (is (= "dashboard" (get-in @app-db-atom [:route :data :page])))
             (app-state/stop! app (fn []
                                    (is (= [[:reroute :start true]
                                            [:login :start :login]
                                            [:login :stop :login]
                                            [:dashboard :start :dashboard]
                                            [:dashboard :stop :dashboard]]
                                           @log))
                                    (unmount)
                                    (set! (.-hash (.-location js/window)) current-hash)
                                    (done)))))))

(defrecord AppStartController [inner-app])

(defmethod controller/params AppStartController [_ _] true)
;; When the controller is started, start the inner app and save
;; it's definition inside the app-db
(defmethod controller/start AppStartController [this params app-db]
  (is (boolean (controller/router this)))
  (assoc app-db :inner-app (app-state/start! (:inner-app this) false)))
(defmethod controller/wake AppStartController [this params app-db]
  (let [serialized-app (:inner-app app-db)]
    (assoc app-db :inner-app (app-state/start! (assoc (:inner-app this) :initial-data serialized-app) false))))
(defmethod controller/stop AppStartController [this params app-db]
  (app-state/stop! (:inner-app app-db))
  app-db)

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
             (app-state/stop! outer-app (fn []
                                          (unmount)
                                          (done)))))))

(defrecord RedirectController [])

(defmethod controller/start RedirectController [this params app-db]
  (controller/redirect this {:foo "bar"})
  app-db)

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
             (app-state/stop! app (fn []
                                    (unmount)
                                    (done)))))))

(defrecord RedirectControllerBack [])

(defmethod controller/params RedirectControllerBack [_ _] true)

(defmethod controller/start RedirectControllerBack [this params app-db]
  (controller/redirect this nil :back)
  app-db)

(deftest redirect-from-controller-back []
  (set! (.-hash js/location) "!?baz=qux")
  (set! (.-hash js/location) "!?qux=foo")
  (is (= (.. js/window -location -hash) "#!?qux=foo"))
  (let [[c unmount] (make-container) 
        app (app-state/start!
             {:html-element c
              :controllers {:redirect (->RedirectControllerBack)}
              :components {:main {:renderer (fn [_] [:div])}}})]
    (async done
           (go
             (<! (timeout 100))
             (is (= (.. js/window -location -hash) "#!?baz=qux"))
             (set! (.-hash js/location) "")
             (app-state/stop! app (fn []
                                    (unmount)
                                    (done)))))))

(deftest redirect-from-controller-back-history-router []
  (let [current-href (.-href js/location)]
    (.pushState js/history nil "" "?baz=qux")
    (.pushState js/history nil "" "?qux=foo")
    (is (= (.. js/window -location -search) "?qux=foo"))
    (let [[c unmount] (make-container) 
          app (app-state/start!
               {:html-element c
                :router :history
                :controllers {:redirect (->RedirectControllerBack)}
                :components {:main {:renderer (fn [_] [:div])}}})]
      (async done
             (go
               (<! (timeout 100))
               (is (= (.. js/window -location -search) "?baz=qux"))
               (.pushState js/history nil "", current-href)
               (app-state/stop! app (fn []
                                      (unmount)
                                      (done))))))))

(deftest redirect-from-component []
  (let [[c unmount] (make-container)
        app (app-state/start!
             {:html-element c 
              :components {:main {:renderer
                                  (fn [ctx]
                                    (is (boolean (ui/router ctx)))
                                    [:button
                                     {:on-click #(ui/redirect ctx {:baz "qux"})}
                                     "click"])}}})]
    (async done
           (go
             (<! (timeout 100))
             (let [button-node (sel1 c [:button])]
               (sim/click button-node nil)
               (<! (timeout 100))
               (is (= (.. js/window -location -hash) "#!?baz=qux"))
               (set! (.-hash js/location) "")
               (app-state/stop! app (fn []
                                      (unmount)
                                      (done))) )))))


(defrecord ClickController [])

(defmethod controller/handler ClickController [_ app-state-atom in-chan _]
  (controller/dispatcher app-state-atom in-chan
                         {:inc-counter (fn [app-state-atom]
                                         (swap! app-state-atom assoc-in [:kv :counter]
                                                (inc (get-in @app-state-atom [:kv :counter]))))}))

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
        app-v1 (app-state/start! app-definition)]
    (async done
           (go
             (<! (timeout 100))
             (let [h1-v1 (sel1 c [:h1])]
               (is (= (.-innerText h1-v1) "0"))
               (sim/click (sel1 c [:button]) nil)
               (<! (timeout 10))
               (is (= (.-innerText h1-v1) "1"))
               (app-state/stop! app-v1)
               (<! (timeout 10))
               (let [app-v2 (app-state/start! app-definition)]
                 (<! (timeout 100))
                 (let [h1-v2 (sel1 c [:h1])]
                   (is (= (.-innerText h1-v2) "0"))
                   (app-state/stop! app-v2)
                   (<! (timeout 10))
                   (let [app-v3 (app-state/start! app-definition)]
                     (<! (timeout 100))
                     (let [h1-v3 (sel1 c [:h1])]
                       (app-state/restore-app-db app-v1 app-v3)
                       (<! (timeout 50))
                       (is (= (.-innerText h1-v3) "1"))
                       (sim/click (sel1 c [:button]) nil)
                       (<! (timeout 10))
                       (is (= (.-innerText h1-v3) "2"))
                       (app-state/stop! app-v3)
                       (unmount)
                       (done))))))))))

(defrecord ReactNativeController [route-atom])

(defmethod controller/params ReactNativeController [this route]
  (reset! (:route-atom this) route)
  true)

(defmethod controller/handler ReactNativeController [this app-db-atom in-chan _]
  (controller/dispatcher app-db-atom in-chan
                         {:route-changed (fn [app-db-atom value]
                                           (reset! (:route-atom this) value))}))

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
                    (:data @route-atom)))
             (rn-router/navigate! :push {:key :foo})
             (<! (timeout 10))
             (is (= {:index 1
                     :key :foo
                     :routes [{:key :init}
                              {:key :foo}]}
                    (:data @route-atom)))
             (rn-router/navigate! :push {:key :bar})
             (<! (timeout 10))
             (is (= {:index 2
                     :key :bar
                     :routes [{:key :init}
                              {:key :foo}
                              {:key :bar}]}
                    (:data @route-atom)))
             (rn-router/navigate! :pop)
             (<! (timeout 10))
             (is (= {:index 1
                     :key :foo
                     :routes [{:key :init}
                              {:key :foo}]}
                    (:data @route-atom)))
             (rn-router/navigate! :home)
             (<! (timeout 10))
             (is (= {:index 0
                     :key :init
                     :routes [{:key :init}]}
                    (:data @route-atom)))
             (rn-router/navigate! :pop)
             (<! (timeout 10))
             (is (= {:index 0
                     :key :init
                     :routes [{:key :init}]}
                    (:data @route-atom)))
             (done)))))


(defrecord ControllerA [kv-state-atom])

(defmethod controller/params ControllerA [_ _] true)
(defmethod controller/handler ControllerA [this app-db-atom _ _]
  (swap! app-db-atom assoc-in [:kv :foo] :bar)
  (reset! (:kv-state-atom this) (:kv @app-db-atom)))



(defrecord ControllerB [kv-state-atom])

(defmethod controller/params ControllerB [_ _] true)
(defmethod controller/start ControllerB [_ _ app-db]
  (assoc-in app-db [:kv :start] :value))
(defmethod controller/handler ControllerB [this app-db-atom _ _]
  (js/setTimeout (fn []
                   (swap! app-db-atom assoc-in [:kv :baz] :qux)
                   (reset! (:kv-state-atom this) (:kv @app-db-atom))) 10))

(deftest controller-kv-test []
  (let [kv-state-atom (atom nil)
        app-definition {:controllers {:a (->ControllerA kv-state-atom)
                                      :b (->ControllerB kv-state-atom)}
                        :components {:main {:renderer (fn [ctx])}}}] 
    (async done
           (go
             (app-state/start! app-definition false)
             (<! (timeout 1))
             (is (= @kv-state-atom {:foo :bar :start :value}))
             (<! (timeout 20))
             (is (= @kv-state-atom {:foo :bar :baz :qux :start :value}))
             (done)))))

(defrecord ControllerChangeNumber [])

(defmethod controller/params ControllerChangeNumber [_ _] true)
(defmethod controller/start ControllerChangeNumber [_ _ app-db]
  (assoc-in app-db [:kv :number] 42))

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
             (<! (timeout 20))
             (is (= "<div>42</div><div>42</div>" (.-innerHTML (sel1 c [:.main-subscriptions]))))
             (swap! (:app-db app) assoc-in [:kv :number] 43)
             (<! (timeout 20))
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
             (<! (timeout 20))
             (is (= "<div>43</div><div>44</div>" (.-innerHTML (sel1 c [:.main-subscriptions]))))
             (swap! (:app-db app) assoc-in [:kv :number] 43)
             (<! (timeout 20))
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
             (<! (timeout 20))
             (is (= "<div>42</div><div>42</div>" (.-innerHTML (sel1 c [:.main-subscriptions]))))
             (swap! (:app-db app) assoc-in [:kv :number] 43)
             (<! (timeout 20))
             (is (= "<div>43</div><div>43</div>" (.-innerHTML (sel1 c [:.main-subscriptions]))))
             (<! (timeout 20))
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
             (<! (timeout 20))
             (is (= "<div>43</div><div>44</div>" (.-innerHTML (sel1 c [:.main-subscriptions]))))
             (swap! (:app-db app) assoc-in [:kv :number] 43)
             (<! (timeout 20))
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
             (<! (timeout 20))
             (is (= "FOO =" (.-innerText (sel1 c [:.main-route]))))
             (set! (.-hash js/location) "#!?foo=foo")
             (<! (timeout 20))
             (is (= "FOO = foo" (.-innerText (sel1 c [:.main-route]))))
             (swap! (:app-db app) assoc-in [:kv :foo] "bar")
             (<! (timeout 20))
             (is (= 2 @renderer-call-count))
             (app-state/stop! app)
             (unmount)
             (set! (.-hash js/location) "")
             (<! (timeout 20))
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
             (<! (timeout 20))
             (is (= "FOO =" (.-innerText c)))
             (set! (.-hash js/location) "#!?foo=foo")
             (<! (timeout 20))
             (is (= "FOO = foo" (.-innerText c)))
             (swap! (:app-db app) assoc-in [:kv :foo] "bar")
             (<! (timeout 20))
             (is (= 2 @renderer-call-count))
             (app-state/stop! app)
             (unmount)
             (set! (.-hash js/location) "")
             (<! (timeout 20))
             (done)))))

(deftest hashchange-router-redirect
  (let [[c unmount] (make-container)
        _ (set! (.-hash js/location) "!page-name?baz=qux")
        app-definition {:html-element c
                        :controllers {}
                        :router :hashchange
                        :routes [":page"]
                        :components {:main {:renderer
                                            (fn [ctx]
                                              [:div
                                               [:span.link-el
                                                {:on-click #(ui/redirect ctx {:page "page-name2" :foo "Bar"})}
                                                "Link"]
                                               [:span.replace-link-el
                                                {:on-click #(ui/redirect ctx {:page "page-name2" :foo "Bar"} :replace)}
                                                "Replace"]
                                               [:span.back-link-el
                                                {:on-click #(ui/redirect ctx nil :back)}
                                                "Back"]])}}}
        app (app-state/start! app-definition)]
    (async done
           (go
             (<! (timeout 20))
             (is (= (get-in @(:app-db app) [:route :data])
                    {:baz "qux"
                     :page "page-name"}))
             (sim/click (sel1 c [:.link-el]) {:button 0})
             (<! (timeout 20))
             (is (= (get-in @(:app-db app) [:route :data])
                    {:foo "Bar"
                     :page "page-name2"}))
             (sim/click (sel1 c [:.back-link-el]) {:button 0})
             (<! (timeout 20))
             (is (= (get-in @(:app-db app) [:route :data])
                    {:baz "qux"
                     :page "page-name"}))
             (let [back-to-route (get-in @(:app-db app) [:route :data])]
               (sim/click (sel1 c [:.replace-link-el]) {:button 0})
               (<! (timeout 20))
               (is (= (get-in @(:app-db app) [:route :data])
                      {:foo "Bar"
                       :page "page-name2"}))
               (app-state/stop! app)
               (unmount)
               (set! (.-hash js/location) "")
               (<! (timeout 20))
               (done))))))

(deftest history-base-href
  (is (= "/app/" (history-router/process-base-href "app")))
  (is (= "/app/" (history-router/process-base-href "/app")))
  (is (= "/app/" (history-router/process-base-href "app/"))))

(deftest history-router
  (let [[c unmount] (make-container)
        current-href (.-href js/location)
        _ (.pushState js/history nil "", "page-name?baz=qux")
        app-definition {:html-element c
                        :controllers {}
                        :router :history
                        :routes [":page"]
                        :components {:main {:renderer
                                            (fn [ctx]
                                              [:a {:href (ui/url ctx {:page "page-name2" :foo "Bar"})}
                                               [:span.link-el "Link"]])}}}
        app (app-state/start! app-definition)]
    (async done
           (go
             (<! (timeout 20))
             (is (= 1 ((:handlers-count history-router/urlchange-dispatcher))))
             (is (= (get-in @(:app-db app) [:route :data])
                    {:baz "qux"
                     :page "page-name"}))
             (sim/click (sel1 c [:.link-el]) {:button 0})
             (<! (timeout 20))
             (is (= (get-in @(:app-db app) [:route :data])
                    {:foo "Bar"
                     :page "page-name2"}))
             (app-state/stop! app)
             (unmount)
             (.pushState js/history nil "", current-href)
             (<! (timeout 20))
             (is (= 0 ((:handlers-count history-router/urlchange-dispatcher))))
             (done)))))

(deftest history-router-base-href
  (let [[c unmount] (make-container)
        current-href (.-href js/location)
        _ (.pushState js/history nil "", "/app/page-name?baz=qux")
        app-definition {:html-element c
                        :controllers {}
                        :base-href "app"
                        :router :history
                        :routes [":page"]
                        :components {:main {:renderer
                                            (fn [ctx]
                                              [:a {:href (ui/url ctx {:page "page-name2" :foo "Bar"})}
                                               [:span.link-el "Link"]])}}}
        app (app-state/start! app-definition)]
    (async done
           (go
             (<! (timeout 20))
             (is (= 1 ((:handlers-count history-router/urlchange-dispatcher))))
             (is (= (get-in @(:app-db app) [:route :data])
                    {:baz "qux"
                     :page "page-name"}))
             (sim/click (sel1 c [:.link-el]) {:button 0})
             (<! (timeout 20))
             (is (= (.-pathname js/location) "/app/page-name2"))
             (is (= (get-in @(:app-db app) [:route :data])
                    {:foo "Bar"
                     :page "page-name2"}))
             (app-state/stop! app)
             (unmount)
             (.pushState js/history nil "", current-href)
             (<! (timeout 20))
             (is (= 0 ((:handlers-count history-router/urlchange-dispatcher))))
             (done)))))

(deftest history-router-redirect
  (let [[c unmount] (make-container)
        current-href (.-href js/location)
        _ (.pushState js/history nil "", "page-name?baz=qux")
        app-definition {:html-element c
                        :controllers {}
                        :router :history
                        :routes [":page"]
                        :components {:main {:renderer
                                            (fn [ctx]
                                              [:div
                                               [:span.link-el
                                                {:on-click #(ui/redirect ctx {:page "page-name2" :foo "Bar"})}
                                                "Link"]
                                               [:span.replace-link-el
                                                {:on-click #(ui/redirect ctx {:page "page-name2" :foo "Bar"} :replace)}
                                                "Replace"]
                                               [:span.back-link-el
                                                {:on-click #(ui/redirect ctx nil :back)}
                                                "Back"]])}}}
        app (app-state/start! app-definition)]
    (async done
           (go
             (<! (timeout 20))
             (is (= 1 ((:handlers-count history-router/urlchange-dispatcher))))
             (is (= (get-in @(:app-db app) [:route :data])
                    {:baz "qux"
                     :page "page-name"}))
             (sim/click (sel1 c [:.link-el]) {:button 0})
             (<! (timeout 20))
             (is (= (get-in @(:app-db app) [:route :data])
                    {:foo "Bar"
                     :page "page-name2"}))
             (sim/click (sel1 c [:.back-link-el]) {:button 0})
             (<! (timeout 20))
             (is (= (get-in @(:app-db app) [:route :data])
                    {:baz "qux"
                     :page "page-name"}))
             (let [back-to-route (get-in @(:app-db app) [:route :data])]
               (sim/click (sel1 c [:.replace-link-el]) {:button 0})
               (<! (timeout 20))
               (is (= (get-in @(:app-db app) [:route :data])
                      {:foo "Bar"
                       :page "page-name2"}))
               (app-state/stop! app)
               (unmount)
               (.pushState js/history nil "", current-href)
               (<! (timeout 20))
               (is (= 0 ((:handlers-count history-router/urlchange-dispatcher))))
               (done))))))

(deftest history-router-2-apps
  (let [[c1 unmount1] (make-container)
        [c2 unmount2] (make-container)
        current-href (.-href js/location)
        _ (.pushState js/history nil "", "test")
        app-definition1 {:html-element c1
                         :controllers {}
                         :router :history
                         :routes [":page"]
                         :components {:main {:renderer
                                             (fn [ctx]
                                               [:a {:href (ui/url ctx {:page "app1" :foo "Bar"})}
                                                [:span.link-el "Link"]])}}}
        app-definition2 {:html-element c2
                         :controllers {}
                         :router :history
                         :routes [":page"]
                         :components {:main {:renderer
                                             (fn [ctx]
                                               [:a {:href (ui/url ctx {:page "app2" :baz "Qux"})}
                                                [:span.link-el "Link"]])}}}
        app1 (app-state/start! app-definition1)
        app2 (app-state/start! app-definition2)]
    (async done
           (go
             (<! (timeout 20))
             (is (= 2 ((:handlers-count history-router/urlchange-dispatcher))))

             (is (= (get-in @(:app-db app1) [:route :data])
                    (get-in @(:app-db app2) [:route :data])
                    {:page "test"}))

             (sim/click (sel1 c1 [:.link-el]) {:button 0})
             (<! (timeout 20))
             (is (= (get-in @(:app-db app1) [:route :data])
                    (get-in @(:app-db app2) [:route :data])
                    {:page "app1"
                     :foo "Bar"}))
             
             (sim/click (sel1 c2 [:.link-el]) {:button 0})
             (<! (timeout 20))
             (is (= (get-in @(:app-db app1) [:route :data])
                    (get-in @(:app-db app2) [:route :data])
                    {:page "app2"
                     :baz "Qux"}))
             (app-state/stop! app1)
             (app-state/stop! app2)
             (unmount1)
             (unmount2)
             (.pushState js/history nil "", current-href)
             (<! (timeout 20))

             (is (= 0 ((:handlers-count history-router/urlchange-dispatcher))))
             (done)))))

(deftest history-and-hashchange-router
  (let [[c1 unmount1] (make-container)
        [c2 unmount2] (make-container)
        current-href (.-href js/location)
        _ (.pushState js/history nil "", "test1#!test2")
        app-definition1 {:html-element c1
                         :controllers {}
                         :router :history
                         :routes [":page"]
                         :components {:main {:renderer
                                             (fn [ctx]
                                               [:a {:href (ui/url ctx {:page "app1" :foo "Bar"})}
                                                [:span.link-el "Link"]])}}}
        app-definition2 {:html-element c2
                         :controllers {}
                         :router :hashchange
                         :routes [":page"]
                         :components {:main {:renderer
                                             (fn [ctx]
                                               [:a#hashtag-link {:href (ui/url ctx {:page "app2" :baz "Qux"})} "Link"])}}}
        app1 (app-state/start! app-definition1)
        app2 (app-state/start! app-definition2)]
    (async done
           (go
             (<! (timeout 20))

             (is (= (get-in @(:app-db app1) [:route :data])
                    {:page "test1"}))

             (is (= (get-in @(:app-db app2) [:route :data])
                    {:page "test2"}))

             (is (= "/test1" (.-pathname js/location)))
             (is (= "#!test2" (.-hash js/location)))


             (sim/click (sel1 c1 [:.link-el]) {:button 0})
             (<! (timeout 20))

             (is (= (get-in @(:app-db app1) [:route :data])
                    {:page "app1" :foo "Bar"}))

             (is (= (get-in @(:app-db app2) [:route :data])
                    {:page "test2"}))

             (is (= "/app1" (.-pathname js/location)))
             (is (= "?foo=Bar" (.-search js/location)))
             (is (= "#!test2" (.-hash js/location)))
             
             (.click (.getElementById js/document "hashtag-link"))
             (<! (timeout 20))

             (is (= (get-in @(:app-db app1) [:route :data])
                    {:page "app1" :foo "Bar"}))

             (is (= (get-in @(:app-db app2) [:route :data])
                    {:page "app2" :baz "Qux"}))

             (is (= "/app1" (.-pathname js/location)))
             (is (= "?foo=Bar" (.-search js/location)))
             (is (= "#!app2?baz=Qux" (.-hash js/location)))
             
             
             (app-state/stop! app1)
             (app-state/stop! app2)
             (unmount1)
             (unmount2)
             (.pushState js/history nil "", current-href)
             (<! (timeout 20))

             (done)))))

(deftest hashchange-router-inside-history-router
  (let [[c1 unmount1] (make-container)
        [c2 unmount2] (make-container)
        current-href (.-href js/location)
        _ (.pushState js/history nil "", "test1#!test2")

        inner-app-definition {:controllers {}
                              :router :hashchange
                              :routes [":page"]
                              :components {:main {:renderer
                                                  (fn [ctx]
                                                    [:a#hashtag-link {:href (ui/url ctx {:page "inner-app" :baz "Qux"})} "Inner Link"])}}}

        inner-app (app-state/start! inner-app-definition false)

        outer-app-definition {:html-element c1
                              :controllers {}
                              :router :history
                              :routes [":page"]
                              :components {:main {:renderer
                                                  (fn [ctx]
                                                    [:div
                                                     [:a {:href (ui/url ctx {:page "outer-app" :foo "Bar"})}
                                                      [:span.link-el "Outer Link"]]
                                                     [:div
                                                      [(:main-component inner-app)]]])}}}
        
        outer-app (app-state/start! outer-app-definition)]
    (async done
           (go
             (<! (timeout 20))

             (is (= (get-in @(:app-db outer-app) [:route :data])
                    {:page "test1"}))

             (is (= (get-in @(:app-db inner-app) [:route :data])
                    {:page "test2"}))

             (is (= "/test1" (.-pathname js/location)))
             (is (= "#!test2" (.-hash js/location)))


             (sim/click (sel1 c1 [:.link-el]) {:button 0})
             (<! (timeout 20))

             (is (= (get-in @(:app-db outer-app) [:route :data])
                    {:page "outer-app" :foo "Bar"}))

             (is (= (get-in @(:app-db inner-app) [:route :data])
                    {:page "test2"}))

             (is (= "/outer-app" (.-pathname js/location)))
             (is (= "?foo=Bar" (.-search js/location)))
             (is (= "#!test2" (.-hash js/location)))
             
             (.click (.getElementById js/document "hashtag-link"))
             (<! (timeout 20))

             (is (= (get-in @(:app-db outer-app) [:route :data])
                    {:page "outer-app" :foo "Bar"}))

             (is (= (get-in @(:app-db inner-app) [:route :data])
                    {:page "inner-app" :baz "Qux"}))

             (is (= "/outer-app" (.-pathname js/location)))
             (is (= "?foo=Bar" (.-search js/location)))
             (is (= "#!inner-app?baz=Qux" (.-hash js/location)))
             
             
             (app-state/stop! outer-app)
             (app-state/stop! inner-app)
             (unmount1)
             (unmount2)
             (.pushState js/history nil "" current-href)
             (<! (timeout 20))

             (done)))))


(deftest history-router-back
  (let [[c unmount] (make-container)
        current-href (.-href js/location)
        _ (.pushState js/history nil "", "page-name?baz=qux")
        app-definition {:html-element c
                        :controllers {}
                        :router :history
                        :routes [":page"]
                        :components {:main {:renderer
                                            (fn [ctx]
                                              [:a {:href (ui/url ctx {:page "page-name2" :foo "Bar"})}
                                               [:span.link-el "Link"]])}}}
        app (app-state/start! app-definition)]
    (async done
           (go
             (<! (timeout 20))
             (is (= 1 ((:handlers-count history-router/urlchange-dispatcher))))
             (is (= (get-in @(:app-db app) [:route :data])
                    {:baz "qux"
                     :page "page-name"}))
             (sim/click (sel1 c [:.link-el]) {:button 0})
             (<! (timeout 20))
             (is (= (get-in @(:app-db app) [:route :data])
                    {:foo "Bar"
                     :page "page-name2"}))

             (.back js/history)
             (<! (timeout 20))
             (is (= (get-in @(:app-db app) [:route :data])
                    {:baz "qux"
                     :page "page-name"}))

             (app-state/stop! app)
             (unmount)
             (.pushState js/history nil "", current-href)
             (<! (timeout 20))
             (is (= 0 ((:handlers-count history-router/urlchange-dispatcher))))
             (done)))))

(defrecord ContextController [])

(defmethod controller/params ContextController [_ _] true)
(defmethod controller/start ContextController [this _ app-db]
  (let [context-fn-1 (controller/context this :context-fn-1)
        context-fn-2 (controller/context this [:context :fn-2])]
    (context-fn-1)
    (context-fn-2)
    app-db))


(deftest passing-context-to-controllers
  (let [call-count (atom 0)
        context-fn-1 (fn [] (swap! call-count inc))
        context-fn-2 (fn [] (swap! call-count #(+ % 2)))
        [c unmount] (make-container)
        app-definition {:controllers {:context (->ContextController)}
                        :html-element c
                        :context {:context-fn-1 context-fn-1
                                  :context {:fn-2 context-fn-2}}
                        :components {:main {:renderer (fn [_] [:div "test"])}}}
        app (app-state/start! app-definition)]
    (async done
           (go
             (<! (timeout 20))
             (is (= 3 @call-count))
             (app-state/stop! app)
             (unmount)
             (done)))))

(defrecord ContextController2 [])

(defmethod controller/params ContextController2 [_ _] true)
(defmethod controller/start ContextController2 [this _ app-db]
  (let [context-fn-1 (controller/context this)]
    (context-fn-1)
    app-db))

(deftest passing-context-to-controllers2
  (let [call-count (atom 0)
        context-fn-1 (fn [] (swap! call-count inc))
        [c unmount] (make-container)
        app-definition {:controllers {:context (->ContextController2)}
                        :html-element c
                        :context  context-fn-1
                        :components {:main {:renderer (fn [_] [:div "test"])}}}
        app (app-state/start! app-definition)]
    (async done
           (go
             (<! (timeout 20))
             (is (= 1 @call-count))
             (app-state/stop! app)
             (unmount)
             (done)))))

(defn ssr-to-chan [app-definition route]
  (let [res-chan (chan)]
    (ssr/render app-definition route (fn [res] (put! res-chan res)))
    res-chan))

(deftest basic-ssr
  (let [[c unmount] (make-container)
        current-href (.-href js/location)
        app-definition {:html-element c
                        :controllers {}
                        :router :history
                        :routes [":page"]
                        :components {:main {:renderer
                                            (fn [ctx]
                                              [:a {:href (ui/url ctx {:page "page-name2" :foo "Bar"})}
                                               [:span.link-el "Link"]])}}}]
    (async done
           (go
             (let [{:keys [html app-state]} (<! (ssr-to-chan app-definition ""))]
               (set! (.-innerHTML c) html)
               (is (= "Link" (.-innerText c)))
               (let [app (app-state/start! app-definition)]
                 (<! (timeout 20))
                 (is (= "Link" (.-innerText c)))
                 (sim/click (sel1 c [:.link-el]) {:button 0})
                 (<! (timeout 20))
                 (is (= (get-in @(:app-db app) [:route :data])
                        {:foo "Bar"
                         :page "page-name2"}))
                 (app-state/stop! app)
                 (unmount)
                 (.pushState js/history nil "", current-href)
                 (done)))))))

(deftest basic-ssr-with-route-processor
  (let [[c unmount] (make-container)
        current-href (.-href js/location)
        app-definition {:html-element c
                        :controllers {}
                        :router :history
                        :route-processor (fn [route]
                                           (update-in route [:data :page] #(str/upper-case (or % ""))))
                        :routes [":page"]
                        :components {:main {:renderer
                                            (fn [ctx]
                                              [:a {:href (ui/url ctx {:page "page-name2" :foo "Bar"})}
                                               [:span.link-el "Link"]])}}}]
    (async done
           (go
             (let [{:keys [html app-state]} (<! (ssr-to-chan app-definition "/page-name1"))]
               (is (= (get-in (app-state/deserialize-app-state {} app-state) [:app-db :route :data :page])
                      "PAGE-NAME1"))
               (set! (.-innerHTML c) html)
               (is (= "Link" (.-innerText c)))
               (let [app (app-state/start! app-definition)]
                 (<! (timeout 20))
                 (is (= "Link" (.-innerText c)))
                 (sim/click (sel1 c [:.link-el]) {:button 0})
                 (<! (timeout 20))
                 (is (= (get-in @(:app-db app) [:route :data])
                        {:foo "Bar"
                         :page "PAGE-NAME2"}))
                 (app-state/stop! app)
                 (unmount)
                 (.pushState js/history nil "" current-href)
                 (done)))))))

(deftest multiple-apps-ssr []
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
        outer-app {:controllers {:main (->AppStartController inner-app)}
                   :html-element c
                   :router :history
                   :components {:main {:renderer outer-app-renderer
                                       :subscription-deps [:inner-app]}}
                   :subscriptions {:inner-app inner-app-sub}}]
    (async done
           (go
             (let [{:keys [html app-state]} (<! (ssr-to-chan outer-app ""))]
               (set! (.-innerHTML c) html)
               (is (= "INNER APP" (.-innerText c)))
               (let [app-config (assoc outer-app :initial-data (app-state/deserialize-app-state {} app-state))
                     app (app-state/start! app-config)]
                 (is (= "INNER APP" (.-innerText c)))
                 (unmount)
                 (done)))))))

(defrecord AsyncSsrHandlerController [log])

(defmethod controller/params AsyncSsrHandlerController [_ _] true)
(defmethod controller/start AsyncSsrHandlerController [this params app-db]
  (swap! (:log this) conj :start)
  app-db)
(defmethod controller/wake AsyncSsrHandlerController [this params app-db]
  (swap! (:log this) conj :wake)
  app-db)
(defmethod controller/handler AsyncSsrHandlerController [this app-db-atom _ _]
  (swap! (:log this) conj :handler))
(defmethod controller/ssr-handler AsyncSsrHandlerController [this app-db-atom done _ _]
  (swap! (:log this) conj :ssr-handler-start)
  (js/setTimeout (fn []
                   (swap! app-db-atom assoc-in [:kv :message] "Hello World!")
                   (swap! (:log this) conj :ssr-handler-done)
                   (done)) 30))

(deftest async-handler-ssr []
  (let [[c unmount] (make-container)
        log (atom [])
        app-renderer
        (fn [ctx]
          [:div @(ui/subscription ctx :message)])
        ;; get the inner app from the app-db
        message-sub
        (fn [app-db]
          (reaction
           (get-in @app-db [:kv :message])))
        ;; definition of the outer app
        outer-app {:controllers {:main (->AsyncSsrHandlerController log)}
                   :html-element c
                   :router :history
                   :components {:main {:renderer app-renderer
                                       :subscription-deps [:message]}}
                   :subscriptions {:message message-sub}}]
    (async done
           (go
             (let [{:keys [html app-state]} (<! (ssr-to-chan outer-app ""))]
               (set! (.-innerHTML c) html)
               (is (= "Hello World!" (.-innerText c)))
               (let [app-config (assoc outer-app :initial-data (app-state/deserialize-app-state {} app-state))
                     app (app-state/start! app-config)]
                 (is (= "Hello World!" (.-innerText c)))
                 (is (= [:start :ssr-handler-start :ssr-handler-done :wake :handler] @log))
                 (unmount)
                 (done)))))))


(defrecord ControllerReturningFalseFromParams [])

(defmethod controller/params ControllerReturningFalseFromParams [this route-params] false)

(deftest controller-returning-false-from-params
  (let [[c unmount] (make-container)
        app-renderer (fn [ctx] [:div])
        app {:controllers {:main (->ControllerReturningFalseFromParams)}
             :html-element c
             :components {:main {:renderer app-renderer}}}]
    (async done
           (go
             (app-state/start! app)
             (<! (timeout 20))
             (unmount)
             (done)))))

(defn store-broadcast-message [controller app-db-atom payload]
  (swap! app-db-atom assoc-in [:kv (:name controller)] payload))

(defrecord BroadcastConsumerControllerA [])
(defmethod controller/params BroadcastConsumerControllerA [_ _] true)

(defmethod controller/handler BroadcastConsumerControllerA [this app-db-atom in-chan _]
  (controller/dispatcher
   app-db-atom
   in-chan
   {::broadcast (fn [app-db-atom args] (store-broadcast-message this app-db-atom args))}))

(defrecord BroadcastConsumerControllerB [])
(defmethod controller/params BroadcastConsumerControllerB [_ _] true)

(defmethod controller/handler BroadcastConsumerControllerB [this app-db-atom in-chan _]
  (controller/dispatcher
   app-db-atom
   in-chan
   {::broadcast (fn [app-db-atom args] (store-broadcast-message this app-db-atom args))}))

(defrecord BroadcastConsumerControllerC [])
(defmethod controller/params BroadcastConsumerControllerC [_ _] true)

(defmethod controller/handler BroadcastConsumerControllerC [this app-db-atom in-chan _]
  (controller/dispatcher
   app-db-atom
   in-chan
   {::broadcast (fn [app-db-atom args] (store-broadcast-message this app-db-atom args))}))

(defrecord BroadcastingController [])
(defmethod controller/params BroadcastingController [_ _] true)

(defmethod controller/handler BroadcastingController [this app-db-atom in-chan _]
  (controller/broadcast this ::broadcast ::payload)
  (controller/dispatcher
   app-db-atom
   in-chan
   {::broadcast (fn [app-db-atom args] (store-broadcast-message this app-db-atom args))}))


(deftest controller-broadcasting
  (let [[c unmount] (make-container)
        app-renderer (fn [ctx] [:div])
        app {:controllers {:a (->BroadcastConsumerControllerA)
                           :b (->BroadcastConsumerControllerA)
                           :c (->BroadcastConsumerControllerA)
                           :broadcaster (->BroadcastingController)}
             :html-element c
             :components {:main {:renderer app-renderer}}}]
    (async done
           (go
             (let [started-app (app-state/start! app)]
               (<! (timeout 20))
               (is (= {:a ::payload
                       :b ::payload
                       :c ::payload}
                      (:kv @(:app-db started-app))))
               (unmount)
               (done))))))

(deftest lifecycle-fns
  (let [[c unmount] (make-container)
        app-renderer (fn [ctx] [:div])
        app {:html-element c
             :components {:main {:renderer app-renderer}}
             :on {:start [(fn [c]
                            (assoc-in c [:lifecycle :log] [:start-1]))
                          (fn [c]
                            (update-in c [:lifecycle :log] #(conj % :start-2)))]
                  :stop [(fn [c]
                           (update-in c [:lifecycle :log] #(conj % :stop-1)))
                         (fn [c]
                           (update-in c [:lifecycle :log] #(conj % :stop-2)))]}}]
    (async done
           (go
             (let [started-app (app-state/start! app)]
               (app-state/stop! started-app
                                (fn [config]
                                  (is (= [:start-1 :start-2 :stop-1 :stop-2]
                                         (get-in config [:lifecycle :log])))
                                  (unmount)
                                  (done))))))))


(deftest lifecycle-fns-with-helpers
  (let [[c unmount] (make-container)
        app-renderer (fn [ctx] [:div])
        app (-> {:html-element c
                 :components {:main {:renderer app-renderer}}}
                (reg-on-start (fn [c] (assoc-in c [:lifecycle :log] [:start-1])))
                (reg-on-start (fn [c]
                                (update-in c [:lifecycle :log] #(conj % :start-2))))
                (reg-on-stop (fn [c]
                               (update-in c [:lifecycle :log] #(conj % :stop-1))))
                (reg-on-stop (fn [c]
                               (update-in c [:lifecycle :log] #(conj % :stop-2)))))]
    (async done
           (go
             (let [started-app (app-state/start! app)]
               (app-state/stop! started-app
                                (fn [config]
                                  (is (= [:start-1 :start-2 :stop-1 :stop-2]
                                         (get-in config [:lifecycle :log])))
                                  (unmount)
                                  (done))))))))

(deftest lifecycle-fns-ssr
  (let [[c unmount] (make-container)
        app-renderer (fn [ctx] [:div])
        app {:html-element c
             :router :history
             :components {:main {:renderer app-renderer}}
             :on {:start [(fn [c]
                            (swap! (:app-db c) assoc-in [:lifecycle :log] [:start-1])
                            c)
                          (fn [c]
                            (swap! (:app-db c) update-in [:lifecycle :log] #(conj % :start-2))
                            c)]
                  :stop [(fn [c]
                           (swap! (:app-db c) update-in [:lifecycle :log] #(conj % :stop-1))
                           c)
                         (fn [c]
                           (swap! (:app-db c) update-in [:lifecycle :log] #(conj % :stop-2))
                           c)]}}]

    (async done
           (go
             (let [{:keys [html app-state]} (<! (ssr-to-chan app ""))]
               (set! (.-innerHTML c) html)

               (is (= [:start-1 :start-2 :stop-1 :stop-2]
                      (get-in (app-state/deserialize-app-state {} app-state)
                              [:app-db :lifecycle :log])))
               
               (let [started-app
                     (app-state/start!
                      (assoc app :initial-data
                             (app-state/deserialize-app-state {} app-state)))]
                 
                 (app-state/stop!
                  started-app
                  (fn [config]
                    (let [app-db (deref (:app-db config))]
                      (is (= [:start-1 :start-2 :stop-1 :stop-2] 
                             (get-in app-db [:lifecycle :log]))))
                    (unmount)
                    (done)))))))))


(deftest memory-router-with-default-route
  (reset! memory-router/app-route-states-atom {})
  (let [[c unmount] (make-container)
        app-definition {:html-element c
                        :controllers {}
                        :router :memory
                        :routes [["" {:foo :bar}]]
                        :components {:main {:renderer
                                            (fn [ctx]
                                              [:div 
                                               [:span.push-link-el 
                                                {:on-click #(ui/redirect ctx {:push :baz})}
                                                "Push"]
                                               [:span.replace-link-el 
                                                {:on-click #(ui/redirect ctx {:replace :qux} :replace)}
                                                "Replace"]])}}}
        app (app-state/start! app-definition)]
    (async done
           (go
             (<! (timeout 20))
             (is (= (:route @(:app-db app))
                    {:data {:foo :bar}
                     :stack [{:foo :bar}]}))
             (sim/click (sel1 c [:.push-link-el]) {:button 0})
             (<! (timeout 20))
             (is (= (:route @(:app-db app))
                    {:data {:push :baz}
                     :stack [{:foo :bar} {:push :baz}]}))
             (sim/click (sel1 c [:.replace-link-el]) {:button 0})
             (<! (timeout 20))
             (is (= (:route @(:app-db app))
                    {:data {:replace :qux}
                     :stack [{:foo :bar} {:replace :qux}]}))
             (app-state/stop! app)
             (unmount)
             (done)))))

(deftest memory-router-without-default-route
  (reset! memory-router/app-route-states-atom {})
  (let [[c unmount] (make-container)
        app-definition {:html-element c
                        :controllers {}
                        :router :memory
                        :components {:main {:renderer
                                            (fn [ctx]
                                              [:div 
                                               [:span.push-link-el 
                                                {:on-click #(ui/redirect ctx {:push :baz})}
                                                "Push"]
                                               [:span.replace-link-el 
                                                {:on-click #(ui/redirect ctx {:replace :qux} :replace)}
                                                "Replace"]])}}}
        app (app-state/start! app-definition)]
    (async done
           (go
             (<! (timeout 20))
             (is (= (:route @(:app-db app))
                    {:data {}
                     :stack [{}]}))
             (sim/click (sel1 c [:.push-link-el]) {:button 0})
             (<! (timeout 20))
             (is (= (:route @(:app-db app))
                    {:data {:push :baz}
                     :stack [{} {:push :baz}]}))
             (sim/click (sel1 c [:.replace-link-el]) {:button 0})
             (<! (timeout 20))
             (is (= (:route @(:app-db app))
                    {:data {:replace :qux}
                     :stack [{} {:replace :qux}]}))
             (app-state/stop! app)
             (unmount)
             (done)))))

(deftest memory-router-go-back-with-default-route
  (reset! memory-router/app-route-states-atom {})
  (let [[c unmount] (make-container)
        app-definition {:html-element c
                        :controllers {}
                        :routes [["" {:foo :bar}]]
                        :router :memory
                        :components {:main {:renderer
                                            (fn [ctx]
                                              [:div 
                                               [:span.push-link-el 
                                                {:on-click #(ui/redirect ctx {:push :baz})}
                                                "Push"]
                                               [:span.replace-link-el 
                                                {:on-click #(ui/redirect ctx {:replace :qux} :replace)}
                                                "Replace"]
                                               [:span.back-link-el
                                                {:on-click #(ui/redirect ctx nil :back)}
                                                "Back"]])}}}
        app (app-state/start! app-definition)]
    (async done
           (go
             (<! (timeout 20))
             (is (= (:route @(:app-db app))
                    {:data {:foo :bar}
                     :stack [{:foo :bar}]}))
             (sim/click (sel1 c [:.push-link-el]) {:button 0})
             (<! (timeout 20))
             (is (= (:route @(:app-db app))
                    {:data {:push :baz}
                     :stack [{:foo :bar} {:push :baz}]}))
             (sim/click (sel1 c [:.replace-link-el]) {:button 0})
             (<! (timeout 20))
             (is (= (:route @(:app-db app))
                    {:data {:replace :qux}
                     :stack [{:foo :bar} {:replace :qux}]}))
             (sim/click (sel1 c [:.back-link-el]) {:button 0})
             (<! (timeout 20))
             (is (= (:route @(:app-db app))
                    {:data {:foo :bar}
                     :stack [{:foo :bar}]}))
             (sim/click (sel1 c [:.back-link-el]) {:button 0})
             (<! (timeout 20))
             (is (= (:route @(:app-db app))
                    {:data {:foo :bar}
                     :stack [{:foo :bar}]}))

             (app-state/stop! app)
             (unmount)
             (done)))))

(deftest memory-router-go-back-without-default-route
  (reset! memory-router/app-route-states-atom {})
  (let [[c unmount] (make-container)
        app-definition {:html-element c
                        :controllers {}
                        :router :memory
                        :components {:main {:renderer
                                            (fn [ctx]
                                              [:div 
                                               [:span.push-link-el 
                                                {:on-click #(ui/redirect ctx {:push :baz})}
                                                "Push"]
                                               [:span.replace-link-el 
                                                {:on-click #(ui/redirect ctx {:replace :qux} :replace)}
                                                "Replace"]
                                               [:span.back-link-el
                                                {:on-click #(ui/redirect ctx nil :back)}
                                                "Back"]])}}}
        app (app-state/start! app-definition)]
    (async done
           (go
             (<! (timeout 20))
             (is (= (:route @(:app-db app))
                    {:data {}
                     :stack [{}]}))
             (sim/click (sel1 c [:.push-link-el]) {:button 0})
             (<! (timeout 20))
             (is (= (:route @(:app-db app))
                    {:data {:push :baz}
                     :stack [{} {:push :baz}]}))
             (sim/click (sel1 c [:.replace-link-el]) {:button 0})
             (<! (timeout 20))
             (is (= (:route @(:app-db app))
                    {:data {:replace :qux}
                     :stack [{} {:replace :qux}]}))
             (sim/click (sel1 c [:.back-link-el]) {:button 0})
             (<! (timeout 20))
             (is (= (:route @(:app-db app))
                    {:data {}
                     :stack [{}]}))
             (sim/click (sel1 c [:.back-link-el]) {:button 0})
             (<! (timeout 20))
             (is (= (:route @(:app-db app))
                    {:data {}
                     :stack [{}]}))

             (app-state/stop! app)
             (unmount)
             (done)))))

(deftest memory-router-should-drop-part-of-stack-if-route-exists-in-stack
  (reset! memory-router/app-route-states-atom {})
  (let [[c unmount] (make-container)
        app-definition {:html-element c
                        :controllers {}
                        :router :memory
                        :components {:main {:renderer
                                            (fn [ctx]
                                              [:div 
                                               [:span.link-el-1 
                                                {:on-click #(ui/redirect ctx {:foo :bar})}
                                                "1"]
                                               [:span.link-el-2 
                                                {:on-click #(ui/redirect ctx {:baz :qux})}
                                                "2"]])}}}
        app (app-state/start! app-definition)]
    (async done
           (go
             (<! (timeout 20))
             (is (= (:route @(:app-db app))
                    {:data {}
                     :stack [{}]}))
             (sim/click (sel1 c [:.link-el-1]) {:button 0})
             (<! (timeout 20))
             (is (= (:route @(:app-db app))
                    {:data {:foo :bar}
                     :stack [{} {:foo :bar}]}))
             (sim/click (sel1 c [:.link-el-2]) {:button 0})
             (<! (timeout 20))
             (is (= (:route @(:app-db app))
                    {:data {:baz :qux}
                     :stack [{} {:foo :bar} {:baz :qux}]}))
             (sim/click (sel1 c [:.link-el-1]) {:button 0})
             (<! (timeout 20))
             (is (= (:route @(:app-db app))
                    {:data {:foo :bar}
                     :stack [{} {:foo :bar}]}))
             (sim/click (sel1 c [:.link-el-1]) {:button 0})
             (<! (timeout 20))
             (is (= (:route @(:app-db app))
                    {:data {:foo :bar}
                     :stack [{} {:foo :bar}]}))

             (app-state/stop! app)
             (unmount)
             (done)))))

(deftest memory-router-should-drop-part-of-stack-if-route-exists-in-stack-with-default-route
  (reset! memory-router/app-route-states-atom {})
  (let [[c unmount] (make-container)
        app-definition {:html-element c
                        :controllers {}
                        :router :memory
                        :routes [["" {:baz :qux}]]
                        :components {:main {:renderer
                                            (fn [ctx]
                                              [:div 
                                               [:span.link-el-1 
                                                {:on-click #(ui/redirect ctx {:foo :bar})}
                                                "1"]
                                               [:span.link-el-2 
                                                {:on-click #(ui/redirect ctx {:baz :qux})}
                                                "2"]])}}}
        app (app-state/start! app-definition)]
    (async done
           (go
             (<! (timeout 20))
             (is (= (:route @(:app-db app))
                    {:data {:baz :qux}
                     :stack [{:baz :qux}]}))
             (sim/click (sel1 c [:.link-el-1]) {:button 0})
             (<! (timeout 20))
             (is (= (:route @(:app-db app))
                    {:data {:foo :bar}
                     :stack [{:baz :qux} {:foo :bar}]}))
             (sim/click (sel1 c [:.link-el-2]) {:button 0})
             (<! (timeout 20))
             (is (= (:route @(:app-db app))
                    {:data {:baz :qux}
                     :stack [{:baz :qux}]}))
             (sim/click (sel1 c [:.link-el-1]) {:button 0})
             (<! (timeout 20))
             (is (= (:route @(:app-db app))
                    {:data {:foo :bar}
                     :stack [{:baz :qux} {:foo :bar}]}))
             (sim/click (sel1 c [:.link-el-1]) {:button 0})
             (<! (timeout 20))
             (is (= (:route @(:app-db app))
                    {:data {:foo :bar}
                     :stack [{:baz :qux} {:foo :bar}]}))

             (app-state/stop! app)
             (unmount)
             (done)))))


(deftest memory-router-knows-how-to-restore-route-state
  (reset! memory-router/app-route-states-atom {})
  (let [[c unmount] (make-container)
        app-definition {:html-element c
                        :controllers {}
                        :router :memory
                        :components {:main {:renderer
                                            (fn [ctx]
                                              [:div 
                                               [:span.link-el-1 
                                                {:on-click #(ui/redirect ctx {:foo :bar})}
                                                "1"]
                                               [:span.link-el-2 
                                                {:on-click #(ui/redirect ctx {:baz :qux})}
                                                "2"]])}}}
        app (app-state/start! app-definition)]
    (async done
           (go
             (<! (timeout 20))
             (is (= (:route @(:app-db app))
                    {:data {}
                     :stack [{}]}))
             (sim/click (sel1 c [:.link-el-1]) {:button 0})
             (<! (timeout 20))
             (is (= (:route @(:app-db app))
                    {:data {:foo :bar}
                     :stack [{} {:foo :bar}]}))
             (app-state/stop! app)
             (<! (timeout 20))
             (let [app2 (app-state/start! app-definition)]
               (is (= (:route @(:app-db app2))
                      {:data {:foo :bar}
                       :stack [{} {:foo :bar}]}))

               (app-state/stop! app2)
               (unmount)
               (done))))))

(defrecord MemoryRouterRedirectController [])

(defmethod controller/params MemoryRouterRedirectController [_ _] true)
(defmethod controller/handler MemoryRouterRedirectController [this app-db-atom in-chan _]
  (go-loop []
    (let [[cmd args] (<! in-chan)]
      (when cmd
        (when (= :redirect cmd)
          (controller/redirect this args))
        (recur)))))

(deftest memory-router-redirect-controller
  (reset! memory-router/app-route-states-atom {})
  (let [[c unmount] (make-container)
        app-definition {:html-element c
                        :controllers {:m (->MemoryRouterRedirectController)}
                        :router :memory
                        :routes [["" {:baz :qux}]]
                        :components {:main {:topic :m
                                            :renderer
                                            (fn [ctx]
                                              [:div 
                                               [:span.link-el-1 
                                                {:on-click #(ui/send-command ctx :redirect {:foo :bar})}
                                                "1"]
                                               [:span.link-el-2 
                                                {:on-click #(ui/send-command ctx :redirect {:baz :qux})}
                                                "2"]])}}}
        app (app-state/start! app-definition)]
    (async done
           (go
             (<! (timeout 20))
             (is (= (:route @(:app-db app))
                    {:data {:baz :qux}
                     :stack [{:baz :qux}]}))
             (sim/click (sel1 c [:.link-el-1]) {:button 0})
             (<! (timeout 20))
             (is (= (:route @(:app-db app))
                    {:data {:foo :bar}
                     :stack [{:baz :qux} {:foo :bar}]}))
             (sim/click (sel1 c [:.link-el-2]) {:button 0})
             (<! (timeout 20))
             (is (= (:route @(:app-db app))
                    {:data {:baz :qux}
                     :stack [{:baz :qux}]}))
             (sim/click (sel1 c [:.link-el-1]) {:button 0})
             (<! (timeout 20))
             (is (= (:route @(:app-db app))
                    {:data {:foo :bar}
                     :stack [{:baz :qux} {:foo :bar}]}))
             (sim/click (sel1 c [:.link-el-1]) {:button 0})
             (<! (timeout 20))
             (is (= (:route @(:app-db app))
                    {:data {:foo :bar}
                     :stack [{:baz :qux} {:foo :bar}]}))

             (app-state/stop! app)
             (unmount)
             (done)))))


(deftest application-context-is-passed-correctly-to-components
  (reset! memory-router/app-route-states-atom {})
  (let [[c unmount] (make-container)
        app-definition {:html-element c
                        :router :memory
                        :routes [["" {:baz :qux}]]
                        :context {:foo "bar" :bar "baz"}
                        :components {:main {:renderer
                                            (fn [ctx]
                                              [:div (get-in ctx [:context :foo]) [(ui/component ctx :child)]])
                                            :component-deps [:child]}
                                     :child {:renderer
                                             (fn [ctx]
                                               [:div (get-in ctx [:context :bar])])}}}
        app (app-state/start! app-definition)]
    (async done
           (go
             (<! (timeout 20))
             (is (= "bar\nbaz" (.-innerText c)))
             (app-state/stop! app)
             (unmount)
             (done)))))

(deftest component-knows-its-path
  (let [[c unmount] (make-container)
        paths$ (atom #{})
        app-definition {:html-element c
                        :router :memory
                        :components {:main
                                     (ui/constructor
                                      {:renderer (fn [ctx]
                                                   (fn []
                                                     (swap! paths$ conj (:path ctx))
                                                     [:div "MAIN"
                                                      [(ui/component ctx :child-1)]
                                                      [(ui/component ctx :child-2)]]))
                                       :component-deps [:child-1 :child-2]})
                                     :child-1
                                     (ui/constructor
                                      {:renderer (fn [ctx]
                                                   (swap! paths$ conj (:path ctx))
                                                   (fn []
                                                     [:div "CHILD 1"
                                                      [(ui/component ctx :child-2)]]))
                                       :component-deps [:child-2]})
                                     :child-2
                                     (ui/constructor
                                      {:renderer (fn [ctx]
                                                   (swap! paths$ conj (:path ctx))
                                                   (fn []
                                                     [:div "CHILD 2"]))})}}
        app (app-state/start! app-definition)]
    (async done
           (go
             (<! (timeout 20))
             (is (= #{[:main] [:main :child-1] [:main :child-1 :child-2] [:main :child-2]}
                    @paths$))
             (app-state/stop! app)
             (unmount)
             (done)))))
