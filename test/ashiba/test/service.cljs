(ns ashiba.test.service
  (:require [cljs.test :refer-macros [deftest is async]]
            [ashiba.service :as service]
            [cljs.core.async :refer [<! >! chan close! sliding-buffer put! alts! timeout]])
  (:require-macros [cljs.core.async.macros :as m :refer [go alt!]]))


;; (defrecord FooService [a]
;;   service/IService
;;   (params [c route] c))

;; (def a (FooService. "baba"))
;; ;;(.log js/console (clj->js a))
;; ;;(.log js/console (clj->js (service/params a {:foo "Bar"})))

;; (defn handler [i o]
;;   (.log js/console "Aa")
;;   (go
;;     (loop []
;;       (let [input (<! i)]
;;         (if (= input :foo)
;;           (>! o "OUT CHAN!!!!1") 
;;           (do
;;             (.log js/console input)
;;             (recur)))))))


;; (deftest async-test []
;;   (async done
;;    (let [in-chan (chan)
;;          out-chan (chan)
;;          chan-3 (chan)
;;          h2 (fn [c]
;;               (go
;;                 (.log js/console (<! c))
;;                 (done)))]
;;      (handler in-chan out-chan)
;;      (put! in-chan "Mama ti je jama")
;;      (.log js/console "AAA")
;;      (go
;;        (.log js/console (<! out-chan))
;;        (h2 chan-3))
;;      (put! in-chan :foo)
;;      (put! chan-3 "aaaaaaa"))))

;; (declare on-next-tick-2)

;; (defn on-next-tick [cb]
;;   (.requestAnimationFrame js/window (fn []
;;                                       (cb)
;;                                       (on-next-tick-2 cb))))

;; (defn on-next-tick-2 [cb]
;;   (on-next-tick cb))

;; (defn next-tick-chan []
;;   (let [c (chan)
;;         write-c (fn [] (put! c true))]
;;     (on-next-tick write-c)
;;     c))

;; (defn update-state [state fns]
;;   (do
;;     (.log js/console "UPDATE: " (clojure.core/count fns) (clj->js state))
;;     (if-not (empty? fns)
;;       (reduce (fn [state cb] (cb state)) state fns)
;;       state)))

;; (defn timeouted [chan data timeout]
;;   (do
;;     (.log js/console "SETTING UP: " (clj->js data))
;;     (.setTimeout js/window (fn []
;;                              (put! chan data)) timeout)))
;; (defn animation-frame
;;   "Return a channel which will close on the nth next animation frame."
;;   ([] (animation-frame 1))
;;   ([n] (animation-frame n (chan 1)))
;;   ([n out]
;;      (js/window.requestAnimationFrame
;;       (fn [timestamp]
;;         (if (= n 1)
;;           (do
;;             (put! out timestamp)
;;             (close! out))
;;           (animation-frame (dec n) out))))
;;      out))

;; (deftest chan-timing []
;;   (let [ch (chan)
;;         state (atom {})
;;         updates (atom [])]
;;     (do
;;       (timeouted ch (fn [state]
;;                       (.log js/console "FN 1 CALLED")
;;                       (merge state {:fn1 "called"})) 1000)
;;       (timeouted ch (fn [state] 
;;                       (.log js/console "FN 2 CALLED")
;;                       (merge state {:fn2 "called"})) 1500)
;;       (async done
;;              (go (loop []
;;                    (let [new-update-fn (<! ch)]
;;                      (swap! updates conj new-update-fn)
;;                      (recur))))
;;              (go (loop []
;;                    (let [updates-fns @updates]
;;                      (.log js/console "CALLING UPDATE")
;;                      (when-not (empty? updates-fns) 
;;                        (.log js/console "UPDATE FNS:" (clj->js @updates))
;;                        (reset! state (update-state @state updates-fns))
;;                        (reset! updates [])
                       
;;                        (.log js/console "UPDATED STATE: " (clj->js @state)))
;;                      (<! (animation-frame 2))
;;                      (recur))))
;;              (go
;;                (<! (timeout 2000))
;;                (.log js/console (clj->js @state))
;;                (done))))))

