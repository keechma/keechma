(ns ashiba.test.service
  (:require [cljs.test :refer-macros [deftest is async]]
            [ashiba.service :as service]
            [cljs.core.async :refer [<! >! chan close! sliding-buffer put! alts!]])
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
