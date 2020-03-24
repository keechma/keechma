(ns keechma.ui-component.test-helpers
  (:require [keechma.ui-component :as ui]
            [router.core :as router]
            [reagent.core :as r]
            [reagent.dom :as rdom]
            [clojure.set :as set]
            [clojure.string :as str])
  (:require-macros [reagent.ratom :refer [reaction]]))

(defprotocol IMockUIComponent
  (command-log [this])
  (update-subscription! [this key data]))

(defrecord MockComponent [state components]
  ui/IUIComponent
  (url [this params]
    (str "#!" (router/map->url [] params)))
  (report [this name payload])
  (redirect [this params]
    (ui/send-command this :ui/redirect params))
  (current-route [this]
    (reaction
     {:data (:route @state)}))
  (subscription [this key]
    (ui/subscription this key nil))
  (subscription [this key args]
    (reaction
     (let [sub (get-in @state [:subscriptions key])]
       (when (nil? sub)
         (throw (ex-info (str "Missing subscription: " key) {})))
       (if (fn? sub)
         (if args
           (sub args)
           (sub))
         sub))))
  (component [this key]
    (when (not (contains? (set (:component-deps this)) key))
      (throw (ex-info (str "Missing component-deps: " key) {})))
    (or (get components key)
        (fn [ctx]
          [:div {:data-component key}])))
  (send-command [this command]
    (ui/send-command this command nil))
  (send-command [this command args]
    (let [command-log (or (:command-log @state) [])]
      (swap! state assoc :command-log
             (conj command-log [command args]))))
  (renderer [this]
    (let [sub-keys (keys (:subscriptions @state))
          sub-diff (set/difference (set (:subscription-deps this))
                                   (set (set sub-keys)))]
      (when (seq sub-diff)
        (throw (ex-info (str "Missing subscriptions: " (str/join ", " sub-diff)) {}))))
    (partial (:renderer this) this))
  IMockUIComponent
  (command-log [this]
    (:command-log @state))
  (update-subscription! [this key data]
    (swap! state assoc-in [:subscriptions key] data)))

(defn mock-ctx [component {:keys [components subscriptions]}]
  (merge (->MockComponent
          (r/atom {:subscriptions (or subscriptions {})})
          (or components {}))
         component))

(defn mock-renderer [mocked-ctx]
  (ui/renderer mocked-ctx))

(defn render
  ([container component] (render container component {}))
  ([container component initial-state]
   (let [mocked (mock-ctx component initial-state)]
     (rdom/render [(mock-renderer mocked)] container)
     mocked)))
