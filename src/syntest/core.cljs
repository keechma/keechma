(ns syntest.core
  (:require [cljsjs.jquery]
            [promesa.core :as p]
            [cljs.core.async :refer [timeout <! chan put!]]
            [syntest.util :as util]
            [syn :as syn]
            [clojure.string :as str])
  (:require-macros [cljs.core.async.macros :refer [go-loop]]))

(def default-timeout 6000)

(defprotocol IMatcher
  (success-message [this])
  (error-message [this]))

(defrecord Matcher [name selector args predicate timeout]
  IMatcher
  (success-message [this]
    (str "Found element `" (util/el->selector selector)
         "` with predicate `" name "`"
         (when (not (empty? args)) (str " with args: " (pr-str args) "`"))))
  (error-message [this]
    (str "Couldn't find element `" (util/el->selector selector)
         "` with predicate `" name "`"
         (when (not (empty? args)) (str " with args: `" (pr-str args) "`")))))

(defn first-el [el]
  (.get el 0))

(defn update-res-message [res msg]
  (let [new-msg (str msg " ('" (:selector res) "')")]
    (util/map->Ok (merge res {:message new-msg}))))

(defn wait-time [msec]
  (p/promise (fn [resolve reject] (.setTimeout js/window resolve msec))))

(defn wait-element [matcher]
  (p/promise
   (fn [resolve reject]
     (go-loop [time 0]
       (let [el (.$ js/window (:selector matcher))]
         (if ((:predicate matcher) el)
           (resolve (util/map->Ok {:message (success-message matcher)
                                   :result el
                                   :selector (:selector matcher)}))
           (if (> time (:timeout matcher))
             (reject (ex-info (str "Error")
                              {:error (util/map->Error
                                       {:message (error-message matcher)
                                        :selector (:selector matcher)})}))
             (do
               (<! (timeout 100))
               (recur (+ time 100))))))))))

(defn existing?
  ([selector] (existing? selector default-timeout))
  ([selector wait-timeout]
   (wait-element (map->Matcher {:name "existing"
                                :selector selector
                                :args {}
                                :predicate #(pos? (.-length %))
                                :timeout wait-timeout}))))

(defn missing?
  ([selector] (missing? selector default-timeout))
  ([selector wait-timeout]
   (wait-element (map->Matcher {:name "missing"
                                :selector selector
                                :args {}
                                :predicate #(zero? (.-length %))
                                :timeout wait-timeout}))))

(defn visible?
  ([selector] (visible? selector default-timeout))
  ([selector wait-timeout]
   (wait-element (map->Matcher {:name "visible"
                                :selector selector
                                :args {}
                                :predicate #(.is % ":visible")
                                :timeout wait-timeout}))))

(defn invisible?
  ([selector] (invisible? selector default-timeout))
  ([selector wait-timeout]
   (wait-element (map->Matcher {:name "invisible"
                                :selector selector
                                :args {}
                                :predicate #(.is % ":invisible")
                                :timeout wait-timeout}))))

(defn has-height?
  ([selector height] (has-height? selector height default-timeout))
  ([selector height wait-timeout]
   (wait-element (map->Matcher {:name "has-height"
                                :selector selector
                                :args height
                                :predicate #(= height (.height %))
                                :timeout wait-timeout}))))

(defn has-width?
  ([selector width] (has-width? selector width default-timeout))
  ([selector width wait-timeout]
   (wait-element (map->Matcher {:name "has-width"
                                :selector selector
                                :args width
                                :predicate #(= width (.width %))
                                :timeout wait-timeout}))))

(defn has-inner-height?
  ([selector inner-height] (has-inner-height? selector inner-height default-timeout))
  ([selector inner-height wait-timeout]
   (wait-element (map->Matcher {:name "has-inner-height"
                                :selector selector
                                :args inner-height
                                :predicate #(= inner-height (.innerHeight %))
                                :timeout wait-timeout}))))

(defn has-inner-width?
  ([selector inner-width] (has-inner-width? selector inner-width default-timeout))
  ([selector inner-width wait-timeout]
   (wait-element (map->Matcher {:name "has-inner-width"
                                :selector selector
                                :args inner-width
                                :predicate #(= inner-width (.innerWidth %))
                                :timeout wait-timeout}))))

(defn has-outer-height?
  ([selector outer-height] (has-outer-height? selector outer-height default-timeout))
  ([selector outer-height wait-timeout]
   (wait-element (map->Matcher {:name "has-outer-height"
                                :selector selector
                                :args outer-height
                                :predicate #(= outer-height (.outerHeight %))
                                :timeout wait-timeout}))))

