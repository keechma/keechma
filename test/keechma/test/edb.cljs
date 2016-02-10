(ns keechma.test.edb 
  (:require [cljs.test :refer-macros [deftest is]]
            [keechma.edb :as edb]
            [keechma.edb.util :as util]
            [keechma.edb.relations :as relations]
            [clojure.string :refer [upper-case]]))

(def schema {:notes {:id :id
                     :middleware {:set [(fn [item] item)]}
                     :relations {:user [:one :users]
                                 :links [:many :links]}
                     :schema {}}
             :links {:id :id}
             :users {:id :id}})

(def dbal (edb/make-dbal schema))

(defn uppercase-note-title [i]
  (assoc i :title (upper-case (:title i))))

(deftest ensure-layout []
  (let [ensured-fn (util/ensure-layout (fn [schema db entity-kw] db))
        new-db (ensured-fn dbal {} :notes)]
    (is (= new-db {:notes {:store {}
                           :c-one {}
                           :c-many {}}}))))

(deftest call-middleware-set []
  (let [processed-item (util/call-middleware-set
                        {:notes {:middleware {:set [uppercase-note-title]}}}
                        :notes
                        {:title "note title"})]
    (is (= (:title processed-item) "NOTE TITLE"))))

(deftest insert-item []
  (let [db (util/add-empty-layout {} :notes)
        db-with-item (edb/insert-item {:notes {:id :id}} db :notes {:id 1 :title "Note title"})]
    (is (= (get-in db-with-item [:notes :store 1 :title]) "Note title"))))

(deftest insert-item-with-custom-id-fn []
  (let [db (util/add-empty-layout {} :notes)
        id-fn (fn [item] (str (:id item) "-note"))
        schema {:notes {:id id-fn}}
        db-with-item (edb/insert-item schema db :notes {:id 1 :title "Note title"})]
    (is (= (get-in db-with-item [:notes :store "1-note" :title]) "Note title"))))

(deftest remove-item []
  (let [db {:notes {:store {1 {:id 1 :title "Note title"}}
                    :c-one {}
                    :c-many {}}
            :users {:store {1 {:id 1 :username "Retro"}}
                    :c-one {[:notes 1 :user] 1}
                    :c-many {}}
            :links {:store {1 {:id 1 :url "www.google.com"}}
                    :c-one {}
                    :c-many {[:notes 1 :links] [1]}}}
        db-after-delete {:notes {:store {}
                                 :c-one {}
                                 :c-many {}}
                         :users {:store {1 {:id 1 :username "Retro"}}
                                 :c-one {}
                                 :c-many {}}
                         :links {:store {1 {:id 1 :url "www.google.com"}}
                                 :c-one {}
                                 :c-many {}}}]
    (is (= db-after-delete (edb/remove-item schema db :notes 1)))))

(deftest get-item-by-id []
  (let [db {:notes {:store {1 {:id 1 :title "Note title"}}}}]
    (is (= (:title (edb/get-item-by-id {} db :notes 1)) "Note title"))))

(deftest insert-named-item []
  (let [schema {:notes {}}
        db (util/add-empty-layout {} :notes)
        db-with-items (edb/insert-named-item schema db :notes :current-item {:id 1 :title "Note title"})
        expected-layout {:notes {:store {1 {:id 1 :title "Note title"}}
                                 :c-one {:current-item 1}
                                 :c-many {}}}]
    (is (= db-with-items expected-layout))))

(deftest insert-collection []
  (let [schema {:notes {}}
        db (util/add-empty-layout {} :notes)
        note-1 {:id 1 :title "Note title 1"}
        note-2 {:id 2 :title "Note title 2"}
        db-with-items (edb/insert-collection schema db :notes :latest [note-1 note-2])
        expected-layout {:notes {:store {1 note-1 2 note-2}
                                 :c-one {}
                                 :c-many {:latest [1 2]}}}]
    (is (= db-with-items expected-layout))))

