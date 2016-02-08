(ns keechma.router
  (:require [clojure.walk :refer [keywordize-keys]]
            [clojure.set :refer [superset? union]]
            [secretary.core :refer [decode-query-params encode-query-params]]
            [cuerdas.core :as str]))

(def ^:private  encode js/encodeURIComponent)

(defn ^:private process-route-part [default-keys part]
  (let [is-placeholder? (= ":" (first part))
        key (when is-placeholder? (keyword (subs part 1)))
        has-default? (contains? default-keys key)
        min-matches (if has-default? "*" "+")
        re-match (if is-placeholder? (str "(" "[^/]" min-matches ")") part)]
    {:is-placeholder? is-placeholder?
     :key key
     :has-default has-default?
     :re-match re-match}))

(defn ^:private route-regex [parts]
  (let [base-regex (clojure.string/join "/" (map (fn [p] (:re-match p)) parts))
        full-regex (str "^" base-regex "$")]
    (re-pattern full-regex)))

(defn ^:private route-placeholders [parts]
  (remove nil? (map (fn [p] (:key p)) parts)))

(defn ^:private add-default-params [route]
  (if (string? route) [route {}] route))

(defn ^:private strip-slashes
  ([route]
   (clojure.string/replace (clojure.string/trim (or route "")) #"^/+|/+$" ""))
  ([side route]
   (case side
     :left (clojure.string/replace (clojure.string/trim (or route "")) #"^/+" "")
     :right (clojure.string/replace (clojure.string/trim (or route "")) #"/+$" ""))))

(defn ^:private process-route [[route defaults]]
  (let [parts (clojure.string/split route #"/")
        processed-parts (map (partial process-route-part (set (keys defaults))) parts)]
    {:parts processed-parts 
     :regex (route-regex processed-parts)
     :placeholders (set (route-placeholders processed-parts))
     :route route
     :defaults (or defaults {})}))

(defn ^:private remove-empty-matches [matches]
  (apply dissoc matches (for [[k v] matches :when (or (= v "null") (empty? v))] k)))

(defn ^:private expand-route [route]
  (let [strip-slashes (fn [[route defaults]] [(strip-slashes route) defaults])]
    (-> route
        add-default-params
        strip-slashes
        process-route)))

(defn ^:private potential-route? [data-keys route]
  (superset? data-keys (:placeholders route)))

(defn ^:private intersect-maps [map1 map2]
  (reduce-kv (fn [m k v]
               (if (= (get map2 k) v)
                 (assoc m k v)
                 m)) {} map1))

(defn ^:private extract-query-param [default-keys placeholders m k v]
  (if-not (or (contains? default-keys k) (contains? placeholders k))
    (assoc m k v)
    m))

(defn ^:private add-url-segment [defaults data url k]
  (let [val (get data k)
        placeholder (str k)
        is-default? (= (get defaults k) val)
        ;; Hack to enforce trailing slash when we have a default value 
        default-val (if (str/starts-with? url placeholder) "" "")
        replacement (if is-default? default-val (encode val))]
    (clojure.string/replace url placeholder replacement)))

(defn ^:private build-url [route data]
  (let [defaults (:defaults route)
        default-keys (set (keys defaults))
        placeholders (:placeholders route)
        query-params (reduce-kv (partial extract-query-param default-keys placeholders) {} data)
        base-url (reduce (partial add-url-segment defaults data) (:route route) placeholders)]
    (if (empty? query-params)
      (if (= "/" base-url) "" base-url)
      (str base-url "?" (encode-query-params query-params)))))

(defn ^:private route-score [data route]
  (let [matched []
        default-matches (fn [matched] 
                          (into matched
                                (keys (intersect-maps data (:defaults route)))))
        placeholder-matches (fn [matched]
                              (into matched
                                    (union (set (:placeholders route))
                                           (set (keys data)))))]
    (count (-> matched
               default-matches
               placeholder-matches
               distinct))))

(defn ^:private match-path-with-route [route url]
  (let [matches (first (re-seq (:regex route) url))]
    (when-not (nil? matches)
      (zipmap (:placeholders route) (rest matches)))))

(defn ^:private match-path [processed-routes path]
  (let [route-count (count processed-routes)
        max-index (dec route-count)]
    (if (pos? route-count)
      (loop [index 0] 
        (let [route (get processed-routes index)
              matches (match-path-with-route route path) 
              end? (= max-index index)]
          (cond
           matches {:route (:route route)
                    :data (merge (:defaults route)
                                 (remove-empty-matches matches))}
           end? nil
           :else (recur (inc index))))))))

;; Public API

(defn url->map
  "Accepts `expanded-routes` vector (returned by the `expand-routes` function)
  and a string as arguments. Returns a map which contains the data represented
  by the route.

  ```clojure
  ;; define routes
  (def routes [[\":page\", {:page \"index\"}]
                \":page/:id\"
                \":page/:id/:action\"]) 

  (def expanded-routes (expand-routes routes))

  (url->map expanded-routes \"foo\")
  ;; {:page \"foo\"}

  (url->map expanded-routes \"foo/1\")
  ;; {:page \"foo\" :id 1}

  (url->map expanded-routes \"foo?bar=baz\")
  ;; {:page \"foo\" :bar \"baz\"}
  ```
  "
  [expanded-routes url]
  (let [[u q] (clojure.string/split url #"\?")
        path (if (= u "/") u (strip-slashes :left u)) 
        query (remove-empty-matches (keywordize-keys (decode-query-params (or q ""))))
        matched-path (match-path expanded-routes path)]
    (if matched-path
      (assoc matched-path :data (merge query (:data matched-path)))
      {:data query})))

(defn map->url 
  "Accepts `expanded-routes` vector (returned by the `expand-routes` function)
  and a map as arguments. Returns a URL part which is the closest representatation
  of the data contained in the map (based on the `expanded-routes` argument).

  ```clojure
  ;; define routes
  (def routes [[\":page\", {:page \"index\"}]
                \":page/:id\"
                \":page/:id/:action\"]) 

  (def expanded-routes (expand-routes routes))

  (map->url expanded-routes {:page \"foo\"})
  ;; \"foo\"

  (map->url expanded-routes {:page \"foo\" :id 1})
  ;; \"foo/1\"

  (map->url expanded-routes {:page \"foo\" :id 1 :action \"bar\" :qux \"baz\"})
  ;; \"foo/1/bar?qux=baz\"
  ```
  "
  [expanded-routes data]
  (let [data-keys (set (keys data))
        potential-routes (filter (partial potential-route? data-keys) expanded-routes)]
    (if (empty? potential-routes)
      (str "?" (encode-query-params data))
      (let [sorted-routes (sort-by (fn [r] (- (route-score data r))) potential-routes)
            best-match (first sorted-routes)]
        (build-url best-match data)))))

(defn expand-routes
  "Accepts a vector of routes as the argument. Returnes the expanded version
  of routes that can be passed to `url->map` and `map->url` functions.

  Elements in the route vector must be string (pattern) or vectors that contain
  the string pattern and default values for that route.

  ```clojure
  (def route \":page\")
  ;; This route will not be matched by an empty string

  (def route-with-defaults [\":page\", {:page \"index\"}])
  ;; This route will match an empty string and the :page key will hold 
  ;; the value \"index\"

  (expand-routes [[\":page\" {:page \"index\"}]
                  \":page/:action\"])
  ;; \"\" will be matched as {:page \"index\"}
  ;; \"foo/bar\" will be matched as {:page \"foo\" :action \"bar\"}
  ```
  "
  [routes]
  ;; sort routes in desc order by count of placeholders
  (into [] (sort-by (fn [r]
                      (- (count (:placeholders r)))) 
                    (map expand-route routes))))
