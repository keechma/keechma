(ns ashiba.test.core
  (:require [doo.runner :refer-macros [doo-tests]]
            [cljs.test :as test]
            [ashiba.test.app-state]
            [ashiba.test.router]))

(doo-tests ;;'ashiba.test.app-state
           'ashiba.test.router)