(deftest append-collection []
  (let [schema {:notes {}}
        db {:notes {:store {1 {:id 1} 2 {:id 2}}
                    :c-one {}
                    :c-many {:latest [1 2]}}}
        expected-db {:notes {:store {1 {:id 1}
                                     2 {:id 2}
                                     3 {:id 3}
                                     4 {:id 4}}
                             :c-one {}
                             :c-many {:latest [1 2 3 4]}}}]
    (is (= expected-db (edb/append-collection
                        schema
                        db
                        :notes 
                        :latest 
                        [{:id 3} {:id 4}])))))

(deftest prepend-collection []
  (let [schema {:notes {}}
        db {:notes {:store {1 {:id 1} 2 {:id 2}}
                    :c-one {}
                    :c-many {:latest [1 2]}}}
        expected-db {:notes {:store {1 {:id 1}
                                     2 {:id 2}
                                     3 {:id 3}
                                     4 {:id 4}}
                             :c-one {}
                             :c-many {:latest [3 4 1 2]}}}]
    (is (= expected-db (edb/prepend-collection
                        schema
                        db
                        :notes 
                        :latest 
                        [{:id 3} {:id 4}])))))

(deftest prepend-to-empty-collection []
  (let [schema {:notes {}}
        db {:notes {:store {}
                    :c-one {}
                    :c-many {}}}
        expected-db {:notes {:store {1 {:id 1}}
                             :c-one {}
                             :c-many {:latest [1]}}}]
    (is (= expected-db (edb/prepend-collection
                        schema
                        db
                        :notes
                        :latest
                        [{:id 1}])))))

(deftest append-to-empty-collection []
  (let [schema {:notes {}}
        db {:notes {:store {}
                    :c-one {}
                    :c-many {}}}
        expected-db {:notes {:store {1 {:id 1}}
                             :c-one {}
                             :c-many {:latest [1]}}}]
    (is (= expected-db (edb/append-collection
                        schema
                        db
                        :notes
                        :latest
                        [{:id 1}])))))


(deftest get-named-item []
  (let [schema {:notes {}}
        note-1 {:id 1 :title "Note title 1"}
        db {:notes {:store {1 note-1}
                    :c-one {:current 1}
                    :c-many {}}}]
    (is (= (edb/get-named-item schema db :notes :current) note-1))))

(deftest get-collection []
  (let [schema {:notes {}}
        note-1 {:id 1 :title "Note title 1"}
        note-2 {:id 2 :title "Note title 2"}
        db {:notes {:store {1 note-1 2 note-2}
                    :c-one {}
                    :c-many {:latest [1 2]}}}]
    (is (= (edb/get-collection schema db :notes :latest) [note-1 note-2]))))

(deftest inserting-nil-relation-one-removes-existing-relation-one []
  (let [schema {:users {}
                :notes {:relations {:user [:one :users]}}}
        db (-> {}
               (util/add-empty-layout :notes)
               (util/add-empty-layout :users))
        note {:id 1 :user {:id 1}}
        note-with-nil-user {:id 1 :user nil}
        db-with-user (edb/insert-item schema db :notes note)
        expected-db-with-user {:notes {:store {1 {:id 1}}
                                       :c-one {}
                                       :c-many {}}
                               :users {:store {1 {:id 1}}
                                       :c-one {[:notes 1 :user] 1}
                                       :c-many {}}}
        expected-db-with-nil-user {:notes {:store {1 {:id 1}}
                                       :c-one {}
                                       :c-many {}}
                                   :users {:store {1 {:id 1}}
                                           :c-one {}
                                           :c-many {}}}]
    (is (= db-with-user expected-db-with-user))
    (is (= (edb/insert-item schema db-with-user :notes note-with-nil-user) expected-db-with-nil-user))))

