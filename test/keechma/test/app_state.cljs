(ns keechma.test.app-state
  (:require [cljs.test :refer-macros [deftest is async]] 
            [cljs-react-test.utils :as tu]
            [cljs-react-test.simulate :as sim]
            [dommy.core :as dommy :refer-macros [sel1]]
            [cljs.core.async :refer [<! >! chan close! put! alts! timeout]]
            [keechma.app-state :as app-state]
            [keechma.controller :as controller]
            [keechma.ui-component :as ui]
            [keechma.app-state.react-native-router :as rn-router]
            [keechma.app-state.history-router :as history-router]
            [keechma.ssr :as ssr])
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
    (assoc app-db :inner-app (app-state/start! inner-app false)))
  (wake [this params app-db]
    (let [serialized-app (:inner-app app-db)]
      (assoc app-db :inner-app (app-state/start! (assoc inner-app :initial-data serialized-app) false))))
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
                                     "click"])}}})]
    (async done
           (go
             (<! (timeout 100))
             (let [button-node (sel1 c [:button])]
               (sim/click button-node nil)
               (<! (timeout 100))
               (is (= (.. js/window -location -hash) "#!?baz=qux"))
               (set! (.-hash js/location) "")
               (app-state/stop! app done))))))


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
                       (done))))))))))

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
             (is (= @kv-state-atom {:foo :bar :start :value}))
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
                                              [:span.link-el
                                               {:on-click #(ui/redirect ctx {:page "page-name2" :foo "Bar"})}
                                               "Link"])}}}
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

(defrecord ContextController []
  controller/IController
  (params [_ _] true)
  (start [this _ app-db]
    (let [context-fn-1 (controller/context this :context-fn-1)
          context-fn-2 (controller/context this [:context :fn-2])]
      (context-fn-1)
      (context-fn-2)
      app-db)))

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

(defrecord ContextController2 []
  controller/IController
  (params [_ _] true)
  (start [this _ app-db]
    (let [context-fn-1 (controller/context this)]
      (context-fn-1)
      app-db)))

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
                 (done)))))))

(defrecord AsyncSsrHandlerController [log]
  controller/IController
  (params [this route-params] true)
  (start [this params app-db]
    (swap! log conj :start)
    app-db)
  (wake [this params app-db]
    (swap! log conj :wake)
    app-db)
  (handler [this app-db-atom _ _]
    (swap! log conj :handler))
  (ssr-handler [this app-db-atom done _ _]
    (swap! log conj :ssr-handler-start)
    (js/setTimeout (fn []
                     (swap! app-db-atom assoc-in [:kv :message] "Hello World!")
                     (swap! log conj :ssr-handler-done)
                     (done)) 30)))

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
                 (done)))))))


(defrecord ControllerReturningFalseFromParams []
  controller/IController
  (params [this route-params] false))

(deftest controller-returning-fasle-from-params
  (let [[c unmount] (make-container)
        app-renderer (fn [ctx] [:div])
        app {:controllers {:main (->ControllerReturningFalseFromParams)}
             :html-element c
             :components {:main {:renderer app-renderer}}}]
    (async done
           (go
             (app-state/start! app)
             (<! (timeout 20))
             (done)))))
