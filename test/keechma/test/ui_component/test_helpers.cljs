(ns keechma.test.ui-component.test-helpers
  (:require [cljs.test :refer-macros [deftest is async]]
            [keechma.ui-component :as ui]
            [keechma.ui-component.test-helpers :as ui-th]
            [cljs.core.async :refer [<! >! chan close! sliding-buffer put! alts! timeout]]
            [syntest.core :as s]
            [syntest.test :refer-macros [synasync]]
            [keechma.test.util :refer [make-container]])
  (:require-macros [cljs.core.async.macros :as m :refer [go alt!]]))


(defn link-renderer [ctx]
  [:a {:href (ui/url ctx {:foo "bar"})} "A link"])

(def link-component
  (ui/constructor {:renderer link-renderer}))

(deftest link
  (let [[c unmount] (make-container)]
    (ui-th/render c link-component)
    (synasync [el]
              (s/existing? "a")
              (s/satisfies-predicate? el #(= "#!?foo=bar" (.attr % "href")))
              (unmount))))

(defn send-command-renderer [ctx]
  [:button {:on-click #(ui/send-command ctx :command {:foo :bar})}])

(def send-command-component
  (ui/constructor {:renderer send-command-renderer}))

(deftest send-command
  (let [[c unmount] (make-container)
        ctx (ui-th/render c send-command-component)]
    (synasync [el]
              (s/existing? "button")
              (s/click! el)
              (is (= [[:command {:foo :bar}]] (ui-th/command-log ctx)))
              (unmount))))

(defn redirect-renderer [ctx]
  [:button {:on-click #(ui/redirect ctx {:foo :bar})}])

(def redirect-component
  (ui/constructor {:renderer redirect-renderer}))

(deftest redirect
  (let [[c unmount] (make-container)
        ctx (ui-th/render c redirect-component)]
    (synasync [el]
              (s/existing? "button")
              (s/click! el)
              (is (= [[:ui/redirect {:foo :bar}]] (ui-th/command-log ctx)))
              (unmount))))

(defn subscription-renderer [ctx]
  (let [user @(ui/subscription ctx :current-user)]
    [:div
     [:span.username (:username user)]
     [:img.profile {:src (:profile-url user)}]]))

(def subscription-component
  (ui/constructor {:renderer subscription-renderer
                   :subscription-deps [:current-user]}))

(deftest subscription
  (let [[c unmount] (make-container)
        ctx (ui-th/render c subscription-component
                          {:subscriptions {:current-user {:username "retro"
                                                          :profile-url "http://example.com/profile.jpg"}}})]
    (synasync [el]
              (s/satisfies-predicate? "span.username" #(= "retro" (.text %)))
              (s/satisfies-predicate? "img.profile" #(= "http://example.com/profile.jpg" (.attr % "src")))
              (ui-th/update-subscription! ctx :current-user {:username "retro1"
                                                             :profile-url "http://example.com/profile1.jpg"})
              (s/satisfies-predicate? "span.username" #(= "retro1" (.text %)))
              (s/satisfies-predicate? "img.profile" #(= "http://example.com/profile1.jpg" (.attr % "src")))
              (unmount))))

(defn with-inner-component-renderer [ctx]
  [:div
   [:h1 "Title"]
   [(ui/component ctx :body)]])

(def with-inner-component-component
  (ui/constructor
   {:renderer with-inner-component-renderer
    :component-deps [:body]}))

(defn custom-inner-component-with-state-renderer [ctx]
  [:h2 @(ui/subscription ctx :title)])

(def custom-inner-component-with-state-component
  (ui/constructor {:renderer custom-inner-component-with-state-renderer
                   :subscription-deps [:title]}))

(deftest with-inner-component
  (let [[c unmount] (make-container)
        ctx (ui-th/render c with-inner-component-component)]
    (synasync [el]
              (s/existing? "h1")
              (s/existing? "[data-component='body']")
              (unmount))))

(deftest with-custom-inner-component
  (let [[c unmount] (make-container)
        inner-renderer (ui-th/mock-renderer
                        (ui-th/mock-ctx custom-inner-component-with-state-component
                                        {:subscriptions {:title "Subtitle"}}))
        ctx (ui-th/render c with-inner-component-component
                          {:components {:body inner-renderer}})]
    (synasync [el]
              (s/existing? "h1")
              (s/existing? "h2")
              (s/satisfies-predicate? el #(= "Subtitle" (.text %)))
              (unmount))))