(deftest inserting-item-with-no-related-data-leaves-existing-relations-intact []
  (let [schema {:users {}
                :notes {:relations {:user [:one :users]}}}
        db (-> {}
                   (util/add-empty-layout :notes)
                   (util/add-empty-layout :users))
        note {:id 1 :user {:id 1}}
        note-with-no-user {:id 1 :title "Foo"}
        db-with-user (edb/insert-item schema db :notes note)
        expected-db-with-user {:notes {:store {1 {:id 1}}
                                       :c-one {}
                                       :c-many {}}
                               :users {:store {1 {:id 1}}
                                       :c-one {[:notes 1 :user] 1}
                                       :c-many {}}}
        expected-db-after-note-with-no-user {:notes {:store {1 {:id 1 :title "Foo"}}
                                                     :c-one {}
                                                     :c-many {}}
                                             :users {:store {1 {:id 1}}
                                                     :c-one {[:notes 1 :user] 1}
                                                     :c-many {}}}]
    (is (= db-with-user expected-db-with-user))
    (is (= (edb/insert-item schema db-with-user :notes note-with-no-user) expected-db-after-note-with-no-user))))

(deftest relation-one []
  (let [db (-> {}
               (util/add-empty-layout :notes)
               (util/add-empty-layout :users))
        note {:id 1 :title "Note title" :user {:id 1 :username "Retro"}}
        db-with-items (edb/insert-item schema db :notes note)
        expected-db {:notes {:store {1 {:id 1 :title "Note title"}}
                             :c-one {}
                             :c-many {}}
                     :users {:store {1 {:id 1 :username "Retro"}}
                             :c-one {[:notes 1 :user] 1}
                             :c-many {}}}]
    (is (= db-with-items expected-db))
    (let [note-from-db (edb/get-item-by-id schema db-with-items :notes 1) 
          user-from-db (edb/get-item-by-id schema db-with-items :users 1)]
      (is (= "Note title" (:title note-from-db)))
      (is (= 1 (:id note)))
      (is (= user-from-db ((:user note-from-db))))
      (is (= user-from-db {:id 1 :username "Retro"})))))

(deftest relation-many []
  (let [db (-> {}
               (util/add-empty-layout :notes)
               (util/add-empty-layout :links))
        note {:id 1 :title "Note title"
              :links [{:id 1 :url "http://google.com"}
                      {:id 2 :url "http://bing.com"}]}
        db-with-items (edb/insert-item schema db :notes note)
        expected-db {:notes {:store {1 {:id 1 :title "Note title"}}
                             :c-one {}
                             :c-many {}}
                     :links {:store {1 {:id 1 :url "http://google.com"}
                                     2 {:id 2 :url "http://bing.com"}}
                             :c-one {}
                             :c-many {[:notes 1 :links] [1 2]}}}]
    (is (= db-with-items expected-db))
    (is (= (edb/get-item-by-id schema db-with-items :links 1) {:id 1 :url "http://google.com"}))
    (let [note-from-db (edb/get-item-by-id schema db-with-items :notes 1)]
      (is (= "Note title" (:title note-from-db)))
      (is (= 1 (:id note-from-db)))
      (is (= (:links note) ((:links note-from-db)))))))

(deftest circular-relations
  (let [db (-> {}
               (util/add-empty-layout :notes)
               (util/add-empty-layout :links))
        schema {:notes {:relations {:links [:many :links]
                                    :user [:one :user]}}
                :links {:relations {:user [:one :user]
                                    :notes [:many :notes]}}}
        note {:id 1
              :title "Note title"
              :links [{:id 1} {:id 2}]}
        link {:id 1
              :url "http://www.google.com"
              :notes [{:id 1}]}
        insert-item (partial edb/insert-item schema)
        db-with-items (-> db
                          (insert-item :notes note)
                          (insert-item :links link))]
    (let [note-from-db (edb/get-item-by-id schema db-with-items :notes 1)
          link-from-db (edb/get-item-by-id schema db-with-items :links 1)
          note-link (get ((:links note-from-db)) 0)
          note-link-note (get ((:notes note-link)) 0)]
      (= (:url note-link) "http://www.google.com")
      (= note-from-db note-link-note))))

