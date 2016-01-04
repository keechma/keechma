(ns ashiba.test.router
  (:require [cljs.test :refer-macros [deftest is]] 
            [ashiba.router :as router]))

(deftest match-path []
  (let [routes (router/expand-routes ["/:foo/bar/:baz"])
        matched-path (router/match-path routes "one/bar/two")]
    (is (= matched-path {:route ":foo/bar/:baz" :data {:foo "one" :baz "two"}}))))

(deftest url->map []
  (do
    (let [routes (router/expand-routes [[":page" {:page "index"}]])
          matched-1 (router/url->map routes "foo.Bar")
          matched-2 (router/url->map routes "")
          matched-3 (router/url->map routes "foo.Bar?where=there")]
      (is (= matched-1 {:route ":page"
                        :data {:page "foo.Bar"}}))
      (is (= matched-2 {:route ":page"
                        :data {:page "index"}}))
      (is (= matched-3 {:route ":page"
                        :data {:page "foo.Bar"
                               :where "there"}})))

    (let [routes (router/expand-routes [[":page/:bar" {:page "index" :bar "foo"}]])
          matched (router/url->map routes "foo.Bar/?where=there")]
      (is (= matched {:route ":page/:bar"
                      :data {:page "foo.Bar"
                             :bar "foo"
                             :where "there"}})))

    (let [routes (router/expand-routes ["/:foo/bar/:baz"])
          url "/one/bar/two?qux=1&test=success"
          matched (router/url->map routes url)
          expected-data {:foo "one" :baz "two" :qux "1" :test "success"}]
      (is (= matched {:route ":foo/bar/:baz"
                      :data expected-data})))))

(deftest url->map-invalid []
  (let [routes (router/expand-routes [["pages/:var1/:var2/:var3"
                                       {:var1 "default1"
                                        :var2 "default2"
                                        :var3 "default3"}]])
        matched-1 (router/url->map routes "pages//")
        matched-2 (router/url->map routes "pages/val1/val2/val3?invalid-param")]
    (is (= matched-1 {:data {}}))
    (is (= matched-2 {:route "pages/:var1/:var2/:var3"
                      :data {:var1 "val1"
                             :var2 "val2"
                             :var3 "val3"}}))))

(deftest url->map-only-query-string []
  (let [routes []
        url "?foo=bar&baz=qux"
        matched-url (router/url->map routes url)]
    (is (= matched-url {:data {:foo "bar" :baz "qux"}}))))

(deftest map->url []
  (do
    (let [routes (router/expand-routes [["pages/:page" {:page "index"}]])
          url-1 (router/map->url routes {:page "foo"})
          url-2 (router/map->url routes {:page "foo" :index "bar"})]
      (is (= url-1 "pages/foo"))
      (is (= url-2 "pages/foo?index=bar")))
    (let [routes (router/expand-routes [["pages/:page" {:page "index"}]
                                        ["pages/:page/:foo" {:page "index" :foo "bar"}]])
          url (router/map->url routes {:page "foo" :foo "bar" :where "there"})]
      (is (= url "pages/foo/?where=there")))
    (let [url (router/map->url nil {:page "foo" :bar "baz" :where "there"})]
      (is (= url "?bar=baz&page=foo&where=there")))))


