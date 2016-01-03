(ns ashiba.router
  (:require [cemerick.url :as u]
            [clojure.walk :refer [keywordize-keys]]))

(defn process-route-part [default-keys part]
  (let [is-placeholder? (= ":" (first part))
        key (when is-placeholder? (keyword (subs part 1)))
        has-default? (contains? default-keys key)
        min-matches (if has-default? "*" "+")
        re-match (if is-placeholder? (str "(" "[^/]" min-matches ")") part)]
    {:is-placeholder? is-placeholder?
     :key key
     :has-default has-default?
     :re-match re-match}))

(defn route-regex [parts]
  (let [base-regex (clojure.string/join "/" (map (fn [p] (:re-match p)) parts))
        full-regex (str "^" base-regex "$")]
    (re-pattern full-regex)))

(defn route-placeholders [parts]
  (remove nil? (map (fn [p] (:key p)) parts)))

(defn add-default-params [route]
  (if (string? route) [route {}] route))

(defn strip-slashes [route]
  (clojure.string/replace (clojure.string/trim route) #"^/+|/+$" ""))

(defn process-route [[route defaults]]
  (let [parts (clojure.string/split route #"/")
        processed-parts (map (partial process-route-part (set (keys defaults))) parts)]
    {:parts processed-parts 
     :regex (route-regex processed-parts)
     :placeholders (route-placeholders processed-parts)
     :route route
     :defaults (or defaults {})}))

(defn remove-empty-matches [matches]
  (apply dissoc matches (for [[k v] matches :when (empty? v)] k)))

(defn expand-route [route]
  (let [strip-slashes (fn [[route defaults]] [(strip-slashes route) defaults])]
    (-> route
        add-default-params
        strip-slashes
        process-route)))

(defn match-path-with-route [route url] 
  (let [matches (first (re-seq (:regex route) url))]
    (when-not (nil? matches)
      (zipmap (:placeholders route) (rest matches)))))

(defn match-path [processed-routes path]
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

(defn url->map [processed-routes url]
  (let [parsed-url (u/url url)
        path (strip-slashes (:path parsed-url)) 
        query (remove-empty-matches (keywordize-keys (or (:query parsed-url) {})))
        ;; Try to match a normal url or a url with "/" appended
        ;; that way we ensure that routes with defaults get matched
        ;; even if the last segment is missing
        matched-path (or (match-path processed-routes path)
                         (match-path processed-routes (str path "/")))]
    (if matched-path
      (assoc matched-path :data (merge query (:data matched-path)))
      {:data query})))

(defn map->url [processed-routes url])

(defn expand-routes [routes]
  ;; sort routes in desc order by count of placeholders
  (into [] (sort-by (fn [r]
                      (- (count (:placeholders r)))) 
                    (map expand-route routes))))

