(ns keechma.app-state.history-router
  (:require [keechma.app-state.core :as core :refer [IRouter]]
            [router.core :as router]
            [goog.events :as events]
            [goog.history.EventType :as EventType]
            [cljs.core.async :refer [put!]]
            [clojure.string :as str])
  (:import goog.history.Event
           goog.history.Html5History
           goog.Uri))

(defn make-urlchange-dispatcher []
  (let [handlers (atom [])
        main-handler
        (fn [_]
          (doseq [h @handlers]
            (h (.-href js/location))))

        bind-main-handler
        (fn []
          (when (= 1 (count @handlers))
            (.addEventListener js/window "popstate" main-handler)))

        unbind-main-handler
        (fn []
          (when (zero? (count @handlers))
            (.removeEventListener js/window "popstate" main-handler)))]
    {:handlers-count #(count @handlers)
     :bind (fn [handler]
             (swap! handlers conj handler)
             (bind-main-handler))
     :unbind (fn [handler]
               (swap! handlers (fn [h] (filter #(not= handler %) h)))
               (unbind-main-handler))
     :replace (fn [href]
                (.replaceState js/history nil "" href)
                (doseq [h @handlers]
                  (h href)))
     :go (fn [href]
           (.pushState js/history nil "" href)
           (doseq [h @handlers]
             (h href)))}))

(def urlchange-dispatcher (make-urlchange-dispatcher))

(defn url-prefix [base-href]
  (str (.-origin js/location) base-href))

(defn route-url [url base-href]
  (let [prefix (url-prefix base-href)]
    (first (str/split (subs url (count prefix) (count url)) #"#"))))

(defn link? [el]
  (and (.-href el)
       (= "a" (str/lower-case (.-tagName el)))))

(defn link-el [el]
  (loop [current-el el]
    (if (link? current-el)
      current-el
      (when-let [parent (.-parentNode current-el)]
        (recur parent)))))

(defn current-target-self? [el]
  (contains? #{"" "_self"} (.-target el)))

(defn left-button-clicked? [e]
  (= 0 (.-button e)))

(defn mod-key-pressed? [e]
  (or (.-altKey e)
      (.-shiftKey e)
      (.-ctrlKey e)
      (.-metaKey e)))

(defn link-has-prefixed-url? [el base-href]
  (str/starts-with? (.-href el) (url-prefix base-href)))

(defn same-href? [el]
  (= (.-href el) (.-href js/location)))

(defn should-href-pass-through? [href]
  (let [[current current-hash] (str/split (.-href js/location) #"#")
        [next next-hash] (str/split href #"#")]
    (and (= current next)
         (not= current-hash next-hash))))

(defn make-url [routes base-href params]
  (let [hash (.-hash js/location)]
    (str base-href (router/map->url routes params) hash)))

(defn add-trailing-slash [base-href]
  (if (str/ends-with? base-href "/")
    base-href
    (str base-href "/")))

(defn add-leading-slash [base-href]
  (if (str/starts-with? base-href "/")
    base-href
    (str "/" base-href)))

(defn process-base-href [base-href]
  (-> base-href
      (add-trailing-slash)
      (add-leading-slash)))

(defn link-has-data-replace-state? [el]
  (and (link? el)
       (boolean (.getAttribute el "data-replace-state"))))

(defrecord HistoryRouter [routes routes-chan base-href app-db]
  IRouter
  (start! [this]
    (let [handler (fn [href]
                    (put! routes-chan (router/url->map routes (route-url href base-href))))]
      ((:bind urlchange-dispatcher) handler)
      (swap! app-db assoc :route (router/url->map routes (route-url (.-href js/location) base-href)))
      (assoc this :urlchange-handler handler)))
  (stop! [this]
    ((:unbind urlchange-dispatcher) (:urlchange-handler this)))
  (redirect! [this params] (core/redirect! this params false))
  (redirect! [this params replace?]
    (let [redirect-fn (get urlchange-dispatcher (if replace? :replace :go))]
      (redirect-fn (str (.-origin js/location) (make-url routes base-href params)))))
  (wrap-component [this]
    (let [click-handler
          (fn [e]
            (when-let [el (link-el (.-target e))]
              (let [href (.-href el)]
                (when (and (current-target-self? el)
                           (left-button-clicked? e)
                           (not (mod-key-pressed? e))
                           (link-has-prefixed-url? el base-href))
                  (when-not (should-href-pass-through? href)
                    (let [redirect-fn (get urlchange-dispatcher (if (link-has-data-replace-state? el) :replace :go))]
                      (redirect-fn href)
                      (.preventDefault e)
                      (.stopPropagation e)))))))]
      (fn [& children]
        (into [:div {:on-click click-handler}]
              children))))
  (url [this params]
    (make-url routes base-href params))
  (linkable? [this] true))

(defn constructor [routes routes-chan state]
  (let [base-href (process-base-href (or (:base-href state) "/"))]
    (core/start! (->HistoryRouter (router/expand-routes routes) routes-chan base-href (:app-db state)))))