(deftest nested-relations []
  (let [db (-> {}
               (util/add-empty-layout :notes)
               (util/add-empty-layout :links)
               (util/add-empty-layout :users))
        note {:id 1 
              :title "Note title"
              :links [{:id 1}]}
        user {:id 1 :username "Retro"}
        link {:id 1 :url "http://google.com" :user {:id 1}}
        schema (assoc schema :links {:relations {:user [:one :users]}})
        insert-item (partial edb/insert-item schema)
        db-with-items (-> db
                          (insert-item :notes note)
                          (insert-item :users user)
                          (insert-item :links link))
        link-from-relation (get ((:links (edb/get-item-by-id schema db-with-items :notes 1))) 0)
        user-from-relation ((:user link-from-relation))]
    (is (= user-from-relation user))))

(deftest tree-relations
  (let [schema {:notes {:relations {:notes [:many :notes]}}}
        db (util/add-empty-layout {} :notes)
        notes [{:id 1 :title "Note #1" :notes [{:id 2}]}
               {:id 2 :title "Note #2" :notes [{:id 3}]}
               {:id 3 :title "Note #3" :notes []}]
        insert-item (partial edb/insert-item schema)
        db-with-items (reduce (fn [db item]
                                (insert-item db :notes item))
                              db notes)
        note-1 (edb/get-item-by-id schema db-with-items :notes 1)]
    (is (= (get-in ((:notes note-1)) [0 :title]) "Note #2"))
    (is (= (get-in ((:notes  (get((:notes note-1)) 0))) [0 :title]) "Note #3"))))

(deftest remove-collection []
  (let [db {:notes {:store {1 {:id 1}}
                    :c-one {:current 1
                                      :pinned 2}
                    :c-many {:latest [1 2]
                                       :starred [2 3]}}}
        expected-db {:notes {:store {1 {:id 1}}
                             :c-one {:pinned 2}
                             :c-many {:starred [2 3]}}}]
    (is (= expected-db
           (-> db
               (edb/remove-named-item :notes :current)
               (edb/remove-collection :notes :latest))))))

(deftest item-meta []
  (let [item-meta {:status :loaded}
        item {:id 1 :title "Note"}
        schema {:notes {}}
        db (util/add-empty-layout {} :notes)
        db-with-items (edb/insert-item schema db :notes item item-meta)
        expected-db {:notes {:store {1 item}
                             :c-one {}
                             :c-many {}}
                     :__meta-store__ {:store {[:notes 1] item-meta}
                                      :c-one {}
                                      :c-many {}}}]
    (is (= db-with-items expected-db))
    (is (= item-meta
           (meta (edb/get-item-by-id schema db-with-items :notes 1))))
    (is (= item-meta (edb/get-item-meta schema db-with-items :notes 1)))))

(deftest named-item-meta []
  (let [collection-meta {:status :loading}
        item-meta {:status :loaded}
        item {:id 1 :title "Note"}
        schema {:notes {}}
        db (util/add-empty-layout {} :notes)

        db-with-collection
        (edb/insert-named-item schema db :notes :current-item nil collection-meta)

        db-with-collection-and-item
        (edb/insert-named-item schema db-with-collection :notes :current-item item item-meta)
        
        expected-db-with-collection
        {:notes {:store {}
                 :c-one {:current-item nil}
                 :c-many {}}
         :__meta-store__ {:store {[:notes :current-item] collection-meta}
                          :c-one {}
                          :c-many {}}}
        
        expected-db-with-collection-and-item
        {:notes {:store {1 item}
                 :c-one {:current-item 1}
                 :c-many {}}
         :__meta-store__ {:store {[:notes 1] item-meta}
                          :c-one {}
                          :c-many {}}}]
    (is (= db-with-collection expected-db-with-collection))
    (is (= db-with-collection-and-item expected-db-with-collection-and-item))
    (is (= collection-meta
           (edb/get-named-item-meta schema db-with-collection :notes :current-item)))
    (is (= item-meta
           (edb/get-named-item-meta schema db-with-collection-and-item :notes :current-item)))))

