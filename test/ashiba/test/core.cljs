(ns ashiba.test.core
  (:require [doo.runner :refer-macros [doo-tests]]
            [cljs.test :as test]
            [ashiba.test.app-state]
            [ashiba.test.router]
            [ashiba.test.edb]))

(doo-tests ;;'ashiba.test.app-state
           'ashiba.test.router
           'ashiba.test.edb)
