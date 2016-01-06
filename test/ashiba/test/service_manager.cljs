(ns ashiba.test.service-manager
  (:require [cljs.test :refer-macros [deftest is]] 
            [ashiba.service-manager :as service-manager]))

(deftest service-actions []
  (let [running-services {:news {:params {:page 1 :per-page 10}}
                          :users {:params true}
                          :comments {:params {:news-id 1}}}
        services {:news {:page 2 :per-page 10}
                  :users true
                  :category {:id 1}
                  :comments nil
                  :image-gallery nil}
        service-actions (service-manager/service-actions
                         running-services
                         services)]
    (is (= service-actions {:news :restart
                            :comments :stop
                            :category :start}))))