(defn has-outer-width?
  ([selector outer-width] (has-outer-width? selector outer-width default-timeout))
  ([selector outer-width wait-timeout]
   (wait-element (map->Matcher {:name "has-outer-width"
                                :selector selector
                                :args outer-width
                                :predicate #(= outer-width (.outerWidth %))
                                :timeout wait-timeout}))))

(defn has-scroll-top?
  ([selector scroll-top] (has-scroll-top? selector scroll-top default-timeout))
  ([selector scroll-top wait-timeout]
   (wait-element (map->Matcher {:name "has-scroll-top"
                                :selector selector
                                :args scroll-top
                                :predicate #(= scroll-top (.scrollTop %))
                                :timeout wait-timeout}))))

(defn has-scroll-left?
  ([selector scroll-left] (has-scroll-left? selector scroll-left default-timeout))
  ([selector scroll-left wait-timeout]
   (wait-element (map->Matcher {:name "has-scroll-left"
                                :selector selector
                                :args scroll-left
                                :predicate #(= scroll-left (.scrollLeft %))
                                :timeout wait-timeout}))))

(defn coordinates-predicate [coordinates {:keys [top left]}]
  (cond
    (nil? top)  (= left (.-left coordinates))
    (nil? left) (= top (.-top coordinates))
    :else       (and (= left (.-left coordinates))
                     (= top (.-top coordinates)))))


(defn has-position?
  ([selector coordinates] (has-position? selector coordinates default-timeout))
  ([selector coordinates wait-timeout]
   (wait-element (map->Matcher {:name "has-scroll-left"
                                :selector selector
                                :args coordinates
                                :predicate #(coordinates-predicate (.position %) coordinates)
                                :timeout wait-timeout}))))

(defn has-offset?
  ([selector coordinates] (has-offset? selector coordinates default-timeout))
  ([selector coordinates wait-timeout]
   (wait-element (map->Matcher {:name "has-scroll-left"
                                :selector selector
                                :args coordinates
                                :predicate #(coordinates-predicate (.offset %) coordinates)
                                :timeout wait-timeout}))))

(defn has-value?
  ([selector value] (has-value? selector value false))
  ([selector value trim?] (has-value? selector value trim? default-timeout))
  ([selector value trim? wait-timeout]
   (wait-element (map->Matcher {:name "has-value"
                                :selector selector
                                :args value
                                :predicate (if trim?
                                             #(= (str/trim value) (str/trim (.val %)))
                                             #(= value (.val %)))
                                :timeout wait-timeout}))))

(defn satisfies-predicate?
  ([selector predicate] (satisfies-predicate? selector predicate default-timeout))
  ([selector predicate wait-timeout]
   (wait-element (map->Matcher {:name "satisfies-predicate"
                                :selector selector
                                :args {}
                                :predicate predicate
                                :timeout wait-timeout}))))

(defn syn-perform! [selector action wait-timeout]
  (->> (existing? selector wait-timeout)
       (p/map (fn [result]
                (p/promise (fn [resolve reject]
                             (action result resolve)))))))

(defn click!
  ([selector] (click! selector default-timeout))
  ([selector wait-timeout]
   (syn-perform! selector
                 (fn [result resolve]
                   (.click syn (first-el (:result result)) #(resolve (update-res-message result "Clicked element"))))
                 wait-timeout)))

(defn dblclick!
  ([selector] (dblclick! selector default-timeout))
  ([selector wait-timeout]
   (syn-perform! selector
                 (fn [result resolve]
                   (.dblclick syn (first-el (:result result)) #(resolve (update-res-message result "Double-clicked element"))))
                 wait-timeout)))

(defn type!
  ([selector text] (type! selector text default-timeout))
  ([selector text wait-timeout]
   (syn-perform! selector
                 (fn [result resolve]
                   (.type syn (first-el (:result result)) text #(resolve (update-res-message result "Typed to element"))))
                 wait-timeout)))

(defn key!
  ([selector key] (key! selector key default-timeout))
  ([selector key wait-timeout]
   (syn-perform! selector
                 (fn [result resolve]
                   (.key syn (first-el (:result result)) key #(resolve (update-res-message result "Pressed key on element"))))
                 wait-timeout)))

(defn drag!
  ([selector target] (drag! selector target default-timeout))
  ([selector target wait-timeout]
   (let [real-target (if (map? target) (clj->js target) (first-el (.$ js/window target)))]
     (syn-perform! selector
                   (fn [result resolve]
                     (.drag syn (first-el (:result result)) real-target #(resolve (update-res-message result "Dragged element"))))
                   wait-timeout))))
