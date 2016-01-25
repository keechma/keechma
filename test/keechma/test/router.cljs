(ns keechma.test.router
  (:require [cljs.test :refer-macros [deftest is]] 
            [keechma.router :as router]))

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
      (is (= url "?page=foo&bar=baz&where=there")))))

(deftest symmetry []
  (let [data {:page "=&[]" :nestedArray ["a"] :nested {:a "b"}}
        url (router/map->url [] data)
        back-data (router/url->map [] url)]
    (is (= data (:data back-data)))))


(deftest light-param []
  (do 
    (let [routes (router/expand-routes [[":page", {:page "index"}]])
          res (router/map->url routes {:page "index"})]
      (is (= res "")))
    (let [routes (router/expand-routes [["pages/:p1/:p2/:p3" {:p1 "index" :p2 "foo" :p3 "bar"}]])
          res-1 (router/map->url routes {:p1 "index" :p2 "foo" :p3 "bar"})
          res-2 (router/map->url routes {:p1 "index" :p2 "baz" :p3 "bar"})]
      (is (= res-1 "pages///"))
      (is (= res-2 "pages//baz/")))))

(deftest map->url-does-not-add-defaults []
  (let [routes (router/expand-routes [["pages/:p1", {:p2 "foo"}]])]
    (is (= (router/map->url routes {:p1 "index" :p2 "foo"}) "pages/index"))))

(deftest map->url-url->map []
  (let [routes (router/expand-routes [[":page/:type", {:page "index", :type "foo"}]])
        data {:page "foo.Bar" :type "document" :bar "baz" :where "there"}
        url (router/map->url routes data)
        url-data (router/url->map routes url)
        data-2 {:page "foo.Bar" :type "foo" :bar "baz" :where "there"}
        url-2 (router/map->url routes data-2)
        url-data-2 (router/url->map routes url-2)
        data-3 {:page "index" :type "foo" :bar "baz" :where "there"}
        url-3 (router/map->url routes data-3)
        url-data-3 (router/url->map routes url-3)]
    (is (= data (:data url-data)))
    (is (= data-2 (:data url-data-2)))
    (is (= data-3 (:data url-data-3))))
  (let [data {:page "foo" :bar "baz" :where "there"}
        url (router/map->url [] data)
        url-data (router/url->map [] url)]
    (is (= data (:data url-data))))
  (let [routes (router/expand-routes [[":foo/:bar" {:foo 1 :bar 2}]])
        url (router/map->url routes {:foo 1 :bar 2})
        data (router/url->map routes "/")]
    (is (= url ""))
    (is (= {:foo 1 :bar 2} (:data data)))))

(deftest precedence []
  (do 
    (let [routes (router/expand-routes [[":who", {:who "index"}]
                                        "search/:search"])
          data-1 (router/url->map routes "foo.Bar")
          data-2 (router/url->map routes "search/foo.Bar")
          url-1 (router/map->url routes {:who "foo.Bar"})
          url-2 (router/map->url routes {:search "foo.Bar"})]
      (is (= (:data data-1) {:who "foo.Bar"}))
      (is (= (:data data-2) {:search "foo.Bar"}))
      (is (= url-1 "foo.Bar"))
      (is (= url-2 "search/foo.Bar")))
    (let [routes (router/expand-routes [[":type" , {:who "index"}]
                                        ":type/:id"])
           data (router/url->map routes "foo/bar")
           url (router/map->url routes {:type "foo" :id "bar"})]
      (is (= (:data data) {:type "foo" :id "bar"}))
      (is (= url "foo/bar")))))