(deftest collection-meta []
  (let [collection-meta {:status :loading}
        items [{:id 1 :title "Note"}]
        schema {:notes {}}
        db (util/add-empty-layout {} :notes)

        db-with-collection
        (edb/insert-collection schema db :notes :latest items collection-meta)
        
        expected-db-with-collection
        {:notes {:store {1 (get items 0)}
                 :c-one {}
                 :c-many {:latest [1]}}
         :__meta-store__ {:store {[:notes :latest] collection-meta}
                          :c-one {}
                          :c-many {}}}]
    (is (= db-with-collection expected-db-with-collection))
    (is (= collection-meta
           (edb/get-collection-meta schema db-with-collection :notes :latest)))
    (is (= collection-meta
           (meta (edb/get-collection schema db-with-collection :notes :latest))))))

(deftest removing-item-removes-its-id-from-collections []
  (let [schema {:notes {}}
        insert-named-item (partial edb/insert-named-item schema)
        insert-collection (partial edb/insert-collection schema)
        note-1 {:id 1}
        note-2 {:id 2}
        db (util/add-empty-layout {} :notes)
        db-with-items-and-collections (-> db
                                          (insert-named-item :notes :current note-1)
                                          (insert-collection :notes :list [note-1 note-2]))
        expected-db-after-delete {:notes {:store {2 {:id 2}}
                                          :c-one {}
                                          :c-many {:list [2]}}}]
    (is (= expected-db-after-delete
           (edb/remove-item schema db-with-items-and-collections :notes 1)))))

(deftest removing-item-removes-meta []
  (let [db {:notes {:store {1 {:id 1}}
                    :c-one {}
                    :c-many {}}
            :__meta-store__ {:store {[:notes 1] {:status :loaded}}
                             :c-one {}
                             :c-many {}}}
        expected-db {:notes {:store {}
                             :c-one {}
                             :c-many {}}
                     :__meta-store__ {:store {}
                                      :c-one {}
                                      :c-many {}}}]
    (is (= expected-db (edb/remove-item {} db :notes 1)))))

(deftest removing-named-item-removes-meta []
  (let [db {:notes {:store {}
                    :c-one {:current-item nil}
                    :c-many {}}
            :__meta-store__ {:store {[:notes :current-item] {:status :loaded}}
                             :c-one {}
                             :c-many {}}}
        expected-db {:notes {:store {}
                             :c-one {}
                             :c-many {}}
                     :__meta-store__ {:store {}
                                      :c-one {}
                                      :c-many {}}}]
    (is (= expected-db (edb/remove-named-item db :notes :current-item)))))

(deftest removing-collection-removes-meta []
  (let [db {:notes {:store {}
                    :c-one {}
                    :c-many {:latest []}}
            :__meta-store__ {:store {[:notes :latest] {:status :loaded}}
                             :c-one {}
                             :c-many {}}}
        expected-db {:notes {:store {}
                             :c-one {}
                             :c-many {}}
                     :__meta-store__ {:store {}
                                      :c-one {}
                                      :c-many {}}}]
    (is (= expected-db (edb/remove-collection db :notes :latest)))))

(deftest vacuum []
  (let [db {:notes {:store {1 {:id 1} 2 {:id 2} 3 {:id 3} 4 {:id 4}}
                    :c-one {:current 1}
                    :c-many {:latest [1 3]}}
            :__meta-store__ {:store {[:notes 2] {:status :loaded}}
                             :c-one {}
                             :c-many {}}}
        expected-db {:notes {:store {1 {:id 1} 3 {:id 3}}
                             :c-one {:current 1}
                             :c-many {:latest [1 3]}}
                     :__meta-store__ {:store {}
                                      :c-one {}
                                      :c-many {}}}]
    (is (= (edb/vacuum db) expected-db))))

;; DBAL
(deftest dbal-insert-item []
  (let [insert-item-fn (:insert-item dbal)
        db (insert-item-fn {} :notes {:id 1 :title "Note title"})]
    (is (= (get-in db [:notes :store 1 :title]) "Note title"))))
