(ns keechma.ssr
  (:require [keechma.app-state :as app-state]
            [router.core :as router]
            [keechma.app-state.core :as app-state-core]
            [keechma.app-state.ssr-router :as ssr-router]
            [reagent.dom.server :as r-server]
            [keechma.controller-manager :as controller-manager]))

(defn start-router! [state current-url]
  (let [routes (:routes state) 
        routes-chan (:routes-chan state)
        router (ssr-router/constructor current-url routes routes-chan state)]
    (-> state
        (assoc :router router)
        (app-state/add-stop-fn (fn [s]
                                 (app-state-core/stop! router)
                                 s)))))

(defn prepare-config [config url]
  (let [config (app-state/process-config (merge (app-state/default-config {}) config))]
    (-> config
        (app-state/start-subs-cache)
        (start-router! url))))

(defn start-controllers [state done-cb]
  (let [router (:router state)
        reporter (:reporter state)
        context (:context state)
        controllers (-> (:controllers state)
                        (app-state/add-context-to-controllers context)
                        (app-state/add-reporter-to-controllers reporter)
                        (app-state/add-redirect-fn-to-controllers router))
        routes-chan (:routes-chan state)
        commands-chan (:commands-chan state)
        app-db (:app-db state)
        manager (controller-manager/start-ssr routes-chan commands-chan app-db controllers reporter #(done-cb state))]))

(defn render-to-string [state]
  (let [app-renderer (app-state/app-renderer state)]
    (r-server/render-to-string app-renderer)))

(defn controllers-done [done-cb state]
  (let [html (-> state
                 (app-state/resolve-main-component)
                 (render-to-string))]
    (done-cb {:html html})))

(defn render [config url done-cb]
  (let [config (prepare-config config url)]
    (start-controllers config (partial controllers-done done-cb))))
